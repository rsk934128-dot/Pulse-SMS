package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ConnectedPeerDevice
import com.example.data.repository.PairedSessionRecord
import com.example.data.repository.QrPairingManager
import com.example.telephony.CapturedDeviceLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiDeviceDialog(
    language: String,
    currentDeviceId: String,
    currentBatteryLevel: Int,
    isCurrentCharging: Boolean,
    activeSessionId: String?,
    pairedSessions: List<PairedSessionRecord>,
    connectedPeerDevices: List<ConnectedPeerDevice>,
    currentLocation: CapturedDeviceLocation? = null,
    hasLocationPermission: Boolean = false,
    isSystemLocationEnabled: Boolean = false,
    isLiveLocationSharingEnabled: Boolean = true,
    isRefreshingLocation: Boolean = false,
    onTurnOnLocation: () -> Unit = {},
    onToggleLocationSharing: (Boolean) -> Unit = {},
    onRefreshLocation: () -> Unit = {},
    onRefreshDevices: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOneClickConnect: () -> Unit,
    onPairWithCode: (String) -> Unit,
    onSwitchPrimarySession: (String) -> Unit,
    onRemoveSession: (String) -> Unit,
    onRemoteDialPeer: (targetSessionId: String, phoneNumber: String) -> Unit,
    onRemoteSmsPeer: (targetSessionId: String, recipient: String, body: String) -> Unit,
    onPingPeerDevice: (targetSessionId: String, deviceName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isBn = language == "BN"
    val thisDeviceName = remember { QrPairingManager.getDeviceDisplayName() }

    var manualCodeInput by remember { mutableStateOf("") }
    var activeDialDeviceId by remember { mutableStateOf<String?>(null) }
    var activeSmsDeviceId by remember { mutableStateOf<String?>(null) }
    var peerDialNumber by remember { mutableStateOf("") }
    var peerSmsRecipient by remember { mutableStateOf("") }
    var peerSmsBody by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBn) "মাল্টিপল ডিভাইস ও স্ক্রিন ম্যানেজার" else "Multi-Device & Screen Hub",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isBn) {
                                "${pairedSessions.size} টি স্ক্রিন · ${connectedPeerDevices.count { it.isOnline }} টি অনলাইন ফোন"
                            } else {
                                "${pairedSessions.size} screens · ${connectedPeerDevices.count { it.isOnline }} online phones"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefreshDevices,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("multi_device_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = if (isBn) "রিফ্রেশ" else "Refresh"
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("multi_device_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (isBn) "বন্ধ করুন" else "Close"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // This Device Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = thisDeviceName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "ID: $currentDeviceId",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (currentBatteryLevel >= 0) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isCurrentCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                        contentDescription = null,
                                        tint = if (currentBatteryLevel <= 20 && !isCurrentCharging) Color(0xFFEF4444) else Color(0xFF10B981),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "$currentBatteryLevel%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (!activeSessionId.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isBn) "প্রাইমারি সেশন: $activeSessionId" else "Primary Session: $activeSessionId",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Add New Screen / Device Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (isBn) "➕ নতুন স্ক্রিন বা ডিভাইস যুক্ত করুন" else "➕ Connect New Screen or Device",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isBn) {
                            "একই সাথে একাধিক কম্পিউটার ব্রাউজার ও অ্যান্ড্রয়েড ফোন যুক্ত রেখে ব্যবহার করুন।"
                        } else {
                            "Connect and sync multiple computer browsers and Android phones simultaneously."
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenQrScanner,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("multi_device_scan_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "QR স্ক্যান" else "Scan QR",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onOneClickConnect,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("multi_device_one_click_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "১-ক্লিক হাব" else "1-Click Hub",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualCodeInput,
                            onValueChange = { manualCodeInput = it },
                            placeholder = {
                                Text(
                                    text = if (isBn) "পেয়ারিং টোকেন লিখুন (pulse_pair_...)" else "Enter pairing token (pulse_pair_...)",
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("multi_device_code_input")
                        )
                        Button(
                            onClick = {
                                val cleaned = manualCodeInput.trim()
                                if (cleaned.isNotBlank()) {
                                    onPairWithCode(cleaned)
                                    manualCodeInput = ""
                                }
                            },
                            enabled = manualCodeInput.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("multi_device_pair_code_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddLink,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "যুক্ত" else "Join",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Paired Web Sessions Section
            Text(
                text = if (isBn) "🌐 যুক্ত ওয়েব স্ক্রিনসমূহ (${pairedSessions.size})" else "🌐 Connected Web Screens (${pairedSessions.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (pairedSessions.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBn) "এখনো কোনো ওয়েব স্ক্রিন যুক্ত নেই। উপরে QR স্ক্যান বা ১-ক্লিক হাব চাপুন।" else "No web screens paired yet. Use Scan QR or 1-Click Hub above.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pairedSessions.forEach { session ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (session.isPrimary) Color(0xFF064E3B).copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = if (session.isPrimary) Color(0xFF10B981).copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (session.isPrimary) Color(0xFF10B981) else Color(0xFF38BDF8))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = session.sessionId,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = if (session.isPrimary) {
                                            if (isBn) "🟢 প্রাইমারি স্ক্রিন (ক্যামেরা/মাইক/SMS অ্যাক্টিভ)" else "🟢 Primary Screen (Active)"
                                        } else {
                                            if (isBn) "🔵 মাল্টি-সিঙ্ক অ্যাক্টিভ" else "🔵 Multi-Sync Active"
                                        },
                                        fontSize = 11.sp,
                                        color = if (session.isPrimary) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!session.isPrimary) {
                                        OutlinedButton(
                                            onClick = { onSwitchPrimarySession(session.sessionId) },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Text(
                                                text = if (isBn) "প্রাইমারি" else "Primary",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveSession(session.sessionId) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = if (isBn) "সেশন মুছুন" else "Disconnect session",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Device Location Map & Turn-On Location Control
            DeviceLocationMapCard(
                language = language,
                currentDeviceId = currentDeviceId,
                currentDeviceName = thisDeviceName,
                currentLocation = currentLocation,
                connectedPeerDevices = connectedPeerDevices,
                hasLocationPermission = hasLocationPermission,
                isSystemLocationEnabled = isSystemLocationEnabled,
                isLiveLocationSharingEnabled = isLiveLocationSharingEnabled,
                isRefreshingLocation = isRefreshingLocation,
                onTurnOnLocation = onTurnOnLocation,
                onToggleLocationSharing = onToggleLocationSharing,
                onRefreshLocation = onRefreshLocation
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            // Connected Peer Devices Section
            Text(
                text = if (isBn) "📱 যুক্ত মোবাইল ডিভাইসসমূহ (${connectedPeerDevices.size})" else "📱 Connected Peer Phones (${connectedPeerDevices.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBn) {
                    "অন্যান্য যুক্ত ফোনের ব্যাটারি/অনলাইন স্ট্যাটাস দেখুন এবং সরাসরি রিমোট কল, এসএমএস বা পিং পাঠান।"
                } else {
                    "Inspect battery/online status and trigger remote calls, SMS, or pings on other connected devices."
                },
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (connectedPeerDevices.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBn) {
                            "অন্য কোনো ডিভাইস এখনো যুক্ত হয়নি। অন্য ফোনে Pulse SMS অ্যাপ খুলে '১-ক্লিকে ওয়েব কানেক্ট' চাপলেই এখানে চলে আসবে।"
                        } else {
                            "No peer phones detected yet. Open Pulse SMS on another device and tap 1-Click Web Connect."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    connectedPeerDevices.forEach { device ->
                        val isSelf = device.deviceId == currentDeviceId
                        val isDialOpen = activeDialDeviceId == device.deviceId
                        val isSmsOpen = activeSmsDeviceId == device.deviceId

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelf) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = if (device.isOnline) Color(0xFF10B981).copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (device.isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = device.deviceName,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isSelf) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                    ) {
                                                        Text(
                                                            text = if (isBn) "এই ফোন" else "This Phone",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = "${if (device.isOnline) (if (isBn) "অনলাইন" else "Online") else (if (isBn) "অফলাইন" else "Offline")} · ${device.sessionId}",
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (device.batteryLevel >= 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (device.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                                contentDescription = null,
                                                tint = if (device.batteryLevel <= 20 && !device.isCharging) Color(0xFFEF4444) else Color(0xFF10B981),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${device.batteryLevel}%",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                val devLat = device.latitude
                                val devLng = device.longitude
                                if (devLat != null && devLng != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Surface(
                                        onClick = {
                                            openPinInExternalMaps(
                                                context,
                                                PlottedDevicePin(
                                                    deviceId = device.deviceId,
                                                    deviceName = device.deviceName,
                                                    latitude = devLat,
                                                    longitude = devLng,
                                                    label = device.locationLabel,
                                                    isOnline = device.isOnline,
                                                    isSelf = isSelf,
                                                    batteryLevel = device.batteryLevel,
                                                    accuracyM = device.locationAccuracyM
                                                )
                                            )
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.14f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocationOn,
                                                    contentDescription = null,
                                                    tint = Color(0xFF10B981),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = device.locationLabel ?: String.format("%.4f, %.4f", devLat, devLng),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF047857),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = if (isBn) "ম্যাপে দেখুন ↗" else "Map ↗",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0284C7)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Action Buttons for this device
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            activeDialDeviceId = if (isDialOpen) null else device.deviceId
                                            activeSmsDeviceId = null
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "কল" else "Call",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            activeSmsDeviceId = if (isSmsOpen) null else device.deviceId
                                            activeDialDeviceId = null
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sms,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "SMS" else "SMS",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { onPingPeerDevice(device.sessionId, device.deviceName) },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isBn) "পিং" else "Ping",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                // Expandable Remote Call Dialer on Peer Phone
                                AnimatedVisibility(visible = isDialOpen) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = peerDialNumber,
                                            onValueChange = { peerDialNumber = it },
                                            placeholder = {
                                                Text(
                                                    text = if (isBn) "ফোন নাম্বার লিখুন..." else "Phone number...",
                                                    fontSize = 12.sp
                                                )
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val num = peerDialNumber.trim()
                                                if (num.isNotBlank()) {
                                                    onRemoteDialPeer(device.sessionId, num)
                                                    peerDialNumber = ""
                                                    activeDialDeviceId = null
                                                }
                                            },
                                            enabled = peerDialNumber.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Call,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isBn) "ডায়াল" else "Dial",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Expandable Remote SMS Sender on Peer Phone
                                AnimatedVisibility(visible = isSmsOpen) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = peerSmsRecipient,
                                            onValueChange = { peerSmsRecipient = it },
                                            placeholder = {
                                                Text(
                                                    text = if (isBn) "প্রাপকের নাম্বার..." else "Recipient number...",
                                                    fontSize = 12.sp
                                                )
                                            },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = peerSmsBody,
                                                onValueChange = { peerSmsBody = it },
                                                placeholder = {
                                                    Text(
                                                        text = if (isBn) "মেসেজ লিখুন..." else "Message text...",
                                                        fontSize = 12.sp
                                                    )
                                                },
                                                singleLine = true,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    val rec = peerSmsRecipient.trim()
                                                    val msg = peerSmsBody.trim()
                                                    if (rec.isNotBlank() && msg.isNotBlank()) {
                                                        onRemoteSmsPeer(device.sessionId, rec, msg)
                                                        peerSmsBody = ""
                                                        activeSmsDeviceId = null
                                                    }
                                                },
                                                enabled = peerSmsRecipient.isNotBlank() && peerSmsBody.isNotBlank(),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Send,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isBn) "পাঠান" else "Send",
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
            }
        }
    }
}
