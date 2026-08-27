package com.giro360.giro360_capture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan
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
    val selectionSource: String = "captured",
    val cameraImageWidth: Int = 0,
    val cameraImageHeight: Int = 0,
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
        "selectionSource" to selectionSource,
        "cameraImageWidth" to cameraImageWidth,
        "cameraImageHeight" to cameraImageHeight,
    )
}

private data class Giro360CalibrationDiagnostics(
    val available: Boolean = false,
    val stable: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val meanFxPixels: Double = 0.0,
    val meanFyPixels: Double = 0.0,
    val meanCxPixels: Double = 0.0,
    val meanCyPixels: Double = 0.0,
    val horizontalFovDegrees: Double = 0.0,
    val verticalFovDegrees: Double = 0.0,
    val focalLengthVariationPercent: Double = 0.0,
    val principalPointDriftPercent: Double = 0.0,
    val cameraCharacteristics: Map<String, Any> = emptyMap(),
) {
    fun value(): Map<String, Any> = mapOf(
        "available" to available,
        "stable" to stable,
        "imageWidth" to imageWidth,
        "imageHeight" to imageHeight,
        "meanFxPixels" to meanFxPixels,
        "meanFyPixels" to meanFyPixels,
        "meanCxPixels" to meanCxPixels,
        "meanCyPixels" to meanCyPixels,
        "horizontalFovDegrees" to horizontalFovDegrees,
        "verticalFovDegrees" to verticalFovDegrees,
        "focalLengthVariationPercent" to focalLengthVariationPercent,
        "principalPointDriftPercent" to principalPointDriftPercent,
        "cameraCharacteristics" to cameraCharacteristics,
    )
}

private data class Giro360LoopClosureDiagnostics(
    val matchedBinCount: Int = 0,
    val meanTranslationMeters: Double = 0.0,
    val maxTranslationMeters: Double = 0.0,
    val meanRotationDegrees: Double = 0.0,
    val maxRotationDegrees: Double = 0.0,
    val meanYawErrorDegrees: Double = 0.0,
    val maxYawErrorDegrees: Double = 0.0,
    val poseReliable: Boolean = false,
    val visualAnalyzed: Boolean = false,
    val visualReferenceBin: Int = -1,
    val visualRawMatchCount: Int = 0,
    val visualGoodMatchCount: Int = 0,
    val visualInlierCount: Int = 0,
    val visualInlierRatio: Double = 0.0,
    val visualSpatialCoverage: Double = 0.0,
    val visualMeanReprojectionErrorPixels: Double = 0.0,
    val visualReliable: Boolean = false,
) {
    fun value(): Map<String, Any> = mapOf(
        "matchedBinCount" to matchedBinCount,
        "meanTranslationMeters" to meanTranslationMeters,
        "maxTranslationMeters" to maxTranslationMeters,
        "meanRotationDegrees" to meanRotationDegrees,
        "maxRotationDegrees" to maxRotationDegrees,
        "meanYawErrorDegrees" to meanYawErrorDegrees,
        "maxYawErrorDegrees" to maxYawErrorDegrees,
        "poseReliable" to poseReliable,
        "visualAnalyzed" to visualAnalyzed,
        "visualReferenceBin" to visualReferenceBin,
        "visualRawMatchCount" to visualRawMatchCount,
        "visualGoodMatchCount" to visualGoodMatchCount,
        "visualInlierCount" to visualInlierCount,
        "visualInlierRatio" to visualInlierRatio,
        "visualSpatialCoverage" to visualSpatialCoverage,
        "visualMeanReprojectionErrorPixels" to visualMeanReprojectionErrorPixels,
        "visualReliable" to visualReliable,
    )
}

private data class Giro360ClosureFeatures(
    val keypoints: Array<org.opencv.core.KeyPoint>,
    val descriptors: Mat,
    val width: Int,
    val height: Int,
) {
    fun release() {
        descriptors.release()
    }
}

