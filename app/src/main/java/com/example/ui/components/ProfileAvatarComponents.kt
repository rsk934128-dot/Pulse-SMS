package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.repository.ProfileAvatarManager
import com.example.data.repository.ProfileAvatarSelection
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reusable circular user avatar composable supporting custom camera/gallery photos,
 * pre-generated illustrated avatars, and Google-ring initials fallback.
 */
@Composable
fun ProfileAvatarImage(
    selection: ProfileAvatarSelection,
    currentUser: FirebaseUser? = null,
    size: Dp = 46.dp,
    initialFontSize: TextUnit = 18.sp,
    showGradientRing: Boolean = true,
    modifier: Modifier = Modifier
) {
    val ringPadding = if (showGradientRing) 2.5.dp else 0.dp
    val ringModifier = if (showGradientRing) {
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFF4285F4),
                        Color(0xFF34A853),
                        Color(0xFFFBBC05),
                        Color(0xFFEA4335),
                        Color(0xFF4285F4)
                    )
                )
            )
            .padding(ringPadding)
    } else {
        Modifier.size(size)
    }

    Box(
        modifier = modifier.then(ringModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF1E3A8A)),
            contentAlignment = Alignment.Center
        ) {
            when (selection) {
                is ProfileAvatarSelection.CustomPhoto -> {
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = selection.filePath) {
                        value = withContext(Dispatchers.IO) {
                            val file = File(selection.filePath)
                            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Custom profile avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        InitialAvatarContent(currentUser = currentUser, fontSize = initialFontSize)
                    }
                }
                is ProfileAvatarSelection.PresetAvatar -> {
                    val preset = ProfileAvatarManager.findPreset(selection.presetId)
                    if (preset != null) {
                        Image(
                            painter = painterResource(id = preset.drawableRes),
                            contentDescription = preset.labelEn,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        InitialAvatarContent(currentUser = currentUser, fontSize = initialFontSize)
                    }
                }
                is ProfileAvatarSelection.DefaultInitials -> {
                    InitialAvatarContent(currentUser = currentUser, fontSize = initialFontSize)
                }
            }
        }
    }
}

@Composable
private fun InitialAvatarContent(
    currentUser: FirebaseUser?,
    fontSize: TextUnit
) {
    if (currentUser != null) {
        val initial = currentUser.displayName?.firstOrNull()?.uppercaseChar()?.toString()
            ?: currentUser.email?.firstOrNull()?.uppercaseChar()?.toString()
            ?: "U"
        Text(
            text = initial,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    } else {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = "Default profile icon",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxSize(0.72f)
        )
    }
}

/**
 * Interactive dialog enabling the user to capture a custom avatar with the device camera,
 * pick a photo via the zero-permission Android Photo Picker, or choose from pre-generated avatars.
 */
