package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Banner displayed on top of the inbox prompting for SMS Read/Receive permissions and Default SMS status.
 */
@Composable
fun PermissionsBanner(
    isDefaultSms: Boolean,
    hasTelephonyPermissions: Boolean,
    language: String,
    onRequestDefaultSms: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenRationale: () -> Unit = onRequestPermissions,
    onSyncDeviceSms: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (isDefaultSms && hasTelephonyPermissions) return

    val isBn = language == "BN"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenRationale() }
            .testTag("permissions_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (!hasTelephonyPermissions) Icons.Default.Security else Icons.Default.MarkEmailRead,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!hasTelephonyPermissions) {
                            if (isBn) "এসএমএস রিড ও রিসিভ পারমিশন প্রয়োজন" else "SMS Read & Receive Permission Required"
                        } else {
                            if (isBn) "ডিফল্ট এসএমএস অ্যাপ হিসেবে সেট করুন" else "Set as Default SMS App"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (!hasTelephonyPermissions) {
                            if (isBn) {
                                "ইনবক্স মেসেজ পড়তে ও নতুন মেসেজ রিয়েল-টাইমে গ্রহণ করতে অনুমতি দিন।"
                            } else {
                                "Grant runtime permissions to read your SMS inbox and receive incoming messages."
                            }
                        } else {
                            if (isBn) {
                                "লাইভ ক্যারিয়ার এসএমএস পাঠাতে ও সম্পূর্ণ নিয়ন্ত্রণ পেতে ডিফল্ট হ্যান্ডলার করুন।"
                            } else {
                                "Set as default SMS handler to send and manage live carrier messages."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }

                IconButton(
                    onClick = onOpenRationale,
                    modifier = Modifier.size(28.dp).testTag("permission_info_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Permission Details",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasTelephonyPermissions) {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("grant_permissions_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isBn) "অনুমতি প্রদান করুন" else "Grant SMS Access",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!isDefaultSms) {
                    OutlinedButton(
                        onClick = onRequestDefaultSms,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("set_default_sms_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isBn) "ডিফল্ট এসএমএস" else "Default SMS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
