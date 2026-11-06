package com.example.util

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

            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            val yBytes = ByteArray(width * height)
            if (rowStride == width && pixelStride == 1) {
                yBuffer.position(0)
                yBuffer.get(yBytes, 0, width * height)
            } else {
                for (row in 0 until height) {
                    val pos = row * rowStride
                    if (pos < yBuffer.capacity()) {
                        yBuffer.position(pos)
                        val bytesToRead = minOf(width, yBuffer.remaining())
                        yBuffer.get(yBytes, row * width, bytesToRead)
                    }
                }
            }

            val source = PlanarYUVLuminanceSource(
                yBytes, width, height, 0, 0, width, height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))

            val result = reader.decodeWithState(bitmap)
            if (result != null && !result.text.isNullOrBlank()) {
                isScanning = false
                onQrCodeScanned(result.text.trim())
            }
        } catch (_: Exception) {
            // No QR code found in current frame, ignore and continue
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
