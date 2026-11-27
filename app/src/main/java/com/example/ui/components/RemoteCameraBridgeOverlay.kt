package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.auth.CurrentActivityHolder
import com.example.auth.findActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "RemoteCameraBridge"

/**
 * Streams compressed CameraX frames in real time to the paired Website whenever
 * `isActive` is true, while displaying a transparent live indicator + mini preview
 * and immediate Stop/Switch/Torch controls on the phone screen.
 */
@Composable
fun RemoteCameraBridgeOverlay(
    activity: ComponentActivity?,
    isActive: Boolean,
    lens: String,
    isTorchOn: Boolean,
    frameCount: Int,
    pendingHdCapture: Boolean,
    permissionNeeded: Boolean,
    language: String,
    onRequestCameraPermission: () -> Unit,
    onDismissPermissionPrompt: () -> Unit,
    onSwitchLens: () -> Unit,
    onToggleTorch: () -> Unit,
    onStopCamera: () -> Unit,
    onFrameCaptured: (base64Jpeg: String, width: Int, height: Int, isHd: Boolean) -> Unit,
    onHardwareError: (String) -> Unit
) {
    val context = LocalContext.current
    val fallbackOwner = LocalLifecycleOwner.current
    val hostActivity = remember(context, activity) {
        activity ?: (context.findActivity() as? ComponentActivity)
        ?: (CurrentActivityHolder.currentActivity as? ComponentActivity)
    }
    val lifecycleOwner: LifecycleOwner = hostActivity ?: fallbackOwner
    val isBn = language == "BN"

    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    val latestPendingHd by rememberUpdatedState(pendingHdCapture)
    val latestOnFrameCaptured by rememberUpdatedState(onFrameCaptured)
    val latestOnHardwareError by rememberUpdatedState(onHardwareError)
    val latestLens by rememberUpdatedState(lens)
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var isPreviewExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(boundCamera, isTorchOn, lens) {
        try {
            boundCamera?.cameraControl?.enableTorch(isTorchOn && lens == "BACK")
        } catch (_: Throwable) {}
    }

    if (permissionNeeded) {
        AlertDialog(
            onDismissRequest = onDismissPermissionPrompt,
            icon = {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = if (isBn) "ওয়েবসাইট থেকে ক্যামেরা রিকোয়েস্ট" else "Website Camera Request",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isBn) {
                        "আপনার পেয়ার করা ওয়েবসাইট থেকে মোবাইলের ক্যামেরা দেখার অনুরোধ এসেছে। লাইভ ক্যামেরা চালু করতে ক্যামেরা পারমিশন দিন।"
                    } else {
                        "Your paired website requested to view this device's camera. Grant camera permission to start live streaming."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.testTag("grant_remote_camera_permission_btn")
                ) {
                    Text(if (isBn) "ক্যামেরা পারমিশন দিন" else "Allow Camera")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismissPermissionPrompt()
                        onStopCamera()
                    }
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    AnimatedVisibility(
        visible = isActive && hasPermission,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
            val lastUploadAtMs = remember { AtomicLong(0L) }

            DisposableEffect(Unit) {
                onDispose {
                    try {
                        boundCamera?.cameraControl?.enableTorch(false)
                    } catch (_: Throwable) {}
                    try {
                        cameraExecutor.shutdown()
                    } catch (_: Throwable) {}
                    try {
                        val future = ProcessCameraProvider.getInstance(context)
                        if (future.isDone) {
                            future.get()?.unbindAll()
                        }
                    } catch (_: Throwable) {}
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.55f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Live CameraX PreviewView thumbnail (tappable to expand)
                    Box(
                        modifier = Modifier
                            .size(
                                width = if (isPreviewExpanded) 132.dp else 72.dp,
                                height = if (isPreviewExpanded) 176.dp else 96.dp
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .clickable { isPreviewExpanded = !isPreviewExpanded }
                            .testTag("remote_camera_preview_thumb")
                    ) {
                        key(lens) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx).apply {
                                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                        scaleType = PreviewView.ScaleType.FILL_CENTER
                                    }

                                    try {
                                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                        cameraProviderFuture.addListener({
                                            try {
                                                val cameraProvider = cameraProviderFuture.get()
                                                val preview = Preview.Builder().build().also {
                                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                                }

                                                val resolutionSelector = ResolutionSelector.Builder()
                                                    .setResolutionStrategy(
                                                        ResolutionStrategy(
                                                            Size(1280, 720),
                                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                                        )
                                                    )
                                                    .build()

                                                val imageAnalysis = ImageAnalysis.Builder()
                                                    .setResolutionSelector(resolutionSelector)
                                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                    .build()
                                                    .also { analysis ->
                                                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                                            processFrameForWebStream(
                                                                imageProxy = imageProxy,
                                                                isFront = latestLens == "FRONT",
                                                                isHdRequested = latestPendingHd,
                                                                lastUploadAtMs = lastUploadAtMs,
                                                                onEncoded = { dataUri, w, h, isHd ->
                                                                    latestOnFrameCaptured(dataUri, w, h, isHd)
                                                                }
                                                            )
                                                        }
                                                    }

                                                val selector = if (lens == "FRONT") {
                                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                                } else {
                                                    CameraSelector.DEFAULT_BACK_CAMERA
                                                }

                                                cameraProvider.unbindAll()
                                                val cam = cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    selector,
                                                    preview,
                                                    imageAnalysis
                                                )
                                                boundCamera = cam
                                                cam.cameraControl.enableTorch(isTorchOn && lens == "BACK")
                                            } catch (t: Throwable) {
                                                Log.e(TAG, "Failed starting remote CameraX stream", t)
                                                latestOnHardwareError(t.localizedMessage ?: "Camera unavailable")
                                            }
                                        }, ContextCompat.getMainExecutor(ctx))
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "CameraProvider error", t)
                                        latestOnHardwareError(t.localizedMessage ?: "CameraProvider error")
                                    }
                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFDC2626),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "LIVE",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Status & Controls Column
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "ওয়েবসাইটে লাইভ ক্যামেরা চলছে" else "Live Camera on Website",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (isBn) {
                                "${if (lens == "FRONT") "ফ্রন্ট ক্যামেরা" else "ব্যাক ক্যামেরা"} • $frameCount ফ্রেম প্রেরিত"
                            } else {
                                "${lens.lowercase().replaceFirstChar { it.uppercase() }} Cam • $frameCount frames sent"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onSwitchLens,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                                    .testTag("remote_cam_switch_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = if (isBn) "ক্যামেরা পরিবর্তন" else "Switch Camera",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (lens == "BACK") {
                                IconButton(
                                    onClick = onToggleTorch,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(if (isTorchOn) Color(0xFFF59E0B) else Color(0xFF1E293B))
                                        .testTag("remote_cam_torch_btn")
                                ) {
                                    Icon(
                                        imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                        contentDescription = if (isBn) "ফ্ল্যাশলাইট" else "Torch",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onStopCamera,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("remote_cam_stop_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StopCircle,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "বন্ধ" else "Stop",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun processFrameForWebStream(
    imageProxy: ImageProxy,
    isFront: Boolean,
    isHdRequested: Boolean,
    lastUploadAtMs: AtomicLong,
    onEncoded: (String, Int, Int, Boolean) -> Unit
) {
    try {
        val now = System.currentTimeMillis()
        val prev = lastUploadAtMs.get()
        if (!isHdRequested && (now - prev) < 420L) {
            return
        }
        lastUploadAtMs.set(now)

        val rawBitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()

        val matrix = Matrix().apply {
            if (rotation != 0f) postRotate(rotation)
            if (isFront) postScale(-1f, 1f)
        }

        val orientedBitmap = if (rotation != 0f || isFront) {
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
        if (orientedBitmap !== rawBitmap) rawBitmap.recycle()

        val maxDim = if (isHdRequested) 1200 else 460
        val quality = if (isHdRequested) 82 else 56

        val scale = minOf(
            maxDim.toFloat() / orientedBitmap.width.coerceAtLeast(1),
            maxDim.toFloat() / orientedBitmap.height.coerceAtLeast(1),
            1f
        )
        val targetW = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)

        val finalBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(orientedBitmap, targetW, targetH, true).also {
                if (it !== orientedBitmap) orientedBitmap.recycle()
            }
        } else {
            orientedBitmap
        }

        val baos = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val w = finalBitmap.width
        val h = finalBitmap.height
        finalBitmap.recycle()

        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$b64"
        onEncoded(dataUri, w, h, isHdRequested)
    } catch (t: Throwable) {
        Log.w(TAG, "Frame encode skipped: ${t.message}")
    } finally {
        imageProxy.close()
    }
}