internal class Giro360CaptureCoordinator(
    context: Context,
    private val statusDidChange: (Map<String, Any>) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val previewView = AtomicReference<View?>()
    private val finalizationExecutor = Executors.newSingleThreadExecutor()

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
    private var rejectedCameraImageFrameCount = 0
    private var encodedCandidateCount = 0
    private var recordedVideoFrameCount = 0
    private var droppedVideoFrameCount = 0
    private var videoPath = ""
    private var videoTimelinePath = ""
    private var videoDurationSeconds = 0.0
    private var selectedFrameStartSeconds = 0.0
    private var selectedFrameEndSeconds = 0.0
    private var selectedLapIndex: Int? = null
    private val reconstructedBinIndices = mutableListOf<Int>()
    private var cameraCharacteristics = emptyMap<String, Any>()
    private var calibrationDiagnostics = Giro360CalibrationDiagnostics()
    private var loopClosureDiagnostics = Giro360LoopClosureDiagnostics()
    private var lastStatusEmissionNanos = 0L
    private var captureGeneration = 0L
    private val videoTimeline = mutableListOf<Map<String, Any>>()
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
        val captureConfig = cameraConfigs.maxByOrNull {
            it.imageSize.width.toLong() * it.imageSize.height
        }
        captureConfig?.let { newSession.cameraConfig = it }
        val selectedCameraConfig = newSession.cameraConfig
        val selectedCameraCharacteristics = readCameraCharacteristics(
            selectedCameraConfig.cameraId,
            selectedCameraConfig.imageSize.width,
            selectedCameraConfig.imageSize.height,
            selectedCameraConfig.textureSize.width,
            selectedCameraConfig.textureSize.height,
        )

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
            captureGeneration += 1
            this.activity = activity
            this.directoryPath = directoryPath
            this.binCount = binCount
            this.requiredLaps = requiredLaps
            running = true
            finishing = false
            complete = false
            failed = false
            message = "Mantenha a tela voltada para você e gire o corpo inteiro devagar."
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
            rejectedCameraImageFrameCount = 0
            encodedCandidateCount = 0
            recordedVideoFrameCount = 0
            droppedVideoFrameCount = 0
            videoPath = "$directoryPath/giro360_capture.mp4"
            videoTimelinePath = "$directoryPath/giro360_video_timeline.json"
            videoDurationSeconds = 0.0
            selectedFrameStartSeconds = 0.0
            selectedFrameEndSeconds = 0.0
            selectedLapIndex = null
            reconstructedBinIndices.clear()
            cameraCharacteristics = selectedCameraCharacteristics
            calibrationDiagnostics = Giro360CalibrationDiagnostics(
                cameraCharacteristics = selectedCameraCharacteristics,
            )
            loopClosureDiagnostics = Giro360LoopClosureDiagnostics()
            lastStatusEmissionNanos = 0
            videoTimeline.clear()
            candidates.clear()
            finalCandidates.clear()
        }

        session = newSession
        try {
            File(videoPath).delete()
            newSession.resume()
            newSession.startRecording(
                RecordingConfig(newSession)
                    .setMp4DatasetUri(Uri.fromFile(File(videoPath)))
                    .setAutoStopOnPause(false)
                    .setRecordingRotation(imageRotationDegrees()),
            )
        } catch (error: Throwable) {
            synchronized(stateLock) {
                failLocked(
                    "Não foi possível iniciar o vídeo ARCore: " +
                        (error.localizedMessage ?: error.javaClass.simpleName),
                )
            }
            closeSession()
            emitStatus(force = true)
            return
        }
        emitStatus(force = true)
    }

    fun updateFrame(): Frame? {
        if (!synchronized(stateLock) { running }) return null
        val currentSession = session ?: return null
        return try {
            val frame = currentSession.update()
            // ARCore can return a bootstrap frame with no media timestamp.
            // It must not become the origin for the recorded MP4 timeline.
            if (frame.timestamp > 0L) {
                processFrame(frame)
            }
            frame
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "ARCore frame update failed", error)
            synchronized(stateLock) {
                if (running) {
                    val detail = error.localizedMessage ?: error.javaClass.simpleName
                    failLocked("Falha no ARCore: $detail")
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
        var shouldStopRecording = false
        synchronized(stateLock) {
            if (!running && !finishing) return
            shouldStopRecording = running
            captureGeneration += 1
            running = false
            finishing = false
            complete = false
            message = "Captura cancelada."
        }
        if (shouldStopRecording) {
            try {
                session?.stopRecording()
            } catch (_: Throwable) {
                // A gravação pode ainda não ter produzido o primeiro frame.
            }
        }
        pause()
        emitStatus(force = true)
    }

    fun pause() {
        val interrupted = synchronized(stateLock) {
            if (!running) {
                false
            } else {
                captureGeneration += 1
                running = false
                finishing = false
                failed = true
                complete = false
                message = "Captura interrompida. Reinicie as duas voltas."
                true
            }
        }
        if (interrupted) {
            try {
                session?.stopRecording()
            } catch (_: Throwable) {
                // A gravação pode já ter sido interrompida pelo sistema.
            }
        }
        try {
            session?.pause()
        } catch (_: Throwable) {
            // A sessão pode já ter sido interrompida pelo sistema.
        }
        if (interrupted) emitStatus(force = true)
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
            previous?.stopRecording()
        } catch (_: Throwable) {
            // A sessão pode não estar gravando.
        }
        try {
            previous?.pause()
        } catch (_: Throwable) {
            // Nada a fazer.
        }
        previous?.close()
    }

    private fun processFrame(frame: Frame) {
        if (frame.timestamp <= 0L) return
        val camera = frame.camera
        val frameTrackingState = when (camera.trackingState) {
            TrackingState.TRACKING -> "normal"
            TrackingState.PAUSED -> "limited"
            TrackingState.STOPPED -> "not_available"
        }

        synchronized(stateLock) {
            if (!running || finishing) return
            processedFrameCount += 1
            recordedVideoFrameCount += 1
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
            val measuredAngularSpeed = abs(yawDelta) / deltaTime
            currentAngularSpeed = if (currentAngularSpeed == 0.0) {
                measuredAngularSpeed
            } else {
                currentAngularSpeed * 0.85 + measuredAngularSpeed * 0.15
            }
            if (abs(yawDelta) > 0.45) return
            unwrappedYaw += yawDelta

            videoTimeline += mapOf(
                "videoTimeSeconds" to
                    ((frame.timestamp - (firstFrameTimestamp ?: frame.timestamp)) /
                        1_000_000_000.0),
                "relativeYawRadians" to unwrappedYaw,
                "pitchRadians" to pitch,
                "rollRadians" to roll,
                "translationMeters" to currentTranslationMeters,
                "angularSpeedRadiansPerSecond" to currentAngularSpeed,
                "trackingState" to trackingState,
            )

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
                camera.trackingState != TrackingState.TRACKING -> {
                    rejectedTrackingFrameCount += 1
                    "Rastreamento limitado. Diminua a velocidade e aponte para detalhes."
                }

                rotationSpeedLabelLocked() == "too_fast" ->
                    "Muito rápido. Gire mais devagar para evitar imagens tremidas."

                rotationSpeedLabelLocked() == "too_slow" ->
                    "Muito devagar. Acelere um pouco e mantenha o movimento contínuo."

                else -> "Velocidade ideal. Mantenha o ritmo e a lente no mesmo ponto."
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

        val lapIndex = min(
            requiredLaps - 1,
            max(0, floor(directedProgress / TWO_PI).toInt()),
        )
        val key = lapIndex * binCount + nearestBin

        val sharpness = try {
            val image = frame.acquireCameraImage()
            try {
                lumaSharpness(image)
            } finally {
                image.close()
            }
        } catch (_: NotYetAvailableException) {
            rejectedCameraImageFrameCount += 1
            0.0
        }
        val sharpnessScore = min(34.0, sharpness * 0.55)
        val centerScore = max(0.0, 22.0 * (1 - centerError / (step * 0.52)))
        val speedPenalty = max(0.0, currentAngularSpeed - 0.28) * 12
        val pitchPenalty = abs(pitch) * 18
        val translationPenalty = currentTranslationMeters * 135
        val score = 24.0 + sharpnessScore + centerScore - speedPenalty -
            pitchPenalty - translationPenalty
        val previousScore = candidates[key]?.qualityScore ?: -Double.MAX_VALUE
        if (score < previousScore + 0.35) return

        val poseMatrix = FloatArray(16)
        frame.camera.displayOrientedPose.toMatrix(poseMatrix, 0)
        val intrinsics = frame.camera.imageIntrinsics
        val focal = intrinsics.focalLength
        val principal = intrinsics.principalPoint
        val imageDimensions = intrinsics.imageDimensions
        val candidatePath = String.format(
            Locale.US,
            "%s/video_%03d.jpg",
            directoryPath,
            nearestBin,
        )
        val frameTimestamp = (frame.timestamp - (firstFrameTimestamp ?: frame.timestamp)) /
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
            frameTimestamp = frameTimestamp,
            cameraIntrinsics = listOf(
                focal[0].toDouble(), 0.0, principal[0].toDouble(),
                0.0, focal[1].toDouble(), principal[1].toDouble(),
                0.0, 0.0, 1.0,
            ),
            cameraTransform = poseMatrix.map(Float::toDouble),
            cameraImageWidth = imageDimensions[0],
            cameraImageHeight = imageDimensions[1],
        )
        lastSelectionTimestamp = frame.timestamp
    }

    private fun finishCaptureLocked() {
        if (finishing) return
        running = false
        finishing = true
        message = "Salvando o vídeo das duas voltas..."
        val generation = captureGeneration
        try {
            session?.stopRecording()
            session?.pause()
        } catch (error: Throwable) {
            failLocked(
                "Falha ao salvar o vídeo Android: " +
                    (error.localizedMessage ?: error.javaClass.simpleName),
            )
            emitStatus(force = true)
            return
        }
        finalizationExecutor.execute { finalizeVideoCapture(generation) }
        emitStatus(force = true)
    }

    private fun finalizeVideoCapture(generation: Long) {
        synchronized(stateLock) {
            if (generation != captureGeneration || !finishing) return
            try {
                val selection = bestCoherentLapCandidatesLocked()
                selectedLapIndex = selection.first
                val completeSelection = reconstructMissingBinsLocked(
                    selection.first,
                    selection.second,
                )
                finalCandidates.clear()
                completeSelection.forEach { candidate ->
                    finalCandidates[candidate.binIndex] = candidate
                }
                calibrationDiagnostics = calculateCalibrationDiagnosticsLocked(
                    completeSelection,
                )
                loopClosureDiagnostics = calculateLoopClosureDiagnosticsLocked()
                writeTimelineLocked()
            } catch (error: Throwable) {
                failLocked(
                    "Falha ao preparar o vídeo Android: " +
                        (error.localizedMessage ?: error.javaClass.simpleName),
                )
                emitStatus(force = true)
                return
            }
        }

        val extractedCount = try {
            extractSelectedFrames()
        } catch (error: Throwable) {
            synchronized(stateLock) {
                failLocked(
                    "O vídeo foi salvo, mas a extração falhou: " +
                        (error.localizedMessage ?: error.javaClass.simpleName),
                )
            }
            emitStatus(force = true)
            return
        }

        synchronized(stateLock) {
            if (generation != captureGeneration || !finishing) return
            encodedCandidateCount = extractedCount
            finishing = false
            val minimumFrameCount = max(24, (binCount * 0.75).toInt())
            if (encodedCandidateCount < minimumFrameCount) {
                failed = true
                message = "O vídeo foi salvo, mas só $encodedCandidateCount/$binCount " +
                    "frames puderam ser extraídos."
            } else {
                complete = true
                val averageTranslation = finalCandidates.values
                    .map { it.translationMeters }
                    .average()
                val closureWarning = loopClosureDiagnostics.matchedBinCount > 0 &&
                    (!loopClosureDiagnostics.poseReliable ||
                        (loopClosureDiagnostics.visualAnalyzed &&
                            !loopClosureDiagnostics.visualReliable))
                message = if (closureWarning) {
                    "Panorama concluído, mas a volta fechou com desvio. " +
                        "Confira o eixo do tripé e a sobreposição."
                } else if (averageTranslation > AXIS_TRANSLATION_WARNING_METERS ||
                    maxTranslationMeters > AXIS_MAX_TRANSLATION_WARNING_METERS
                ) {
                    "Panorama concluído, mas a lente saiu do eixo. " +
                        "Posicione a câmera sobre o centro do tripé."
                } else {
                    "Vídeo salvo. Volta ${(selectedLapIndex ?: 0) + 1} " +
                        "selecionada com $encodedCandidateCount frames."
                }
            }
            Log.i(
                LOG_TAG,
                "Capture complete: selected=$encodedCandidateCount/$binCount, " +
                    "lapCounts=${lapCandidateCountsLocked()}, " +
                    "processed=$processedFrameCount, " +
                    "trackingRejected=$rejectedTrackingFrameCount, " +
                    "translationRejected=$rejectedTranslationFrameCount, " +
                    "cameraImageUnavailable=$rejectedCameraImageFrameCount, " +
                    "closurePoseReliable=${loopClosureDiagnostics.poseReliable}, " +
                    "closureVisualReliable=${loopClosureDiagnostics.visualReliable}",
            )
        }
        emitStatus(force = true)
    }

    private fun extractSelectedFrames(): Int {
        val selected = synchronized(stateLock) {
            finalCandidates.values.sortedBy { it.binIndex }
        }
        if (selected.isEmpty()) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMilliseconds = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: error("O MP4 não informou sua duração.")
            if (durationMilliseconds <= 0L) {
                error("O MP4 foi salvo sem uma duração válida.")
            }

            val durationSeconds = durationMilliseconds / 1_000.0
            val firstSelectedSeconds = selected.minOf { it.frameTimestamp }
            val lastSelectedSeconds = selected.maxOf { it.frameTimestamp }
            if (!firstSelectedSeconds.isFinite() ||
                !lastSelectedSeconds.isFinite() ||
                firstSelectedSeconds < 0.0 ||
                lastSelectedSeconds > durationSeconds + VIDEO_TIMESTAMP_TOLERANCE_SECONDS
            ) {
                error(
                    String.format(
                        Locale.US,
                        "Timestamps fora do vídeo: %.3f-%.3fs para MP4 de %.3fs.",
                        firstSelectedSeconds,
                        lastSelectedSeconds,
                        durationSeconds,
                    ),
                )
            }

            synchronized(stateLock) {
                videoDurationSeconds = durationSeconds
                selectedFrameStartSeconds = firstSelectedSeconds
                selectedFrameEndSeconds = lastSelectedSeconds
                writeTimelineLocked()
            }

            val visualClosurePair = synchronized(stateLock) {
                visualClosurePairLocked()
            }
            if (visualClosurePair != null) {
                val visualDiagnostics = analyzeVisualLoopClosure(
                    retriever,
                    visualClosurePair.first,
                    visualClosurePair.second,
                )
                synchronized(stateLock) {
                    loopClosureDiagnostics = visualDiagnostics
                    writeTimelineLocked()
                }
            }
            Log.i(
                LOG_TAG,
                String.format(
                    Locale.US,
                    "Extracting %d frames at %.3f-%.3fs from %.3fs video",
                    selected.size,
                    firstSelectedSeconds,
                    lastSelectedSeconds,
                    durationSeconds,
                ),
            )

            val lastValidTimestampMicros = max(0L, durationMilliseconds * 1_000L - 1_000L)
            var extracted = 0
            selected.forEach { candidate ->
                val requestedTimestampMicros =
                    (candidate.frameTimestamp * 1_000_000).toLong()
                        .coerceIn(0L, lastValidTimestampMicros)
                val frame = retriever.getFrameAtTime(
                    requestedTimestampMicros,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: return@forEach
                val portrait = ensurePortrait(frame)
                FileOutputStream(candidate.filePath).use { stream ->
                    portrait.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                }
                if (portrait !== frame) portrait.recycle()
                frame.recycle()
                extracted += 1
            }
            extracted
        } finally {
            retriever.release()
        }
    }

    private fun ensurePortrait(source: Bitmap): Bitmap {
        if (source.height >= source.width) return source
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(90f) },
            true,
        )
    }

    private fun writeTimelineLocked() {
        val jsonTimeline = JSONArray()
        videoTimeline.forEach { sample -> jsonTimeline.put(JSONObject(sample)) }
        val jsonFrames = JSONArray()
        finalCandidates.values.sortedBy { it.binIndex }.forEach { frame ->
            jsonFrames.put(JSONObject(frame.flutterValue()))
        }
        val root = JSONObject().apply {
            put("captureSource", "video")
            put("platform", "android")
            put("videoPath", videoPath)
            put("videoDurationSeconds", videoDurationSeconds)
            put("selectedFrameStartSeconds", selectedFrameStartSeconds)
            put("selectedFrameEndSeconds", selectedFrameEndSeconds)
            put("selectedLap", (selectedLapIndex ?: 0) + 1)
            put("binCount", binCount)
            put("reconstructedBins", JSONArray(reconstructedBinIndices))
            put("lapCandidateCounts", JSONArray(lapCandidateCountsLocked()))
            put("processedFrameCount", processedFrameCount)
            put("rejectedTrackingFrameCount", rejectedTrackingFrameCount)
            put("rejectedTranslationFrameCount", rejectedTranslationFrameCount)
            put("rejectedCameraImageFrameCount", rejectedCameraImageFrameCount)
            put("cameraCalibration", JSONObject(calibrationDiagnostics.value()))
            put("loopClosure", JSONObject(loopClosureDiagnostics.value()))
            put("timeline", jsonTimeline)
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

    private fun calculateCalibrationDiagnosticsLocked(
        selectedFrames: List<Giro360AndroidFrameCandidate>,
    ): Giro360CalibrationDiagnostics {
        val measured = selectedFrames.filter {
            it.selectionSource == "captured" &&
                it.cameraIntrinsics.size >= 6 &&
                it.cameraImageWidth > 0 &&
                it.cameraImageHeight > 0
        }
        if (measured.isEmpty()) {
            return Giro360CalibrationDiagnostics(
                cameraCharacteristics = cameraCharacteristics,
            )
        }

        val width = measured.map { it.cameraImageWidth }.groupingBy { it }
            .eachCount().maxByOrNull { it.value }?.key ?: measured.first().cameraImageWidth
        val height = measured.map { it.cameraImageHeight }.groupingBy { it }
            .eachCount().maxByOrNull { it.value }?.key ?: measured.first().cameraImageHeight
        val fx = measured.map { it.cameraIntrinsics[0] }
        val fy = measured.map { it.cameraIntrinsics[4] }
        val cx = measured.map { it.cameraIntrinsics[2] }
        val cy = measured.map { it.cameraIntrinsics[5] }
        val meanFx = fx.average()
        val meanFy = fy.average()
        val meanCx = cx.average()
        val meanCy = cy.average()
        val focalVariation = max(
            relativeSpanPercent(fx, meanFx),
            relativeSpanPercent(fy, meanFy),
        )
        val principalDrift = max(
            span(cx) / width.coerceAtLeast(1) * 100.0,
            span(cy) / height.coerceAtLeast(1) * 100.0,
        )
        return Giro360CalibrationDiagnostics(
            available = meanFx > 0.0 && meanFy > 0.0,
            stable = focalVariation <= CALIBRATION_MAX_VARIATION_PERCENT &&
                principalDrift <= CALIBRATION_MAX_PRINCIPAL_DRIFT_PERCENT,
            imageWidth = width,
            imageHeight = height,
            meanFxPixels = meanFx,
            meanFyPixels = meanFy,
            meanCxPixels = meanCx,
            meanCyPixels = meanCy,
            horizontalFovDegrees = fieldOfViewDegrees(width, meanFx),
            verticalFovDegrees = fieldOfViewDegrees(height, meanFy),
            focalLengthVariationPercent = focalVariation,
            principalPointDriftPercent = principalDrift,
            cameraCharacteristics = cameraCharacteristics,
        )
    }

    private fun calculateLoopClosureDiagnosticsLocked(): Giro360LoopClosureDiagnostics {
        if (requiredLaps < 2) return Giro360LoopClosureDiagnostics()
        val closurePairs = loopClosureCandidatePairsLocked()
        if (closurePairs.isEmpty()) return Giro360LoopClosureDiagnostics()

        val translations = mutableListOf<Double>()
        val rotations = mutableListOf<Double>()
        val yawErrors = mutableListOf<Double>()
        closurePairs.forEach { (first, last) ->
            translations += transformTranslationDistance(
                first.cameraTransform,
                last.cameraTransform,
            )
            rotations += transformRotationDifferenceDegrees(
                first.cameraTransform,
                last.cameraTransform,
            )
            yawErrors += abs(normalizedAngle(last.relativeYaw - first.relativeYaw)) * 180 / PI
        }
        val meanTranslation = translations.average()
        val maxTranslation = translations.maxOrNull() ?: 0.0
        val meanRotation = rotations.average()
        val maxRotation = rotations.maxOrNull() ?: 0.0
        val minimumMatchedBins = max(4, (binCount * 4 + 4) / 5)
        return Giro360LoopClosureDiagnostics(
            matchedBinCount = closurePairs.size,
            meanTranslationMeters = meanTranslation,
            maxTranslationMeters = maxTranslation,
            meanRotationDegrees = meanRotation,
            maxRotationDegrees = maxRotation,
            meanYawErrorDegrees = yawErrors.average(),
            maxYawErrorDegrees = yawErrors.maxOrNull() ?: 0.0,
            poseReliable = closurePairs.size >= minimumMatchedBins &&
                meanTranslation <= LOOP_CLOSURE_MEAN_TRANSLATION_METERS &&
                maxTranslation <= LOOP_CLOSURE_MAX_TRANSLATION_METERS &&
                meanRotation <= LOOP_CLOSURE_MEAN_ROTATION_DEGREES &&
                maxRotation <= LOOP_CLOSURE_MAX_ROTATION_DEGREES,
        )
    }

    private fun visualClosurePairLocked(): Pair<
        Giro360AndroidFrameCandidate,
        Giro360AndroidFrameCandidate
        >? {
        if (requiredLaps < 2) return null
        return loopClosureCandidatePairsLocked().maxByOrNull { (first, last) ->
            last.frameTimestamp - first.frameTimestamp
        }
    }

    private fun loopClosureCandidatePairsLocked(): List<Pair<
        Giro360AndroidFrameCandidate,
        Giro360AndroidFrameCandidate
        >> {
        val firstLap = candidates.values.filter { it.lapIndex == 0 }.associateBy { it.binIndex }
        val lastLap = candidates.values.filter { it.lapIndex == requiredLaps - 1 }
            .associateBy { it.binIndex }
        val commonBins = firstLap.keys.intersect(lastLap.keys)
        if (commonBins.isEmpty()) return emptyList()
        val pairs = commonBins.map { bin ->
            firstLap.getValue(bin) to lastLap.getValue(bin)
        }.filter { (first, last) -> last.frameTimestamp > first.frameTimestamp }
        val maximumSeparation = pairs.maxOfOrNull { (first, last) ->
            last.frameTimestamp - first.frameTimestamp
        } ?: return emptyList()
        val minimumSeparation = maximumSeparation * LOOP_CLOSURE_MIN_TIME_SEPARATION_RATIO
        return pairs.filter { (first, last) ->
            last.frameTimestamp - first.frameTimestamp >= minimumSeparation
        }
    }

    private fun analyzeVisualLoopClosure(
        retriever: MediaMetadataRetriever,
        first: Giro360AndroidFrameCandidate,
        last: Giro360AndroidFrameCandidate,
    ): Giro360LoopClosureDiagnostics {
        val base = synchronized(stateLock) { loopClosureDiagnostics }
        val firstRaw = retriever.getFrameAtTime(
            (first.frameTimestamp * 1_000_000).toLong(),
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return base
        val lastRaw = retriever.getFrameAtTime(
            (last.frameTimestamp * 1_000_000).toLong(),
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: run {
            firstRaw.recycle()
            return base
        }
        val firstBitmap = ensurePortrait(firstRaw)
        val lastBitmap = ensurePortrait(lastRaw)
        val orb = ORB.create(VISUAL_CLOSURE_ORB_FEATURES)
        var firstFeatures: Giro360ClosureFeatures? = null
        var lastFeatures: Giro360ClosureFeatures? = null
        try {
            firstFeatures = closureFeatures(firstBitmap, orb)
            lastFeatures = closureFeatures(lastBitmap, orb)
            return matchVisualClosure(
                base,
                first.binIndex,
                firstFeatures,
                lastFeatures,
            )
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Visual loop closure analysis failed", error)
            return base.copy(
                visualAnalyzed = true,
                visualReferenceBin = first.binIndex,
            )
        } finally {
            firstFeatures?.release()
            lastFeatures?.release()
            orb.clear()
            if (firstBitmap !== firstRaw) firstBitmap.recycle()
            if (lastBitmap !== lastRaw) lastBitmap.recycle()
            firstRaw.recycle()
            lastRaw.recycle()
        }
    }

    private fun closureFeatures(bitmap: Bitmap, orb: ORB): Giro360ClosureFeatures {
        val source = Mat()
        val scaled = Mat()
        val gray = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            val width = min(VISUAL_CLOSURE_WIDTH, source.cols()).coerceAtLeast(1)
            val height = max(1, source.rows() * width / source.cols().coerceAtLeast(1))
            Imgproc.resize(source, scaled, Size(width.toDouble(), height.toDouble()))
            Imgproc.cvtColor(scaled, gray, Imgproc.COLOR_RGBA2GRAY)
            orb.detectAndCompute(gray, mask, keypoints, descriptors)
            return Giro360ClosureFeatures(
                keypoints = keypoints.toArray(),
                descriptors = descriptors,
                width = width,
                height = height,
            )
        } finally {
            source.release()
            scaled.release()
            gray.release()
            keypoints.release()
            mask.release()
        }
    }

    private fun matchVisualClosure(
        base: Giro360LoopClosureDiagnostics,
        referenceBin: Int,
        first: Giro360ClosureFeatures,
        last: Giro360ClosureFeatures,
    ): Giro360LoopClosureDiagnostics {
        if (first.descriptors.empty() || last.descriptors.empty()) {
            return base.copy(visualAnalyzed = true, visualReferenceBin = referenceBin)
        }
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val groups = mutableListOf<MatOfDMatch>()
        val sourcePoints = mutableListOf<Point>()
        val targetPoints = mutableListOf<Point>()
        return try {
            matcher.knnMatch(first.descriptors, last.descriptors, groups, 2)
            groups.forEach { group ->
                val pair = group.toArray()
                if (pair.size < 2 || pair[0].distance >= VISUAL_CLOSURE_RATIO * pair[1].distance) {
                    return@forEach
                }
                sourcePoints += first.keypoints[pair[0].queryIdx].pt
                targetPoints += last.keypoints[pair[0].trainIdx].pt
            }
            if (sourcePoints.size < VISUAL_CLOSURE_MIN_GOOD_MATCHES) {
                return base.copy(
                    visualAnalyzed = true,
                    visualReferenceBin = referenceBin,
                    visualRawMatchCount = groups.size,
                    visualGoodMatchCount = sourcePoints.size,
                )
            }

            val sourceMat = MatOfPoint2f(*sourcePoints.toTypedArray())
            val targetMat = MatOfPoint2f(*targetPoints.toTypedArray())
            val inlierMask = Mat()
            val projected = MatOfPoint2f()
            val homography = Calib3d.findHomography(
                sourceMat,
                targetMat,
                Calib3d.RANSAC,
                VISUAL_CLOSURE_RANSAC_THRESHOLD_PIXELS,
                inlierMask,
                VISUAL_CLOSURE_RANSAC_ITERATIONS,
                VISUAL_CLOSURE_RANSAC_CONFIDENCE,
            )
            try {
                if (homography.empty()) {
                    return base.copy(
                        visualAnalyzed = true,
                        visualReferenceBin = referenceBin,
                        visualRawMatchCount = groups.size,
                        visualGoodMatchCount = sourcePoints.size,
                    )
                }
                Core.perspectiveTransform(sourceMat, projected, homography)
                val projectedPoints = projected.toArray()
                val maskValues = ByteArray(inlierMask.rows().coerceAtLeast(inlierMask.cols()))
                if (maskValues.isNotEmpty()) inlierMask.get(0, 0, maskValues)
                val inlierIndices = maskValues.indices.filter { maskValues[it].toInt() != 0 }
                val errors = inlierIndices.map { index ->
                    val dx = projectedPoints[index].x - targetPoints[index].x
                    val dy = projectedPoints[index].y - targetPoints[index].y
                    hypot(dx, dy)
                }
                val occupiedCells = inlierIndices.map { index ->
                    val point = sourcePoints[index]
                    val column = (point.x / first.width * VISUAL_CLOSURE_GRID_COLUMNS)
                        .toInt().coerceIn(0, VISUAL_CLOSURE_GRID_COLUMNS - 1)
                    val row = (point.y / first.height * VISUAL_CLOSURE_GRID_ROWS)
                        .toInt().coerceIn(0, VISUAL_CLOSURE_GRID_ROWS - 1)
                    row * VISUAL_CLOSURE_GRID_COLUMNS + column
                }.toSet().size
                val inlierCount = inlierIndices.size
                val inlierRatio = inlierCount / sourcePoints.size.toDouble()
                val spatialCoverage = occupiedCells.toDouble() /
                    (VISUAL_CLOSURE_GRID_COLUMNS * VISUAL_CLOSURE_GRID_ROWS)
                val meanError = if (errors.isEmpty()) 0.0 else errors.average()
                return base.copy(
                    visualAnalyzed = true,
                    visualReferenceBin = referenceBin,
                    visualRawMatchCount = groups.size,
                    visualGoodMatchCount = sourcePoints.size,
                    visualInlierCount = inlierCount,
                    visualInlierRatio = inlierRatio,
                    visualSpatialCoverage = spatialCoverage,
                    visualMeanReprojectionErrorPixels = meanError,
                    visualReliable = inlierCount >= VISUAL_CLOSURE_MIN_INLIERS &&
                        inlierRatio >= VISUAL_CLOSURE_MIN_INLIER_RATIO &&
                        spatialCoverage >= VISUAL_CLOSURE_MIN_SPATIAL_COVERAGE &&
                        meanError <= VISUAL_CLOSURE_MAX_REPROJECTION_ERROR_PIXELS,
                )
            } finally {
                sourceMat.release()
                targetMat.release()
                inlierMask.release()
                projected.release()
                homography.release()
            }
        } finally {
            groups.forEach(MatOfDMatch::release)
            matcher.clear()
        }
    }

    private fun reconstructMissingBinsLocked(
        lapIndex: Int?,
        selectedFrames: List<Giro360AndroidFrameCandidate>,
    ): List<Giro360AndroidFrameCandidate> {
        reconstructedBinIndices.clear()
        if (selectedFrames.size == binCount) return selectedFrames.sortedBy { it.binIndex }

        val minimumMeasuredBins = max(2, (binCount * 9 + 9) / 10)
        if (lapIndex == null || selectedFrames.size < minimumMeasuredBins) {
            error(
                "A melhor volta cobriu só ${selectedFrames.size}/$binCount setores; " +
                    "faça outra captura mais lenta.",
            )
        }

        val completed = selectedFrames.associateBy { it.binIndex }.toMutableMap()
        for (missingBin in 0 until binCount) {
            if (completed.containsKey(missingBin)) continue
            val previous = nearestCandidate(completed, missingBin, -1)
            val next = nearestCandidate(completed, missingBin, 1)
            if (previous == null || next == null) {
                error("Não foi possível reconstruir o setor $missingBin do vídeo.")
            }

            val span = positiveModulo(next.binIndex - previous.binIndex, binCount)
            val offset = positiveModulo(missingBin - previous.binIndex, binCount)
            val ratio = if (span == 0) 0.5 else offset.toDouble() / span
            val timestamp = interpolatedFrameTimestamp(
                lapIndex,
                missingBin,
                previous,
                next,
                selectedFrames,
                ratio,
            )
            val targetYaw = missingBin * TWO_PI / binCount
            completed[missingBin] = Giro360AndroidFrameCandidate(
                binIndex = missingBin,
                lapIndex = lapIndex,
                filePath = String.format(
                    Locale.US,
                    "%s/video_%03d.jpg",
                    directoryPath,
                    missingBin,
                ),
                targetYaw = targetYaw,
                relativeYaw = targetYaw,
                pitch = interpolate(previous.pitch, next.pitch, ratio),
                roll = interpolate(previous.roll, next.roll, ratio),
                translationMeters = interpolate(
                    previous.translationMeters,
                    next.translationMeters,
                    ratio,
                ),
                qualityScore = min(previous.qualityScore, next.qualityScore) - 5.0,
                sharpnessScore = min(previous.sharpnessScore, next.sharpnessScore),
                angularSpeed = interpolate(
                    previous.angularSpeed,
                    next.angularSpeed,
                    ratio,
                ),
                centerError = 0.0,
                trackingState = "interpolated_video",
                capturedAt = isoDate(),
                frameTimestamp = timestamp,
                cameraIntrinsics = interpolateList(
                    previous.cameraIntrinsics,
                    next.cameraIntrinsics,
                    ratio,
                ),
                cameraTransform = previous.cameraTransform,
                selectionSource = "interpolated_video",
                cameraImageWidth = previous.cameraImageWidth,
                cameraImageHeight = previous.cameraImageHeight,
            )
            reconstructedBinIndices += missingBin
        }
        Log.i(LOG_TAG, "Reconstructed video bins: $reconstructedBinIndices")
        return completed.values.sortedBy { it.binIndex }
    }

    private fun nearestCandidate(
        candidatesByBin: Map<Int, Giro360AndroidFrameCandidate>,
        originBin: Int,
        direction: Int,
    ): Giro360AndroidFrameCandidate? {
        for (distance in 1 until binCount) {
            val bin = positiveModulo(originBin + direction * distance, binCount)
            candidatesByBin[bin]?.let { return it }
        }
        return null
    }

    private fun interpolatedFrameTimestamp(
        lapIndex: Int,
        missingBin: Int,
        previous: Giro360AndroidFrameCandidate,
        next: Giro360AndroidFrameCandidate,
        allFrames: List<Giro360AndroidFrameCandidate>,
        ratio: Double,
    ): Double {
        if (next.frameTimestamp > previous.frameTimestamp) {
            return interpolate(previous.frameTimestamp, next.frameTimestamp, ratio)
        }

        val measuredStep = allFrames.sortedBy { it.frameTimestamp }
            .zipWithNext { first, second -> second.frameTimestamp - first.frameTimestamp }
            .filter { it > 0.0 }
            .sorted()
            .let { steps ->
                if (steps.isEmpty()) 1.0 else steps[steps.size / 2]
            }
        return when {
            missingBin == 0 && lapIndex > 0 -> next.frameTimestamp - measuredStep
            missingBin == 0 -> previous.frameTimestamp + measuredStep
            else -> previous.frameTimestamp + measuredStep
        }.coerceAtLeast(0.0)
    }

    private fun interpolate(start: Double, end: Double, ratio: Double): Double =
        start + (end - start) * ratio

    private fun interpolateList(
        start: List<Double>,
        end: List<Double>,
        ratio: Double,
    ): List<Double> = start.zip(end) { first, second ->
        interpolate(first, second, ratio)
    }

    private fun lapCandidateCountsLocked(): List<Int> =
        (0 until requiredLaps).map { lap ->
            candidates.values.count { it.lapIndex == lap }
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

    private fun rotationSpeedLabelLocked(): String = when {
        !running || direction == 0.0 -> "pending"
        currentAngularSpeed < MIN_IDEAL_ANGULAR_SPEED_RADIANS_PER_SECOND -> "too_slow"
        currentAngularSpeed > MAX_IDEAL_ANGULAR_SPEED_RADIANS_PER_SECOND -> "too_fast"
        else -> "ideal"
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
            "reconstructedBins" to reconstructedBinIndices.toList(),
            "lapCandidateCounts" to lapCandidateCountsLocked(),
            "missingBins" to (0 until binCount).filter { it !in selectedBins },
            "currentPitchDegrees" to currentPitch * 180 / PI,
            "currentRollDegrees" to currentRoll * 180 / PI,
            "currentAngularSpeed" to currentAngularSpeed,
            "rotationSpeed" to rotationSpeedLabelLocked(),
            "currentTranslationMeters" to currentTranslationMeters,
            "maxTranslationMeters" to maxTranslationMeters,
            "processedFrameCount" to processedFrameCount,
            "encodedCandidateCount" to encodedCandidateCount,
            "recordedVideoFrameCount" to recordedVideoFrameCount,
            "droppedVideoFrameCount" to droppedVideoFrameCount,
            "videoPath" to videoPath,
            "videoTimelinePath" to videoTimelinePath,
            "captureSource" to "video",
            "rejectedTrackingFrameCount" to rejectedTrackingFrameCount,
            "rejectedTranslationFrameCount" to rejectedTranslationFrameCount,
            "rejectedCameraImageFrameCount" to rejectedCameraImageFrameCount,
            "cameraCalibration" to calibrationDiagnostics.value(),
            "loopClosure" to loopClosureDiagnostics.value(),
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

    private fun readCameraCharacteristics(
        cameraId: String,
        imageWidth: Int,
        imageHeight: Int,
        textureWidth: Int,
        textureHeight: Int,
    ): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "cameraId" to cameraId,
            "arcoreImageSize" to listOf(imageWidth, imageHeight),
            "arcoreTextureSize" to listOf(textureWidth, textureHeight),
        )
        return try {
            val manager = applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let {
                result["lensIntrinsicCalibration"] = it.map(Float::toDouble)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                characteristics.get(CameraCharacteristics.LENS_DISTORTION)?.let {
                    result["lensDistortion"] = it.map(Float::toDouble)
                }
                characteristics.get(
                    CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
                )?.let {
                    result["distortionCorrectionModes"] = it.toList()
                }
            }
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
                result["availableFocalLengthsMillimeters"] = it.map(Float::toDouble)
            }
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                result["sensorPhysicalSizeMillimeters"] = listOf(
                    it.width.toDouble(),
                    it.height.toDouble(),
                )
            }
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                result["sensorPixelArraySize"] = listOf(it.width, it.height)
            }
            characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                result["sensorActiveArray"] = listOf(it.left, it.top, it.right, it.bottom)
            }
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                result["sensorOrientationDegrees"] = it
            }
            characteristics.get(CameraCharacteristics.LENS_FACING)?.let {
                result["lensFacing"] = it
            }
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.let {
                result["hardwareLevel"] = it
            }
            characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let {
                result["autoFocusModes"] = it.toList()
            }
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
                result["minimumFocusDistanceDiopters"] = it.toDouble()
            }
            result
        } catch (error: Throwable) {
            result["readError"] = error.localizedMessage ?: error.javaClass.simpleName
            result
        }
    }

    private fun transformTranslationDistance(first: List<Double>, last: List<Double>): Double {
        if (first.size < 15 || last.size < 15) return Double.POSITIVE_INFINITY
        val x = last[12] - first[12]
        val y = last[13] - first[13]
        val z = last[14] - first[14]
        return sqrt(x * x + y * y + z * z)
    }

    private fun transformRotationDifferenceDegrees(
        first: List<Double>,
        last: List<Double>,
    ): Double {
        if (first.size < 11 || last.size < 11) return 180.0
        val trace = first[0] * last[0] + first[1] * last[1] + first[2] * last[2] +
            first[4] * last[4] + first[5] * last[5] + first[6] * last[6] +
            first[8] * last[8] + first[9] * last[9] + first[10] * last[10]
        val cosine = ((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        return acos(cosine) * 180 / PI
    }

    private fun fieldOfViewDegrees(imageSizePixels: Int, focalLengthPixels: Double): Double {
        if (imageSizePixels <= 0 || focalLengthPixels <= 0.0) return 0.0
        return 2.0 * atan(imageSizePixels / (2.0 * focalLengthPixels)) * 180 / PI
    }

    private fun span(values: List<Double>): Double =
        (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)

    private fun relativeSpanPercent(values: List<Double>, mean: Double): Double =
        if (mean == 0.0) 0.0 else span(values) / abs(mean) * 100.0

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

    private fun positiveModulo(value: Int, modulus: Int): Int =
        ((value % modulus) + modulus) % modulus

    private fun isoDate(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        private const val LOG_TAG = "Giro360Capture"
        private const val TWO_PI = PI * 2
        private const val JPEG_QUALITY = 94
        private const val VIDEO_TIMESTAMP_TOLERANCE_SECONDS = 0.25
        private const val AXIS_TRANSLATION_WARNING_METERS = 0.08
        private const val AXIS_MAX_TRANSLATION_WARNING_METERS = 0.12
        private const val MIN_IDEAL_ANGULAR_SPEED_RADIANS_PER_SECOND = 0.17
        private const val MAX_IDEAL_ANGULAR_SPEED_RADIANS_PER_SECOND = 0.28
        private const val CALIBRATION_MAX_VARIATION_PERCENT = 0.5
        private const val CALIBRATION_MAX_PRINCIPAL_DRIFT_PERCENT = 0.5
        private const val LOOP_CLOSURE_MEAN_TRANSLATION_METERS = 0.05
        private const val LOOP_CLOSURE_MAX_TRANSLATION_METERS = 0.10
        private const val LOOP_CLOSURE_MEAN_ROTATION_DEGREES = 3.0
        private const val LOOP_CLOSURE_MAX_ROTATION_DEGREES = 8.0
        private const val LOOP_CLOSURE_MIN_TIME_SEPARATION_RATIO = 0.60
        private const val VISUAL_CLOSURE_ORB_FEATURES = 1800
        private const val VISUAL_CLOSURE_WIDTH = 720
        private const val VISUAL_CLOSURE_RATIO = 0.76f
        private const val VISUAL_CLOSURE_MIN_GOOD_MATCHES = 12
        private const val VISUAL_CLOSURE_RANSAC_THRESHOLD_PIXELS = 3.0
        private const val VISUAL_CLOSURE_RANSAC_ITERATIONS = 3000
        private const val VISUAL_CLOSURE_RANSAC_CONFIDENCE = 0.995
        private const val VISUAL_CLOSURE_GRID_COLUMNS = 4
        private const val VISUAL_CLOSURE_GRID_ROWS = 3
        private const val VISUAL_CLOSURE_MIN_INLIERS = 24
        private const val VISUAL_CLOSURE_MIN_INLIER_RATIO = 0.45
        private const val VISUAL_CLOSURE_MIN_SPATIAL_COVERAGE = 0.33
        private const val VISUAL_CLOSURE_MAX_REPROJECTION_ERROR_PIXELS = 4.0
    }
}
