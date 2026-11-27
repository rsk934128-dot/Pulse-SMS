package com.example.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

data class DeviceCallLogItem(
    val id: String,
    val number: String,
    val contactName: String?,
    val callType: String,
    val durationSec: Long,
    val simLabel: String?,
    val timestampMs: Long
)

/**
 * Reads real device call history from Android CallLog ContentProvider
 * (when READ_CALL_LOG is granted) and exposes a ContentObserver for real-time updates.
 */
object CallLogSyncHelper {
    private const val TAG = "CallLogSyncHelper"

    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun readRecentCallLogs(context: Context, limit: Int = 80): List<DeviceCallLogItem> {
        if (!hasCallLogPermission(context)) return emptyList()

        val results = mutableListOf<DeviceCallLogItem>()
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val contactCache = mutableMapOf<String, String?>()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val simIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                while (cursor.moveToNext() && results.size < limit) {
                    val rawNumber = if (numberIdx >= 0) cursor.getString(numberIdx)?.trim().orEmpty() else ""
                    val safeNumber = rawNumber.ifBlank { "Private / Unknown" }.take(100)
                    var cachedName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotBlank() } else null
                    val rawType = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
                    val timestampMs = if (dateIdx >= 0) cursor.getLong(dateIdx).coerceAtLeast(1L) else System.currentTimeMillis()
                    val durationSec = if (durationIdx >= 0) cursor.getLong(durationIdx).coerceIn(0L, 864000L) else 0L
                    val rawSim = if (simIdx >= 0) cursor.getString(simIdx)?.trim()?.takeIf { it.isNotBlank() } else null

                    if (cachedName == null && hasContacts && safeNumber != "Private / Unknown") {
                        cachedName = contactCache.getOrPut(safeNumber) {
                            lookupContactName(context, safeNumber)
                        }
                    }

                    val callType = when (rawType) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
                        else -> "UNKNOWN"
                    }

                    val simLabel = rawSim?.let { accountId ->
                        when {
                            accountId.contains("1") || accountId.endsWith("0") -> "SIM 1"
                            accountId.contains("2") -> "SIM 2"
                            else -> accountId.take(24)
                        }
                    }

                    val sig = "$safeNumber|$callType|$durationSec"
                    val hashHex = (sig.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
                    val docId = "call_${timestampMs}_$hashHex".take(120)

                    results.add(
                        DeviceCallLogItem(
                            id = docId,
                            number = safeNumber,
                            contactName = cachedName?.take(120),
                            callType = callType,
                            durationSec = durationSec,
                            simLabel = simLabel?.take(40),
                            timestampMs = timestampMs
                        )
                    )
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "READ_CALL_LOG permission denied: ${se.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Error querying CallLog: ${t.message}")
        }

        return results
    }

    private fun lookupContactName(context: Context, phoneNumber: String): String? {
        return try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun registerCallLogObserver(context: Context, onChanged: () -> Unit): ContentObserver? {
        if (!hasCallLogPermission(context)) return null
        return try {
            val handler = Handler(Looper.getMainLooper())
            var lastDispatchedMs = 0L
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    val now = System.currentTimeMillis()
                    if (now - lastDispatchedMs >= 1500L) {
                        lastDispatchedMs = now
                        onChanged()
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer
            )
            observer
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register CallLog ContentObserver: ${t.message}")
            null
        }
    }

    fun unregisterCallLogObserver(context: Context, observer: ContentObserver?) {
        if (observer == null) return
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) {}
    }
}
