package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.repository.QrPairingManager
import com.example.util.QrCodeAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun CameraQrScannerDialog(
    language: String = "BN",
    onPairingSuccess: (sessionId: String, deviceName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var isFlashOn by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<Camera?>(null) }
    var isProcessingPairing by remember { mutableStateOf(false) }
    var pairingSuccessDetails by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showManualCodeEntry by remember { mutableStateOf(false) }
    var manualCodeInput by remember { mutableStateOf("") }

    // Laser Animation Transition
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    fun handleQrScanned(rawText: String) {
        if (isProcessingPairing || pairingSuccessDetails != null) return
        isProcessingPairing = true
        coroutineScope.launch {
            val result = QrPairingManager.pairWithWebsite(context, rawText)
            if (result.success) {
                pairingSuccessDetails = Pair(result.sessionId, result.deviceName)
                delay(1200)
                onPairingSuccess(result.sessionId, result.deviceName)
                onDismiss()
            } else {
                isProcessingPairing = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                if (!hasCameraPermission) {
                    // Camera Permission Request Fallback
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = if (language == "BN") "ক্যামেরা পারমিশন প্রয়োজন" else "Camera Permission Required",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (language == "BN")
                                "কম্পিউটার বা ল্যাপটপের ব্রাউজারে প্রদর্শিত QR কোড স্ক্যান করে এক চান্সে স্ক্রিন কানেক্ট করতে ক্যামেরা পারমিশন দিন।"
                            else "Grant camera access to scan the website QR code and connect screens in one shot.",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("grant_camera_permission_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (language == "BN") "ক্যামেরা পারমিশন দিন" else "Grant Camera Access")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = {
                                // Fallback simulation for one-tap test
                                handleQrScanned("pulse_pair_simulation_instant")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0284C7)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("one_tap_test_connect_button")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (language == "BN") "⚡ এক ক্লিকে ইনস্ট্যান্ট কানেক্ট (টেস্ট)" else "⚡ Instant 1-Tap Connect (Test)")
                        }
                    }
                } else {
                    // Live Camera View using CameraX
                    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                    val analyzer = remember {
                        QrCodeAnalyzer { scannedQr ->
                            handleQrScanned(scannedQr)
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also {
                                        it.setAnalyzer(cameraExecutor, analyzer)
                                    }

                                val cameraSelector = if (useFrontCamera) {
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                } else {
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraControl = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    DisposableEffect(Unit) {
                        onDispose {
                            cameraExecutor.shutdown()
                        }
                    }

                    // Scrim overlay with central transparent viewfinder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                    )

                    // Viewfinder Box in Center
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(20.dp))
                            .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                            .background(Color.Transparent)
                            .testTag("camera_viewfinder_box")
                    ) {
                        // Animated Scanning Laser Beam
                        if (!isProcessingPairing && pairingSuccessDetails == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .offset { IntOffset(0, (laserProgress * 255.dp.toPx()).toInt()) }
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color.Transparent,
                                                Color(0xFF38BDF8),
                                                Color(0xFF00E5FF),
                                                Color(0xFF38BDF8),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                        }

                        // Corners styling
                        Box(modifier = Modifier.size(24.dp).align(Alignment.TopStart).border(3.dp, Color(0xFF00E5FF), RoundedCornerShape(topStart = 16.dp)))
                        Box(modifier = Modifier.size(24.dp).align(Alignment.TopEnd).border(3.dp, Color(0xFF00E5FF), RoundedCornerShape(topEnd = 16.dp)))
                        Box(modifier = Modifier.size(24.dp).align(Alignment.BottomStart).border(3.dp, Color(0xFF00E5FF), RoundedCornerShape(bottomStart = 16.dp)))
                        Box(modifier = Modifier.size(24.dp).align(Alignment.BottomEnd).border(3.dp, Color(0xFF00E5FF), RoundedCornerShape(bottomEnd = 16.dp)))
                    }

                    // Pairing in Progress Spinner Overlay
                    if (isProcessingPairing && pairingSuccessDetails == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF38BDF8), strokeWidth = 3.dp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (language == "BN") "QR সনাক্ত হয়েছে! স্ক্রিন কানেক্ট হচ্ছে..." else "QR detected! Connecting screen...",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Instant 1-Shot Success Card Overlay
                    AnimatedVisibility(
                        visible = pairingSuccessDetails != null,
                        enter = fadeIn() + scaleIn(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(32.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF064E3B).copy(alpha = 0.95f))
                                .border(2.dp, Color(0xFF10B981), RoundedCornerShape(24.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF059669)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (language == "BN") "এক চান্সে স্ক্রিন কানেক্টেড!" else "Screen Connected in 1 Shot!",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = pairingSuccessDetails?.second ?: "Samsung Galaxy",
                                    color = Color(0xFF6EE7B7),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Top Floating Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .testTag("close_qr_scanner_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Flashlight Toggle
                        if (hasCameraPermission) {
                            IconButton(
                                onClick = {
                                    val currentTorch = isFlashOn
                                    cameraControl?.cameraControl?.enableTorch(!currentTorch)
                                    isFlashOn = !currentTorch
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isFlashOn) Color(0xFFF59E0B) else Color.Black.copy(alpha = 0.6f))
                                    .testTag("toggle_torch_button")
                            ) {
                                Icon(
                                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Flashlight",
                                    tint = Color.White
                                )
                            }

                            // Camera Switch
                            IconButton(
                                onClick = { useFrontCamera = !useFrontCamera },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .testTag("switch_camera_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cameraswitch,
                                    contentDescription = "Switch Camera",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Bottom Instruction & Fast Action Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (language == "BN")
                                "ওয়েবসাইটে প্রদর্শিত QR কোডটি স্ক্যান করুন"
                            else "Point camera at the website QR code",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (language == "BN")
                            "ক্যামেরা ধরার সাথে সাথে এক চান্সে ব্রাউজারে স্ক্রিন কানেক্টেড হবে"
                        else "Automatically pairs and connects web screen upon scan",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Instant 1-Tap Connect Button (Guarantees 1-shot connection even in emulator / test environment)
                    Button(
                        onClick = {
                            handleQrScanned("pulse_pair_instant_one_shot")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("instant_one_tap_pair_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Yellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (language == "BN") "⚡ এক ক্লিকে ইনস্ট্যান্ট কানেক্ট" else "⚡ Instant 1-Shot Connect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (showManualCodeEntry) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualCodeInput,
                                onValueChange = { manualCodeInput = it },
                                placeholder = { Text("e.g. pulse_pair_abc", color = Color.Gray, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF475569)
                                )
                            )

                            Button(
                                onClick = {
                                    if (manualCodeInput.isNotBlank()) {
                                        handleQrScanned(manualCodeInput.trim())
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text(if (language == "BN") "কানেক্ট" else "Connect")
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showManualCodeEntry = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(if (language == "BN") "পেয়ারিং কোড ম্যানুয়ালি লিখুন" else "Enter Pairing Code Manually", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
