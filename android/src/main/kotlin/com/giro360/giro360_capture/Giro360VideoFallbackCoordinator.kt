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
        val lapFrames = mutableListOf<List<Giro360AndroidFrameCandidate>>()
        try {
            retriever.setDataSource(videoPath)
            val durationMillis = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()?.coerceAtLeast(1L) ?: error("Duração do vídeo indisponível.")
            val rotationDegrees = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toFloatOrNull() ?: 0f
            val totalDurationUs = durationMillis * 1000L
            val step = TWO_PI / binCount

            for (lapIndex in 0 until requiredLaps) {
                val frames = mutableListOf<Giro360AndroidFrameCandidate>()
                for (binIndex in 0 until binCount) {
                    if (synchronized(stateLock) { cancelled }) return
                    val normalizedTime = (
                        lapIndex + (binIndex + 0.5) / binCount.toDouble()
                        ) / requiredLaps.toDouble()
                    val timestampUs = (normalizedTime * totalDurationUs).toLong()
                    val raw = retriever.getFrameAtTime(
                        timestampUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: continue
                    val bitmap = rotateBitmap(raw, rotationDegrees)
                    val sharpness = sharpness(bitmap)
                    val candidatePath = String.format(
                        Locale.US,
                        "%s/video_lap_%02d_bin_%03d.jpg",
                        directoryPath,
                        lapIndex,
                        binIndex,
                    )
                    FileOutputStream(candidatePath).use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)
                    }
                    if (bitmap !== raw) bitmap.recycle()
                    raw.recycle()
                    processedFrameCount += 1
                    if (processedFrameCount % 3 == 0 || processedFrameCount == binCount * requiredLaps) {
                        synchronized(stateLock) {
                            message = "Extraindo frame $processedFrameCount/${binCount * requiredLaps} do vídeo..."
                            emitStatusLocked()
                        }
                    }
                    val yaw = binIndex * step
                    frames += Giro360AndroidFrameCandidate(
                        binIndex = binIndex,
                        lapIndex = lapIndex,
                        filePath = candidatePath,
                        targetYaw = yaw,
                        relativeYaw = yaw,
                        pitch = 0.0,
                        roll = 0.0,
                        translationMeters = 0.0,
                        qualityScore = sharpness,
                        sharpnessScore = sharpness,
                        angularSpeed = TWO_PI / SECONDS_PER_LAP,
                        centerError = 0.0,
                        trackingState = "visual_timed",
                        capturedAt = isoDate(),
                        frameTimestamp = timestampUs / 1_000_000.0,
                        cameraIntrinsics = emptyList(),
                        cameraTransform = IDENTITY_TRANSFORM,
                    )
                }
                lapFrames += frames
                synchronized(stateLock) {
                    message = "Volta ${lapIndex + 1}/$requiredLaps analisada."
                    emitStatusLocked()
                }
            }

            val bestLap = lapFrames.indices.maxWithOrNull(
                compareBy<Int> { lapFrames[it].size }
                    .thenBy { averageSharpness(lapFrames[it]) }
                    .thenBy { it },
            ) ?: error("Nenhuma volta pôde ser extraída do vídeo.")
            val selected = lapFrames[bestLap]
            val minimumFrameCount = max(24, (binCount * 0.75).toInt())
            if (selected.size < minimumFrameCount) {
                error("Só ${selected.size}/$binCount frames puderam ser extraídos.")
            }

            val finalFrames = selected.map { candidate ->
                val finalPath = String.format(
                    Locale.US,
                    "%s/video_%03d.jpg",
                    directoryPath,
                    candidate.binIndex,
                )
                File(candidate.filePath).copyTo(File(finalPath), overwrite = true)
                candidate.copy(filePath = finalPath)
            }
            synchronized(stateLock) {
                selectedLapIndex = bestLap
                finalCandidates = finalFrames
                encodedCandidateCount = finalFrames.size
                writeTimelineLocked()
                finishing = false
                complete = true
                message = "Volta ${bestLap + 1} selecionada do vídeo com ${finalFrames.size} frames."
                emitStatusLocked()
            }
        } catch (error: Throwable) {
            fail("Falha ao analisar o vídeo: ${error.localizedMessage}")
        } finally {
            retriever.release()
            unbindCamera()
        }
    }

    private fun writeTimelineLocked() {
        val frames = JSONArray()
        finalCandidates.sortedBy { it.binIndex }.forEach {
            frames.put(JSONObject(it.flutterValue()))
        }
        val root = JSONObject().apply {
            put("captureSource", "videoOnly")
            put("platform", "android")
            put("selectedLap", (selectedLapIndex ?: 0) + 1)
            put("binCount", binCount)
            put("videoPath", videoPath)
            put("frames", frames)
        }
        File(timelinePath).writeText(root.toString(2))
    }

    private fun statusLocked(): Map<String, Any> {
        val progress = if (durationNanos > 0) {
            min(1.0, elapsedNanos.toDouble() / durationNanos)
        } else {
            0.0
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
            "trackingState" to "visual_timed",
            "direction" to "timed",
            "movingWrongDirection" to false,
            "progress" to progress,
            "progressDegrees" to progress * requiredLaps * 360.0,
            "completedLaps" to completedLaps,
            "requiredLaps" to requiredLaps,
            "binCount" to binCount,
            "selectedCount" to frames.size,
            "selectedLap" to ((selectedLapIndex ?: -1) + 1),
            "lapCandidateCounts" to (0 until requiredLaps).map { lap ->
                if (complete && lap == selectedLapIndex) frames.size else 0
            },
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
            "captureSource" to "videoOnly",
            "rejectedTrackingFrameCount" to 0,
            "rejectedTranslationFrameCount" to 0,
            "frames" to frames.map { it.flutterValue() },
        )
    }

    private fun sharpness(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0.0
        var total = 0.0
        var samples = 0
        val step = max(2, min(width, height) / 240)
        var y = step
        while (y < height - step) {
            var x = step
            while (x < width - step) {
                val center = luma(bitmap.getPixel(x, y))
                total += abs(center - luma(bitmap.getPixel(x + step, y)))
                total += abs(center - luma(bitmap.getPixel(x, y + step)))
                samples += 2
                x += step
            }
            y += step
        }
        return if (samples == 0) 0.0 else total / samples
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun luma(color: Int): Double {
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        return red * 0.299 + green * 0.587 + blue * 0.114
    }

    private fun averageSharpness(frames: List<Giro360AndroidFrameCandidate>): Double =
        if (frames.isEmpty()) 0.0 else frames.sumOf { it.sharpnessScore } / frames.size

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
        private val IDENTITY_TRANSFORM = listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }
}
