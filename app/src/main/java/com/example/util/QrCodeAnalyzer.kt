package com.example.util

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX ImageAnalysis.Analyzer that decodes QR codes from the live camera stream in real time.
 * Designed with defensive byte-buffer handling and comprehensive exception isolation.
 */
class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    @Volatile
    private var isScanning = true

    fun reset() {
        isScanning = true
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!isScanning) {
            imageProxy.close()
            return
        }

        try {
            val yPlane = imageProxy.planes.firstOrNull()
            if (yPlane == null) {
                imageProxy.close()
                return
            }

            val buffer = yPlane.buffer
            val remaining = buffer.remaining()
            if (remaining <= 0) {
                imageProxy.close()
                return
            }

            val data = ByteArray(remaining)
            buffer.get(data)

            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = yPlane.rowStride

            val source = PlanarYUVLuminanceSource(
                data,
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false
            )

            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            if (result != null && !result.text.isNullOrBlank()) {
                isScanning = false
                onQrCodeScanned(result.text.trim())
            }
        } catch (_: Throwable) {
            // Frame did not contain a valid QR code or decode failed - continue stream
        } finally {
            try {
                reader.reset()
            } catch (_: Throwable) {}
            try {
                imageProxy.close()
            } catch (_: Throwable) {}
        }
    }
}
