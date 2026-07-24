package com.giro360.giro360_capture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class Giro360AndroidFrameCandidate(
    val binIndex: Int,
    val lapIndex: Int,
    val filePath: String,
    val targetYaw: Double,
    val relativeYaw: Double,
    val pitch: Double,
    val roll: Double,
    val translationMeters: Double,
    val qualityScore: Double,
    val sharpnessScore: Double,
    val angularSpeed: Double,
    val centerError: Double,
    val trackingState: String,
    val capturedAt: String,
    val frameTimestamp: Double,
    val cameraIntrinsics: List<Double>,
    val cameraTransform: List<Double>,
) {
    fun flutterValue(): Map<String, Any> = mapOf(
        "binIndex" to binIndex,
        "lapIndex" to lapIndex,
        "filePath" to filePath,
        "targetYawRadians" to targetYaw,
        "relativeYawRadians" to relativeYaw,
        "pitchRadians" to pitch,
        "rollRadians" to roll,
        "translationMeters" to translationMeters,
        "qualityScore" to qualityScore,
        "sharpnessScore" to sharpnessScore,
        "angularSpeedRadiansPerSecond" to angularSpeed,
        "centerErrorRadians" to centerError,
        "trackingState" to trackingState,
        "capturedAt" to capturedAt,
        "frameTimestampSeconds" to frameTimestamp,
        "videoTimeSeconds" to frameTimestamp,
        "cameraIntrinsics" to cameraIntrinsics,
        "cameraTransform" to cameraTransform,
    )
}

