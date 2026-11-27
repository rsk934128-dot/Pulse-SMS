package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

private const val TAG = "RemoteMicBridge"
private const val SAMPLE_RATE = 16000
private const val TARGET_CHUNK_BYTES = 25600 // ~0.8s @ 16kHz 16-bit mono (~34KB base64)

/**
 * Streams live 16kHz Mono PCM-16 audio from the device microphone to the paired Website
 * whenever `isActive` is true, while displaying a transparent live privacy indicator
 * with an audio level meter and 1-tap Stop button on the phone screen.
 */
@Composable
fun RemoteMicBridgeOverlay(
    isActive: Boolean,
    chunkCount: Int,
    permissionNeeded: Boolean,
    isCameraOverlayVisible: Boolean,
    language: String,
    onRequestMicPermission: () -> Unit,
    onDismissPermissionPrompt: () -> Unit,
    onStopMic: () -> Unit,
    onAudioChunkCaptured: (base64Pcm: String, sampleRate: Int, audioLevel: Int) -> Unit,
    onHardwareError: (String) -> Unit
) {
    val context = LocalContext.current
    val isBn = language == "BN"
    val hasPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val latestOnChunkCaptured by rememberUpdatedState(onAudioChunkCaptured)
    val latestOnHardwareError by rememberUpdatedState(onHardwareError)
    var localLevel by remember { mutableIntStateOf(0) }

    if (permissionNeeded) {
        AlertDialog(
            onDismissRequest = onDismissPermissionPrompt,
            icon = {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color(0xFF10B981)
                )
            },
            title = {
                Text(
                    text = if (isBn) "ওয়েবসাইট থেকে মাইক্রোফোন রিকোয়েস্ট" else "Website Microphone Request",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isBn) {
                        "আপনার পেয়ার করা ওয়েবসাইট থেকে মোবাইলের মাইক্রোফোনের লাইভ অডিও শোনার অনুরোধ এসেছে। চালু করতে মাইক্রোফোন পারমিশন দিন।"
                    } else {
                        "Your paired website requested to listen to this device's live microphone. Grant microphone permission to start streaming."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier.testTag("grant_remote_mic_permission_btn")
                ) {
                    Text(if (isBn) "মাইক্রোফোন অনুমতি দিন" else "Allow Microphone")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDismissPermissionPrompt()
                        onStopMic()
                    }
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    LaunchedEffect(isActive, hasPermission) {
        if (!isActive || !hasPermission) {
            localLevel = 0
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var noiseSuppressor: NoiseSuppressor? = null
            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withContext
                }

                val minBufBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)

                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufBytes * 2
                )
                recorder = rec

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        latestOnHardwareError("Microphone hardware initialization failed")
                    }
                    return@withContext
                }

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply {
                            enabled = true
                        }
                    }
                } catch (_: Throwable) {}

                rec.startRecording()

                val shortBuf = ShortArray(1024)
                val chunkStream = ByteArrayOutputStream(TARGET_CHUNK_BYTES + 2048)

                while (isActive) {
                    val readSamples = rec.read(shortBuf, 0, shortBuf.size)
                    if (readSamples > 0) {
                        var sumSq = 0.0
                        for (i in 0 until readSamples) {
                            val sample = shortBuf[i].toInt()
                            sumSq += sample.toDouble() * sample.toDouble()
                            chunkStream.write(sample and 0xFF)
                            chunkStream.write((sample ushr 8) and 0xFF)
                        }
                        val rms = sqrt(sumSq / readSamples.coerceAtLeast(1))
                        val level = ((rms / 5500.0) * 100.0).toInt().coerceIn(0, 100)
                        localLevel = level

                        if (chunkStream.size() >= TARGET_CHUNK_BYTES) {
                            val pcmBytes = chunkStream.toByteArray()
                            chunkStream.reset()
                            val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                            latestOnChunkCaptured(b64, SAMPLE_RATE, level)
                        }
                    } else if (readSamples < 0) {
                        Log.w(TAG, "AudioRecord read error code: $readSamples")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Remote mic streaming error", t)
                withContext(Dispatchers.Main) {
                    latestOnHardwareError(t.localizedMessage ?: "Microphone stream error")
                }
            } finally {
                try {
                    noiseSuppressor?.release()
                } catch (_: Throwable) {}
                try {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Throwable) {}
                try {
                    recorder?.release()
                } catch (_: Throwable) {}
                localLevel = 0
            }
        }
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
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = if (isCameraOverlayVisible) 144.dp else 14.dp
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isBn) "লাইভ মাইক্রোফোন চালু" else "Live Microphone Active",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF059669)
                            ) {
                                Text(
                                    text = "MIC LIVE",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Text(
                                text = if (isBn) "ওয়েবসাইটে লাইভ অডিও যাচ্ছে" else "Streaming Audio to Website",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { (localLevel / 100f).coerceIn(0.05f, 1f) },
                            color = Color(0xFF34D399),
                            trackColor = Color(0xFF1E293B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (isBn) {
                                "১৬ kHz লাইভ অডিও • $chunkCount খণ্ড প্রেরিত"
                            } else {
                                "16kHz Live Audio • $chunkCount chunks sent"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onStopMic,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("remote_mic_stop_btn")
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
