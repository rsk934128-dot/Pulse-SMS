package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.google.firebase.auth.FirebaseUser
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.ConversationEntity
import com.example.ui.auth.GoogleSignInButton
import com.example.ui.components.ConversationItem
import com.example.ui.components.PermissionsBanner
import kotlinx.coroutines.CoroutineScope

@Composable
fun InboxScreen(
    conversations: List<ConversationEntity>,
    selectedCategory: String,
    searchQuery: String,
    totalUnreadCount: Int,
    isDefaultSms: Boolean,
    hasTelephonyPermissions: Boolean,
    isWebConnected: Boolean,
    language: String,
    currentUser: FirebaseUser? = null,
    activity: android.app.Activity? = null,
    authScope: CoroutineScope? = null,
    onAuthSuccess: () -> Unit = {},
    onAuthError: (String) -> Unit = {},
    onCategorySelected: (String) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onConversationClicked: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onToggleArchive: (String, Boolean) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenCompose: () -> Unit,
    onOpenWebSync: () -> Unit,
    onOpenQrScanner: () -> Unit = {},
    onConnectToWeb: () -> Unit = {},
    onToggleLanguage: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestDefaultSms: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenRationale: () -> Unit = onRequestPermissions,
    onSyncDeviceSms: () -> Unit = {}
) {
    var isSearchActive by remember { mutableStateOf(false) }

    val categories = listOf(
        Pair("ALL", if (language == "BN") "সব" else "All"),
        Pair("PERSONAL", if (language == "BN") "ব্যক্তিগত" else "Personal"),
        Pair("OTP", if (language == "BN") "ওটিপি" else "OTP"),
        Pair("TRANSACTION", if (language == "BN") "লেনদেন" else "Transactions"),
        Pair("PROMOTION", if (language == "BN") "অফার" else "Offers"),
        Pair("ARCHIVE", if (language == "BN") "আর্কাইভ" else "Archive")
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenCompose,
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                text = { Text(if (language == "BN") "নতুন মেসেজ" else "New SMS") },
                modifier = Modifier.testTag("compose_fab")
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header Top Bar - Responsive layout guaranteed to fit all screen widths without pushing Settings off-screen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Pulse SMS",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Pulse SMS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (totalUnreadCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Text(text = "$totalUnreadCount", fontSize = 10.sp)
                                }
                            }
                        }
                        Text(
                            text = if (language == "BN") "মেসেজিং ও টেলিফোনি" else "Telephony & Messaging",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Camera QR Scanner button for Instant Screen Connection
                    IconButton(
                        onClick = onOpenQrScanner,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .testTag("open_camera_qr_scanner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = if (language == "BN") "ক্যামেরা QR স্ক্যানার" else "Camera QR Scanner",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Web sync bridge indicator button
                    IconButton(
                        onClick = onOpenWebSync,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .testTag("open_web_sync_button")
                    ) {
                        BadgedBox(
                            badge = {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isWebConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cable,
                                contentDescription = "Web Sync Bridge",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Language toggle
                    IconButton(
                        onClick = onToggleLanguage,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("toggle_language_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (language == "BN") "বাং" else "EN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // User Profile Avatar button (Prominently displays avatar with Google initial or sign in)
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (currentUser != null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .testTag("top_bar_user_profile_button")
                    ) {
                        if (currentUser != null) {
                            BadgedBox(
                                badge = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                }
                            ) {
                                val initial = (currentUser.displayName?.firstOrNull() ?: currentUser.email?.firstOrNull() ?: 'U').uppercaseChar()
                                Text(
                                    text = "$initial",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = if (language == "BN") "সাইন-ইন" else "Sign In",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Settings button - Guaranteed visible with prominent circle background!
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = if (language == "BN") "সেটিংস" else "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = {
                        Text(
                            text = if (language == "BN") "মেসেজ বা কন্টাক্ট খুঁজুন..." else "Search messages or contacts...",
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChanged("") }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_sms_field")
                )
            }

            // Google Profile & Web Screen Connect Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("profile_and_web_connect_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentUser != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Profile Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (currentUser != null) MaterialTheme.colorScheme.primary else Color(0xFF64748B)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentUser != null) {
                                    val initial = (currentUser.displayName?.firstOrNull() ?: currentUser.email?.firstOrNull() ?: 'U').uppercaseChar()
                                    Text(
                                        text = "$initial",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = if (currentUser != null) (currentUser.displayName ?: "Google User")
                                    else (if (language == "BN") "গুগল সাইন-ইন করা নেই" else "Not Signed In"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentUser?.email ?: (if (language == "BN") "ক্লাউডে মেসেজ ব্যাকআপ রাখতে লগইন করুন" else "Sign in to backup messages to cloud"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Profile action button
                        OutlinedButton(
                            onClick = onOpenSettings,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("profile_card_action_button")
                        ) {
                            Text(
                                text = if (currentUser != null) (if (language == "BN") "প্রোফাইল" else "Profile")
                                else (if (language == "BN") "লগইন" else "Sign In"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (currentUser == null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        GoogleSignInButton(
                            activity = activity,
                            actionScope = authScope,
                            onAuthSuccess = onAuthSuccess,
                            onAuthError = onAuthError,
                            buttonTestTag = "inbox_google_sign_in_button",
                            buttonText = if (language == "BN") "গুগল দিয়ে সাইন-ইন করুন" else "Sign in with Google",
                            subtitleText = null
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Web Connection Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onConnectToWeb,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWebConnected) Color(0xFF059669) else Color(0xFF2563EB)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("inbox_instant_web_connect_button")
                        ) {
                            Icon(
                                imageVector = if (isWebConnected) Icons.Default.CloudDone else Icons.Default.ElectricBolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWebConnected) {
                                    if (language == "BN") "🟢 ওয়েব কানেক্টেড (Live)" else "🟢 Web Connected"
                                } else {
                                    if (language == "BN") "⚡ ১-ক্লিকে ওয়েব কানেক্ট" else "⚡ 1-Click Web Connect"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        OutlinedButton(
                            onClick = onOpenQrScanner,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("inbox_open_qr_scanner_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (language == "BN") "QR স্ক্যান" else "QR Scan",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Category Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { (key, label) ->
                    val isSelected = selectedCategory == key
                    FilterChip(
                        selected = isSelected,
                        onClick = { onCategorySelected(key) },
                        label = {
                            Text(
                                text = label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("category_chip_$key")
                    )
                }
            }

            // Permission / Default SMS prompt banner
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                PermissionsBanner(
                    isDefaultSms = isDefaultSms,
                    hasTelephonyPermissions = hasTelephonyPermissions,
                    language = language,
                    onRequestDefaultSms = onRequestDefaultSms,
                    onRequestPermissions = onRequestPermissions,
                    onOpenRationale = onOpenRationale,
                    onSyncDeviceSms = onSyncDeviceSms
                )
            }

            // Conversations List
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                if (language == "BN") "\"$searchQuery\" দিয়ে কোনো মেসেজ পাওয়া যায়নি" else "No messages matching \"$searchQuery\""
                            } else {
                                if (language == "BN") "এই ক্যাটাগরিতে কোনো মেসেজ নেই" else "No messages in this folder"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (language == "BN") "একটি নতুন এসএমএস শুরু করুন অথবা সিমুলেটর টেস্ট করুন" else "Start a new conversation or test with the SMS simulator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = conversations,
                        key = { it.address }
                    ) { item ->
                        ConversationItem(
                            conversation = item,
                            language = language,
                            onClick = { onConversationClicked(item.address) },
                            onTogglePin = { onTogglePin(item.address, item.isPinned) },
                            onToggleArchive = { onToggleArchive(item.address, item.isArchived) },
                            onDelete = { onDeleteConversation(item.address) }
                        )
                    }
                }
            }
        }
    }
}
