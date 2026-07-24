package com.giro360.giro360_capture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class Giro360AnalyzedVideoFrame(
    val timestampUs: Long,
    val sharpness: Double,
    val motionFromPrevious: Double?,
    var cumulativeMotion: Double = 0.0,
)

private data class Giro360VisualFeatures(
    val keypoints: Array<KeyPoint>,
    val descriptors: Mat,
    val width: Double,
    val sharpness: Double,
) {
    fun release() {
        descriptors.release()
    }
}

private data class Giro360LapSelection(
    val lapIndex: Int,
    val frames: List<Giro360AnalyzedVideoFrame>,
    val score: Double,
)

internal class Giro360VideoFallbackCoordinator(
    context: Context,
    private val statusDidChange: (Map<String, Any>) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stateLock = Any()
    private val previewView = AtomicReference<PreviewView?>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var directoryPath = ""
    private var videoPath = ""
    private var timelinePath = ""
    private var binCount = 30
    private var requiredLaps = 2
    private var durationNanos = 30_000_000_000L
    private var elapsedNanos = 0L
    private var running = false
    private var finishing = false
    private var complete = false
    private var failed = false
    private var cancelled = false
    private var stopRequested = false
    private var message = "Modo vídeo pronto."
    private var selectedLapIndex: Int? = null
    private var processedFrameCount = 0
    private var encodedCandidateCount = 0
    private var expectedProcessingFrameCount = 0
    private var visualMotionReliable = false
    private var visualMotionSampleCount = 0
    private var visualMotionMatchedPairCount = 0
    private var visualCumulativeMotion = 0.0
    private var visualDirectionConsistency = 0.0
    private var appliedFrameRotationDegrees = 0f
    private var captureSource = "videoOnly"
    private var lapCandidateCounts = emptyList<Int>()
    private var finalCandidates = emptyList<Giro360AndroidFrameCandidate>()

    fun attachPreview(view: PreviewView) {
        previewView.set(view)
        updateKeepScreenOn()
    }

    fun detachPreview(view: PreviewView) {
        previewView.compareAndSet(view, null)
    }

    fun showPreview() {
        mainHandler.post { previewView.get()?.visibility = android.view.View.VISIBLE }
    }

    fun hidePreview() {
        mainHandler.post { previewView.get()?.visibility = android.view.View.GONE }
    }

    fun startCapture(
        activity: Activity,
        directoryPath: String,
        binCount: Int,
        requiredLaps: Int,
    ) {
        val lifecycleOwner = activity as? LifecycleOwner
            ?: error("A Activity precisa implementar LifecycleOwner para usar CameraX.")
        val preview = previewView.get() ?: error("O preview CameraX ainda não está disponível.")
        val outputDirectory = File(directoryPath).apply { mkdirs() }

        cancelCapture()
        synchronized(stateLock) {
            this.directoryPath = outputDirectory.path
            this.videoPath = File(outputDirectory, "giro360_capture.mp4").path
            this.timelinePath = File(outputDirectory, "giro360_video_only_timeline.json").path
            this.binCount = binCount
            this.requiredLaps = requiredLaps
            durationNanos = requiredLaps * SECONDS_PER_LAP * 1_000_000_000L
            elapsedNanos = 0
            running = true
            finishing = false
            complete = false
            failed = false
            cancelled = false
            stopRequested = false
            message = "Prepare-se: gire devagar por $requiredLaps voltas completas."
            selectedLapIndex = null
            processedFrameCount = 0
            encodedCandidateCount = 0
            expectedProcessingFrameCount = 0
            visualMotionReliable = false
            visualMotionSampleCount = 0
            visualMotionMatchedPairCount = 0
            visualCumulativeMotion = 0.0
            visualDirectionConsistency = 0.0
            appliedFrameRotationDegrees = 0f
            captureSource = "videoOnly"
            lapCandidateCounts = List(requiredLaps) { 0 }
            finalCandidates = emptyList()
        }
        showPreview()
        emitStatus()

        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener(
            {
                try {
                    if (!synchronized(stateLock) { running && !cancelled }) return@addListener
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()

                    val previewUseCase = Preview.Builder().build().also {
                        it.surfaceProvider = preview.surfaceProvider
                    }
                    val qualitySelector = QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    )
                    val recorder = Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build()
                    val videoCapture = VideoCapture.withOutput(recorder)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        videoCapture,
                    )

                    val output = FileOutputOptions.Builder(File(videoPath)).build()
                    recording = recorder.prepareRecording(applicationContext, output)
                        .start(ContextCompat.getMainExecutor(applicationContext), ::onVideoEvent)
                } catch (error: Throwable) {
                    fail("Não foi possível iniciar o vídeo: ${error.localizedMessage}")
                }
            },
            ContextCompat.getMainExecutor(applicationContext),
        )
    }

    fun processVideo(
        sourceVideoPath: String,
        directoryPath: String,
        binCount: Int,
        requiredLaps: Int,
    ) {
        val sourceVideo = File(sourceVideoPath)
        require(sourceVideo.isFile) { "O vídeo selecionado não foi encontrado." }
        val outputDirectory = File(directoryPath).apply { mkdirs() }

        cancelCapture()
        synchronized(stateLock) {
            this.directoryPath = outputDirectory.path
            this.videoPath = File(outputDirectory, "giro360_capture.mp4").path
            this.timelinePath = File(outputDirectory, "giro360_video_only_timeline.json").path
            this.binCount = binCount
            this.requiredLaps = requiredLaps
            durationNanos = 0
            elapsedNanos = 0
            running = false
            finishing = true
            complete = false
            failed = false
            cancelled = false
            stopRequested = false
            message = "Copiando o vídeo selecionado..."
            selectedLapIndex = null
            processedFrameCount = 0
            encodedCandidateCount = 0
            val totalTargetFrames = binCount * requiredLaps
            expectedProcessingFrameCount = max(
                totalTargetFrames + 1,
                min(
                    totalTargetFrames * ANALYSIS_SAMPLES_PER_TARGET,
                    MAX_ANALYSIS_FRAME_COUNT,
                ),
            ) + binCount
            visualMotionReliable = false
            visualMotionSampleCount = 0
            visualMotionMatchedPairCount = 0
            visualCumulativeMotion = 0.0
            visualDirectionConsistency = 0.0
            appliedFrameRotationDegrees = 0f
            captureSource = "importedVideo"
            lapCandidateCounts = List(requiredLaps) { 0 }
            finalCandidates = emptyList()
        }
        hidePreview()
        emitStatus()

        worker.execute {
            try {
                val destination = File(videoPath)
                if (sourceVideo.canonicalPath != destination.canonicalPath) {
                    sourceVideo.copyTo(destination, overwrite = true)
                }
                synchronized(stateLock) {
                    message = "Analisando o movimento visual do vídeo..."
                    emitStatusLocked()
                }
                extractAndSelectFrames()
            } catch (error: Throwable) {
                fail("Falha ao importar o vídeo: ${error.localizedMessage}")
            }
        }
    }

    fun status(): Map<String, Any> = synchronized(stateLock) { statusLocked() }

    fun cancelCapture() {
        val activeRecording: Recording?
        synchronized(stateLock) {
            if (!running && !finishing) return
            cancelled = true
            running = false
            finishing = false
            complete = false
            message = "Captura cancelada."
            activeRecording = recording
            recording = null
        }
        activeRecording?.stop()
        unbindCamera()
        updateKeepScreenOn()
        emitStatus()
    }

    fun pause() {
        if (synchronized(stateLock) { running || finishing }) cancelCapture()
        unbindCamera()
    }

    fun close() {
        cancelCapture()
        unbindCamera()
        cameraProvider = null
        worker.shutdownNow()
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> synchronized(stateLock) {
                message = "Volta 1/$requiredLaps: gire devagar no mesmo sentido."
                emitStatusLocked()
            }

            is VideoRecordEvent.Status -> {
                var shouldStop = false
                synchronized(stateLock) {
                    if (!running || cancelled) return
                    elapsedNanos = event.recordingStats.recordedDurationNanos
                    val activeLap = min(
                        requiredLaps,
                        (elapsedNanos / (SECONDS_PER_LAP * 1_000_000_000L)).toInt() + 1,
                    )
                    val secondsLeft = max(0L, (durationNanos - elapsedNanos) / 1_000_000_000L)
                    message = "Volta $activeLap/$requiredLaps: continue devagar por ${secondsLeft}s."
                    if (elapsedNanos >= durationNanos && !stopRequested) {
                        stopRequested = true
                        shouldStop = true
                    }
                    emitStatusLocked()
                }
                if (shouldStop) recording?.stop()
            }

            is VideoRecordEvent.Finalize -> {
                synchronized(stateLock) { recording = null }
                if (synchronized(stateLock) { cancelled }) return
                if (event.hasError()) {
                    fail("A gravação do vídeo falhou (${event.error}).")
                    return
                }
                synchronized(stateLock) {
                    running = false
                    finishing = true
                    elapsedNanos = max(elapsedNanos, event.recordingStats.recordedDurationNanos)
                    message = "Analisando as duas voltas gravadas..."
                    emitStatusLocked()
                }
                worker.execute(::extractAndSelectFrames)
            }
        }
    }

    private fun extractAndSelectFrames() {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val durationMillis = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()?.coerceAtLeast(1L) ?: error("Duração do vídeo indisponível.")
            val rotationDegrees = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toFloatOrNull() ?: 0f
            val totalDurationUs = durationMillis * 1000L
            synchronized(stateLock) {
                durationNanos = durationMillis * 1_000_000L
                elapsedNanos = durationNanos
            }

            val totalTargetFrames = binCount * requiredLaps
            val analysisFrameCount = max(
                totalTargetFrames + 1,
                min(totalTargetFrames * ANALYSIS_SAMPLES_PER_TARGET, MAX_ANALYSIS_FRAME_COUNT),
            )
            synchronized(stateLock) {
                expectedProcessingFrameCount = analysisFrameCount + binCount
            }
            val analyzedFrames = analyzeVideoMotion(
                retriever = retriever,
                totalDurationUs = totalDurationUs,
                rotationDegrees = rotationDegrees,
                analysisFrameCount = analysisFrameCount,
            )
            if (synchronized(stateLock) { cancelled }) return
            val selections = buildLapSelections(analyzedFrames)
            if (selections.isEmpty()) {
                error("Não foi possível separar as voltas do vídeo.")
            }
            val bestSelection = selections.maxWith(
                compareBy<Giro360LapSelection> { it.frames.size }
                    .thenBy { it.score }
                    .thenBy { it.lapIndex },
            )
            val minimumFrameCount = max(24, (binCount * 0.75).toInt())
            if (bestSelection.frames.size < minimumFrameCount) {
                error("Só ${bestSelection.frames.size}/$binCount posições foram identificadas.")
            }

            synchronized(stateLock) {
                selectedLapIndex = bestSelection.lapIndex
                lapCandidateCounts = selections
                    .sortedBy { it.lapIndex }
                    .map { it.frames.size }
                message = "Volta ${bestSelection.lapIndex + 1} escolhida. Extraindo em resolução original..."
                emitStatusLocked()
            }
            val finalFrames = extractOriginalFrames(
                retriever = retriever,
                selectedFrames = bestSelection.frames,
                selectedLap = bestSelection.lapIndex,
                rotationDegrees = rotationDegrees,
            )
            if (synchronized(stateLock) { cancelled }) return
            if (finalFrames.size < minimumFrameCount) {
                error("Só ${finalFrames.size}/$binCount frames foram extraídos em resolução original.")
            }
            synchronized(stateLock) {
                finalCandidates = finalFrames
                encodedCandidateCount = finalFrames.size
                writeTimelineLocked()
                finishing = false
                complete = true
                message = "Volta ${bestSelection.lapIndex + 1} selecionada visualmente com ${finalFrames.size} frames."
                emitStatusLocked()
            }
        } catch (error: Throwable) {
            fail("Falha ao analisar o vídeo: ${error.localizedMessage}")
        } finally {
            retriever.release()
            unbindCamera()
        }
    }

    private fun analyzeVideoMotion(
        retriever: MediaMetadataRetriever,
        totalDurationUs: Long,
        rotationDegrees: Float,
        analysisFrameCount: Int,
    ): List<Giro360AnalyzedVideoFrame> {
        val orb = ORB.create(1400)
        val frames = mutableListOf<Giro360AnalyzedVideoFrame>()
        var previousFeatures: Giro360VisualFeatures? = null
        try {
            for (index in 0 until analysisFrameCount) {
                if (synchronized(stateLock) { cancelled }) return emptyList()
                val normalizedTime = if (analysisFrameCount == 1) {
                    0.0
                } else {
                    index / (analysisFrameCount - 1).toDouble()
                }
                val timestampUs = (normalizedTime * totalDurationUs).toLong()
                val raw = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                val bitmap = normalizeVideoBitmap(raw, rotationDegrees)
                val features = visualFeatures(bitmap, orb)
                val motion = previousFeatures?.let { estimateHorizontalMotion(it, features) }
                frames += Giro360AnalyzedVideoFrame(
                    timestampUs = timestampUs,
                    sharpness = features.sharpness,
                    motionFromPrevious = motion,
                )
                previousFeatures?.release()
                previousFeatures = features
                if (bitmap !== raw) bitmap.recycle()
                raw.recycle()

                processedFrameCount += 1
                if (processedFrameCount % 4 == 0 || index == analysisFrameCount - 1) {
                    synchronized(stateLock) {
                        message = "Mapeando movimento $processedFrameCount/$analysisFrameCount..."
                        emitStatusLocked()
                    }
                }
            }
        } finally {
            previousFeatures?.release()
            orb.clear()
        }
        if (frames.size < binCount + 1) {
            error("Poucos frames puderam ser lidos do vídeo (${frames.size}).")
        }
        applyVisualProgress(frames)
        return frames
    }

    private fun applyVisualProgress(frames: List<Giro360AnalyzedVideoFrame>) {
        val validMotions = frames.mapNotNull { it.motionFromPrevious }
            .filter { abs(it) in MIN_VISUAL_MOTION..MAX_VISUAL_MOTION }
        val direction = if (validMotions.sum() >= 0.0) 1.0 else -1.0
        val forwardMotions = validMotions.map { it * direction }.filter { it > 0.0 }
        val directionConsistency = if (validMotions.isEmpty()) {
            0.0
        } else {
            forwardMotions.size.toDouble() / validMotions.size
        }
        val typicalMotion = median(forwardMotions).coerceAtLeast(MIN_VISUAL_MOTION)
        var cumulative = 0.0
        frames.first().cumulativeMotion = 0.0
        for (index in 1 until frames.size) {
            val measured = frames[index].motionFromPrevious?.times(direction)
            val forward = when {
                measured == null -> typicalMotion
                measured < -typicalMotion -> 0.0
                else -> measured.coerceAtLeast(0.0)
            }.coerceAtMost(max(typicalMotion * 4.0, 0.08))
            cumulative += forward
            frames[index].cumulativeMotion = cumulative
        }

        visualMotionSampleCount = frames.size
        visualMotionMatchedPairCount = validMotions.size
        visualCumulativeMotion = cumulative
        visualDirectionConsistency = directionConsistency
        visualMotionReliable = validMotions.size >= max(18, (frames.size * 0.28).toInt()) &&
            cumulative >= requiredLaps * MIN_MOTION_PER_LAP &&
            directionConsistency >= MIN_DIRECTION_CONSISTENCY
        if (!visualMotionReliable) {
            frames.forEachIndexed { index, frame ->
                frame.cumulativeMotion = requiredLaps * index / (frames.size - 1).toDouble()
            }
            synchronized(stateLock) {
                message = "Movimento visual parcial; usando distribuição temporal como apoio."
                emitStatusLocked()
            }
        }
    }

    private fun buildLapSelections(
        frames: List<Giro360AnalyzedVideoFrame>,
    ): List<Giro360LapSelection> {
        val totalMotion = frames.last().cumulativeMotion
        if (totalMotion <= 0.0) return emptyList()
        val lapMotion = totalMotion / requiredLaps
        val binMotion = lapMotion / binCount
        val sharpnessValues = frames.map { it.sharpness }
        val minimumSharpness = sharpnessValues.minOrNull() ?: 0.0
        val sharpnessRange = ((sharpnessValues.maxOrNull() ?: 0.0) - minimumSharpness)
            .coerceAtLeast(1.0)

        return (0 until requiredLaps).map { lapIndex ->
            val selected = mutableListOf<Giro360AnalyzedVideoFrame>()
            var lastIndex = -1
            var totalSelectionCost = 0.0
            for (binIndex in 0 until binCount) {
                val target = lapIndex * lapMotion + binIndex * binMotion
                val remainingBins = binCount - binIndex - 1
                val maximumIndex = (frames.lastIndex - remainingBins).coerceAtLeast(lastIndex + 1)
                val candidateIndices = ((lastIndex + 1)..maximumIndex).toList()
                if (candidateIndices.isEmpty()) break
                val nearest = candidateIndices.minBy {
                    abs(frames[it].cumulativeMotion - target)
                }
                val searchStart = max(lastIndex + 1, nearest - 2)
                val searchEnd = min(maximumIndex, nearest + 2)
                val bestIndex = (searchStart..searchEnd).minBy { index ->
                    val distanceCost = abs(frames[index].cumulativeMotion - target) /
                        binMotion.coerceAtLeast(0.0001)
                    val sharpnessBonus =
                        (frames[index].sharpness - minimumSharpness) / sharpnessRange
                    distanceCost - sharpnessBonus * 0.22
                }
                val frame = frames[bestIndex]
                selected += frame
                totalSelectionCost += abs(frame.cumulativeMotion - target) /
                    binMotion.coerceAtLeast(0.0001)
                lastIndex = bestIndex
            }
            val averageSharpness = if (selected.isEmpty()) {
                0.0
            } else {
                selected.sumOf {
                    (it.sharpness - minimumSharpness) / sharpnessRange
                } / selected.size
            }
            val averageSelectionError = if (selected.isEmpty()) {
                Double.MAX_VALUE
            } else {
                totalSelectionCost / selected.size
            }
            Giro360LapSelection(
                lapIndex = lapIndex,
                frames = selected,
                score = (averageSharpness * 0.15) - averageSelectionError,
            )
        }
    }

    private fun extractOriginalFrames(
        retriever: MediaMetadataRetriever,
        selectedFrames: List<Giro360AnalyzedVideoFrame>,
        selectedLap: Int,
        rotationDegrees: Float,
    ): List<Giro360AndroidFrameCandidate> {
        val step = TWO_PI / binCount
        val firstTimestamp = selectedFrames.first().timestampUs
        val lastTimestamp = selectedFrames.last().timestampUs
        val lapDurationSeconds = ((lastTimestamp - firstTimestamp) / 1_000_000.0)
            .coerceAtLeast(0.001)
        val angularSpeed = TWO_PI / lapDurationSeconds
        val result = mutableListOf<Giro360AndroidFrameCandidate>()
        selectedFrames.forEachIndexed { binIndex, analyzed ->
            if (synchronized(stateLock) { cancelled }) return emptyList()
            val raw = retriever.getFrameAtTime(
                analyzed.timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return@forEachIndexed
            val bitmap = normalizeVideoBitmap(raw, rotationDegrees)
            val finalPath = String.format(
                Locale.US,
                "%s/video_%03d.jpg",
                directoryPath,
                binIndex,
            )
            FileOutputStream(finalPath).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)
            }
            if (bitmap !== raw) bitmap.recycle()
            raw.recycle()
            processedFrameCount += 1
            if (processedFrameCount % 2 == 0 || binIndex == selectedFrames.lastIndex) {
                synchronized(stateLock) {
                    message = "Extraindo frame ${binIndex + 1}/${selectedFrames.size} em resolução original..."
                    emitStatusLocked()
                }
            }
            val targetYaw = binIndex * step
            result += Giro360AndroidFrameCandidate(
                binIndex = binIndex,
                lapIndex = selectedLap,
                filePath = finalPath,
                targetYaw = targetYaw,
                relativeYaw = targetYaw,
                pitch = 0.0,
                roll = 0.0,
                translationMeters = 0.0,
                qualityScore = analyzed.sharpness,
                sharpnessScore = analyzed.sharpness,
                angularSpeed = angularSpeed,
                centerError = 0.0,
                trackingState = if (visualMotionReliable) "visual_motion" else "visual_timed",
                capturedAt = isoDate(),
                frameTimestamp = analyzed.timestampUs / 1_000_000.0,
                cameraIntrinsics = emptyList(),
                cameraTransform = IDENTITY_TRANSFORM,
            )
        }
        return result
    }

    private fun visualFeatures(bitmap: Bitmap, orb: ORB): Giro360VisualFeatures {
        val source = Mat()
        val scaled = Mat()
        val gray = Mat()
        Utils.bitmapToMat(bitmap, source)
        val targetWidth = min(ANALYSIS_WIDTH, source.cols()).coerceAtLeast(1)
        val targetHeight = max(1, source.rows() * targetWidth / source.cols().coerceAtLeast(1))
        Imgproc.resize(source, scaled, Size(targetWidth.toDouble(), targetHeight.toDouble()))
        Imgproc.cvtColor(scaled, gray, Imgproc.COLOR_RGBA2GRAY)

        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        Core.meanStdDev(laplacian, mean, deviation)
        val standardDeviation = deviation.toArray().firstOrNull() ?: 0.0
        val sharpness = standardDeviation * standardDeviation

        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        orb.detectAndCompute(gray, mask, keypoints, descriptors)
        val result = Giro360VisualFeatures(
            keypoints = keypoints.toArray(),
            descriptors = descriptors,
            width = targetWidth.toDouble(),
            sharpness = sharpness,
        )
        source.release()
        scaled.release()
        gray.release()
        laplacian.release()
        mean.release()
        deviation.release()
        keypoints.release()
        mask.release()
        return result
    }

    private fun estimateHorizontalMotion(
        previous: Giro360VisualFeatures,
        current: Giro360VisualFeatures,
    ): Double? {
        if (previous.descriptors.empty() || current.descriptors.empty()) return null
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val matches = mutableListOf<MatOfDMatch>()
        return try {
            matcher.knnMatch(previous.descriptors, current.descriptors, matches, 2)
            val horizontalShifts = mutableListOf<Double>()
            matches.forEach { matchGroup ->
                val pair = matchGroup.toArray()
                if (pair.size < 2 || pair[0].distance >= 0.76f * pair[1].distance) {
                    return@forEach
                }
                val previousPoint = previous.keypoints[pair[0].queryIdx].pt
                val currentPoint = current.keypoints[pair[0].trainIdx].pt
                val horizontal = previousPoint.x / previous.width - currentPoint.x / current.width
                val vertical = (previousPoint.y - currentPoint.y) / previous.width
                if (abs(horizontal) <= MAX_VISUAL_MOTION && abs(vertical) <= 0.12) {
                    horizontalShifts += horizontal
                }
            }
            if (horizontalShifts.size < MIN_VISUAL_MATCH_COUNT) return null
            val center = median(horizontalShifts)
            val deviations = horizontalShifts.map { abs(it - center) }
            val maxDeviation = max(0.008, median(deviations) * 3.5)
            val filtered = horizontalShifts.filter { abs(it - center) <= maxDeviation }
            if (filtered.size < MIN_VISUAL_MATCH_COUNT) null else median(filtered)
        } finally {
            matches.forEach(MatOfDMatch::release)
            matcher.clear()
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun writeTimelineLocked() {
        val frames = JSONArray()
        finalCandidates.sortedBy { it.binIndex }.forEach {
            frames.put(JSONObject(it.flutterValue()))
        }
        val root = JSONObject().apply {
            put("captureSource", captureSource)
            put("platform", "android")
            put("selectedLap", (selectedLapIndex ?: 0) + 1)
            put("binCount", binCount)
            put("videoPath", videoPath)
            put("visualMotionReliable", visualMotionReliable)
            put("visualMotionSampleCount", visualMotionSampleCount)
            put("visualMotionMatchedPairCount", visualMotionMatchedPairCount)
            put("visualCumulativeMotion", visualCumulativeMotion)
            put("visualDirectionConsistency", visualDirectionConsistency)
            put("appliedFrameRotationDegrees", appliedFrameRotationDegrees.toDouble())
            put("frames", frames)
        }
        File(timelinePath).writeText(root.toString(2))
    }

    private fun statusLocked(): Map<String, Any> {
        val captureProgress = if (durationNanos > 0) {
            min(1.0, elapsedNanos.toDouble() / durationNanos)
        } else {
            0.0
        }
        val progress = if (captureSource == "importedVideo" && expectedProcessingFrameCount > 0) {
            min(1.0, processedFrameCount.toDouble() / expectedProcessingFrameCount)
        } else {
            captureProgress
        }
        val frames = finalCandidates.sortedBy { it.binIndex }
        val selectedBins = frames.map { it.binIndex }.toSet()
        val completedLaps = min(requiredLaps, (progress * requiredLaps).toInt())
        return mapOf(
            "running" to running,
            "finishing" to finishing,
            "complete" to complete,
            "failed" to failed,
            "message" to message,
            "trackingState" to if (visualMotionReliable) "visual_motion" else "visual_timed",
            "direction" to if (visualMotionReliable) "visual" else "timed",
            "movingWrongDirection" to false,
            "progress" to progress,
            "progressDegrees" to progress * requiredLaps * 360.0,
            "completedLaps" to completedLaps,
            "requiredLaps" to requiredLaps,
            "binCount" to binCount,
            "selectedCount" to frames.size,
            "selectedLap" to ((selectedLapIndex ?: -1) + 1),
            "lapCandidateCounts" to lapCandidateCounts,
            "missingBins" to (0 until binCount).filter { it !in selectedBins },
            "currentPitchDegrees" to 0.0,
            "currentRollDegrees" to 0.0,
            "currentAngularSpeed" to TWO_PI / SECONDS_PER_LAP,
            "currentTranslationMeters" to 0.0,
            "maxTranslationMeters" to 0.0,
            "processedFrameCount" to processedFrameCount,
            "encodedCandidateCount" to encodedCandidateCount,
            "recordedVideoFrameCount" to (elapsedNanos / 1_000_000_000.0 * 30).toInt(),
            "droppedVideoFrameCount" to 0,
            "videoPath" to videoPath,
            "videoTimelinePath" to timelinePath,
            "captureSource" to captureSource,
            "visualMotionReliable" to visualMotionReliable,
            "visualMotionSampleCount" to visualMotionSampleCount,
            "visualMotionMatchedPairCount" to visualMotionMatchedPairCount,
            "visualCumulativeMotion" to visualCumulativeMotion,
            "visualDirectionConsistency" to visualDirectionConsistency,
            "appliedFrameRotationDegrees" to appliedFrameRotationDegrees.toDouble(),
            "rejectedTrackingFrameCount" to 0,
            "rejectedTranslationFrameCount" to 0,
            "frames" to frames.map { it.flutterValue() },
        )
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun normalizeVideoBitmap(source: Bitmap, metadataRotationDegrees: Float): Bitmap {
        val metadataRotation = ((metadataRotationDegrees % 360f) + 360f) % 360f
        val portraitRotation = when {
            source.height >= source.width -> 0f
            metadataRotation % 180f != 0f -> metadataRotation
            else -> 90f
        }
        appliedFrameRotationDegrees = portraitRotation
        return rotateBitmap(source, portraitRotation)
    }

    private fun fail(detail: String) {
        synchronized(stateLock) {
            running = false
            finishing = false
            complete = false
            failed = true
            message = detail
            emitStatusLocked()
        }
        unbindCamera()
    }

    private fun emitStatus() {
        synchronized(stateLock) { emitStatusLocked() }
    }

    private fun emitStatusLocked() {
        val snapshot = statusLocked()
        mainHandler.post {
            statusDidChange(snapshot)
            updateKeepScreenOn()
        }
    }

    private fun updateKeepScreenOn() {
        val active = synchronized(stateLock) { running || finishing }
        mainHandler.post { previewView.get()?.keepScreenOn = active }
    }

    private fun unbindCamera() {
        mainHandler.post { cameraProvider?.unbindAll() }
    }

    private fun isoDate(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    companion object {
        private const val SECONDS_PER_LAP = 15L
        private const val TWO_PI = PI * 2
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_SAMPLES_PER_TARGET = 6
        private const val MAX_ANALYSIS_FRAME_COUNT = 360
        private const val MIN_VISUAL_MATCH_COUNT = 8
        private const val MIN_VISUAL_MOTION = 0.001
        private const val MAX_VISUAL_MOTION = 0.42
        private const val MIN_MOTION_PER_LAP = 0.75
        private const val MIN_DIRECTION_CONSISTENCY = 0.68
        private val IDENTITY_TRANSFORM = listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }
}
