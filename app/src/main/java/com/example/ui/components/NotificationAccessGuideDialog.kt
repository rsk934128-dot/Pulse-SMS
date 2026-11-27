package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Interactive helper dialog explaining how to unlock Android 13/14/15's
 * "Restricted setting" dialog on sideloaded APKs and providing direct
 * 1-tap launchers for both Device & App Notifications and App Info.
 */
@Composable
fun NotificationAccessGuideDialog(
    language: String,
    hasNotificationAccess: Boolean,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isBn = language == "BN"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
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
                                .background(
                                    if (hasNotificationAccess) Color(0xFF10B981).copy(alpha = 0.16f)
                                    else Color(0xFFF59E0B).copy(alpha = 0.16f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hasNotificationAccess) Icons.Default.CheckCircle else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (hasNotificationAccess) Color(0xFF10B981) else Color(0xFFD97706),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isBn) "সব অ্যাপ নোটিফিকেশন ও Restricted সেটআপ" else "All-App Notifications & Restricted Fix",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasNotificationAccess) {
                                    if (isBn) "✅ নোটিফিকেশন অ্যাক্সেস সক্রিয় আছে" else "✅ Notification Access is active"
                                } else {
                                    if (isBn) "Android 13/14/15-এ Restricted Setting আনলক গাইড" else "Unlock 'Restricted setting' on Android 13+"
                                },
                                fontSize = 12.sp,
                                color = if (hasNotificationAccess) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_restricted_guide_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (hasNotificationAccess) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isBn) {
                                "আপনার ফোনের সব অ্যাপের (WhatsApp, Messenger, Gmail, bKash ইত্যাদি) নোটিফিকেশন এখন ওয়েবসাইটের সাথে রিয়েল-টাইম সিঙ্ক হচ্ছে!"
                            } else {
                                "Notifications from all your apps (WhatsApp, Messenger, Gmail, etc.) are actively synced to Pulse Web."
                            },
                            fontSize = 13.sp,
                            color = Color(0xFF065F46),
                            modifier = Modifier.padding(14.dp),
                            lineHeight = 19.sp
                        )
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFFBEB)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (isBn) {
                                    "⚠️ যদি 'Restricted setting' পপআপ দেখায়:"
                                } else {
                                    "⚠️ Seeing 'Restricted setting' when enabling Pulse SMS?"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF92400E)
                            )

                            StepItemRow(
                                number = "১",
                                text = if (isBn) {
                                    "নিচের '১. নোটিফিকেশন অ্যাক্সেস খুলুন' বাটনে গিয়ে Pulse SMS চাপুন (Restricted setting এলে OK দিন)।"
                                } else {
                                    "Tap '1. Open Notification Access' below, tap Pulse SMS, and tap OK if 'Restricted setting' appears."
                                }
                            )
                            StepItemRow(
                                number = "২",
                                text = if (isBn) {
                                    "এবার '২. App Info (Restricted আনলক)' বাটনে যান → উপরের ডান কোণায় ⋮ (৩-ডট মেনু) চাপুন → 'Allow restricted settings' অন করে পিন/ফিঙ্গারপ্রিন্ট দিন।"
                                } else {
                                    "Tap '2. Open App Info' below → tap the top-right ⋮ (3-dot menu) → select 'Allow restricted settings' and verify PIN/biometric."
                                }
                            )
                            StepItemRow(
                                number = "৩",
                                text = if (isBn) {
                                    "ফিরে এসে আবার '১. নোটিফিকেশন অ্যাক্সেস খুলুন'-এ গিয়ে Pulse SMS সুইচ চালু করে দিন!"
                                } else {
                                    "Return to '1. Open Notification Access' and toggle Pulse SMS ON."
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isBn) {
                            "💡 তথ্য: আপনার ফোনের ইনকামিং SMS ও OTP এই অনুমতি ছাড়াই ওয়েবসাইটে সিঙ্ক হয়। এটি কেবল WhatsApp/Messenger-এর মতো অন্য অ্যাপের নোটিফিকেশনের জন্য দরকার।"
                        } else {
                            "💡 Note: Incoming SMS & OTP already sync without this permission. Notification Access is only needed for third-party apps."
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action 1: Open Notification Listener Settings
                Button(
                    onClick = onOpenNotificationAccessSettings,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_open_notif_listener_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasNotificationAccess) {
                            if (isBn) "নোটিফিকেশন অ্যাক্সেস সেটিংস দেখুন" else "Open Notification Access"
                        } else {
                            if (isBn) "১. নোটিফিকেশন অ্যাক্সেস খুলুন" else "1. Open Notification Access"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action 2: Open App Info to tap ⋮ -> Allow restricted settings
                FilledTonalButton(
                    onClick = onOpenAppInfoSettings,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFF59E0B).copy(alpha = 0.16f),
                        contentColor = Color(0xFFB45309)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("guide_open_app_info_restricted_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "২. App Info খুলুন (Restricted আনলক)" else "2. Open App Info (Unlock Restricted)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StepItemRow(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xFFD97706)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color(0xFF78350F),
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
