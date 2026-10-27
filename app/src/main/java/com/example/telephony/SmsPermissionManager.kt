package com.example.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Information model describing an SMS/Telephony permission, its rationale, and current grant state.
 */
data class SmsPermissionItem(
    val permission: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isCritical: Boolean = true
)

/**
 * SmsPermissionManager handles checking, querying, and managing runtime permissions
 * for reading, receiving, and sending SMS messages in Pulse SMS.
 */
object SmsPermissionManager {

    val CRITICAL_SMS_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    /**
     * Builds the full array of runtime permissions required for telephony & messaging.
     */
    fun getAllRequiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.RECEIVE_MMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /**
     * Checks if a specific permission has been granted by the user.
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the essential SMS permissions (READ_SMS and RECEIVE_SMS) are granted.
     */
    fun hasEssentialSmsPermissions(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_SMS) &&
                hasPermission(context, Manifest.permission.RECEIVE_SMS)
    }

    /**
     * Checks if all required SMS permissions are granted.
     */
    fun hasAllSmsPermissions(context: Context): Boolean {
        return getAllRequiredPermissions().all { hasPermission(context, it) }
    }

    /**
     * Returns detailed descriptions of all SMS permissions with localized rationales.
     */
    fun getPermissionItems(context: Context, language: String): List<SmsPermissionItem> {
        val isBn = language == "BN"
        return listOf(
            SmsPermissionItem(
                permission = Manifest.permission.READ_SMS,
                title = if (isBn) "এসএমএস পড়ার অনুমতি (Read SMS)" else "Read SMS Messages",
                description = if (isBn) {
                    "আপনার ইনবক্স মেসেজগুলো প্রদর্শন ও ওটিপি স্বয়ংক্রিয়ভাবে শনাক্ত করতে প্রয়োজন।"
                } else {
                    "Required to read your inbox messages, threads, and detect OTP codes."
                },
                isGranted = hasPermission(context, Manifest.permission.READ_SMS),
                isCritical = true
            ),
            SmsPermissionItem(
                permission = Manifest.permission.RECEIVE_SMS,
                title = if (isBn) "এসএমএস পাওয়ার অনুমতি (Receive SMS)" else "Receive Live SMS",
                description = if (isBn) {
                    "নতুন ইনকামিং মেসেজ রিয়েল-টাইমে গ্রহণ ও প্রসেস করার জন্য প্রয়োজন।"
                } else {
                    "Required to receive and process incoming carrier messages in real-time."
                },
                isGranted = hasPermission(context, Manifest.permission.RECEIVE_SMS),
                isCritical = true
            ),
            SmsPermissionItem(
                permission = Manifest.permission.SEND_SMS,
                title = if (isBn) "এসএমএস পাঠানোর অনুমতি (Send SMS)" else "Send SMS Messages",
                description = if (isBn) {
                    "সিম কার্ডের মাধ্যমে অ্যাপ থেকে সরাসরি রিপ্লাই বা মেসেজ পাঠাতে প্রয়োজন।"
                } else {
                    "Required to send outbound SMS replies directly from your SIM card."
                },
                isGranted = hasPermission(context, Manifest.permission.SEND_SMS),
                isCritical = true
            ),
            SmsPermissionItem(
                permission = Manifest.permission.READ_CONTACTS,
                title = if (isBn) "কন্টাক্টস পড়ার অনুমতি (Read Contacts)" else "Read Contacts",
                description = if (isBn) {
                    "নম্বরের বদলে আপনার পরিচিত মানুষদের নাম ও প্রোফাইল দেখাতে প্রয়োজন।"
                } else {
                    "Required to display contact names instead of raw phone numbers."
                },
                isGranted = hasPermission(context, Manifest.permission.READ_CONTACTS),
                isCritical = false
            ),
            SmsPermissionItem(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = if (isBn) "নোটিফিকেশন পাঠানো (Notifications)" else "Post Notifications",
                description = if (isBn) {
                    "নতুন এসএমএস আসলে স্ক্রিনে নোটিফিকেশন প্রদর্শন করার জন্য।"
                } else {
                    "Required to notify you instantly when a new message arrives."
                },
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                } else true,
                isCritical = false
            )
        )
    }

    /**
     * Directs the user to App Settings if permissions are permanently denied.
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
