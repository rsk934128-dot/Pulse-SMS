package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

sealed class ProfileAvatarSelection {
    data object DefaultInitials : ProfileAvatarSelection()
    data class PresetAvatar(val presetId: String) : ProfileAvatarSelection()
    data class CustomPhoto(val filePath: String) : ProfileAvatarSelection()
}

data class PresetAvatarOption(
    val id: String,
    @DrawableRes val drawableRes: Int,
    val labelEn: String,
    val labelBn: String
)

object ProfileAvatarManager {
    private const val TAG = "ProfileAvatarManager"
    private const val PREFS_NAME = "pulse_profile_prefs"
    private const val KEY_AVATAR_TYPE = "avatar_type"
    private const val KEY_AVATAR_VALUE = "avatar_value"

    private const val TYPE_DEFAULT = "default"
    private const val TYPE_PRESET = "preset"
    private const val TYPE_CUSTOM = "custom"

    val PRESET_AVATARS: List<PresetAvatarOption> = listOf(
        PresetAvatarOption(
            id = "nebula",
            drawableRes = R.drawable.img_avatar_nebula,
            labelEn = "Explorer",
            labelBn = "এক্সপ্লোরার"
        ),
        PresetAvatarOption(
            id = "bot",
            drawableRes = R.drawable.img_avatar_bot,
            labelEn = "Pulse Bot",
            labelBn = "পালস বট"
        ),
        PresetAvatarOption(
            id = "bengal",
            drawableRes = R.drawable.img_avatar_bengal,
            labelEn = "Bengal",
            labelBn = "বেঙ্গল"
        ),
        PresetAvatarOption(
            id = "wave",
            drawableRes = R.drawable.img_avatar_wave,
            labelEn = "Sonic Wave",
            labelBn = "সনিক ওয়েভ"
        ),
        PresetAvatarOption(
            id = "owl",
            drawableRes = R.drawable.img_avatar_owl,
            labelEn = "Night Owl",
            labelBn = "নাইট আউল"
        )
    )

    fun findPreset(presetId: String): PresetAvatarOption? =
        PRESET_AVATARS.firstOrNull { it.id == presetId }

    fun loadSelection(context: Context): ProfileAvatarSelection {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val type = prefs.getString(KEY_AVATAR_TYPE, TYPE_DEFAULT) ?: TYPE_DEFAULT
        val value = prefs.getString(KEY_AVATAR_VALUE, null)
        return when (type) {
            TYPE_PRESET -> {
                if (!value.isNullOrBlank() && findPreset(value) != null) {
                    ProfileAvatarSelection.PresetAvatar(value)
                } else {
                    ProfileAvatarSelection.DefaultInitials
                }
            }
            TYPE_CUSTOM -> {
                if (!value.isNullOrBlank() && File(value).exists()) {
                    ProfileAvatarSelection.CustomPhoto(value)
                } else {
                    ProfileAvatarSelection.DefaultInitials
                }
            }
            else -> ProfileAvatarSelection.DefaultInitials
        }
    }

    fun selectPreset(context: Context, presetId: String): ProfileAvatarSelection {
        if (findPreset(presetId) == null) return loadSelection(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AVATAR_TYPE, TYPE_PRESET)
            .putString(KEY_AVATAR_VALUE, presetId)
            .apply()
        return ProfileAvatarSelection.PresetAvatar(presetId)
    }

    fun resetToDefault(context: Context): ProfileAvatarSelection {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AVATAR_TYPE, TYPE_DEFAULT)
            .remove(KEY_AVATAR_VALUE)
            .apply()
        return ProfileAvatarSelection.DefaultInitials
    }

    fun createTempCameraUri(context: Context): Uri {
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val tempFile = File(cameraDir, "avatar_capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    suspend fun saveAvatarFromUri(context: Context, sourceUri: Uri): Result<ProfileAvatarSelection> =
        withContext(Dispatchers.IO) {
            try {
                val decoded = decodeBitmapFromUri(context, sourceUri)
                    ?: return@withContext Result.failure(IllegalStateException("Unable to decode selected image."))

                val cropped = cropAndScaleSquare(decoded, targetSize = 512)
                if (cropped !== decoded && !decoded.isRecycled) {
                    decoded.recycle()
                }

                val avatarsDir = File(context.filesDir, "avatars").apply { mkdirs() }
                // Clean up older avatar captures to avoid accumulating stale files
                avatarsDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("profile_avatar_")) {
                        file.delete()
                    }
                }

                val outFile = File(avatarsDir, "profile_avatar_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { stream ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                }
                if (!cropped.isRecycled) {
                    cropped.recycle()
                }

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_AVATAR_TYPE, TYPE_CUSTOM)
                    .putString(KEY_AVATAR_VALUE, outFile.absolutePath)
                    .apply()

                Result.success(ProfileAvatarSelection.CustomPhoto(outFile.absolutePath))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed saving avatar from URI", t)
                Result.failure(t)
            }
        }

    private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > 1024) {
                    val scale = 1024f / maxDim.toFloat()
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            var inSampleSize = 1
            while (boundsOptions.outWidth / inSampleSize > 1024 || boundsOptions.outHeight / inSampleSize > 1024) {
                inSampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        }
    }

    private fun cropAndScaleSquare(bitmap: Bitmap, targetSize: Int): Bitmap {
        val side = min(bitmap.width, bitmap.height).coerceAtLeast(1)
        val x = ((bitmap.width - side) / 2).coerceAtLeast(0)
        val y = ((bitmap.height - side) / 2).coerceAtLeast(0)
        val square = if (bitmap.width == side && bitmap.height == side) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, x, y, side, side)
        }
        return if (square.width == targetSize && square.height == targetSize) {
            square
        } else {
            Bitmap.createScaledBitmap(square, targetSize, targetSize, true).also {
                if (square !== bitmap && square !== it && !square.isRecycled) {
                    square.recycle()
                }
            }
        }
    }
}
