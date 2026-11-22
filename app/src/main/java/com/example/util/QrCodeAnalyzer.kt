package com.example.util

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX ImageAnalysis.Analyzer that decodes QR codes from the live camera stream in real time.
 * Normalizes Y-plane rowStride/pixelStride padding and tries both HybridBinarizer and
 * GlobalHistogramBinarizer across center-crop and full-frame regions so backlit LCD/laptop
 * screen QR codes decode reliably.
 */
class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
            DecodeHintType.ALSO_INVERTED to java.lang.Boolean.TRUE,
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
                return
            }

            val width = imageProxy.width
            val height = imageProxy.height
            if (width <= 0 || height <= 0) {
                return
            }

            val normalizedY = extractCompactYPlane(
                buffer = yPlane.buffer,
                width = width,
                height = height,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride
            ) ?: return

            val fullSource = PlanarYUVLuminanceSource(
                normalizedY,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )

            // 1. Center 75% crop matches the on-screen viewfinder and avoids surrounding dark UI glare
            val cropSize = (minOf(width, height) * 3) / 4
            val cropLeft = ((width - cropSize) / 2).coerceAtLeast(0)
            val cropTop = ((height - cropSize) / 2).coerceAtLeast(0)

            val decodedText = if (cropSize > 48 && (cropLeft > 0 || cropTop > 0)) {
                val centerSource = fullSource.crop(cropLeft, cropTop, cropSize, cropSize)
                tryDecodeSource(centerSource) ?: tryDecodeSource(fullSource)
            } else {
                tryDecodeSource(fullSource)
            }

            if (!decodedText.isNullOrBlank()) {
                isScanning = false
                onQrCodeScanned(decodedText.trim())
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Frame QR decode skipped: ${t.message}")
        } finally {
            try {
                reader.reset()
            } catch (_: Throwable) {}
            try {
                imageProxy.close()
            } catch (_: Throwable) {}
        }
    }

    private fun tryDecodeSource(source: LuminanceSource): String? {
        try {
            val hybridResult = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            if (!hybridResult?.text.isNullOrBlank()) {
                return hybridResult.text
            }
        } catch (_: Throwable) {
            // Fall through to GlobalHistogramBinarizer (much stronger on backlit LCD monitors)
        } finally {
            try {
                reader.reset()
            } catch (_: Throwable) {}
        }

        try {
            val histogramResult = reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
            if (!histogramResult?.text.isNullOrBlank()) {
                return histogramResult.text
            }
        } catch (_: Throwable) {
            // Frame did not contain a readable QR code
        } finally {
            try {
                reader.reset()
            } catch (_: Throwable) {}
        }

        return null
    }

    private fun extractCompactYPlane(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ByteArray? {
        buffer.rewind()
        val remaining = buffer.remaining()
        if (remaining <= 0) return null

        val out = ByteArray(width * height)
        if (pixelStride == 1 && rowStride == width && remaining >= out.size) {
            buffer.get(out, 0, out.size)
            return out
        }

        val raw = ByteArray(remaining)
        buffer.get(raw)

        if (pixelStride == 1) {
            for (row in 0 until height) {
                val srcOffset = row * rowStride
                if (srcOffset >= raw.size) break
                val copyLen = minOf(width, raw.size - srcOffset)
                System.arraycopy(raw, srcOffset, out, row * width, copyLen)
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                val dstStart = row * width
                for (col in 0 until width) {
                    val idx = rowStart + col * pixelStride
                    if (idx < raw.size) {
                        out[dstStart + col] = raw[idx]
                    }
                }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "QrCodeAnalyzer"
    }
}
