package com.giro360.giro360_capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.ArCoreApk
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

class Giro360CapturePlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var applicationContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var coordinator: Giro360CaptureCoordinator
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var eventSink: EventChannel.EventSink? = null
    private var pendingPrepareResult: MethodChannel.Result? = null
    private var nativeStitchingAvailable = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        nativeStitchingAvailable = try {
            System.loadLibrary("opencv_java4")
            System.loadLibrary("giro360_stitcher")
            true
        } catch (_: Throwable) {
            false
        }

        coordinator = Giro360CaptureCoordinator(applicationContext) { status ->
            eventSink?.success(status)
        }
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        binding.platformViewRegistry.registerViewFactory(
            VIEW_TYPE,
            Giro360PreviewFactory(coordinator),
        )
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isSupported" -> supportInfo { info ->
                result.success(info["supported"] == true)
            }

            "supportInfo" -> supportInfo(result::success)
            "prepare" -> prepare(result)
            "startCapture" -> startCapture(call, result)
            "status" -> result.success(coordinator.status())
            "cancelCapture" -> {
                coordinator.cancelCapture()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
        events.success(coordinator.status())
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    private fun prepare(result: MethodChannel.Result) {
        val currentActivity = activity
        if (currentActivity == null) {
            result.error(
                "activity_unavailable",
                "Abra a captura com o aplicativo em primeiro plano.",
                null,
            )
            return
        }

        if (!hasCameraPermission()) {
            if (pendingPrepareResult != null) {
                result.error(
                    "permission_in_progress",
                    "A permissão da câmera já está sendo solicitada.",
                    null,
                )
                return
            }
            pendingPrepareResult = result
            currentActivity.requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST,
            )
            return
        }

        requestArCoreInstall(currentActivity, result)
    }

    private fun requestArCoreInstall(
        currentActivity: Activity,
        result: MethodChannel.Result,
    ) {
        try {
            ArCoreApk.getInstance().requestInstall(currentActivity, true)
            supportInfo(result::success)
        } catch (error: Throwable) {
            result.error(
                "arcore_prepare_failed",
                "Não foi possível preparar o ARCore: ${error.localizedMessage}",
                null,
            )
        }
    }

    private fun startCapture(call: MethodCall, result: MethodChannel.Result) {
        val currentActivity = activity
        val directoryPath = call.argument<String>("directoryPath")
        if (currentActivity == null || directoryPath.isNullOrBlank()) {
            result.error(
                "invalid_arguments",
                "A Activity ou o diretório da captura não está disponível.",
                null,
            )
            return
        }
        if (!hasCameraPermission()) {
            result.error(
                "camera_permission_required",
                "Autorize a câmera antes de iniciar a captura.",
                null,
            )
            return
        }
        val availability = ArCoreApk.getInstance().checkAvailability(applicationContext)
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            result.error(
                "arcore_not_ready",
                "O Google Play Services para RA precisa ser instalado ou atualizado.",
                availability.name,
            )
            return
        }
        if (!nativeStitchingAvailable) {
            result.error(
                "opencv_not_ready",
                "O motor OpenCV não foi carregado neste aparelho.",
                null,
            )
            return
        }

        try {
            coordinator.startCapture(
                activity = currentActivity,
                directoryPath = directoryPath,
                binCount = (call.argument<Int>("binCount") ?: 30).coerceIn(24, 90),
                requiredLaps = (call.argument<Int>("requiredLaps") ?: 2).coerceIn(1, 3),
            )
            result.success(null)
        } catch (error: Throwable) {
            result.error(
                "arcore_start_failed",
                "Não foi possível iniciar o ARCore: ${error.localizedMessage}",
                null,
            )
        }
    }

    private fun supportInfo(callback: (Map<String, Any>) -> Unit) {
        ArCoreApk.getInstance().checkAvailabilityAsync(applicationContext) { availability ->
            callback(buildSupportInfo(availability))
        }
    }

    private fun buildSupportInfo(
        availability: ArCoreApk.Availability,
    ): Map<String, Any> {
        val sensorManager =
            applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasRearCamera = hasRearCamera()
        val hasAccelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val permissionGranted = hasCameraPermission()
        val arCoreSupported = availability.isSupported
        val arCoreInstalled = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
        val hardwareSupported = hasRearCamera && hasAccelerometer && hasGyroscope && arCoreSupported
        val ready = hardwareSupported && permissionGranted && arCoreInstalled &&
            nativeStitchingAvailable

        val reason = when {
            !hasRearCamera -> "Este aparelho não possui câmera traseira compatível."
            !hasAccelerometer -> "O acelerômetro necessário não está disponível."
            !hasGyroscope -> "O giroscópio necessário não está disponível."
            availability.isUnsupported -> "Este modelo não é certificado para ARCore."
            availability.isUnknown -> "Ainda não foi possível confirmar o suporte ao ARCore."
            !permissionGranted -> "O aparelho é compatível. Autorize a câmera para começar."
            !arCoreInstalled -> "Instale ou atualize o Google Play Services para RA."
            !nativeStitchingAvailable -> "O motor OpenCV não foi carregado neste build."
            else -> "Captura disponível com ARCore e OpenCV no dispositivo."
        }

        return mapOf(
            "platform" to "android",
            "supported" to hardwareSupported,
            "ready" to ready,
            "reason" to reason,
            "requirements" to listOf(
                hardwareRequirement(
                    "rear_camera",
                    "Câmera traseira",
                    hasRearCamera,
                ),
                hardwareRequirement(
                    "accelerometer",
                    "Acelerômetro",
                    hasAccelerometer,
                ),
                hardwareRequirement(
                    "gyroscope",
                    "Giroscópio",
                    hasGyroscope,
                ),
                mapOf(
                    "id" to "motion_tracking",
                    "label" to "Rastreamento ARCore 6-DoF",
                    "required" to true,
                    "state" to when {
                        availability.isSupported -> "available"
                        availability.isTransient || availability.isUnknown -> "checking"
                        else -> "missing"
                    },
                    "message" to availability.name,
                ),
                mapOf(
                    "id" to "camera_permission",
                    "label" to "Permissão da câmera",
                    "required" to true,
                    "state" to if (permissionGranted) {
                        "available"
                    } else {
                        "permission_required"
                    },
                    "message" to if (permissionGranted) "Concedida" else "Será solicitada",
                ),
                mapOf(
                    "id" to "ar_service",
                    "label" to "Google Play Services para RA",
                    "required" to true,
                    "state" to when {
                        arCoreInstalled -> "available"
                        availability.isSupported -> "install_required"
                        availability.isTransient || availability.isUnknown -> "checking"
                        else -> "missing"
                    },
                    "message" to when (availability) {
                        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "Instalado"
                        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "Atualização necessária"
                        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "Instalação necessária"
                        else -> availability.name
                    },
                ),
                hardwareRequirement(
                    "native_stitching",
                    "Motor OpenCV",
                    nativeStitchingAvailable,
                    if (nativeStitchingAvailable) "Embarcado no plugin" else "Falha ao carregar",
                ),
            ),
        )
    }

    private fun hardwareRequirement(
        id: String,
        label: String,
        available: Boolean,
        customMessage: String? = null,
    ): Map<String, Any> = mapOf(
        "id" to id,
        "label" to label,
        "required" to true,
        "state" to if (available) "available" else "missing",
        "message" to (customMessage ?: if (available) "Disponível" else "Não encontrado"),
    )

    private fun hasCameraPermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasRearCamera(): Boolean {
        val cameraManager =
            applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            cameraManager.cameraIdList.any { cameraId ->
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != CAMERA_PERMISSION_REQUEST) return false
        val pending = pendingPrepareResult ?: return true
        pendingPrepareResult = null
        val currentActivity = activity
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            currentActivity != null
        ) {
            requestArCoreInstall(currentActivity, pending)
        } else {
            supportInfo(pending::success)
        }
        return true
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachActivity()
    }

    private fun detachActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
        coordinator.pause()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        coordinator.close()
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
    }

    companion object {
        private const val METHOD_CHANNEL = "giro360_capture/methods"
        private const val EVENT_CHANNEL = "giro360_capture/events"
        private const val VIEW_TYPE = "giro360_capture/preview"
        private const val CAMERA_PERMISSION_REQUEST = 46360
    }
}
