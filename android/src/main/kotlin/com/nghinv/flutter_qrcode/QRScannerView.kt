package com.nghinv.flutter_qrcode

import android.Manifest
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QRScannerView(
    private val context: Context,
    private val id: Int,
    private val creationParams: Map<String, Any>?,
    private val messenger: io.flutter.plugin.common.BinaryMessenger
) : PlatformView, MethodChannel.MethodCallHandler {

    private val frameLayout: FrameLayout = FrameLayout(context)
    private val previewView: PreviewView = PreviewView(context)
    private val methodChannel: MethodChannel = MethodChannel(
        messenger,
        "plugins.lumi_qr_scanner/scanner_view_$id"
    )

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var barcodeAnalyzer: BarcodeScannerAnalyzer? = null
    private var isScanning = true
    private var useFrontCamera = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // Zoom support
    private var scaleGestureDetector: ScaleGestureDetector? = null

    init {
        methodChannel.setMethodCallHandler(this)
        frameLayout.addView(previewView)

        // Parse configuration
        useFrontCamera = creationParams?.get("useFrontCamera") as? Boolean ?: false

        setupTouchListeners()

        if (hasPermission()) {
            startCamera()
        }
    }

    private fun setupTouchListeners() {
        // Pinch-to-zoom
        scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = camera ?: return false
                    val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val newZoom = currentZoom * detector.scaleFactor
                    camera.cameraControl.setZoomRatio(newZoom)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            // Handle pinch-to-zoom
            scaleGestureDetector?.onTouchEvent(event)

            // Handle tap-to-focus
            if (event.action == MotionEvent.ACTION_UP &&
                event.pointerCount == 1 &&
                !scaleGestureDetector!!.isInProgress
            ) {
                handleTapToFocus(event.x, event.y)
            }

            true
        }
    }

    private fun handleTapToFocus(x: Float, y: Float) {
        val camera = camera ?: return

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)

        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }

    override fun getView(): View = frameLayout

    override fun dispose() {
        methodChannel.setMethodCallHandler(null)
        barcodeAnalyzer?.close()
        cameraExecutor.shutdown()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startScanning" -> {
                isScanning = true
                result.success(null)
            }
            "stopScanning" -> {
                isScanning = false
                result.success(null)
            }
            "resumeScanning" -> {
                isScanning = true
                result.success(null)
            }
            "pauseScanning" -> {
                isScanning = false
                result.success(null)
            }
            "toggleTorch" -> {
                toggleTorch()
                result.success(null)
            }
            "setTorch" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                setTorch(enabled)
                result.success(null)
            }
            "switchCamera" -> {
                useFrontCamera = !useFrontCamera
                startCamera()
                result.success(null)
            }
            "setZoom" -> {
                val zoom = call.argument<Double>("zoom")?.toFloat() ?: 1f
                camera?.cameraControl?.setZoomRatio(zoom)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview with continuous auto-focus via Camera2 interop
            val previewBuilder = Preview.Builder()

            // Force continuous auto-focus for instant focus
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )

            val preview = previewBuilder
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image analysis with high resolution for better distance scanning
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            // Get barcode formats from params
            val formats = (creationParams?.get("formats") as? List<*>)
                ?.mapNotNull { it as? Int } ?: listOf(Barcode.FORMAT_ALL_FORMATS)

            barcodeAnalyzer = BarcodeScannerAnalyzer(formats) { scanResult ->
                if (isScanning) {
                    handleBarcodes(scanResult)
                }
            }

            imageAnalysis?.setAnalyzer(cameraExecutor, barcodeAnalyzer!!)

            // Camera selector
            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    context as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )


            } catch (e: Exception) {
                // Handle error
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun handleBarcodes(scanResult: ScanResult) {
        if (!isScanning) return

        // Calculate image dimensions considering rotation
        val rotation = scanResult.rotationDegrees
        val imageWidth: Double
        val imageHeight: Double

        if (rotation == 90 || rotation == 270) {
            imageWidth = scanResult.imageHeight.toDouble()
            imageHeight = scanResult.imageWidth.toDouble()
        } else {
            imageWidth = scanResult.imageWidth.toDouble()
            imageHeight = scanResult.imageHeight.toDouble()
        }

        val previewWidth = previewView.width.toDouble()
        val previewHeight = previewView.height.toDouble()

        // Only send decoded barcodes (rawValue != null)
        val decodedBarcodes = scanResult.barcodes.filter { it.rawValue != null }
        if (decodedBarcodes.isEmpty()) return

        val vibrateOnSuccess = creationParams?.get("vibrateOnSuccess") as? Boolean ?: true
        if (vibrateOnSuccess) {
            vibrate()
        }

        decodedBarcodes.forEach { barcode ->
            val barcodeData = mapOf(
                "rawValue" to barcode.rawValue,
                "format" to barcode.format,
                "cornerPoints" to barcode.cornerPoints?.map { point ->
                    mapOf("x" to point.x.toDouble(), "y" to point.y.toDouble())
                },
                "boundingBox" to barcode.boundingBox?.let { rect ->
                    mapOf(
                        "left" to rect.left.toDouble(),
                        "top" to rect.top.toDouble(),
                        "right" to rect.right.toDouble(),
                        "bottom" to rect.bottom.toDouble()
                    )
                },
                "valueType" to mapOf(
                    "type" to barcode.valueType,
                    "data" to getBarcodeValueTypeData(barcode)
                ),
                "imageSize" to mapOf(
                    "width" to imageWidth,
                    "height" to imageHeight
                ),
                "previewSize" to mapOf(
                    "width" to previewWidth,
                    "height" to previewHeight
                )
            )

            mainHandler.post {
                methodChannel.invokeMethod("onBarcodeScanned", barcodeData)
            }
        }

        val autoPause = creationParams?.get("autoPauseAfterScan") as? Boolean ?: false
        if (autoPause) {
            isScanning = false
        }
    }

    private fun getBarcodeValueTypeData(barcode: Barcode): Map<String, Any?>? {
        return when (barcode.valueType) {
            Barcode.TYPE_URL -> mapOf("url" to barcode.url?.url)
            Barcode.TYPE_EMAIL -> mapOf(
                "address" to barcode.email?.address,
                "subject" to barcode.email?.subject,
                "body" to barcode.email?.body
            )
            Barcode.TYPE_PHONE -> mapOf("number" to barcode.phone?.number)
            Barcode.TYPE_SMS -> mapOf(
                "message" to barcode.sms?.message,
                "phoneNumber" to barcode.sms?.phoneNumber
            )
            Barcode.TYPE_WIFI -> mapOf(
                "ssid" to barcode.wifi?.ssid,
                "password" to barcode.wifi?.password,
                "type" to barcode.wifi?.encryptionType
            )
            Barcode.TYPE_GEO -> mapOf(
                "latitude" to barcode.geoPoint?.lat,
                "longitude" to barcode.geoPoint?.lng
            )
            else -> null
        }
    }

    private fun vibrate() {
        vibrator?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(100)
            }
        }
    }

    private fun toggleTorch() {
        camera?.cameraControl?.let { control ->
            val torchState = camera?.cameraInfo?.torchState?.value ?: TorchState.OFF
            control.enableTorch(torchState == TorchState.OFF)
        }
    }

    private fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }
}
