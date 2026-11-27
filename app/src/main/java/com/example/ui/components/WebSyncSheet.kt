package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.MirroredNotificationItem
import com.example.data.repository.WebSyncEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSyncSheet(
    isConnected: Boolean,
    syncLogs: List<WebSyncEvent>,
    language: String,
    activePairingSessionId: String? = null,
    hasNotificationAccess: Boolean = false,
    hasPostNotificationPermission: Boolean = true,
    isRemoteCameraActive: Boolean = false,
    isRemoteMicActive: Boolean = false,
    hasCallLogPermission: Boolean = false,
    syncedCallLogsCount: Int = 0,
    autoCallRecordingEnabled: Boolean = false,
    isCallRecordingNow: Boolean = false,
    callRecordingDurationSec: Long = 0L,
    callRecordingTargetNumber: String = "",
    recordedCallsCount: Int = 0,
    hasLocationPermission: Boolean = false,
    recentCallNumbers: List<String> = emptyList(),
    mirroredNotifications: List<MirroredNotificationItem> = emptyList(),
    onToggleConnection: () -> Unit,
    onSimulateIncoming: (sender: String, body: String) -> Unit,
    onOpenQrScanner: () -> Unit = {},
    onConnectToWeb: () -> Unit = {},
    onOpenNotificationAccessSettings: () -> Unit = {},
    onOpenAppInfoSettings: () -> Unit = {},
    onToggleRemoteCamera: () -> Unit = {},
    onToggleRemoteMic: () -> Unit = {},
    onSyncCallLogsOrRequestPermission: () -> Unit = {},
    onToggleAutoCallRecording: (Boolean) -> Unit = {},
    onStartManualCallRecording: (String) -> Unit = {},
    onStopManualCallRecording: () -> Unit = {},
    onRequestCallLocationPermissions: () -> Unit = {},
    onRequestPermissions: () -> Unit = {},
    onSendSimultaneousTestNotification: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showRestrictedGuide by remember { mutableStateOf(false) }

    var customSender by remember { mutableStateOf("+8801911998877") }
    var customBody by remember { mutableStateOf("হ্যালো! কেমন আছেন? এটি একটি লাইভ টেস্ট এসএমএস।") }
    var recordTargetNumber by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (language == "BN") "রিয়েল-টাইম ওয়েব সিঙ্ক ও সিমুলেটর" else "Real-time Web Sync & Simulator",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Socket.io Bridge · Telephony Receiver",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Socket connection status card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isConnected) {
                                    if (language == "BN") "ওয়েব সিঙ্ক সক্রিয় (Active)" else "Web Sync Active (Connected)"
                                } else {
                                    if (language == "BN") "ওয়েব সিঙ্ক সংযোগ বিচ্ছিন্ন" else "Web Sync Disconnected"
                                },
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) Color(0xFF065F46) else Color(0xFF991B1B),
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (isConnected) "ws://localhost:5000/realtime · Syncing 2-way" else "Server offline",
                                fontSize = 12.sp,
                                color = if (isConnected) Color(0xFF047857) else Color(0xFFB91C1C)
                            )
                        }
                    }

                    Switch(
                        checked = isConnected,
                        onCheckedChange = { onToggleConnection() },
                        modifier = Modifier.testTag("toggle_web_sync_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Instant 1-Tap Connect to Web Button
            Button(
                onClick = {
                    onConnectToWeb()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("instant_one_tap_connect_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (language == "BN") "⚡ ওয়েবসাইটে ১-ক্লিকে এখনই কানেক্ট করুন" else "⚡ Connect to Website in 1-Click",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Camera QR Scan Button
            OutlinedButton(
                onClick = onOpenQrScanner,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_camera_qr_scan_from_sheet")
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (language == "BN") "ক্যামেরা দিয়ে ওয়েবসাইটের QR স্ক্যান করুন" else "Scan Website QR with Camera",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Two-Way Notification Sync & Simultaneous Test Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (language == "BN") "দুই-মুখী নোটিফিকেশন সিঙ্ক ও টেস্ট" else "Two-Way Notification Mirror",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (activePairingSessionId != null) {
                                        if (language == "BN") "মোবাইল ⇄ ওয়েব সেম-টাইম লাইভ মিরর সক্রিয়" else "Mobile ⇄ Web Live Mirror Active"
                                    } else {
                                        if (language == "BN") "লাইভ সিঙ্কের জন্য উপরে QR বা ১-ক্লিকে কানেক্ট করুন" else "Pair above for live cross-device alerts"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (activePairingSessionId != null) Color(0xFF10B981).copy(alpha = 0.18f) else Color(0xFFF59E0B).copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = if (activePairingSessionId != null) "LIVE" else "OFFLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activePairingSessionId != null) Color(0xFF059669) else Color(0xFFD97706),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Status pills for Notification Access & POST_NOTIFICATIONS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (hasNotificationAccess) {
                                    onOpenNotificationAccessSettings()
                                } else {
                                    showRestrictedGuide = true
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("grant_notification_listener_button")
                        ) {
                            Icon(
                                imageVector = if (hasNotificationAccess) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = if (hasNotificationAccess) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasNotificationAccess) {
                                    if (language == "BN") "সব অ্যাপ চালু" else "All Apps On"
                                } else {
                                    if (language == "BN") "সব অ্যাপ নোটিফিকেশন চালু করুন" else "Enable All Apps"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        if (!hasPostNotificationPermission) {
                            OutlinedButton(
                                onClick = onRequestPermissions,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("grant_post_notification_button")
                            ) {
                                Text(
                                    text = if (language == "BN") "পুশ অনুমতি দিন" else "Allow Alerts",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    if (!hasNotificationAccess) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFFBEB),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (language == "BN") "Restricted setting দেখালে App Info → ⋮ Allow restricted settings দিন"
                                    else "Restricted setting? Open App Info → ⋮ Allow restricted",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF92400E),
                                    modifier = Modifier.weight(1f),
                                    lineHeight = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = onOpenAppInfoSettings,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("open_app_info_restricted_button")
                                ) {
                                    Text(
                                        text = if (language == "BN") "App Info" else "App Info",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onSendSimultaneousTestNotification,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("send_simultaneous_test_notification_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (language == "BN") "🧪 মোবাইল ও ওয়েবে একসাথে টেস্ট নোটিফিকেশন পাঠান" else "🧪 Send Simultaneous Test (Mobile + Web)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onToggleRemoteCamera,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("toggle_remote_camera_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = if (isRemoteCameraActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRemoteCameraActive) {
                                if (language == "BN") "🔴 লাইভ ক্যামেরা শেয়ার চলছে (বন্ধ করুন)" else "🔴 Live Camera Sharing (Stop)"
                            } else {
                                if (language == "BN") "📷 ওয়েবসাইটে লাইভ মোবাইল ক্যামেরা শেয়ার করুন" else "📷 Share Live Phone Camera to Web"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRemoteCameraActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onSyncCallLogsOrRequestPermission,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("sync_call_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = if (hasCallLogPermission) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasCallLogPermission) {
                                if (language == "BN") "📞 কল হিস্টরি ওয়েবে সিঙ্ক করুন (${syncedCallLogsCount}টি)" else "📞 Sync Call History to Web (${syncedCallLogsCount})"
                            } else {
                                if (language == "BN") "📞 কল হিস্টরি অনুমতি দিন (Web Call Log)" else "📞 Enable Call History for Web"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasCallLogPermission) Color(0xFF059669) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onToggleRemoteMic,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("toggle_remote_mic_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isRemoteMicActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRemoteMicActive) {
                                if (language == "BN") "🔴 লাইভ মাইক্রোফোন শেয়ার চলছে (বন্ধ করুন)" else "🔴 Live Microphone Active (Stop)"
                            } else {
                                if (language == "BN") "🎙️ ওয়েবসাইটে লাইভ মাইক্রোফোন শেয়ার করুন" else "🎙️ Share Live Phone Microphone to Web"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRemoteMicActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                        )
                    }

                    if (mirroredNotifications.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (language == "BN") "সাম্প্রতিক সেম-টাইম নোটিফিকেশন (${mirroredNotifications.size}টি):" else "Live Mirrored Feed (${mirroredNotifications.size}):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val timeFmt = remember { SimpleDateFormat("hh:mm:ss a", Locale.getDefault()) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            mirroredNotifications.take(4).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (item.sourceDevice == "WEB") "🌐" else "📱",
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        if (item.body.isNotBlank()) {
                                            Text(
                                                text = item.body,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = timeFmt.format(Date(item.createdAtMs)),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Call Recording & Number-Mapped Location Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (language == "BN") "কল রেকর্ডিং ও নাম্বার ম্যাপ" else "Call Recording & Number Map",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (language == "BN") {
                                        "প্রতিটি নাম্বারের রেকর্ডিং ও লাইভ লোকেশন ওয়েব ম্যাপে পাঠান (${recordedCallsCount}টি)"
                                    } else {
                                        "Send call audio & location pinned by phone number (${recordedCallsCount})"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = autoCallRecordingEnabled,
                            onCheckedChange = onToggleAutoCallRecording,
                            modifier = Modifier.testTag("auto_call_recording_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (autoCallRecordingEnabled) {
                            if (language == "BN") "✅ অটো কল রেকর্ডিং চালু (কল এলে/গেলে অটো রেকর্ড হয়ে ম্যাপসহ ওয়েবে যাবে)"
                            else "✅ Auto Call Recording ON (records calls and uploads with map pin)"
                        } else {
                            if (language == "BN") "সুইচ অন করলে ফোনে কল আসা-যাওয়া অটো রেকর্ড হবে; অথবা নিচ থেকে ম্যানুয়ালি রেকর্ড করুন।"
                            else "Turn on to auto-record phone calls, or record manually for any number below."
                        },
                        fontSize = 11.sp,
                        color = if (autoCallRecordingEnabled) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = if (isCallRecordingNow && callRecordingTargetNumber.isNotBlank()) callRecordingTargetNumber else recordTargetNumber,
                        onValueChange = { if (!isCallRecordingNow) recordTargetNumber = it },
                        enabled = !isCallRecordingNow,
                        label = { Text(if (language == "BN") "ফোন নাম্বার (যার নামে ম্যাপ ও রেকর্ড হবে)" else "Phone Number to Map") },
                        placeholder = { Text("+8801711000000") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("call_record_target_number_input")
                    )

                    if (recentCallNumbers.isNotEmpty() && !isCallRecordingNow) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            recentCallNumbers.forEach { num ->
                                FilterChip(
                                    selected = recordTargetNumber == num,
                                    onClick = { recordTargetNumber = num },
                                    label = { Text(num, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isCallRecordingNow) {
                                    onStopManualCallRecording()
                                } else {
                                    val chosen = recordTargetNumber.trim().ifBlank {
                                        recentCallNumbers.firstOrNull() ?: "+8801711000000"
                                    }
                                    recordTargetNumber = chosen
                                    onStartManualCallRecording(chosen)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCallRecordingNow) Color(0xFFEF4444) else Color(0xFFD97706)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.4f)
                                .heightIn(min = 48.dp)
                                .testTag("manual_call_record_button")
                        ) {
                            Icon(
                                imageVector = if (isCallRecordingNow) Icons.Default.StopCircle else Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCallRecordingNow) {
                                    val min = callRecordingDurationSec / 60L
                                    val sec = callRecordingDurationSec % 60L
                                    if (language == "BN") "⏹️ বন্ধ ও ওয়েবে পাঠান (${String.format("%02d:%02d", min, sec)})"
                                    else "⏹️ Stop & Send (${String.format("%02d:%02d", min, sec)})"
                                } else {
                                    if (language == "BN") "🎙️ কল রেকর্ড শুরু করুন" else "🎙️ Start Call Record"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = onRequestCallLocationPermissions,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("grant_call_location_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = if (hasLocationPermission) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (hasLocationPermission) {
                                    if (language == "BN") "ম্যাপ চালু" else "Map On"
                                } else {
                                    if (language == "BN") "ম্যাপ অনুমতি" else "Map Perm"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasLocationPermission) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Preset fast simulators
            Text(
                text = if (language == "BN") "দ্রুত ইনকামিং এসএমএস টেস্ট (Presets)" else "Quick Incoming SMS Simulation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val code = (100000..999999).random()
                        onSimulateIncoming(
                            "Google",
                            "$code is your Google Verification Code. Do not share this with anyone."
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_otp_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("OTP Code", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        val amount = listOf("1,500.00", "3,200.00", "5,000.00").random()
                        onSimulateIncoming(
                            "bKash",
                            "Cash In Tk $amount successful from 01799887766. Fee Tk 0.00. Balance Tk 18,340.00. TrxID ${(10000000..99999999).random()}."
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_bank_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("bKash/Bank", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        onSimulateIncoming(
                            "+8801711223344",
                            "কিরে দোস্ত! অফিস শেষ করে আড্ডা দিবি নাকি? জানা।"
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_personal_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Friend (বাং)", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Custom incoming test
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (language == "BN") "কাস্টম ইনকামিং এসএমএস পাঠান" else "Custom Incoming SMS Dispatcher",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = customSender,
                        onValueChange = { customSender = it },
                        label = { Text(if (language == "BN") "প্রেরক (নাম বা নাম্বার)" else "Sender (Name or Number)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_sender_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customBody,
                        onValueChange = { customBody = it },
                        label = { Text(if (language == "BN") "মেসেজের বিবরণ" else "Message Body") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_body_input"),
                        minLines = 2,
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (customSender.isNotBlank() && customBody.isNotBlank()) {
                                onSimulateIncoming(customSender, customBody)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dispatch_custom_sms_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (language == "BN") "এখনই ইনকামিং এসএমএস ট্রিগার করুন" else "Trigger Incoming SMS Now")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Live event log
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (language == "BN") "রিয়েল-টাইম লাইভ ইভেন্ট লগ" else "Live Real-time Event Stream",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${syncLogs.size} events",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp)
            ) {
                if (syncLogs.isEmpty()) {
                    Text(
                        text = "Listening for telephony and websocket events...",
                        color = Color(0xFF64748B),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(syncLogs) { log ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                val tagColor = when (log.type) {
                                    "INCOMING" -> Color(0xFF10B981)
                                    "OUTGOING" -> Color(0xFF3B82F6)
                                    "WEB_SOCKET" -> Color(0xFFA855F7)
                                    else -> Color(0xFFF59E0B)
                                }
                                Text(
                                    text = "[${log.type}]",
                                    color = tagColor,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(90.dp)
                                )
                                Text(
                                    text = log.description,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRestrictedGuide) {
        NotificationAccessGuideDialog(
            language = language,
            hasNotificationAccess = hasNotificationAccess,
            onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
            onOpenAppInfoSettings = onOpenAppInfoSettings,
            onDismiss = { showRestrictedGuide = false }
        )
    }
}