@Composable
fun ProfileAvatarPickerDialog(
    currentSelection: ProfileAvatarSelection,
    currentUser: FirebaseUser? = null,
    language: String = "BN",
    onSelectPreset: (String) -> Unit,
    onSaveCustomPhotoUri: (Uri, (Boolean, String?) -> Unit) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var isSavingPhoto by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            isSavingPhoto = true
            statusMessage = null
            onSaveCustomPhotoUri(uri) { ok, err ->
                isSavingPhoto = false
                if (ok) {
                    isErrorStatus = false
                    statusMessage = if (language == "BN") "ক্যামেরা প্রোফাইল ছবি সেভ হয়েছে!" else "Camera profile photo saved!"
                } else {
                    isErrorStatus = true
                    statusMessage = err ?: if (language == "BN") "ছবি সেভ করা যায়নি।" else "Could not save photo."
                }
            }
        }
    }

    val launchDeviceCamera: () -> Unit = {
        try {
            val uri = ProfileAvatarManager.createTempCameraUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } catch (t: Throwable) {
            isErrorStatus = true
            statusMessage = t.localizedMessage
                ?: if (language == "BN") "ডিভাইস ক্যামেরা চালু করা যায়নি।" else "Could not open device camera."
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchDeviceCamera()
        } else {
            isErrorStatus = true
            statusMessage = if (language == "BN") {
                "ক্যামেরা দিয়ে ছবি তুলতে ক্যামেরা পারমিশন প্রয়োজন। অথবা নিচের গ্যালারি বা প্রি-জেনারেটেড অবতার ব্যবহার করুন।"
            } else {
                "Camera permission is required to take a photo. You can also use Gallery or a pre-generated avatar below."
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isSavingPhoto = true
            statusMessage = null
            onSaveCustomPhotoUri(uri) { ok, err ->
                isSavingPhoto = false
                if (ok) {
                    isErrorStatus = false
                    statusMessage = if (language == "BN") "প্রোফাইল ছবি আপডেট হয়েছে!" else "Profile picture updated!"
                } else {
                    isErrorStatus = true
                    statusMessage = err ?: if (language == "BN") "ছবি লোড করা যায়নি।" else "Could not load photo."
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("avatar_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (language == "BN") "প্রোফাইল অবতার নির্বাচন" else "Choose Profile Avatar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (language == "BN") "ক্যামেরা ছবি তুলুন অথবা প্রস্তুত অবতার বাছুন"
                            else "Capture with camera or pick a pre-generated avatar",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_avatar_picker_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (language == "BN") "বন্ধ করুন" else "Close"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Active Preview
                Box(contentAlignment = Alignment.BottomEnd) {
                    ProfileAvatarImage(
                        selection = currentSelection,
                        currentUser = currentUser,
                        size = 96.dp,
                        initialFontSize = 34.sp,
                        showGradientRing = true
                    )

                    if (isSavingPhoto) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val currentLabel = when (currentSelection) {
                    is ProfileAvatarSelection.CustomPhoto ->
                        if (language == "BN") "কাস্টম ক্যামেরা / গ্যালারি ছবি" else "Custom Profile Photo"
                    is ProfileAvatarSelection.PresetAvatar -> {
                        val preset = ProfileAvatarManager.findPreset(currentSelection.presetId)
                        if (language == "BN") "অবতার: ${preset?.labelBn ?: currentSelection.presetId}"
                        else "Avatar: ${preset?.labelEn ?: currentSelection.presetId}"
                    }
                    is ProfileAvatarSelection.DefaultInitials ->
                        if (language == "BN") "ডিফল্ট প্রোফাইল ব্যাজ" else "Default Profile Badge"
                }

                Text(
                    text = currentLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (currentSelection !is ProfileAvatarSelection.DefaultInitials) {
                    TextButton(
                        onClick = {
                            onResetToDefault()
                            isErrorStatus = false
                            statusMessage = if (language == "BN") "ডিফল্ট অবতারে ফিরে যাওয়া হয়েছে" else "Reset to default badge"
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("reset_avatar_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (language == "BN") "ডিফল্টে রিসেট করুন" else "Reset to Default",
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Inline Status / Error Feedback
                if (!statusMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isErrorStatus) MaterialTheme.colorScheme.errorContainer else Color(0xFFDCFCE7),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("avatar_picker_status_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isErrorStatus) Icons.Default.Warning else Icons.Default.Check,
                                contentDescription = null,
                                tint = if (isErrorStatus) MaterialTheme.colorScheme.error else Color(0xFF15803D),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusMessage!!,
                                fontSize = 12.sp,
                                color = if (isErrorStatus) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF166534)
                            )
                        }
                    }
                }

                // Primary Camera & Photo Picker Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                launchDeviceCamera()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        enabled = !isSavingPhoto,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("avatar_take_photo_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (language == "BN") "ক্যামেরা দিয়ে তুলুন" else "Take Photo",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isSavingPhoto,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("avatar_pick_photo_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (language == "BN") "গ্যালারি ছবি" else "Gallery",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Pre-generated Avatars Showcase
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (language == "BN") "প্রস্তুত অবতার (Pre-generated)" else "Pre-Generated Avatars",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${ProfileAvatarManager.PRESET_AVATARS.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ProfileAvatarManager.PRESET_AVATARS, key = { it.id }) { preset ->
                        val isSelected = currentSelection is ProfileAvatarSelection.PresetAvatar &&
                            currentSelection.presetId == preset.id
                        val borderColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            label = "avatar_border"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    onSelectPreset(preset.id)
                                    isErrorStatus = false
                                    statusMessage = if (language == "BN") {
                                        "${preset.labelBn} অবতার সিলেক্ট হয়েছে"
                                    } else {
                                        "${preset.labelEn} avatar selected"
                                    }
                                }
                                .padding(6.dp)
                                .testTag("preset_avatar_${preset.id}")
                        ) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                Image(
                                    painter = painterResource(id = preset.drawableRes),
                                    contentDescription = preset.labelEn,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(66.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.5.dp,
                                            color = borderColor,
                                            shape = CircleShape
                                        )
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = if (language == "BN") preset.labelBn else preset.labelEn,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("avatar_picker_done_btn")
                ) {
                    Text(
                        text = if (language == "BN") "সম্পন্ন হয়েছে" else "Done",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
