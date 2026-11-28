package com.example.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R

data class DialerContactItem(
    val name: String,
    val number: String
)

data class SimAccountInfo(
    val slotIndex: Int,
    val label: String,
    val accountHandle: PhoneAccountHandle?
)

data class CallDialOutcome(
    val success: Boolean,
    val mode: String, // DIRECT_CALL, DIALER_OPENED, PERMISSION_NEEDED, FAILED
    val message: String
)

/**
 * Handles outgoing phone calls via TelecomManager.placeCall / ACTION_CALL (when CALL_PHONE
 * permission is granted, including from 24/7 background service) with clean zero-permission
 * ACTION_DIAL fallback and device contact/SIM lookup.
 */
object CallDialerHelper {
    private const val TAG = "CallDialerHelper"
    private const val DIAL_CHANNEL_ID = "pulse_dial_requests"
    private const val DIAL_NOTIFICATION_ID = 9455

    fun sanitizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder()
        for (ch in trimmed) {
            if (ch.isDigit() || ch == '+' || ch == '*' || ch == '#' || ch == ',' || ch == ';') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun hasCallPhonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun loadSimAccounts(context: Context): List<SimAccountInfo> {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return emptyList()

        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return emptyList()
            val handles = tm.callCapablePhoneAccounts ?: return emptyList()
            handles.mapIndexed { idx, handle ->
                val account = try {
                    tm.getPhoneAccount(handle)
                } catch (_: Throwable) {
                    null
                }
                val rawLabel = account?.label?.toString()?.trim()?.takeIf { it.isNotBlank() }
                val label = if (rawLabel != null) "SIM ${idx + 1} ($rawLabel)" else "SIM ${idx + 1}"
                SimAccountInfo(
                    slotIndex = idx,
                    label = label,
                    accountHandle = handle
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadSimAccounts failed: ${t.message}")
            emptyList()
        }
    }

    fun loadDeviceContactsForDialer(context: Context, limit: Int = 120): List<DialerContactItem> {
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return emptyList()

        val items = mutableListOf<DialerContactItem>()
        val seenNumbers = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && items.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val clean = sanitizePhoneNumber(rawNum)
                    if (clean.length >= 2) {
                        val normKey = clean.takeLast(10)
                        if (seenNumbers.add(normKey)) {
                            items.add(
                                DialerContactItem(
                                    name = name.ifBlank { clean },
                                    number = clean
                                )
                            )
                        }
                    }
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "READ_CONTACTS denied: ${se.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "loadDeviceContacts failed: ${t.message}")
        }
        return items
    }

    @SuppressLint("MissingPermission")
    fun placeCall(
        context: Context,
        rawNumber: String,
        simSlotIndex: Int = -1,
        fromBackground: Boolean = false,
        openDialerOnly: Boolean = false
    ): CallDialOutcome {
        val cleaned = sanitizePhoneNumber(rawNumber)
        if (cleaned.isBlank()) {
            return CallDialOutcome(
                success = false,
                mode = "FAILED",
                message = "সঠিক ফোন নাম্বার লিখুন।"
            )
        }

        // Uri.fromParts preserves # and * in USSD / carrier codes (e.g. *121#)
        val telUri = Uri.fromParts("tel", cleaned, null)

        if (!openDialerOnly && hasCallPhonePermission(context)) {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (tm != null) {
                try {
                    val extras = Bundle()
                    if (simSlotIndex >= 0) {
                        val accounts = loadSimAccounts(context)
                        accounts.getOrNull(simSlotIndex)?.accountHandle?.let { handle ->
                            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        }
                    }
                    tm.placeCall(telUri, extras)
                    return CallDialOutcome(
                        success = true,
                        mode = "DIRECT_CALL",
                        message = "$cleaned নম্বরে কল করা হচ্ছে..."
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "TelecomManager.placeCall failed (${t.message}), trying ACTION_CALL")
                }
            }

            if (!fromBackground) {
                try {
                    val callIntent = Intent(Intent.ACTION_CALL, telUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    return CallDialOutcome(
                        success = true,
                        mode = "DIRECT_CALL",
                        message = "$cleaned নম্বরে কল করা হচ্ছে..."
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "ACTION_CALL failed: ${t.message}")
                }
            }
        }

        // Fallback when CALL_PHONE isn't granted or user asked for system dialer
        if (!fromBackground) {
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL, telUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                CallDialOutcome(
                    success = true,
                    mode = "DIALER_OPENED",
                    message = "ফোন ডায়ালারে $cleaned প্রস্তুত করা হয়েছে"
                )
            } catch (t: Throwable) {
                CallDialOutcome(
                    success = false,
                    mode = "FAILED",
                    message = "ফোন ডায়ালার পাওয়া যায়নি: ${t.localizedMessage ?: "error"}"
                )
            }
        }

        // Background + no CALL_PHONE permission: post an actionable tap-to-dial notification
        showTapToDialNotification(context, cleaned, telUri)
        return CallDialOutcome(
            success = false,
            mode = "PERMISSION_NEEDED",
            message = "ব্যাকগ্রাউন্ড থেকে ডাইরেক্ট কল করতে ফোনে CALL_PHONE পারমিশন দিন বা নোটিফিকেশনে ট্যাপ করুন।"
        )
    }

    private fun showTapToDialNotification(context: Context, number: String, telUri: Uri) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(DIAL_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    DIAL_CHANNEL_ID,
                    "Web Remote Call Dialer",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Tap-to-dial alert when website requests an outgoing call"
                    enableVibration(true)
                }
                nm.createNotificationChannel(ch)
            }

            val dialIntent = Intent(Intent.ACTION_DIAL, telUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val dialPi = PendingIntent.getActivity(
                context,
                number.hashCode(),
                dialIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, DIAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("📞 ওয়েবসাইট থেকে কল ডায়াল রিকোয়েস্ট")
                .setContentText("$number নম্বরে কল করতে এখানে ট্যাপ করুন")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(dialPi)
                .addAction(0, "কল করুন", dialPi)
                .build()

            nm.notify(DIAL_NOTIFICATION_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "showTapToDialNotification failed: ${t.message}")
        }
    }
}
