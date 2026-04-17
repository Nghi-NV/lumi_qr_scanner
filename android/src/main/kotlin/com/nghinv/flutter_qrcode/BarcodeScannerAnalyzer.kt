package com.nghinv.flutter_qrcode

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

data class ScanResult(
    val barcodes: List<Barcode>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int
)

class BarcodeScannerAnalyzer(
    private val formats: List<Int>,
    private val onBarcodeDetected: (ScanResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner
    @Volatile
    private var isProcessing = false

    init {
        val optionsBuilder = BarcodeScannerOptions.Builder()

        val formatMask = formats.fold(0) { acc, format -> acc or format }
        if (formatMask != 0) {
            optionsBuilder.setBarcodeFormats(formatMask)
        }

        scanner = BarcodeScanning.getClient(optionsBuilder.build())
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            isProcessing = true
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val imageWidth = imageProxy.width
            val imageHeight = imageProxy.height

            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        onBarcodeDetected(ScanResult(
                            barcodes = barcodes,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            rotationDegrees = rotationDegrees
                        ))
                    }
                }
                .addOnFailureListener {
                    // Handle error silently
                }
                .addOnCompleteListener {
                    isProcessing = false
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    fun close() {
        scanner.close()
    }
}
