package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.telephony.DeviceFileEntry
import com.example.telephony.DeviceFileManagerHelper
import com.example.telephony.FileBreadcrumbItem
import com.example.telephony.FileRootInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerSheet(
    language: String,
    roots: List<FileRootInfo>,
    currentRootId: String,
    currentPath: String,
    breadcrumbs: List<FileBreadcrumbItem>,
    entries: List<DeviceFileEntry>,
    hasCustomFolderAccess: Boolean,
    isLoading: Boolean,
    onGrantFolder: () -> Unit,
    onPickPhotosOrFiles: () -> Unit,
    onOpenDirectory: (rootId: String, path: String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (folderName: String) -> Unit,
    onDeleteEntry: (rootId: String, fileId: String) -> Unit,
    onRemoveRoot: (rootId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isBn = language == "BN"
    val dateFmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    var showNewFolderRow by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

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
                            .background(Color(0xFF0EA5E9).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF0284C7)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBn) "মোবাইল ফাইল ম্যানেজার" else "Mobile File Manager",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasCustomFolderAccess) {
                                if (isBn) "🟢 ওয়েবসাইটের সাথে লাইভ সিঙ্ক চালু" else "🟢 Synced live with paired Website"
                            } else {
                                if (isBn) "ফোনের ফোল্ডার বা ছবি যুক্ত করলে ওয়েবসাইটে দেখাবে" else "Grant a folder or pick files to browse from Web"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasCustomFolderAccess) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("file_manager_refresh_button")
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
                            .testTag("file_manager_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (isBn) "বন্ধ করুন" else "Close"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // SAF Folder Grant + Photo Picker CTA Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0EA5E9).copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = Color(0xFF0284C7).copy(alpha = 0.3f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (isBn) "📂 ওয়েবসাইট থেকে ফোনের ফাইল দেখতে ফোল্ডার শেয়ার করুন" else "📂 Share Phone Folders or Files with Website",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isBn) {
                            "Downloads, Documents, Pictures বা যেকোনো ফোল্ডার বেছে নিন—ওয়েবসাইট থেকেই সব সাব-ফোল্ডার দেখা, ছবি প্রিভিউ, ডাউনলোড ও আপলোড করা যাবে।"
                        } else {
                            "Choose Downloads, Documents, Pictures, or any folder to browse, preview, download, and upload files directly from the website."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onGrantFolder,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("file_manager_grant_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "ফোল্ডার বেছে নিন" else "Choose Folder",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        OutlinedButton(
                            onClick = onPickPhotosOrFiles,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("file_manager_pick_files_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBn) "ফাইল পিন করুন" else "Pin Files",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Roots Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                roots.forEach { root ->
                    val selected = root.id == currentRootId
                    FilterChip(
                        selected = selected,
                        onClick = { onOpenDirectory(root.id, "") },
                        label = {
                            Text(
                                text = root.name,
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingIcon = if (root.rootType == "SAF_FOLDER") {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = if (isBn) "ফোল্ডার সরান" else "Remove folder",
                                    modifier = Modifier
                                        .size(15.dp)
                                        .clickable { onRemoveRoot(root.id) }
                                )
                            }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Breadcrumbs + New Folder Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    breadcrumbs.forEachIndexed { idx, crumb ->
                        if (idx > 0) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Surface(
                            onClick = { onOpenDirectory(currentRootId, crumb.path) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (idx == breadcrumbs.lastIndex) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent
                        ) {
                            Text(
                                text = crumb.name,
                                fontSize = 11.5.sp,
                                fontWeight = if (idx == breadcrumbs.lastIndex) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showNewFolderRow = !showNewFolderRow },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("file_manager_new_folder_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = if (isBn) "নতুন ফোল্ডার" else "New Folder",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (showNewFolderRow) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text(if (isBn) "নতুন ফোল্ডারের নাম" else "New folder name", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val clean = newFolderName.trim()
                            if (clean.isNotBlank()) {
                                onCreateFolder(clean)
                                newFolderName = ""
                                showNewFolderRow = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (isBn) "তৈরি" else "Create", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp))
                }
            } else if (entries.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isBn) "এই ফোল্ডারে এখনো কোনো ফাইল নেই। উপরে 'ফোল্ডার বেছে নিন' দিয়ে আপনার ফোনের ফোল্ডার যুক্ত করুন।"
                        else "This folder is empty. Tap 'Choose Folder' above to share phone folders with the website.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.forEach { entry ->
                        Card(
                            onClick = {
                                if (entry.isDir) {
                                    onOpenDirectory(currentRootId, entry.path)
                                } else {
                                    val shareUri = DeviceFileManagerHelper.getShareableFileUri(context, currentRootId, entry)
                                    if (shareUri != null) {
                                        try {
                                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(shareUri, entry.mimeType)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(Intent.createChooser(viewIntent, entry.name))
                                        } catch (_: Throwable) {
                                            Toast.makeText(
                                                context,
                                                if (isBn) "ফাইলটি ওপেন করার অ্যাপ পাওয়া যায়নি" else "No app available to open this file",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val (iconVec, iconTint) = when (entry.category) {
                                        "FOLDER" -> Icons.Default.Folder to Color(0xFFF59E0B)
                                        "IMAGE" -> Icons.Default.Image to Color(0xFF10B981)
                                        "VIDEO" -> Icons.Default.VideoFile to Color(0xFFEC4899)
                                        "AUDIO" -> Icons.Default.AudioFile to Color(0xFF8B5CF6)
                                        "ARCHIVE" -> Icons.Default.FolderZip to Color(0xFF6366F1)
                                        "DOCUMENT" -> Icons.Default.Description to Color(0xFF0284C7)
                                        else -> Icons.Default.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(iconTint.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = iconVec,
                                            contentDescription = null,
                                            tint = iconTint,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val metaLine = buildString {
                                            if (entry.isDir) {
                                                append(if (isBn) "ফোল্ডার" else "Folder")
                                            } else {
                                                append(DeviceFileManagerHelper.formatFileSize(entry.sizeBytes))
                                            }
                                            if (entry.modifiedMs > 0L) {
                                                append(" · ")
                                                append(dateFmt.format(Date(entry.modifiedMs)))
                                            }
                                        }
                                        Text(
                                            text = metaLine,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!entry.isDir) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp)
                                                .size(16.dp)
                                        )
                                    }
                                    if (entry.isDeletable) {
                                        IconButton(
                                            onClick = { onDeleteEntry(currentRootId, entry.id) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = if (isBn) "মুছুন" else "Delete",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(19.dp)
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