internal class Giro360CaptureCoordinator(
    context: Context,
    private val statusDidChange: (Map<String, Any>) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val previewView = AtomicReference<View?>()

    @Volatile
    private var session: Session? = null
    private var activity: Activity? = null
    private var cameraTextureName = 0
    private var displayRotation = Surface.ROTATION_0
    private var displayWidth = 1
    private var displayHeight = 1

    private var directoryPath = ""
    private var binCount = 30
    private var requiredLaps = 2
    private var running = false
    private var finishing = false
    private var complete = false
    private var failed = false
    private var message = "Pronto para iniciar."
    private var trackingState = "not_available"
    private var firstFrameTimestamp: Long? = null
    private var initialPosition: FloatArray? = null
    private var lastYaw: Double? = null
    private var lastFrameTimestamp: Long? = null
    private var lastSelectionTimestamp: Long = 0
    private var unwrappedYaw = 0.0
    private var direction = 0.0
    private var maxProgress = 0.0
    private var currentTranslationMeters = 0.0
    private var maxTranslationMeters = 0.0
    private var currentPitch = 0.0
    private var currentRoll = 0.0
    private var currentAngularSpeed = 0.0
    private var movingWrongDirection = false
    private var processedFrameCount = 0
    private var rejectedTrackingFrameCount = 0
    private var rejectedTranslationFrameCount = 0
    private var encodedCandidateCount = 0
    private var videoTimelinePath = ""
    private var selectedLapIndex: Int? = null
    private var lastStatusEmissionNanos = 0L
    private val candidates = mutableMapOf<Int, Giro360AndroidFrameCandidate>()
    private val finalCandidates = mutableMapOf<Int, Giro360AndroidFrameCandidate>()

    fun attachPreview(view: View) {
        previewView.set(view)
        updateKeepScreenOn()
    }

    fun detachPreview(view: View) {
        previewView.compareAndSet(view, null)
    }

    fun setCameraTextureName(textureName: Int) {
        cameraTextureName = textureName
        session?.setCameraTextureName(textureName)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        displayRotation = rotation
        displayWidth = width.coerceAtLeast(1)
        displayHeight = height.coerceAtLeast(1)
        session?.setDisplayGeometry(displayRotation, displayWidth, displayHeight)
    }

    fun startCapture(
        activity: Activity,
        directoryPath: String,
        binCount: Int,
        requiredLaps: Int,
    ) {
        File(directoryPath).mkdirs()
        closeSession()

        val newSession = Session(activity)
        val cameraConfigs = newSession.getSupportedCameraConfigs(
            CameraConfigFilter(newSession).setTargetFps(
                EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30),
            ),
        )
        cameraConfigs.maxByOrNull {
            it.imageSize.width.toLong() * it.imageSize.height.toLong()
        }?.let { newSession.cameraConfig = it }

        val config = Config(newSession).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        }
        newSession.configure(config)
        if (cameraTextureName != 0) {
            newSession.setCameraTextureName(cameraTextureName)
        }
        newSession.setDisplayGeometry(displayRotation, displayWidth, displayHeight)

        synchronized(stateLock) {
            this.activity = activity
            this.directoryPath = directoryPath
            this.binCount = binCount
            this.requiredLaps = requiredLaps
            running = true
            finishing = false
            complete = false
            failed = false
            message = "Comece a girar devagar para um lado."
            trackingState = "initializing"
            firstFrameTimestamp = null
            initialPosition = null
            lastYaw = null
            lastFrameTimestamp = null
            lastSelectionTimestamp = 0
            unwrappedYaw = 0.0
            direction = 0.0
            maxProgress = 0.0
            currentTranslationMeters = 0.0
            maxTranslationMeters = 0.0
            currentPitch = 0.0
            currentRoll = 0.0
            currentAngularSpeed = 0.0
            movingWrongDirection = false
            processedFrameCount = 0
            rejectedTrackingFrameCount = 0
            rejectedTranslationFrameCount = 0
            encodedCandidateCount = 0
            videoTimelinePath = "$directoryPath/giro360_android_timeline.json"
            selectedLapIndex = null
            lastStatusEmissionNanos = 0
            candidates.clear()
            finalCandidates.clear()
        }

        session = newSession
        newSession.resume()
        emitStatus(force = true)
    }

    fun updateFrame(): Frame? {
        val currentSession = session ?: return null
        return try {
            val frame = currentSession.update()
            processFrame(frame)
            frame
        } catch (error: Throwable) {
            synchronized(stateLock) {
                if (running || finishing) {
                    failLocked("Falha no ARCore: ${error.localizedMessage}")
                }
            }
            emitStatus(force = true)
            null
        }
    }

    fun status(): Map<String, Any> = synchronized(stateLock) {
        statusSnapshotLocked()
    }

    fun cancelCapture() {
        synchronized(stateLock) {
            if (!running && !finishing) return
            running = false
            finishing = false
            complete = false
            message = "Captura cancelada."
        }
        pause()
        emitStatus(force = true)
    }

    fun pause() {
        try {
            session?.pause()
        } catch (_: Throwable) {
            // A sessão pode já ter sido interrompida pelo sistema.
        }
        updateKeepScreenOn()
    }

    fun close() {
        cancelCapture()
        closeSession()
    }

    private fun closeSession() {
        val previous = session
        session = null
        try {
            previous?.pause()
        } catch (_: Throwable) {
            // Nada a fazer.
        }
        previous?.close()
    }

    private fun processFrame(frame: Frame) {
        val camera = frame.camera
        val frameTrackingState = when (camera.trackingState) {
            TrackingState.TRACKING -> "normal"
            TrackingState.PAUSED -> "limited"
            TrackingState.STOPPED -> "not_available"
        }

        synchronized(stateLock) {
            if (!running || finishing) return
            processedFrameCount += 1
            trackingState = frameTrackingState

            val pose = camera.displayOrientedPose
            val position = pose.translation
            val forward = pose.rotateVector(floatArrayOf(0f, 0f, -1f))
            val up = pose.rotateVector(floatArrayOf(0f, 1f, 0f))
            val yaw = atan2(forward[0].toDouble(), -forward[2].toDouble())
            val pitch = atan2(
                forward[1].toDouble(),
                max(0.000001, hypot(forward[0].toDouble(), forward[2].toDouble())),
            )
            val roll = atan2(up[0].toDouble(), max(0.000001, up[1].toDouble()))
            currentPitch = pitch
            currentRoll = roll

            if (firstFrameTimestamp == null) {
                firstFrameTimestamp = frame.timestamp
                initialPosition = position.copyOf()
                lastYaw = yaw
                lastFrameTimestamp = frame.timestamp
                emitStatus(force = true)
                return
            }

            initialPosition?.let { origin ->
                currentTranslationMeters = distance(position, origin)
                maxTranslationMeters = max(maxTranslationMeters, currentTranslationMeters)
            }

            val previousYaw = lastYaw ?: yaw
            val previousTimestamp = lastFrameTimestamp ?: frame.timestamp
            lastYaw = yaw
            lastFrameTimestamp = frame.timestamp
            val deltaTime = max(0.001, (frame.timestamp - previousTimestamp) / 1_000_000_000.0)
            val yawDelta = normalizedAngle(yaw - previousYaw)
            currentAngularSpeed = abs(yawDelta) / deltaTime
            if (abs(yawDelta) > 0.45) return
            unwrappedYaw += yawDelta

            if (direction == 0.0 && abs(unwrappedYaw) >= 0.18) {
                direction = if (unwrappedYaw >= 0) 1.0 else -1.0
                message = "Direção definida. Continue no mesmo sentido por duas voltas."
            }
            if (direction == 0.0) return

            val directedProgress = direction * unwrappedYaw
            movingWrongDirection = direction * yawDelta < -0.002
            if (directedProgress > maxProgress) maxProgress = directedProgress

            message = when {
                currentTranslationMeters > 0.12 -> {
                    rejectedTranslationFrameCount += 1
                    "A lente saiu do eixo. Reposicione sem voltar a rotação."
                }

                movingWrongDirection -> "Continue no mesmo sentido da volta."
                camera.trackingState == TrackingState.TRACKING ->
                    "Continue devagar, mantendo a lente no mesmo ponto."

                else -> {
                    rejectedTrackingFrameCount += 1
                    "Rastreamento limitado. Diminua a velocidade e aponte para detalhes."
                }
            }

            val totalProgress = requiredLaps * TWO_PI
            if (maxProgress >= totalProgress) {
                finishCaptureLocked()
                return
            }

            val selectionDelay = frame.timestamp - lastSelectionTimestamp
            if (camera.trackingState != TrackingState.TRACKING ||
                currentTranslationMeters > 0.18 ||
                directedProgress < maxProgress - 0.08 ||
                selectionDelay < 80_000_000L
            ) {
                emitStatus()
                return
            }
            lastSelectionTimestamp = frame.timestamp
            considerFrameLocked(frame, directedProgress, pitch, roll)
            emitStatus()
        }
    }

    private fun considerFrameLocked(
        frame: Frame,
        directedProgress: Double,
        pitch: Double,
        roll: Double,
    ) {
        val step = TWO_PI / binCount
        val circularYaw = positiveModulo(directedProgress, TWO_PI)
        val nearestBin = (circularYaw / step).roundToInt() % binCount
        val targetYaw = nearestBin * step
        val centerError = abs(normalizedAngle(circularYaw - targetYaw))
        if (centerError > step * 0.52) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        }

        try {
            val sharpness = lumaSharpness(image)
            val sharpnessScore = min(34.0, sharpness * 0.55)
            val centerScore = max(0.0, 22.0 * (1 - centerError / (step * 0.52)))
            val speedPenalty = max(0.0, currentAngularSpeed - 0.28) * 12
            val pitchPenalty = abs(pitch) * 18
            val translationPenalty = currentTranslationMeters * 135
            val score = 24.0 + sharpnessScore + centerScore - speedPenalty -
                pitchPenalty - translationPenalty
            val lapIndex = min(
                requiredLaps - 1,
                max(0, floor(directedProgress / TWO_PI).toInt()),
            )
            val key = lapIndex * binCount + nearestBin
            val previousScore = candidates[key]?.qualityScore ?: -Double.MAX_VALUE
            if (score < previousScore + 0.35) return

            val candidatePath = String.format(
                Locale.US,
                "%s/android_lap_%02d_bin_%03d.jpg",
                directoryPath,
                lapIndex,
                nearestBin,
            )
            val jpeg = imageToPortraitJpeg(image)
            File(candidatePath).writeBytes(jpeg)

            val poseMatrix = FloatArray(16)
            frame.camera.displayOrientedPose.toMatrix(poseMatrix, 0)
            val intrinsics = frame.camera.imageIntrinsics
            val focal = intrinsics.focalLength
            val principal = intrinsics.principalPoint
            val timestampSeconds = (frame.timestamp - (firstFrameTimestamp ?: frame.timestamp)) /
                1_000_000_000.0

            candidates[key] = Giro360AndroidFrameCandidate(
                binIndex = nearestBin,
                lapIndex = lapIndex,
                filePath = candidatePath,
                targetYaw = targetYaw,
                relativeYaw = circularYaw,
                pitch = pitch,
                roll = roll,
                translationMeters = currentTranslationMeters,
                qualityScore = score,
                sharpnessScore = sharpness,
                angularSpeed = currentAngularSpeed,
                centerError = centerError,
                trackingState = trackingState,
                capturedAt = isoDate(),
                frameTimestamp = timestampSeconds,
                cameraIntrinsics = listOf(
                    focal[0].toDouble(), 0.0, principal[0].toDouble(),
                    0.0, focal[1].toDouble(), principal[1].toDouble(),
                    0.0, 0.0, 1.0,
                ),
                cameraTransform = poseMatrix.map(Float::toDouble),
            )
        } finally {
            image.close()
        }
    }

    private fun finishCaptureLocked() {
        if (finishing) return
        running = false
        finishing = true
        message = "Selecionando os melhores frames da volta..."
        try {
            session?.pause()
            val selection = bestCoherentLapCandidatesLocked()
            selectedLapIndex = selection.first
            finalCandidates.clear()

            selection.second.forEach { candidate ->
                val finalPath = String.format(
                    Locale.US,
                    "%s/video_%03d.jpg",
                    directoryPath,
                    candidate.binIndex,
                )
                File(candidate.filePath).copyTo(File(finalPath), overwrite = true)
                finalCandidates[candidate.binIndex] = candidate.copy(filePath = finalPath)
            }
            encodedCandidateCount = finalCandidates.size
            writeTimelineLocked()
            finishing = false

            val minimumFrameCount = max(24, (binCount * 0.75).toInt())
            if (encodedCandidateCount < minimumFrameCount) {
                failed = true
                message = "Só $encodedCandidateCount/$binCount frames úteis foram capturados."
            } else {
                complete = true
                message = "Volta ${(selectedLapIndex ?: 0) + 1} selecionada com " +
                    "$encodedCandidateCount frames Android."
            }
        } catch (error: Throwable) {
            failLocked("Falha ao finalizar os frames Android: ${error.localizedMessage}")
        }
        emitStatus(force = true)
    }

    private fun writeTimelineLocked() {
        val frames = finalCandidates.values.sortedBy { it.binIndex }
        val jsonFrames = JSONArray()
        frames.forEach { frame ->
            jsonFrames.put(JSONObject(frame.flutterValue()))
        }
        val root = JSONObject().apply {
            put("captureSource", "directFrames")
            put("platform", "android")
            put("selectedLap", (selectedLapIndex ?: 0) + 1)
            put("binCount", binCount)
            put("frames", jsonFrames)
        }
        File(videoTimelinePath).writeText(root.toString(2))
    }

    private fun bestCoherentLapCandidatesLocked(): Pair<Int?, List<Giro360AndroidFrameCandidate>> {
        var bestLap: Int? = null
        var bestFrames = emptyList<Giro360AndroidFrameCandidate>()
        var bestScore = -Double.MAX_VALUE
        for (lap in 0 until requiredLaps) {
            val frames = candidates.values.filter { it.lapIndex == lap }.sortedBy { it.binIndex }
            if (frames.isEmpty()) continue
            val score = coherentLapScore(frames)
            if (frames.size > bestFrames.size ||
                (frames.size == bestFrames.size && score > bestScore)
            ) {
                bestLap = lap
                bestFrames = frames
                bestScore = score
            }
        }
        return bestLap to bestFrames
    }

    private fun coherentLapScore(frames: List<Giro360AndroidFrameCandidate>): Double {
        val count = frames.size.toDouble()
        val averageSharpness = frames.sumOf { it.sharpnessScore } / count
        val averageCenterError = frames.sumOf { it.centerError } / count
        val averageAngularSpeed = frames.sumOf { it.angularSpeed } / count
        val pitches = frames.map { it.pitch }
        val translations = frames.map { it.translationMeters }
        val pitchSpan = (pitches.maxOrNull() ?: 0.0) - (pitches.minOrNull() ?: 0.0)
        val translationSpan = (translations.maxOrNull() ?: 0.0) -
            (translations.minOrNull() ?: 0.0)
        return averageSharpness * 0.8 - pitchSpan * 80 - translationSpan * 120 -
            averageCenterError * 50 - averageAngularSpeed * 3
    }

    private fun statusSnapshotLocked(): Map<String, Any> {
        val totalRadians = requiredLaps * TWO_PI
        val progress = if (totalRadians > 0) min(1.0, maxProgress / totalRadians) else 0.0
        val coherentSelection = bestCoherentLapCandidatesLocked()
        val statusCandidates = if (finalCandidates.isEmpty()) {
            coherentSelection.second
        } else {
            finalCandidates.values.sortedBy { it.binIndex }
        }
        val selectedBins = statusCandidates.map { it.binIndex }.toSet()
        val directionLabel = when {
            direction > 0 -> "right"
            direction < 0 -> "left"
            else -> "pending"
        }

        return mapOf(
            "running" to running,
            "finishing" to finishing,
            "complete" to complete,
            "failed" to failed,
            "message" to message,
            "trackingState" to trackingState,
            "direction" to directionLabel,
            "movingWrongDirection" to movingWrongDirection,
            "progress" to progress,
            "progressDegrees" to maxProgress * 180 / PI,
            "completedLaps" to min(requiredLaps, (maxProgress / TWO_PI).toInt()),
            "requiredLaps" to requiredLaps,
            "binCount" to binCount,
            "selectedCount" to statusCandidates.size,
            "selectedLap" to ((selectedLapIndex ?: coherentSelection.first ?: -1) + 1),
            "lapCandidateCounts" to (0 until requiredLaps).map { lap ->
                candidates.values.count { it.lapIndex == lap }
            },
            "missingBins" to (0 until binCount).filter { it !in selectedBins },
            "currentPitchDegrees" to currentPitch * 180 / PI,
            "currentRollDegrees" to currentRoll * 180 / PI,
            "currentAngularSpeed" to currentAngularSpeed,
            "currentTranslationMeters" to currentTranslationMeters,
            "maxTranslationMeters" to maxTranslationMeters,
            "processedFrameCount" to processedFrameCount,
            "encodedCandidateCount" to encodedCandidateCount,
            "recordedVideoFrameCount" to 0,
            "droppedVideoFrameCount" to 0,
            "videoPath" to "",
            "videoTimelinePath" to videoTimelinePath,
            "captureSource" to "directFrames",
            "rejectedTrackingFrameCount" to rejectedTrackingFrameCount,
            "rejectedTranslationFrameCount" to rejectedTranslationFrameCount,
            "frames" to statusCandidates.map { it.flutterValue() },
        )
    }

    private fun emitStatus(force: Boolean = false) {
        val now = System.nanoTime()
        synchronized(stateLock) {
            if (!force && now - lastStatusEmissionNanos < 150_000_000L) return
            lastStatusEmissionNanos = now
            val snapshot = statusSnapshotLocked()
            mainHandler.post {
                statusDidChange(snapshot)
                updateKeepScreenOn()
            }
        }
    }

    private fun updateKeepScreenOn() {
        val active = synchronized(stateLock) { running || finishing }
        mainHandler.post { previewView.get()?.keepScreenOn = active }
    }

    private fun failLocked(detail: String) {
        running = false
        finishing = false
        failed = true
        complete = false
        message = detail
    }

    private fun imageToPortraitJpeg(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(image.planes[0], width, height, nv21, 0, 1)

        var output = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val u = image.planes[1]
        val v = image.planes[2]
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                nv21[output++] = planeByte(v, row, column)
                nv21[output++] = planeByte(u, row, column)
            }
        }

        val rawStream = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(android.graphics.Rect(0, 0, width, height), 94, rawStream)
        val rawJpeg = rawStream.toByteArray()
        val rotation = imageRotationDegrees()
        if (rotation == 0) return rawJpeg

        val source = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        val rotatedStream = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 94, rotatedStream)
        if (rotated !== source) rotated.recycle()
        source.recycle()
        return rotatedStream.toByteArray()
    }

    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        outputPixelStride: Int,
    ) {
        var outputIndex = offset
        for (row in 0 until height) {
            for (column in 0 until width) {
                output[outputIndex] = planeByte(plane, row, column)
                outputIndex += outputPixelStride
            }
        }
    }

    private fun planeByte(plane: Image.Plane, row: Int, column: Int): Byte {
        val index = row * plane.rowStride + column * plane.pixelStride
        return if (index < plane.buffer.limit()) plane.buffer.get(index) else 0
    }

    private fun lumaSharpness(image: Image): Double {
        val plane = image.planes[0]
        var total = 0.0
        var samples = 0
        var y = 8
        while (y < image.height - 8) {
            var x = 8
            while (x < image.width - 8) {
                val center = planeByte(plane, y, x).toInt() and 0xff
                val left = planeByte(plane, y, x - 4).toInt() and 0xff
                val right = planeByte(plane, y, x + 4).toInt() and 0xff
                val top = planeByte(plane, y - 4, x).toInt() and 0xff
                val bottom = planeByte(plane, y + 4, x).toInt() and 0xff
                total += abs(4 * center - left - right - top - bottom)
                samples += 1
                x += 12
            }
            y += 12
        }
        return if (samples == 0) 0.0 else total / samples
    }

    private fun imageRotationDegrees(): Int {
        val currentActivity = activity ?: return 90
        val currentSession = session ?: return 90
        val cameraManager =
            applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sensorOrientation = try {
            cameraManager.getCameraCharacteristics(currentSession.cameraConfig.cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Throwable) {
            90
        }
        val rotation = currentActivity.windowManager.defaultDisplay.rotation
        val displayDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val x = (a[0] - b[0]).toDouble()
        val y = (a[1] - b[1]).toDouble()
        val z = (a[2] - b[2]).toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private fun normalizedAngle(value: Double): Double {
        var angle = value
        while (angle > PI) angle -= TWO_PI
        while (angle < -PI) angle += TWO_PI
        return angle
    }

    private fun positiveModulo(value: Double, modulus: Double): Double =
        ((value % modulus) + modulus) % modulus

    private fun isoDate(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        private const val TWO_PI = PI * 2
    }
}
