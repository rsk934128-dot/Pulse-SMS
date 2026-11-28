package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.remote.FirebaseSyncRepository
import com.example.telephony.CallLogSyncHelper
import com.example.telephony.CallRecorderManager
import com.example.telephony.DeviceCallLogItem
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PairingResult(
    val success: Boolean,
    val sessionId: String,
    val deviceName: String,
    val message: String,
    val syncedMessagesCount: Int = 0
)

data class MirroredNotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val appName: String,
    val packageName: String,
    val sourceDevice: String,
    val category: String,
    val isTest: Boolean,
    val createdAtMs: Long
)

data class RemoteCameraCommand(
    val command: String,
    val commandSeq: Long,
    val status: String,
    val updatedAtMs: Long
)

data class RemoteMicCommand(
    val command: String,
    val commandSeq: Long,
    val status: String,
    val updatedAtMs: Long
)

data class RemoteCallRecorderCommand(
    val command: String,
    val commandSeq: Long,
    val targetNumber: String,
    val status: String,
    val updatedAtMs: Long
)

data class CallRecordingUploadItem(
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val callType: String,
    val durationSec: Long,
    val audioBase64: String,
    val mimeType: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationLabel: String?,
    val timestampMs: Long
)

data class RemoteDialRequest(
    val id: String,
    val phoneNumber: String,
    val simSlot: Int,
    val status: String,
    val createdAtMs: Long
)

data class PairedSessionRecord(
    val sessionId: String,
    val deviceName: String,
    val connectedAtMs: Long,
    val isPrimary: Boolean
)

data class ConnectedPeerDevice(
    val deviceId: String,
    val deviceName: String,
    val sessionId: String,
    val platform: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val isOnline: Boolean,
    val appVersion: String,
    val updatedAtMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationLabel: String? = null,
    val locationEnabled: Boolean = false,
    val locationAccuracyM: Double? = null
)

data class RemoteFileManagerCommand(
    val command: String,
    val commandSeq: Long,
    val targetRootId: String?,
    val targetPath: String?,
    val targetFileId: String?,
    val targetName: String?,
    val status: String,
    val updatedAtMs: Long
)

data class RemoteFileTransferCommand(
    val command: String,
    val commandSeq: Long,
    val direction: String,
    val targetRootId: String?,
    val targetFileId: String?,
    val targetPath: String?,
    val fileName: String,
    val mimeType: String,
    val base64Data: String?,
    val status: String,
    val updatedAtMs: Long
)

object QrPairingManager {
    private const val TAG = "QrPairingManager"
    const val HUB_SESSION_ID = "pulse_pair_instant_one_shot"
    private const val PREFS_NAME = "pulse_pairing_prefs"
    private const val KEY_ACTIVE_SESSION_ID = "active_pairing_session_id"
    private const val KEY_ACTIVE_DEVICE_NAME = "active_device_name"
    private const val KEY_DEVICE_ID = "persistent_device_id"
    const val KEY_PAIRED_SESSIONS_JSON = "paired_sessions_json"
    private const val KEY_SEEN_NOTIF_IDS = "seen_web_notification_ids"
    private const val DEFAULT_PROJECT_ID = "gen-lang-client-0040983236"
    private const val DEFAULT_DATABASE_ID = "ai-studio-android-pulsesms-d91f8c0e-d0eb-454c-a3c4-23804c092a8d"

    private val ALLOWED_CATEGORIES = setOf("PERSONAL", "OTP", "TRANSACTION", "PROMOTION", "SPAM", "TRAVEL")
    private val ALLOWED_NOTIF_CATEGORIES = setOf("SMS", "APP", "OTP", "WEB_ALERT", "TEST")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun getOrCreateDeviceId(context: Context): String {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            if (existing != null) return existing
            val created = "dev_" + UUID.randomUUID().toString().replace("-", "").take(10)
            prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            created
        } catch (_: Throwable) {
            "dev_${Build.ID.replace(Regex("[^a-zA-Z0-9_-]"), "").take(10).ifBlank { "android" }}"
        }
    }

    fun getDeviceDisplayName(): String {
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim().take(100)
    }

    fun readBatteryStatus(context: Context): Pair<Int, Boolean> {
        return try {
            val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(0, 100) else -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        } catch (_: Throwable) {
            -1 to false
        }
    }

    fun getActiveSessionId(context: Context): String? {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_ACTIVE_SESSION_ID, null)?.takeIf { it.isNotBlank() }
                ?: getPairedSessions(context).firstOrNull()?.sessionId
        } catch (_: Throwable) {
            null
        }
    }

    fun getPairedSessions(context: Context): List<PairedSessionRecord> {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val primaryId = prefs.getString(KEY_ACTIVE_SESSION_ID, null)?.takeIf { it.isNotBlank() }
            val raw = prefs.getString(KEY_PAIRED_SESSIONS_JSON, null)
            val list = mutableListOf<PairedSessionRecord>()
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sid = obj.optString("sessionId").trim()
                    if (sid.isBlank()) continue
                    val dName = obj.optString("deviceName").ifBlank { getDeviceDisplayName() }
                    val ts = obj.optLong("connectedAtMs", System.currentTimeMillis())
                    list.add(
                        PairedSessionRecord(
                            sessionId = sid,
                            deviceName = dName,
                            connectedAtMs = ts,
                            isPrimary = (sid == primaryId) || (primaryId == null && i == 0)
                        )
                    )
                }
            }
            if (primaryId != null && list.none { it.sessionId == primaryId }) {
                val devName = prefs.getString(KEY_ACTIVE_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: getDeviceDisplayName()
                list.add(0, PairedSessionRecord(primaryId, devName, System.currentTimeMillis(), true))
            }
            list.distinctBy { it.sessionId }.take(12)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun persistPairedSessions(context: Context, sessions: List<PairedSessionRecord>) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            sessions.distinctBy { it.sessionId }.take(12).forEach { rec ->
                arr.put(
                    JSONObject().apply {
                        put("sessionId", rec.sessionId)
                        put("deviceName", rec.deviceName)
                        put("connectedAtMs", rec.connectedAtMs)
                    }
                )
            }
            prefs.edit().putString(KEY_PAIRED_SESSIONS_JSON, arr.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to persist multi-session list", t)
        }
    }

    fun addOrUpdatePairedSession(
        context: Context,
        sessionId: String,
        deviceName: String,
        makePrimary: Boolean = true
    ): List<PairedSessionRecord> {
        val cleanId = sessionId.trim().take(128)
        if (cleanId.isBlank()) return getPairedSessions(context)
        val cleanName = deviceName.ifBlank { getDeviceDisplayName() }.take(100)
        val current = getPairedSessions(context).filterNot { it.sessionId == cleanId }.toMutableList()
        val entry = PairedSessionRecord(
            sessionId = cleanId,
            deviceName = cleanName,
            connectedAtMs = System.currentTimeMillis(),
            isPrimary = makePrimary || current.isEmpty()
        )
        if (makePrimary || current.isEmpty()) {
            current.add(0, entry)
        } else {
            current.add(entry)
        }
        persistPairedSessions(context, current)
        if (makePrimary || getActiveSessionId(context).isNullOrBlank()) {
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_ACTIVE_SESSION_ID, cleanId)
                    .putString(KEY_ACTIVE_DEVICE_NAME, cleanName)
                    .apply()
            } catch (_: Throwable) {}
        }
        return getPairedSessions(context)
    }

    fun setPrimaryActiveSession(context: Context, sessionId: String): List<PairedSessionRecord> {
        val sessions = getPairedSessions(context)
        val target = sessions.firstOrNull { it.sessionId == sessionId } ?: return sessions
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_ACTIVE_SESSION_ID, target.sessionId)
                .putString(KEY_ACTIVE_DEVICE_NAME, target.deviceName)
                .apply()
        } catch (_: Throwable) {}
        return getPairedSessions(context)
    }

    fun removePairedSession(context: Context, sessionId: String): List<PairedSessionRecord> {
        val remaining = getPairedSessions(context).filterNot { it.sessionId == sessionId }
        persistPairedSessions(context, remaining)
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentPrimary = prefs.getString(KEY_ACTIVE_SESSION_ID, null)
            if (currentPrimary == sessionId) {
                val next = remaining.firstOrNull()
                if (next != null) {
                    prefs.edit()
                        .putString(KEY_ACTIVE_SESSION_ID, next.sessionId)
                        .putString(KEY_ACTIVE_DEVICE_NAME, next.deviceName)
                        .apply()
                } else {
                    prefs.edit().remove(KEY_ACTIVE_SESSION_ID).apply()
                }
            }
        } catch (_: Throwable) {}
        return getPairedSessions(context)
    }

    fun getAllTargetSessionIds(context: Context, overrideSessionId: String? = null): List<String> {
        if (!overrideSessionId.isNullOrBlank()) {
            return listOf(overrideSessionId.trim())
        }
        val ordered = mutableListOf<String>()
        getActiveSessionId(context)?.let { ordered.add(it) }
        getPairedSessions(context).forEach { rec ->
            if (rec.sessionId !in ordered) ordered.add(rec.sessionId)
        }
        return ordered.take(8)
    }

    fun saveActiveSession(context: Context, sessionId: String, deviceName: String) {
        try {
            addOrUpdatePairedSession(context, sessionId, deviceName, makePrimary = true)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to persist active pairing session", t)
        }
    }

    fun clearActiveSession(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_ACTIVE_SESSION_ID)
                .remove(KEY_PAIRED_SESSIONS_JSON)
                .apply()
        } catch (_: Throwable) {}
    }

    /**
     * Generates a deterministic, Firestore-rule-compliant document ID for an SMS message
     * so repeated inbox imports never create duplicate messages on the web companion.
     */
    fun buildStableCloudMessageId(message: MessageEntity): String {
        val rawSig = "${message.threadAddress}|${message.timestamp}|${message.isIncoming}|${message.body}"
        val unsignedHex = (rawSig.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val safeTs = message.timestamp.coerceAtLeast(1L)
        return "sms_${safeTs}_$unsignedHex".take(120)
    }

    /**
     * Builds a map strictly matching `isValidCloudMessage` in firestore.rules,
     * preserving the authentic chronological timestamp of each device SMS.
     */
    fun buildCloudMessagePayload(message: MessageEntity, docId: String): Map<String, Any> {
        val safeCategory = message.category.uppercase(Locale.US).let {
            if (it in ALLOWED_CATEGORIES) it else "PERSONAL"
        }
        val safeTimestampMs = message.timestamp.coerceIn(1L, System.currentTimeMillis())
        return mapOf(
            "id" to docId,
            "threadAddress" to message.threadAddress.ifBlank { "Unknown" }.take(100),
            "senderAddress" to message.senderAddress.ifBlank { "Unknown" }.take(100),
            "recipientAddress" to message.recipientAddress.ifBlank { "Self" }.take(100),
            "body" to message.body.take(5000),
            "isIncoming" to message.isIncoming,
            "category" to safeCategory,
            "createdAt" to Timestamp(Date(safeTimestampMs))
        )
    }

    /**
     * Parses the scanned QR payload which can be JSON or raw token string.
     */
    fun extractSessionId(rawQrText: String): String {
        val trimmed = rawQrText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("sessionId")) return json.getString("sessionId")
                if (json.has("token")) return json.getString("token")
                if (json.has("session")) return json.getString("session")
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing JSON QR payload", e)
            }
        }

        val regex = Regex("(?:pulse_pair_|pulse_session_|session_)[a-zA-Z0-9_-]+")
        val match = regex.find(trimmed)
        if (match != null) {
            return match.value
        }

        return trimmed.replace(Regex("[^a-zA-Z0-9_-]"), "").take(64)
    }

    private fun resolveDatabaseId(context: Context): String {
        return try {
            context.getString(R.string.firestore_database_id).takeIf { it.isNotBlank() } ?: DEFAULT_DATABASE_ID
        } catch (_: Exception) {
            DEFAULT_DATABASE_ID
        }
    }

    /**
     * Connects this mobile device to the website's pairing session in Firestore in one shot
     * and immediately pushes the phone's real SMS inbox to that session so the website shows
     * the exact mobile inbox in real time (even without matching Google Sign-In on the web).
     */
    suspend fun pairWithWebsite(
        context: Context,
        rawQrText: String
    ): PairingResult {
        val parsedToken = extractSessionId(rawQrText)
        if (parsedToken.isBlank()) {
            return PairingResult(false, "", "", "অবৈধ QR কোড বা সেশন আইডি।")
        }

        val appContext = context.applicationContext ?: context
        val deviceId = getOrCreateDeviceId(appContext)
        val deviceName = getDeviceDisplayName()
        val isInstantOneShot = parsedToken == HUB_SESSION_ID || parsedToken == "pulse_pair_simulation_instant"
        // Give each phone its own dedicated session when using 1-Click Connect so 2+ phones never overwrite each other
        val sessionId = if (isInstantOneShot) "pulse_pair_${deviceId}" else parsedToken

        val hasFirebase = ensureFirebaseAvailable(appContext)
        val currentUser = if (hasFirebase) {
            try {
                FirebaseAuth.getInstance().currentUser
            } catch (t: Throwable) {
                Log.w(TAG, "FirebaseAuth lookup skipped: ${t.message}")
                null
            }
        } else {
            null
        }

        val dbId = resolveDatabaseId(appContext)
        val userId = currentUser?.uid ?: "mobile_device_${Build.ID.take(6)}"
        val userEmail = currentUser?.email ?: ""

        var anyWriteSucceeded = false

        if (hasFirebase) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                val payload = hashMapOf<String, Any>(
                    "sessionId" to sessionId,
                    "status" to "CONNECTED",
                    "deviceName" to deviceName,
                    "userId" to userId,
                    "userEmail" to userEmail,
                    "connectedAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp()
                )

                try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .set(payload, SetOptions.merge())
                        .await()
                    anyWriteSucceeded = true

                    if (isInstantOneShot) {
                        val hubAnnouncement = payload.toMutableMap().apply {
                            put("sessionId", sessionId)
                        }
                        fs.collection("pairing_sessions").document(HUB_SESSION_ID).set(hubAnnouncement, SetOptions.merge())
                        fs.collection("pairing_sessions").document("pulse_pair_simulation_instant").set(hubAnnouncement, SetOptions.merge())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Write to named Firestore returned error: ${e.message}")
                }
            }
        }

        // Firestore REST API fallback (works even if FirebaseApp failed to initialize in APK)
        if (!anyWriteSucceeded) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            anyWriteSucceeded = writePairingSessionViaRest(
                projectId = projectId,
                databaseId = dbId,
                docId = sessionId,
                sessionId = sessionId,
                deviceName = deviceName,
                userId = userId,
                userEmail = userEmail
            )
            if (anyWriteSucceeded && isInstantOneShot) {
                writePairingSessionViaRest(projectId, dbId, HUB_SESSION_ID, sessionId, deviceName, userId, userEmail)
                writePairingSessionViaRest(projectId, dbId, "pulse_pair_simulation_instant", sessionId, deviceName, userId, userEmail)
            }
        }

        // Save into multi-session store so future incoming/outgoing SMS & multi-screen sync work seamlessly
        saveActiveSession(appContext, sessionId, deviceName)
        registerDevicePresence(appContext, overrideSessionId = sessionId, isOnline = true)

        // Immediately import device SMS inbox (if permission granted) and push real messages to the paired web session
        val syncedCount = syncInitialDeviceInboxForPairing(appContext, sessionId, currentUser != null)
        if (CallLogSyncHelper.hasCallLogPermission(appContext)) {
            val recentCalls = CallLogSyncHelper.readRecentCallLogs(appContext, limit = 80)
            if (recentCalls.isNotEmpty()) {
                syncCallLogsToPairedSession(appContext, recentCalls, sessionId)
            }
            publishCallLogPermissionStatus(appContext, sessionId, "GRANTED")
        } else {
            publishCallLogPermissionStatus(appContext, sessionId, "NEEDED")
        }

        triggerHapticFeedback(appContext)
        Log.i(TAG, "Pairing processed for session: $sessionId on device: $deviceName (cloudSync=$anyWriteSucceeded, syncedSms=$syncedCount)")

        return PairingResult(
            success = true,
            sessionId = sessionId,
            deviceName = deviceName,
            syncedMessagesCount = syncedCount,
            message = if (anyWriteSucceeded) {
                "ওয়েবসাইটে কানেক্টেড হয়েছে এবং $syncedCount টি এসএমএস সিঙ্ক হয়েছে!"
            } else {
                "ডিভাইস পেয়ারিং হয়েছে ($deviceName) — ইন্টারনেট বা ক্লাউড সিঙ্ক চেক করুন"
            }
        )
    }

    private suspend fun syncInitialDeviceInboxForPairing(
        context: Context,
        sessionId: String,
        isUserSignedIn: Boolean
    ): Int = withContext(Dispatchers.IO) {
        try {
            val smsRepo = SmsRepository(context)
            smsRepo.syncDeviceSmsMessages(syncCloudAfter = false)
            val recentMessages = AppDatabase.getInstance(context).messageDao().getRecentMessages(100)
            if (recentMessages.isEmpty()) return@withContext 0

            val count = syncMessagesToPairedSession(context, recentMessages, sessionId)

            if (isUserSignedIn) {
                try {
                    FirebaseSyncRepository(context).syncMessagesBatch(recentMessages)
                } catch (e: Exception) {
                    Log.w(TAG, "User cloud batch sync after pairing: ${e.message}")
                }
            }
            count
        } catch (t: Throwable) {
            Log.w(TAG, "Initial inbox push during pairing failed: ${t.message}")
            0
        }
    }

    /**
     * Pushes a single incoming or outgoing SMS immediately to the currently paired web session.
     * Safe to call from BroadcastReceivers (SmsReceiver / SmsFallbackReceiver) or Repositories.
     */
    suspend fun syncSingleMessageToPairedSession(context: Context, message: MessageEntity): Boolean {
        val targets = getAllTargetSessionIds(context)
        if (targets.isEmpty()) return false
        return syncMessagesToPairedSession(context, listOf(message), overrideSessionId = null) > 0
    }

    /**
     * Batch-pushes SMS messages into `/pairing_sessions/{sessionId}/messages` across all
     * paired sessions on the named Firestore database, falling back to Firestore REST API.
     */
    suspend fun syncMessagesToPairedSession(
        context: Context,
        messages: List<MessageEntity>,
        overrideSessionId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext 0
        val appContext = context.applicationContext ?: context
        val targetSessions = getAllTargetSessionIds(appContext, overrideSessionId)
        if (targetSessions.isEmpty()) return@withContext 0
        val dbId = resolveDatabaseId(appContext)

        val hasFirebase = ensureFirebaseAvailable(appContext)
        val fs = if (hasFirebase) getNamedFirestore(appContext, dbId) else null
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        var maxSyncedCount = 0
        for (targetSession in targetSessions) {
            var sessionCount = 0
            if (fs != null) {
                try {
                    messages.chunked(400).forEach { chunk ->
                        val batch = fs.batch()
                        chunk.forEach { msg ->
                            val docId = buildStableCloudMessageId(msg)
                            val docRef = fs.collection("pairing_sessions")
                                .document(targetSession)
                                .collection("messages")
                                .document(docId)
                            batch.set(docRef, buildCloudMessagePayload(msg, docId), SetOptions.merge())
                        }
                        batch.commit().await()
                    }
                    sessionCount = messages.size
                } catch (e: Exception) {
                    Log.w(TAG, "Batch sync to pairing_sessions/$targetSession failed: ${e.message}")
                }
            }

            if (sessionCount == 0) {
                for (msg in messages.take(60)) {
                    val docId = buildStableCloudMessageId(msg)
                    if (writeMessageViaRest(projectId, dbId, targetSession, docId, msg)) {
                        sessionCount++
                    }
                }
            }
            if (sessionCount > maxSyncedCount) {
                maxSyncedCount = sessionCount
            }
        }

        maxSyncedCount
    }

    /**
     * Listens for outgoing SMS send requests created by the paired web companion.
     */
    fun listenToOutgoingWebRequests(
        context: Context,
        sessionId: String,
        onNewRequest: (requestId: String, recipient: String, body: String) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        if (!ensureFirebaseAvailable(appContext)) return null
        val fs = getNamedFirestore(appContext, resolveDatabaseId(appContext)) ?: return null

        return try {
            fs.collection("pairing_sessions")
                .document(sessionId)
                .collection("outgoing_requests")
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    for (doc in snapshot.documents) {
                        val recipient = doc.getString("recipient")?.trim().orEmpty()
                        val body = doc.getString("body")?.trim().orEmpty()
                        if (recipient.isNotEmpty() && body.isNotEmpty()) {
                            onNewRequest(doc.id, recipient, body)
                        }
                    }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach outgoing web request listener: ${t.message}")
            null
        }
    }

    suspend fun markOutgoingWebRequestStatus(
        context: Context,
        sessionId: String,
        requestId: String,
        status: String
    ) = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext ?: context
            if (!ensureFirebaseAvailable(appContext)) return@withContext
            val fs = getNamedFirestore(appContext, resolveDatabaseId(appContext)) ?: return@withContext
            fs.collection("pairing_sessions")
                .document(sessionId)
                .collection("outgoing_requests")
                .document(requestId)
                .update("status", status)
                .await()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed updating outgoing request $requestId status: ${t.message}")
        }
    }

    /**
     * Listens for remote phone call dial requests issued from the paired Website
     * on `/pairing_sessions/{sessionId}/dial_requests`.
     */
    fun listenToDialRequests(
        context: Context,
        sessionId: String,
        onNewRequest: (RemoteDialRequest) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("dial_requests")
                        .whereEqualTo("status", "PENDING")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Dial requests Firestore listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startDialRequestsRestPolling(projectId, dbId, sessionId, onNewRequest)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null) return@addSnapshotListener
                            for (doc in snapshot.documents) {
                                val number = doc.getString("phoneNumber")?.trim().orEmpty()
                                val simSlot = (doc.getLong("simSlot") ?: -1L).toInt()
                                val status = doc.getString("status")?.trim().orEmpty().ifBlank { "PENDING" }
                                val createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()
                                if (number.isNotEmpty() && status == "PENDING") {
                                    onNewRequest(
                                        RemoteDialRequest(
                                            id = doc.id,
                                            phoneNumber = number,
                                            simSlot = simSlot,
                                            status = status,
                                            createdAtMs = createdAtMs
                                        )
                                    )
                                }
                            }
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach dial request listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }

        return startDialRequestsRestPolling(projectId, dbId, sessionId, onNewRequest)
    }

    suspend fun markDialRequestStatus(
        context: Context,
        sessionId: String,
        requestId: String,
        status: String,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val safeStatus = status.take(32)
        val safeError = errorMessage?.take(300)

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    val updates = hashMapOf<String, Any>(
                        "status" to safeStatus
                    )
                    if (safeError != null) {
                        updates["errorMessage"] = safeError
                    }
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("dial_requests")
                        .document(requestId)
                        .update(updates)
                        .await()
                    ok = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed updating dial request $requestId status: ${t.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            writeDialRequestStatusViaRest(projectId, dbId, sessionId, requestId, safeStatus, safeError)
        }
    }

    /**
     * Pushes a mobile or simultaneous-test notification into `/pairing_sessions/{sessionId}/notifications`
     * so the paired Website displays it in real time (with Firestore REST API fallback).
     */
    suspend fun syncNotificationToPairedSession(
        context: Context,
        notifId: String,
        title: String,
        body: String,
        appName: String,
        packageName: String,
        sourceDevice: String = "MOBILE",
        category: String = "APP",
        isTest: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
        overrideSessionId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSessions = getAllTargetSessionIds(appContext, overrideSessionId)
        if (targetSessions.isEmpty()) return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val safeId = notifId.replace(Regex("[^a-zA-Z0-9_\\-+]"), "_").take(120).ifBlank { "notif_$timestampMs" }
        val safeTitle = title.ifBlank { appName.ifBlank { "Notification" } }.take(200)
        val safeBody = body.take(2000)
        val safeAppName = appName.ifBlank { "Pulse SMS" }.take(100)
        val safePkg = packageName.ifBlank { appContext.packageName }.take(200)
        val safeSource = if (sourceDevice == "WEB") "WEB" else "MOBILE"
        val safeCat = category.uppercase(Locale.US).let { if (it in ALLOWED_NOTIF_CATEGORIES) it else "APP" }
        val safeTs = timestampMs.coerceIn(1L, System.currentTimeMillis())

        val payload = mapOf(
            "id" to safeId,
            "title" to safeTitle,
            "body" to safeBody,
            "appName" to safeAppName,
            "packageName" to safePkg,
            "sourceDevice" to safeSource,
            "category" to safeCat,
            "isTest" to isTest,
            "createdAt" to Timestamp(Date(safeTs))
        )

        val fs = if (ensureFirebaseAvailable(appContext)) getNamedFirestore(appContext, dbId) else null
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        var anyOk = false
        for (sessionId in targetSessions) {
            var ok = false
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("notifications")
                        .document(safeId)
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore notification write error ($sessionId): ${e.message}")
                }
            }

            if (!ok) {
                ok = writeNotificationViaRest(
                    projectId = projectId,
                    databaseId = dbId,
                    sessionId = sessionId,
                    docId = safeId,
                    title = safeTitle,
                    body = safeBody,
                    appName = safeAppName,
                    packageName = safePkg,
                    sourceDevice = safeSource,
                    category = safeCat,
                    isTest = isTest,
                    timestampMs = safeTs
                )
            }
            if (ok) anyOk = true
        }
        anyOk
    }

    /**
     * Real-time listener on `/pairing_sessions/{sessionId}/notifications` ordered newest-first.
     * Both Mobile and Web listen to this exact same stream simultaneously.
     */
    fun listenToSessionNotifications(
        context: Context,
        sessionId: String,
        onNotificationsUpdated: (List<MirroredNotificationItem>) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        if (!ensureFirebaseAvailable(appContext)) return null
        val fs = getNamedFirestore(appContext, resolveDatabaseId(appContext)) ?: return null

        return try {
            fs.collection("pairing_sessions")
                .document(sessionId)
                .collection("notifications")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val items = snapshot.documents.mapNotNull { doc ->
                        val title = doc.getString("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val ts = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()
                        MirroredNotificationItem(
                            id = doc.getString("id") ?: doc.id,
                            title = title,
                            body = doc.getString("body") ?: "",
                            appName = doc.getString("appName") ?: "Pulse",
                            packageName = doc.getString("packageName") ?: "",
                            sourceDevice = doc.getString("sourceDevice") ?: "WEB",
                            category = doc.getString("category") ?: "WEB_ALERT",
                            isTest = doc.getBoolean("isTest") ?: false,
                            createdAtMs = ts
                        )
                    }
                    onNotificationsUpdated(items)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach session notifications listener: ${t.message}")
            null
        }
    }

    fun isWebNotificationSeen(context: Context, notifId: String): Boolean {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_SEEN_NOTIF_IDS, emptySet()) ?: emptySet()
            notifId in set
        } catch (_: Throwable) {
            false
        }
    }

    fun markWebNotificationSeen(context: Context, notifId: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getStringSet(KEY_SEEN_NOTIF_IDS, emptySet())?.toMutableList() ?: mutableListOf()
            if (notifId !in existing) {
                existing.add(notifId)
                val trimmed = existing.takeLast(80).toSet()
                prefs.edit().putStringSet(KEY_SEEN_NOTIF_IDS, trimmed).apply()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Listens for remote camera commands (START_BACK, START_FRONT, STOP, CAPTURE_HD, TOGGLE_TORCH)
     * issued by the paired Website on `/pairing_sessions/{sessionId}/camera/live`.
     */
    fun listenToRemoteCameraCommands(
        context: Context,
        sessionId: String,
        onCommand: (RemoteCameraCommand) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("camera")
                        .document("live")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Remote camera Firestore listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startCameraRestPolling(projectId, dbId, sessionId, onCommand)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cmd = snapshot.getString("command")?.trim() ?: "NONE"
                            val cmdSeq = snapshot.getLong("commandSeq") ?: 0L
                            val status = snapshot.getString("status")?.trim() ?: "IDLE"
                            val updatedAtMs = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            onCommand(RemoteCameraCommand(cmd, cmdSeq, status, updatedAtMs))
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach remote camera listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }

        Log.i(TAG, "Using Firestore REST polling for remote camera commands ($sessionId)")
        return startCameraRestPolling(projectId, dbId, sessionId, onCommand)
    }

    /**
     * Batch-pushes device CallLog entries into `/pairing_sessions/{sessionId}/call_logs`
     * with Firestore REST fallback.
     */
    suspend fun syncCallLogsToPairedSession(
        context: Context,
        callLogs: List<DeviceCallLogItem>,
        overrideSessionId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        if (callLogs.isEmpty()) return@withContext 0
        val appContext = context.applicationContext ?: context
        val targetSessions = getAllTargetSessionIds(appContext, overrideSessionId)
        if (targetSessions.isEmpty()) return@withContext 0
        val dbId = resolveDatabaseId(appContext)
        val fs = if (ensureFirebaseAvailable(appContext)) getNamedFirestore(appContext, dbId) else null
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        var maxSynced = 0
        for (sessionId in targetSessions) {
            var syncedCount = 0
            if (fs != null) {
                try {
                    callLogs.chunked(250).forEach { chunk ->
                        val batch = fs.batch()
                        chunk.forEach { call ->
                            val docRef = fs.collection("pairing_sessions")
                                .document(sessionId)
                                .collection("call_logs")
                                .document(call.id)
                            val safeTs = call.timestampMs.coerceIn(1L, System.currentTimeMillis())
                            val payload = hashMapOf<String, Any>(
                                "id" to call.id,
                                "number" to call.number.ifBlank { "Unknown" }.take(100),
                                "callType" to call.callType,
                                "durationSec" to call.durationSec.coerceIn(0L, 864000L),
                                "createdAt" to Timestamp(Date(safeTs))
                            )
                            call.contactName?.takeIf { it.isNotBlank() }?.let {
                                payload["contactName"] = it.take(120)
                            }
                            call.simLabel?.takeIf { it.isNotBlank() }?.let {
                                payload["simLabel"] = it.take(40)
                            }
                            batch.set(docRef, payload, SetOptions.merge())
                        }
                        batch.commit().await()
                    }
                    syncedCount = callLogs.size
                } catch (e: Exception) {
                    Log.w(TAG, "Batch sync call_logs failed ($sessionId): ${e.message}")
                }
            }

            if (syncedCount == 0) {
                for (call in callLogs.take(40)) {
                    if (writeCallLogViaRest(projectId, dbId, sessionId, call)) {
                        syncedCount++
                    }
                }
            }
            if (syncedCount > maxSynced) maxSynced = syncedCount
        }
        maxSynced
    }

    suspend fun publishCallLogPermissionStatus(
        context: Context,
        sessionId: String? = null,
        permissionStatus: String
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext
        if (!ensureFirebaseAvailable(appContext)) return@withContext
        val fs = getNamedFirestore(appContext, resolveDatabaseId(appContext)) ?: return@withContext
        try {
            fs.collection("pairing_sessions")
                .document(targetSession)
                .set(
                    mapOf(
                        "sessionId" to targetSession,
                        "status" to "CONNECTED",
                        "callLogPermission" to permissionStatus
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed publishing callLogPermission: ${t.message}")
        }
    }

    fun listenToCallLogSyncRequests(
        context: Context,
        sessionId: String,
        onRequested: (Long) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val syncSeq = snapshot.getLong("callLogSyncSeq") ?: 0L
                            if (syncSeq > 0L) {
                                onRequested(syncSeq)
                            }
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach callLogSyncSeq listener: ${t.message}")
                    null
                }
                if (sdkListener != null) return sdkListener
            }
        }

        return startCallLogSyncRestPolling(projectId, dbId, sessionId, onRequested)
    }

    /**
     * Publishes live camera stream status and compressed JPEG frames to `/pairing_sessions/{sessionId}/camera/live`.
     */
    suspend fun publishRemoteCameraState(
        context: Context,
        sessionId: String? = null,
        status: String? = null,
        lens: String? = null,
        torchOn: Boolean? = null,
        frameBase64: String? = null,
        hdFrameBase64: String? = null,
        frameSeq: Int? = null,
        hdSeq: Int? = null,
        width: Int? = null,
        height: Int? = null,
        errorMessage: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(100)

        val payload = hashMapOf<String, Any>(
            "id" to "live",
            "deviceName" to deviceName,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        status?.let { payload["status"] = it }
        lens?.let { payload["lens"] = it }
        torchOn?.let { payload["torchOn"] = it }
        frameBase64?.takeIf { it.length <= 740_000 }?.let { payload["frameBase64"] = it }
        hdFrameBase64?.takeIf { it.length <= 740_000 }?.let { payload["hdFrameBase64"] = it }
        frameSeq?.let { payload["frameSeq"] = it }
        hdSeq?.let { payload["hdSeq"] = it }
        width?.let { payload["width"] = it }
        height?.let { payload["height"] = it }
        errorMessage?.let { payload["errorMessage"] = it.take(300) }

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(targetSession)
                        .collection("camera")
                        .document("live")
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore remote camera write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeCameraStateViaRest(
                projectId = projectId,
                databaseId = dbId,
                sessionId = targetSession,
                deviceName = deviceName,
                status = status,
                lens = lens,
                torchOn = torchOn,
                frameBase64 = frameBase64?.takeIf { it.length <= 740_000 },
                hdFrameBase64 = hdFrameBase64?.takeIf { it.length <= 740_000 },
                frameSeq = frameSeq,
                hdSeq = hdSeq,
                width = width,
                height = height,
                errorMessage = errorMessage
            )
        }
        ok
    }

    /**
     * Listens for remote microphone commands (START, STOP) issued by the paired Website
     * on `/pairing_sessions/{sessionId}/microphone/live`.
     */
    fun listenToRemoteMicCommands(
        context: Context,
        sessionId: String,
        onCommand: (RemoteMicCommand) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("microphone")
                        .document("live")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Remote mic Firestore listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startMicRestPolling(projectId, dbId, sessionId, onCommand)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cmd = snapshot.getString("command")?.trim() ?: "NONE"
                            val cmdSeq = snapshot.getLong("commandSeq") ?: 0L
                            val status = snapshot.getString("status")?.trim() ?: "IDLE"
                            val updatedAtMs = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            onCommand(RemoteMicCommand(cmd, cmdSeq, status, updatedAtMs))
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach remote mic listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }

        Log.i(TAG, "Using Firestore REST polling for remote mic commands ($sessionId)")
        return startMicRestPolling(projectId, dbId, sessionId, onCommand)
    }

    /**
     * Publishes live microphone status and Base64 PCM-16 audio chunks to
     * `/pairing_sessions/{sessionId}/microphone/live`.
     */
    suspend fun publishRemoteMicState(
        context: Context,
        sessionId: String? = null,
        status: String? = null,
        audioBase64: String? = null,
        chunkSeq: Int? = null,
        sampleRate: Int? = null,
        audioLevel: Int? = null,
        errorMessage: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(100)

        val payload = hashMapOf<String, Any>(
            "id" to "live",
            "deviceName" to deviceName,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        status?.let { payload["status"] = it }
        audioBase64?.takeIf { it.length <= 390_000 }?.let { payload["audioBase64"] = it }
        chunkSeq?.let { payload["chunkSeq"] = it.coerceAtLeast(0) }
        sampleRate?.let { payload["sampleRate"] = it.coerceIn(8000, 48000) }
        audioLevel?.let { payload["audioLevel"] = it.coerceIn(0, 100) }
        errorMessage?.let { payload["errorMessage"] = it.take(300) }

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(targetSession)
                        .collection("microphone")
                        .document("live")
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore remote mic write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeMicStateViaRest(
                projectId = projectId,
                databaseId = dbId,
                sessionId = targetSession,
                deviceName = deviceName,
                status = status,
                audioBase64 = audioBase64?.takeIf { it.length <= 390_000 },
                chunkSeq = chunkSeq,
                sampleRate = sampleRate,
                audioLevel = audioLevel,
                errorMessage = errorMessage
            )
        }
        ok
    }

    /**
     * Uploads a recorded call audio + real device location coordinates mapped to the phone number
     * into `/pairing_sessions/{sessionId}/call_recordings/{recordingId}` with REST fallback.
     */
    suspend fun syncCallRecordingToPairedSession(
        context: Context,
        recording: CallRecordingUploadItem,
        overrideSessionId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSessions = getAllTargetSessionIds(appContext, overrideSessionId)
        if (targetSessions.isEmpty()) return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = getDeviceDisplayName()
        val safeCallType = when (recording.callType.uppercase(Locale.US)) {
            "INCOMING", "OUTGOING", "MANUAL", "UNKNOWN" -> recording.callType.uppercase(Locale.US)
            else -> "UNKNOWN"
        }
        val safeNumber = recording.phoneNumber.ifBlank { "Unknown" }.take(100)
        val safeAudio = recording.audioBase64.takeIf { it.isNotEmpty() && it.length <= 750_000 } ?: return@withContext false
        val safeMime = recording.mimeType.ifBlank { "audio/mp4" }.take(50)
        val safeTs = recording.timestampMs.coerceIn(1L, System.currentTimeMillis())

        val payload = hashMapOf<String, Any>(
            "id" to recording.id.take(128),
            "phoneNumber" to safeNumber,
            "callType" to safeCallType,
            "durationSec" to recording.durationSec.coerceIn(0L, 86400L),
            "audioBase64" to safeAudio,
            "mimeType" to safeMime,
            "deviceName" to deviceName,
            "createdAt" to Timestamp(Date(safeTs))
        )
        recording.contactName?.takeIf { it.isNotBlank() }?.let {
            payload["contactName"] = it.take(120)
        }
        val lat = recording.latitude
        val lng = recording.longitude
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            payload["latitude"] = lat
            payload["longitude"] = lng
        }
        recording.locationLabel?.takeIf { it.isNotBlank() }?.let {
            payload["locationLabel"] = it.take(200)
        }

        val fs = if (ensureFirebaseAvailable(appContext)) getNamedFirestore(appContext, dbId) else null
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        var anyOk = false
        for (sessionId in targetSessions) {
            var ok = false
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("call_recordings")
                        .document(recording.id.take(128))
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore call_recording write error ($sessionId): ${e.message}")
                }
            }

            if (!ok) {
                ok = writeCallRecordingViaRest(
                    projectId = projectId,
                    databaseId = dbId,
                    sessionId = sessionId,
                    recording = recording.copy(
                        phoneNumber = safeNumber,
                        callType = safeCallType,
                        audioBase64 = safeAudio,
                        mimeType = safeMime,
                        timestampMs = safeTs
                    ),
                    deviceName = deviceName
                )
            }
            if (ok) anyOk = true
        }
        anyOk
    }

    /**
     * Listens for remote call recorder commands (START, STOP) issued by the paired Website
     * on `/pairing_sessions/{sessionId}/call_recorder/live`.
     */
    fun listenToRemoteCallRecorderCommands(
        context: Context,
        sessionId: String,
        onCommand: (RemoteCallRecorderCommand) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("call_recorder")
                        .document("live")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Call recorder Firestore listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startCallRecorderRestPolling(projectId, dbId, sessionId, onCommand)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cmd = snapshot.getString("command")?.trim() ?: "NONE"
                            val cmdSeq = snapshot.getLong("commandSeq") ?: 0L
                            val targetNumber = snapshot.getString("targetNumber")?.trim() ?: ""
                            val status = snapshot.getString("status")?.trim() ?: "IDLE"
                            val updatedAtMs = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            onCommand(RemoteCallRecorderCommand(cmd, cmdSeq, targetNumber, status, updatedAtMs))
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach call recorder listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }

        return startCallRecorderRestPolling(projectId, dbId, sessionId, onCommand)
    }

    /**
     * Publishes call recorder status and elapsed duration to `/pairing_sessions/{sessionId}/call_recorder/live`.
     */
    suspend fun publishRemoteCallRecorderState(
        context: Context,
        sessionId: String? = null,
        status: String? = null,
        targetNumber: String? = null,
        durationSec: Long? = null,
        errorMessage: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targets = if (!sessionId.isNullOrBlank()) {
            listOf(sessionId)
        } else {
            getAllTargetSessionIds(appContext)
        }
        if (targets.isEmpty()) return@withContext false

        val dbId = resolveDatabaseId(appContext)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(100)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        var anyOk = false
        for (targetSession in targets) {
            val payload = hashMapOf<String, Any>(
                "id" to "live",
                "deviceName" to deviceName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            status?.let { payload["status"] = it }
            targetNumber?.let { payload["targetNumber"] = it.take(100) }
            durationSec?.let { payload["durationSec"] = it.coerceIn(0L, 86400L) }
            errorMessage?.let { payload["errorMessage"] = it.take(300) }

            var ok = false
            if (ensureFirebaseAvailable(appContext)) {
                val fs = getNamedFirestore(appContext, dbId)
                if (fs != null) {
                    try {
                        fs.collection("pairing_sessions")
                            .document(targetSession)
                            .collection("call_recorder")
                            .document("live")
                            .set(payload, SetOptions.merge())
                            .await()
                        ok = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Firestore call_recorder state write error ($targetSession): ${e.message}")
                    }
                }
            }

            if (!ok) {
                ok = writeCallRecorderStateViaRest(
                    projectId = projectId,
                    databaseId = dbId,
                    sessionId = targetSession,
                    deviceName = deviceName,
                    status = status,
                    targetNumber = targetNumber?.take(100),
                    durationSec = durationSec,
                    errorMessage = errorMessage
                )
            }
            if (ok) anyOk = true
        }
        anyOk
    }

    private fun getNamedFirestore(context: Context, databaseId: String): FirebaseFirestore? {
        return try {
            FirebaseFirestore.getInstance(databaseId)
        } catch (t: Throwable) {
            Log.w(TAG, "Named Firestore unavailable ($databaseId): ${t.message}")
            null
        }
    }

    private fun ensureFirebaseAvailable(context: Context): Boolean {
        return try {
            val appContext = context.applicationContext ?: context
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(appContext) != null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firebase unavailable during pairing: ${t.message}")
            false
        }
    }

    @Suppress("DiscouragedApi")
    private fun resolveStringResource(context: Context, name: String): String? {
        return try {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            if (id != 0) context.getString(id).takeIf { it.isNotBlank() } else null
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun writePairingSessionViaRest(
        projectId: String,
        databaseId: String,
        docId: String,
        sessionId: String,
        deviceName: String,
        userId: String,
        userEmail: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())

            val fields = JSONObject().apply {
                put("sessionId", JSONObject().put("stringValue", sessionId))
                put("status", JSONObject().put("stringValue", "CONNECTED"))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("userId", JSONObject().put("stringValue", userId))
                put("userEmail", JSONObject().put("stringValue", userEmail))
                put("connectedAt", JSONObject().put("timestampValue", nowIso))
                put("createdAt", JSONObject().put("timestampValue", nowIso))
            }
            val bodyJson = JSONObject().put("fields", fields).toString()

            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$docId" +
                "?updateMask.fieldPaths=sessionId&updateMask.fieldPaths=status&updateMask.fieldPaths=deviceName&updateMask.fieldPaths=userId&updateMask.fieldPaths=userEmail&updateMask.fieldPaths=connectedAt&updateMask.fieldPaths=createdAt"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    true
                } else {
                    Log.w(TAG, "Firestore REST pairing failed (${response.code})")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST pairing exception: ${t.message}")
            false
        }
    }

    private suspend fun writeMessageViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        docId: String,
        message: MessageEntity
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val safeTs = message.timestamp.coerceIn(1L, System.currentTimeMillis())
            val msgIso = isoFormat.format(Date(safeTs))
            val safeCategory = message.category.uppercase(Locale.US).let {
                if (it in ALLOWED_CATEGORIES) it else "PERSONAL"
            }

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", docId))
                put("threadAddress", JSONObject().put("stringValue", message.threadAddress.ifBlank { "Unknown" }.take(100)))
                put("senderAddress", JSONObject().put("stringValue", message.senderAddress.ifBlank { "Unknown" }.take(100)))
                put("recipientAddress", JSONObject().put("stringValue", message.recipientAddress.ifBlank { "Self" }.take(100)))
                put("body", JSONObject().put("stringValue", message.body.take(5000)))
                put("isIncoming", JSONObject().put("booleanValue", message.isIncoming))
                put("category", JSONObject().put("stringValue", safeCategory))
                put("createdAt", JSONObject().put("timestampValue", msgIso))
            }
            val bodyJson = JSONObject().put("fields", fields).toString()

            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/messages/$docId" +
                "?updateMask.fieldPaths=id&updateMask.fieldPaths=threadAddress&updateMask.fieldPaths=senderAddress&updateMask.fieldPaths=recipientAddress&updateMask.fieldPaths=body&updateMask.fieldPaths=isIncoming&updateMask.fieldPaths=category&updateMask.fieldPaths=createdAt"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST message sync exception: ${t.message}")
            false
        }
    }

    private suspend fun writeNotificationViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        docId: String,
        title: String,
        body: String,
        appName: String,
        packageName: String,
        sourceDevice: String,
        category: String,
        isTest: Boolean,
        timestampMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val msgIso = isoFormat.format(Date(timestampMs))

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", docId))
                put("title", JSONObject().put("stringValue", title))
                put("body", JSONObject().put("stringValue", body))
                put("appName", JSONObject().put("stringValue", appName))
                put("packageName", JSONObject().put("stringValue", packageName))
                put("sourceDevice", JSONObject().put("stringValue", sourceDevice))
                put("category", JSONObject().put("stringValue", category))
                put("isTest", JSONObject().put("booleanValue", isTest))
                put("createdAt", JSONObject().put("timestampValue", msgIso))
            }
            val bodyJson = JSONObject().put("fields", fields).toString()

            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/notifications/$docId" +
                "?updateMask.fieldPaths=id&updateMask.fieldPaths=title&updateMask.fieldPaths=body&updateMask.fieldPaths=appName&updateMask.fieldPaths=packageName&updateMask.fieldPaths=sourceDevice&updateMask.fieldPaths=category&updateMask.fieldPaths=isTest&updateMask.fieldPaths=createdAt"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST notification sync exception: ${t.message}")
            false
        }
    }

    private suspend fun writeCameraStateViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        deviceName: String,
        status: String?,
        lens: String?,
        torchOn: Boolean?,
        frameBase64: String?,
        hdFrameBase64: String?,
        frameSeq: Int?,
        hdSeq: Int?,
        width: Int?,
        height: Int?,
        errorMessage: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", "live"))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("updatedAt", JSONObject().put("timestampValue", nowIso))
                status?.let { put("status", JSONObject().put("stringValue", it)) }
                lens?.let { put("lens", JSONObject().put("stringValue", it)) }
                torchOn?.let { put("torchOn", JSONObject().put("booleanValue", it)) }
                frameBase64?.let { put("frameBase64", JSONObject().put("stringValue", it)) }
                hdFrameBase64?.let { put("hdFrameBase64", JSONObject().put("stringValue", it)) }
                frameSeq?.let { put("frameSeq", JSONObject().put("integerValue", it.toString())) }
                hdSeq?.let { put("hdSeq", JSONObject().put("integerValue", it.toString())) }
                width?.let { put("width", JSONObject().put("integerValue", it.toString())) }
                height?.let { put("height", JSONObject().put("integerValue", it.toString())) }
                errorMessage?.let { put("errorMessage", JSONObject().put("stringValue", it.take(300))) }
            }

            val maskFields = mutableListOf("id", "deviceName", "updatedAt")
            if (status != null) maskFields.add("status")
            if (lens != null) maskFields.add("lens")
            if (torchOn != null) maskFields.add("torchOn")
            if (frameBase64 != null) maskFields.add("frameBase64")
            if (hdFrameBase64 != null) maskFields.add("hdFrameBase64")
            if (frameSeq != null) maskFields.add("frameSeq")
            if (hdSeq != null) maskFields.add("hdSeq")
            if (width != null) maskFields.add("width")
            if (height != null) maskFields.add("height")
            if (errorMessage != null) maskFields.add("errorMessage")

            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/camera/live?$queryMask"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST camera sync exception: ${t.message}")
            false
        }
    }

    private suspend fun writeMicStateViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        deviceName: String,
        status: String?,
        audioBase64: String?,
        chunkSeq: Int?,
        sampleRate: Int?,
        audioLevel: Int?,
        errorMessage: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", "live"))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("updatedAt", JSONObject().put("timestampValue", nowIso))
                status?.let { put("status", JSONObject().put("stringValue", it)) }
                audioBase64?.let { put("audioBase64", JSONObject().put("stringValue", it)) }
                chunkSeq?.let { put("chunkSeq", JSONObject().put("integerValue", it.coerceAtLeast(0).toString())) }
                sampleRate?.let { put("sampleRate", JSONObject().put("integerValue", it.coerceIn(8000, 48000).toString())) }
                audioLevel?.let { put("audioLevel", JSONObject().put("integerValue", it.coerceIn(0, 100).toString())) }
                errorMessage?.let { put("errorMessage", JSONObject().put("stringValue", it.take(300))) }
            }

            val maskFields = mutableListOf("id", "deviceName", "updatedAt")
            if (status != null) maskFields.add("status")
            if (audioBase64 != null) maskFields.add("audioBase64")
            if (chunkSeq != null) maskFields.add("chunkSeq")
            if (sampleRate != null) maskFields.add("sampleRate")
            if (audioLevel != null) maskFields.add("audioLevel")
            if (errorMessage != null) maskFields.add("errorMessage")

            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/microphone/live?$queryMask"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST mic sync exception: ${t.message}")
            false
        }
    }

    private suspend fun writeCallLogViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        call: DeviceCallLogItem
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val safeTs = call.timestampMs.coerceIn(1L, System.currentTimeMillis())
            val callIso = isoFormat.format(Date(safeTs))

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", call.id))
                put("number", JSONObject().put("stringValue", call.number.ifBlank { "Unknown" }.take(100)))
                put("callType", JSONObject().put("stringValue", call.callType))
                put("durationSec", JSONObject().put("integerValue", call.durationSec.coerceIn(0L, 864000L).toString()))
                put("createdAt", JSONObject().put("timestampValue", callIso))
                call.contactName?.takeIf { it.isNotBlank() }?.let {
                    put("contactName", JSONObject().put("stringValue", it.take(120)))
                }
                call.simLabel?.takeIf { it.isNotBlank() }?.let {
                    put("simLabel", JSONObject().put("stringValue", it.take(40)))
                }
            }

            val maskFields = mutableListOf("id", "number", "callType", "durationSec", "createdAt")
            if (!call.contactName.isNullOrBlank()) maskFields.add("contactName")
            if (!call.simLabel.isNullOrBlank()) maskFields.add("simLabel")
            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/call_logs/${call.id}?$queryMask"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST call_log sync exception: ${t.message}")
            false
        }
    }

    private fun startCameraRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onCommand: (RemoteCameraCommand) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val fields = fetchFirestoreDocFieldsViaRest(
                        "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/camera/live"
                    )
                    if (fields != null) {
                        val cmd = fields.optJSONObject("command")?.optString("stringValue")?.trim().orEmpty().ifBlank { "NONE" }
                        val cmdSeq = fields.optJSONObject("commandSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L
                        val status = fields.optJSONObject("status")?.optString("stringValue")?.trim().orEmpty().ifBlank { "IDLE" }
                        val updatedAtMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get()) {
                                onCommand(RemoteCameraCommand(cmd, cmdSeq, status, updatedAtMs))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Camera REST poll tick error: ${t.message}")
                }
                delay(1200)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun startMicRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onCommand: (RemoteMicCommand) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val fields = fetchFirestoreDocFieldsViaRest(
                        "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/microphone/live"
                    )
                    if (fields != null) {
                        val cmd = fields.optJSONObject("command")?.optString("stringValue")?.trim().orEmpty().ifBlank { "NONE" }
                        val cmdSeq = fields.optJSONObject("commandSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L
                        val status = fields.optJSONObject("status")?.optString("stringValue")?.trim().orEmpty().ifBlank { "IDLE" }
                        val updatedAtMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get()) {
                                onCommand(RemoteMicCommand(cmd, cmdSeq, status, updatedAtMs))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Mic REST poll tick error: ${t.message}")
                }
                delay(1200)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun startCallLogSyncRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onRequested: (Long) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val fields = fetchFirestoreDocFieldsViaRest(
                        "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId"
                    )
                    val syncSeq = fields?.optJSONObject("callLogSyncSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L
                    if (syncSeq > 0L) {
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get()) {
                                onRequested(syncSeq)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(2500)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private suspend fun writeCallRecordingViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        recording: CallRecordingUploadItem,
        deviceName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val createdIso = isoFormat.format(Date(recording.timestampMs))
            val docId = recording.id.take(128)

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", docId))
                put("phoneNumber", JSONObject().put("stringValue", recording.phoneNumber))
                put("callType", JSONObject().put("stringValue", recording.callType))
                put("durationSec", JSONObject().put("integerValue", recording.durationSec.coerceIn(0L, 86400L).toString()))
                put("audioBase64", JSONObject().put("stringValue", recording.audioBase64))
                put("mimeType", JSONObject().put("stringValue", recording.mimeType))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("createdAt", JSONObject().put("timestampValue", createdIso))
                recording.contactName?.takeIf { it.isNotBlank() }?.let {
                    put("contactName", JSONObject().put("stringValue", it.take(120)))
                }
                val lat = recording.latitude
                val lng = recording.longitude
                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                    put("latitude", JSONObject().put("doubleValue", lat))
                    put("longitude", JSONObject().put("doubleValue", lng))
                }
                recording.locationLabel?.takeIf { it.isNotBlank() }?.let {
                    put("locationLabel", JSONObject().put("stringValue", it.take(200)))
                }
            }

            val maskFields = mutableListOf(
                "id", "phoneNumber", "callType", "durationSec",
                "audioBase64", "mimeType", "deviceName", "createdAt"
            )
            if (!recording.contactName.isNullOrBlank()) maskFields.add("contactName")
            val lat = recording.latitude
            val lng = recording.longitude
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                maskFields.add("latitude")
                maskFields.add("longitude")
            }
            if (!recording.locationLabel.isNullOrBlank()) maskFields.add("locationLabel")

            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/call_recordings/$docId?$queryMask"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST call_recording write exception: ${t.message}")
            false
        }
    }

    private suspend fun writeCallRecorderStateViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        deviceName: String,
        status: String?,
        targetNumber: String?,
        durationSec: Long?,
        errorMessage: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())

            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", "live"))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("updatedAt", JSONObject().put("timestampValue", nowIso))
                status?.let { put("status", JSONObject().put("stringValue", it)) }
                targetNumber?.let { put("targetNumber", JSONObject().put("stringValue", it)) }
                durationSec?.let { put("durationSec", JSONObject().put("integerValue", it.coerceIn(0L, 86400L).toString())) }
                errorMessage?.let { put("errorMessage", JSONObject().put("stringValue", it.take(300))) }
            }

            val maskFields = mutableListOf("id", "deviceName", "updatedAt")
            if (status != null) maskFields.add("status")
            if (targetNumber != null) maskFields.add("targetNumber")
            if (durationSec != null) maskFields.add("durationSec")
            if (errorMessage != null) maskFields.add("errorMessage")

            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/call_recorder/live?$queryMask"

            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST call_recorder write exception: ${t.message}")
            false
        }
    }

    private fun startCallRecorderRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onCommand: (RemoteCallRecorderCommand) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val fields = fetchFirestoreDocFieldsViaRest(
                        "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/call_recorder/live"
                    )
                    if (fields != null) {
                        val cmd = fields.optJSONObject("command")?.optString("stringValue")?.trim().orEmpty().ifBlank { "NONE" }
                        val cmdSeq = fields.optJSONObject("commandSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L
                        val targetNumber = fields.optJSONObject("targetNumber")?.optString("stringValue")?.trim().orEmpty()
                        val status = fields.optJSONObject("status")?.optString("stringValue")?.trim().orEmpty().ifBlank { "IDLE" }
                        val updatedAtMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                        withContext(Dispatchers.Main) {
                            if (!cancelled.get()) {
                                onCommand(RemoteCallRecorderCommand(cmd, cmdSeq, targetNumber, status, updatedAtMs))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Call recorder REST poll error: ${t.message}")
                }
                delay(1500)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun startDialRequestsRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onNewRequest: (RemoteDialRequest) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/dial_requests?pageSize=20"
                    val request = Request.Builder().url(url).get().build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val docs = JSONObject(body).optJSONArray("documents")
                                if (docs != null) {
                                    for (i in 0 until docs.length()) {
                                        val docObj = docs.optJSONObject(i) ?: continue
                                        val namePath = docObj.optString("name")
                                        val docId = namePath.substringAfterLast('/')
                                        val fields = docObj.optJSONObject("fields") ?: continue
                                        val status = fields.optJSONObject("status")?.optString("stringValue")?.trim().orEmpty()
                                        val number = fields.optJSONObject("phoneNumber")?.optString("stringValue")?.trim().orEmpty()
                                        val simSlot = fields.optJSONObject("simSlot")?.optString("integerValue")?.toIntOrNull() ?: -1
                                        val createdAtMs = parseRestTimestampMs(fields.optJSONObject("createdAt")?.optString("timestampValue"))
                                        if (docId.isNotBlank() && number.isNotBlank() && status == "PENDING") {
                                            withContext(Dispatchers.Main) {
                                                if (!cancelled.get()) {
                                                    onNewRequest(
                                                        RemoteDialRequest(
                                                            id = docId,
                                                            phoneNumber = number,
                                                            simSlot = simSlot,
                                                            status = status,
                                                            createdAtMs = if (createdAtMs > 0L) createdAtMs else System.currentTimeMillis()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Dial requests REST poll error: ${t.message}")
                }
                delay(1500)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun writeDialRequestStatusViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        requestId: String,
        status: String,
        errorMessage: String?
    ): Boolean {
        return try {
            val fields = JSONObject().apply {
                put("status", JSONObject().put("stringValue", status))
                errorMessage?.let { put("errorMessage", JSONObject().put("stringValue", it)) }
            }
            val maskFields = mutableListOf("status")
            if (errorMessage != null) maskFields.add("errorMessage")
            val queryMask = maskFields.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/dial_requests/$requestId?$queryMask"
            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore REST dial_requests status update failed: ${t.message}")
            false
        }
    }

    private fun fetchFirestoreDocFieldsViaRest(url: String): JSONObject? {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body).optJSONObject("fields")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseRestTimestampMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val d = fmt.parse(iso)
                if (d != null) return d.time
            } catch (_: Throwable) {}
        }
        return 0L
    }

    // =====================================================
    // 🖥️ MULTI-DEVICE HUB & PEER DEVICE REMOTE DISPATCH
    // =====================================================

    suspend fun registerDevicePresence(
        context: Context,
        overrideSessionId: String? = null,
        isOnline: Boolean = true,
        includeFreshLocation: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val deviceId = getOrCreateDeviceId(appContext)
        val deviceName = getDeviceDisplayName().ifBlank { "Android Device" }
        val boundSessionId = (overrideSessionId?.takeIf { it.isNotBlank() }
            ?: getActiveSessionId(appContext)
            ?: "pulse_pair_${deviceId}").take(128)
        val (batteryLevel, isCharging) = readBatteryStatus(appContext)
        val dbId = resolveDatabaseId(appContext)

        val locationSharingActive = CallRecorderManager.isLocationSharingActive(appContext)
        val capturedLoc = if (locationSharingActive) {
            if (includeFreshLocation) {
                CallRecorderManager.captureFreshDeviceLocation(appContext)
            } else {
                CallRecorderManager.captureDeviceLocation(appContext)
            }
        } else {
            null
        }

        val payload = hashMapOf<String, Any>(
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "sessionId" to boundSessionId,
            "platform" to "ANDROID",
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "isOnline" to isOnline,
            "locationEnabled" to locationSharingActive,
            "appVersion" to "1.0.5",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (capturedLoc != null) {
            payload["latitude"] = capturedLoc.latitude
            payload["longitude"] = capturedLoc.longitude
            capturedLoc.label?.takeIf { it.isNotBlank() }?.let { payload["locationLabel"] = it.take(200) }
            capturedLoc.accuracyMeters?.let { acc ->
                payload["locationAccuracyM"] = acc.toDouble().coerceIn(0.0, 100000.0)
            }
        }

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(HUB_SESSION_ID)
                        .collection("devices")
                        .document(deviceId)
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Device presence Firestore write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeDevicePresenceViaRest(
                projectId = projectId,
                databaseId = dbId,
                deviceId = deviceId,
                deviceName = deviceName,
                sessionId = boundSessionId,
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                isOnline = isOnline,
                locationEnabled = locationSharingActive,
                latitude = capturedLoc?.latitude,
                longitude = capturedLoc?.longitude,
                locationLabel = capturedLoc?.label,
                locationAccuracyM = capturedLoc?.accuracyMeters?.toDouble()?.coerceIn(0.0, 100000.0)
            )
        }
        ok
    }

    fun listenToConnectedDevices(
        context: Context,
        onUpdated: (List<ConnectedPeerDevice>) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(HUB_SESSION_ID)
                        .collection("devices")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Connected devices listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startConnectedDevicesRestPolling(projectId, dbId, onUpdated)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null) return@addSnapshotListener
                            val parsed = snapshot.documents.mapNotNull { doc ->
                                val dId = doc.getString("deviceId")?.takeIf { it.isNotBlank() } ?: doc.id
                                val dName = doc.getString("deviceName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val sId = doc.getString("sessionId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val platform = doc.getString("platform") ?: "ANDROID"
                                val battery = (doc.getLong("batteryLevel") ?: -1L).toInt().coerceIn(-1, 100)
                                val charging = doc.getBoolean("isCharging") ?: false
                                val onlineFlag = doc.getBoolean("isOnline") ?: false
                                val updatedMs = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                                val isRecentlySeen = onlineFlag && (updatedMs == 0L || (System.currentTimeMillis() - updatedMs) <= 180_000L)
                                val lat = doc.getDouble("latitude")?.takeIf { it in -90.0..90.0 }
                                val lng = doc.getDouble("longitude")?.takeIf { it in -180.0..180.0 }
                                val locLabel = doc.getString("locationLabel")?.takeIf { it.isNotBlank() }
                                val locEnabled = doc.getBoolean("locationEnabled") ?: (lat != null && lng != null)
                                val locAcc = doc.getDouble("locationAccuracyM")
                                ConnectedPeerDevice(
                                    deviceId = dId,
                                    deviceName = dName,
                                    sessionId = sId,
                                    platform = platform,
                                    batteryLevel = battery,
                                    isCharging = charging,
                                    isOnline = isRecentlySeen,
                                    appVersion = doc.getString("appVersion") ?: "1.0.5",
                                    updatedAtMs = updatedMs,
                                    latitude = lat,
                                    longitude = lng,
                                    locationLabel = locLabel,
                                    locationEnabled = locEnabled,
                                    locationAccuracyM = locAcc
                                )
                            }.sortedWith(compareByDescending<ConnectedPeerDevice> { it.isOnline }.thenByDescending { it.updatedAtMs })
                            onUpdated(parsed)
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach connected devices listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }
        return startConnectedDevicesRestPolling(projectId, dbId, onUpdated)
    }

    fun listenToOwnDeviceLocationRequests(
        context: Context,
        onLocationRequested: (Long) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val deviceId = getOrCreateDeviceId(appContext)
        val dbId = resolveDatabaseId(appContext)
        if (!ensureFirebaseAvailable(appContext)) return null
        val fs = getNamedFirestore(appContext, dbId) ?: return null
        var lastSeq = -1L
        return try {
            fs.collection("pairing_sessions")
                .document(HUB_SESSION_ID)
                .collection("devices")
                .document(deviceId)
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                    val seq = snap.getLong("locationRequestSeq") ?: 0L
                    if (lastSeq < 0L) {
                        lastSeq = seq
                    } else if (seq > lastSeq) {
                        lastSeq = seq
                        onLocationRequested(seq)
                    }
                }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun dispatchDialRequestToSession(
        context: Context,
        targetSessionId: String,
        phoneNumber: String,
        simSlot: Int = -1
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanSession = targetSessionId.trim().take(128)
        val cleanNumber = phoneNumber.trim().take(100)
        if (cleanSession.isBlank() || cleanNumber.isBlank()) return@withContext false

        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val reqId = "mobdial_${System.currentTimeMillis()}_${(100..999).random()}"
        val safeSim = simSlot.coerceIn(-1, 4)

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    val payload = hashMapOf<String, Any>(
                        "id" to reqId,
                        "phoneNumber" to cleanNumber,
                        "simSlot" to safeSim,
                        "status" to "PENDING",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    fs.collection("pairing_sessions")
                        .document(cleanSession)
                        .collection("dial_requests")
                        .document(reqId)
                        .set(payload)
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Peer dial dispatch Firestore error: ${e.message}")
                }
            }
        }
        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeDialRequestViaRest(projectId, dbId, cleanSession, reqId, cleanNumber, safeSim)
        }
        ok
    }

    suspend fun dispatchSmsRequestToSession(
        context: Context,
        targetSessionId: String,
        recipient: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanSession = targetSessionId.trim().take(128)
        val cleanRecipient = recipient.trim().take(100)
        val cleanBody = body.trim().take(2000)
        if (cleanSession.isBlank() || cleanRecipient.isBlank() || cleanBody.isBlank()) return@withContext false

        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val reqId = "mobsms_${System.currentTimeMillis()}_${(100..999).random()}"

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    val payload = hashMapOf<String, Any>(
                        "id" to reqId,
                        "recipient" to cleanRecipient,
                        "body" to cleanBody,
                        "status" to "PENDING",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    fs.collection("pairing_sessions")
                        .document(cleanSession)
                        .collection("outgoing_requests")
                        .document(reqId)
                        .set(payload)
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Peer SMS dispatch Firestore error: ${e.message}")
                }
            }
        }
        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeOutgoingRequestViaRest(projectId, dbId, cleanSession, reqId, cleanRecipient, cleanBody)
        }
        ok
    }

    private fun writeDevicePresenceViaRest(
        projectId: String,
        databaseId: String,
        deviceId: String,
        deviceName: String,
        sessionId: String,
        batteryLevel: Int,
        isCharging: Boolean,
        isOnline: Boolean,
        locationEnabled: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        locationLabel: String? = null,
        locationAccuracyM: Double? = null
    ): Boolean {
        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())
            val maskPaths = mutableListOf(
                "deviceId", "deviceName", "sessionId", "platform",
                "batteryLevel", "isCharging", "isOnline", "locationEnabled",
                "appVersion", "updatedAt"
            )
            val fields = JSONObject().apply {
                put("deviceId", JSONObject().put("stringValue", deviceId))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("sessionId", JSONObject().put("stringValue", sessionId))
                put("platform", JSONObject().put("stringValue", "ANDROID"))
                put("batteryLevel", JSONObject().put("integerValue", batteryLevel.toString()))
                put("isCharging", JSONObject().put("booleanValue", isCharging))
                put("isOnline", JSONObject().put("booleanValue", isOnline))
                put("locationEnabled", JSONObject().put("booleanValue", locationEnabled))
                put("appVersion", JSONObject().put("stringValue", "1.0.5"))
                put("updatedAt", JSONObject().put("timestampValue", nowIso))
                if (latitude != null && longitude != null) {
                    put("latitude", JSONObject().put("doubleValue", latitude))
                    put("longitude", JSONObject().put("doubleValue", longitude))
                    maskPaths.add("latitude")
                    maskPaths.add("longitude")
                }
                if (!locationLabel.isNullOrBlank()) {
                    put("locationLabel", JSONObject().put("stringValue", locationLabel.take(200)))
                    maskPaths.add("locationLabel")
                }
                if (locationAccuracyM != null) {
                    put("locationAccuracyM", JSONObject().put("doubleValue", locationAccuracyM))
                    maskPaths.add("locationAccuracyM")
                }
            }
            val mask = maskPaths.joinToString("&") { "updateMask.fieldPaths=$it" }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$HUB_SESSION_ID/devices/$deviceId?$mask"
            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "REST device presence failed: ${t.message}")
            false
        }
    }

    private fun writeDialRequestViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        requestId: String,
        phoneNumber: String,
        simSlot: Int
    ): Boolean {
        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())
            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", requestId))
                put("phoneNumber", JSONObject().put("stringValue", phoneNumber))
                put("simSlot", JSONObject().put("integerValue", simSlot.toString()))
                put("status", JSONObject().put("stringValue", "PENDING"))
                put("createdAt", JSONObject().put("timestampValue", nowIso))
            }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/dial_requests?documentId=$requestId"
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "REST peer dial failed: ${t.message}")
            false
        }
    }

    private fun writeOutgoingRequestViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        requestId: String,
        recipient: String,
        body: String
    ): Boolean {
        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val nowIso = isoFormat.format(Date())
            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", requestId))
                put("recipient", JSONObject().put("stringValue", recipient))
                put("body", JSONObject().put("stringValue", body))
                put("status", JSONObject().put("stringValue", "PENDING"))
                put("createdAt", JSONObject().put("timestampValue", nowIso))
            }
            val bodyJson = JSONObject().put("fields", fields).toString()
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/outgoing_requests?documentId=$requestId"
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "REST peer SMS failed: ${t.message}")
            false
        }
    }

    private fun startConnectedDevicesRestPolling(
        projectId: String,
        databaseId: String,
        onUpdated: (List<ConnectedPeerDevice>) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job: Job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$HUB_SESSION_ID/devices?pageSize=30"
                    val request = Request.Builder().url(url).get().build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrBlank()) {
                                val docs = JSONObject(body).optJSONArray("documents")
                                val items = mutableListOf<ConnectedPeerDevice>()
                                if (docs != null) {
                                    for (i in 0 until docs.length()) {
                                        val docObj = docs.optJSONObject(i) ?: continue
                                        val docId = docObj.optString("name").substringAfterLast('/')
                                        val fields = docObj.optJSONObject("fields") ?: continue
                                        val dId = fields.optJSONObject("deviceId")?.optString("stringValue")?.takeIf { it.isNotBlank() } ?: docId
                                        val dName = fields.optJSONObject("deviceName")?.optString("stringValue")?.takeIf { it.isNotBlank() } ?: continue
                                        val sId = fields.optJSONObject("sessionId")?.optString("stringValue")?.takeIf { it.isNotBlank() } ?: continue
                                        val platform = fields.optJSONObject("platform")?.optString("stringValue") ?: "ANDROID"
                                        val battery = fields.optJSONObject("batteryLevel")?.optString("integerValue")?.toIntOrNull()?.coerceIn(-1, 100) ?: -1
                                        val charging = fields.optJSONObject("isCharging")?.optBoolean("booleanValue") ?: false
                                        val onlineFlag = fields.optJSONObject("isOnline")?.optBoolean("booleanValue") ?: false
                                        val appVersion = fields.optJSONObject("appVersion")?.optString("stringValue") ?: "1.0.5"
                                        val updatedMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                                        val isRecentlySeen = onlineFlag && (updatedMs == 0L || (System.currentTimeMillis() - updatedMs) <= 180_000L)
                                        val latObj = fields.optJSONObject("latitude")
                                        val lat = (latObj?.opt("doubleValue") as? Number)?.toDouble()
                                            ?: latObj?.optString("integerValue")?.toDoubleOrNull()
                                        val lngObj = fields.optJSONObject("longitude")
                                        val lng = (lngObj?.opt("doubleValue") as? Number)?.toDouble()
                                            ?: lngObj?.optString("integerValue")?.toDoubleOrNull()
                                        val locLabel = fields.optJSONObject("locationLabel")?.optString("stringValue")?.takeIf { it.isNotBlank() }
                                        val locEnabled = fields.optJSONObject("locationEnabled")?.optBoolean("booleanValue") ?: (lat != null && lng != null)
                                        val accObj = fields.optJSONObject("locationAccuracyM")
                                        val locAcc = (accObj?.opt("doubleValue") as? Number)?.toDouble()
                                            ?: accObj?.optString("integerValue")?.toDoubleOrNull()
                                        items.add(
                                            ConnectedPeerDevice(
                                                deviceId = dId,
                                                deviceName = dName,
                                                sessionId = sId,
                                                platform = platform,
                                                batteryLevel = battery,
                                                isCharging = charging,
                                                isOnline = isRecentlySeen,
                                                appVersion = appVersion,
                                                updatedAtMs = updatedMs,
                                                latitude = lat?.takeIf { it in -90.0..90.0 },
                                                longitude = lng?.takeIf { it in -180.0..180.0 },
                                                locationLabel = locLabel,
                                                locationEnabled = locEnabled,
                                                locationAccuracyM = locAcc
                                            )
                                        )
                                    }
                                }
                                val sorted = items.sortedWith(compareByDescending<ConnectedPeerDevice> { it.isOnline }.thenByDescending { it.updatedAtMs })
                                withContext(Dispatchers.Main) {
                                    if (!cancelled.get()) onUpdated(sorted)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Connected devices REST poll error: ${t.message}")
                }
                delay(4000)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // Remote Mobile File Manager Bridge (`file_manager/live` & `file_manager/transfer`)
    // -------------------------------------------------------------------------

    fun listenToFileManagerCommands(
        context: Context,
        sessionId: String,
        onCommand: (RemoteFileManagerCommand) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("file_manager")
                        .document("live")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "File manager listener error (${error.message}), switching to REST polling")
                                if (restFallback == null) {
                                    restFallback = startFileManagerRestPolling(projectId, dbId, sessionId, onCommand)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cmd = snapshot.getString("command")?.trim() ?: "NONE"
                            val cmdSeq = snapshot.getLong("commandSeq") ?: 0L
                            val targetRootId = snapshot.getString("targetRootId")
                            val targetPath = snapshot.getString("targetPath")
                            val targetFileId = snapshot.getString("targetFileId")
                            val targetName = snapshot.getString("targetName")
                            val status = snapshot.getString("status")?.trim() ?: "IDLE"
                            val updatedAtMs = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            onCommand(
                                RemoteFileManagerCommand(
                                    command = cmd,
                                    commandSeq = cmdSeq,
                                    targetRootId = targetRootId,
                                    targetPath = targetPath,
                                    targetFileId = targetFileId,
                                    targetName = targetName,
                                    status = status,
                                    updatedAtMs = updatedAtMs
                                )
                            )
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach file manager listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }
        return startFileManagerRestPolling(projectId, dbId, sessionId, onCommand)
    }

    suspend fun publishFileManagerSnapshot(
        context: Context,
        sessionId: String? = null,
        snapshot: com.example.telephony.DirectoryBrowseResult
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = getDeviceDisplayName().take(120)

        val payload = hashMapOf<String, Any>(
            "id" to "live",
            "status" to snapshot.status,
            "hasFolderAccess" to snapshot.hasFolderAccess,
            "rootsJson" to snapshot.rootsJson.take(49_500),
            "currentRootId" to snapshot.currentRootId.take(160),
            "currentRootName" to snapshot.currentRootName.take(200),
            "currentPath" to snapshot.currentPath.take(1000),
            "breadcrumbsJson" to snapshot.breadcrumbsJson.take(19_500),
            "entriesJson" to snapshot.entriesJson.take(295_000),
            "totalFolders" to snapshot.totalFolders.coerceIn(0, 100_000),
            "totalFiles" to snapshot.totalFiles.coerceIn(0, 100_000),
            "totalBytes" to snapshot.totalBytes.coerceAtLeast(0L),
            "deviceName" to deviceName,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!snapshot.errorMessage.isNullOrBlank()) {
            payload["errorMessage"] = snapshot.errorMessage.take(380)
        }

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(targetSession)
                        .collection("file_manager")
                        .document("live")
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore file_manager snapshot write error: ${e.message}")
                }
            }
        }
        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeFileManagerSnapshotViaRest(projectId, dbId, targetSession, deviceName, snapshot)
        }
        ok
    }

    fun listenToFileTransferCommands(
        context: Context,
        sessionId: String,
        onCommand: (RemoteFileTransferCommand) -> Unit
    ): ListenerRegistration? {
        val appContext = context.applicationContext ?: context
        val dbId = resolveDatabaseId(appContext)
        val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID

        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                var restFallback: ListenerRegistration? = null
                val sdkListener = try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .collection("file_manager")
                        .document("transfer")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                if (restFallback == null) {
                                    restFallback = startFileTransferRestPolling(projectId, dbId, sessionId, onCommand)
                                }
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cmd = snapshot.getString("command")?.trim() ?: "NONE"
                            val cmdSeq = snapshot.getLong("commandSeq") ?: 0L
                            val direction = snapshot.getString("direction")?.trim() ?: "PHONE_TO_WEB"
                            val targetRootId = snapshot.getString("targetRootId")
                            val targetFileId = snapshot.getString("targetFileId")
                            val targetPath = snapshot.getString("targetPath")
                            val fileName = snapshot.getString("fileName") ?: "file"
                            val mimeType = snapshot.getString("mimeType") ?: "application/octet-stream"
                            val base64Data = snapshot.getString("base64Data")
                            val status = snapshot.getString("status")?.trim() ?: "IDLE"
                            val updatedAtMs = snapshot.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            onCommand(
                                RemoteFileTransferCommand(
                                    command = cmd,
                                    commandSeq = cmdSeq,
                                    direction = direction,
                                    targetRootId = targetRootId,
                                    targetFileId = targetFileId,
                                    targetPath = targetPath,
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    base64Data = base64Data,
                                    status = status,
                                    updatedAtMs = updatedAtMs
                                )
                            )
                        }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not attach file transfer listener: ${t.message}")
                    null
                }
                if (sdkListener != null) {
                    return ListenerRegistration {
                        try { sdkListener.remove() } catch (_: Throwable) {}
                        try { restFallback?.remove() } catch (_: Throwable) {}
                    }
                }
            }
        }
        return startFileTransferRestPolling(projectId, dbId, sessionId, onCommand)
    }

    suspend fun publishFileTransferResult(
        context: Context,
        sessionId: String? = null,
        status: String,
        direction: String = "PHONE_TO_WEB",
        fileName: String,
        mimeType: String = "application/octet-stream",
        sizeBytes: Long = 0L,
        base64Data: String? = null,
        transferSeq: Long = System.currentTimeMillis(),
        isPreviewImage: Boolean = false,
        errorMessage: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = getDeviceDisplayName().take(120)

        val payload = hashMapOf<String, Any>(
            "id" to "transfer",
            "command" to "NONE",
            "direction" to direction,
            "fileName" to fileName.take(250),
            "mimeType" to mimeType.take(120),
            "sizeBytes" to sizeBytes.coerceAtLeast(0L),
            "transferSeq" to transferSeq.coerceAtLeast(0L),
            "status" to status,
            "isPreviewImage" to isPreviewImage,
            "deviceName" to deviceName,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (base64Data != null && base64Data.length <= 740_000) {
            payload["base64Data"] = base64Data
        }
        if (!errorMessage.isNullOrBlank()) {
            payload["errorMessage"] = errorMessage.take(380)
        }

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
            if (fs != null) {
                try {
                    fs.collection("pairing_sessions")
                        .document(targetSession)
                        .collection("file_manager")
                        .document("transfer")
                        .set(payload, SetOptions.merge())
                        .await()
                    ok = true
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore file_manager transfer write error: ${e.message}")
                }
            }
        }
        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            ok = writeFileTransferResultViaRest(
                projectId, dbId, targetSession, deviceName,
                status, direction, fileName, mimeType, sizeBytes,
                base64Data?.takeIf { it.length <= 740_000 }, transferSeq, isPreviewImage, errorMessage
            )
        }
        ok
    }

    private fun startFileManagerRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onCommand: (RemoteFileManagerCommand) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/file_manager/live"
                    httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                val fields = JSONObject(body).optJSONObject("fields")
                                if (fields != null) {
                                    val cmd = RemoteFileManagerCommand(
                                        command = fields.optJSONObject("command")?.optString("stringValue") ?: "NONE",
                                        commandSeq = fields.optJSONObject("commandSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L,
                                        targetRootId = fields.optJSONObject("targetRootId")?.optString("stringValue"),
                                        targetPath = fields.optJSONObject("targetPath")?.optString("stringValue"),
                                        targetFileId = fields.optJSONObject("targetFileId")?.optString("stringValue"),
                                        targetName = fields.optJSONObject("targetName")?.optString("stringValue"),
                                        status = fields.optJSONObject("status")?.optString("stringValue") ?: "IDLE",
                                        updatedAtMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (!cancelled.get()) onCommand(cmd)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                delay(2000)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun startFileTransferRestPolling(
        projectId: String,
        databaseId: String,
        sessionId: String,
        onCommand: (RemoteFileTransferCommand) -> Unit
    ): ListenerRegistration {
        val cancelled = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            while (isActive && !cancelled.get()) {
                try {
                    val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/file_manager/transfer"
                    httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                val fields = JSONObject(body).optJSONObject("fields")
                                if (fields != null) {
                                    val cmd = RemoteFileTransferCommand(
                                        command = fields.optJSONObject("command")?.optString("stringValue") ?: "NONE",
                                        commandSeq = fields.optJSONObject("commandSeq")?.optString("integerValue")?.toLongOrNull() ?: 0L,
                                        direction = fields.optJSONObject("direction")?.optString("stringValue") ?: "PHONE_TO_WEB",
                                        targetRootId = fields.optJSONObject("targetRootId")?.optString("stringValue"),
                                        targetFileId = fields.optJSONObject("targetFileId")?.optString("stringValue"),
                                        targetPath = fields.optJSONObject("targetPath")?.optString("stringValue"),
                                        fileName = fields.optJSONObject("fileName")?.optString("stringValue") ?: "file",
                                        mimeType = fields.optJSONObject("mimeType")?.optString("stringValue") ?: "application/octet-stream",
                                        base64Data = fields.optJSONObject("base64Data")?.optString("stringValue"),
                                        status = fields.optJSONObject("status")?.optString("stringValue") ?: "IDLE",
                                        updatedAtMs = parseRestTimestampMs(fields.optJSONObject("updatedAt")?.optString("timestampValue"))
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (!cancelled.get()) onCommand(cmd)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                delay(2000)
            }
        }
        return ListenerRegistration {
            cancelled.set(true)
            job.cancel()
        }
    }

    private fun writeFileManagerSnapshotViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        deviceName: String,
        snapshot: com.example.telephony.DirectoryBrowseResult
    ): Boolean {
        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val maskPaths = mutableListOf(
                "id", "status", "hasFolderAccess", "rootsJson",
                "currentRootId", "currentRootName", "currentPath",
                "breadcrumbsJson", "entriesJson", "totalFolders",
                "totalFiles", "totalBytes", "deviceName", "updatedAt"
            )
            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", "live"))
                put("status", JSONObject().put("stringValue", snapshot.status))
                put("hasFolderAccess", JSONObject().put("booleanValue", snapshot.hasFolderAccess))
                put("rootsJson", JSONObject().put("stringValue", snapshot.rootsJson.take(49_500)))
                put("currentRootId", JSONObject().put("stringValue", snapshot.currentRootId.take(160)))
                put("currentRootName", JSONObject().put("stringValue", snapshot.currentRootName.take(200)))
                put("currentPath", JSONObject().put("stringValue", snapshot.currentPath.take(1000)))
                put("breadcrumbsJson", JSONObject().put("stringValue", snapshot.breadcrumbsJson.take(19_500)))
                put("entriesJson", JSONObject().put("stringValue", snapshot.entriesJson.take(295_000)))
                put("totalFolders", JSONObject().put("integerValue", snapshot.totalFolders.toString()))
                put("totalFiles", JSONObject().put("integerValue", snapshot.totalFiles.toString()))
                put("totalBytes", JSONObject().put("integerValue", snapshot.totalBytes.toString()))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("updatedAt", JSONObject().put("timestampValue", isoFormat.format(Date())))
                if (!snapshot.errorMessage.isNullOrBlank()) {
                    put("errorMessage", JSONObject().put("stringValue", snapshot.errorMessage.take(380)))
                    maskPaths.add("errorMessage")
                }
            }
            val mask = maskPaths.joinToString("&") { "updateMask.fieldPaths=$it" }
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/file_manager/live?$mask"
            val req = Request.Builder()
                .url(url)
                .patch(JSONObject().put("fields", fields).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "REST file_manager snapshot failed: ${t.message}")
            false
        }
    }

    private fun writeFileTransferResultViaRest(
        projectId: String,
        databaseId: String,
        sessionId: String,
        deviceName: String,
        status: String,
        direction: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        base64Data: String?,
        transferSeq: Long,
        isPreviewImage: Boolean,
        errorMessage: String?
    ): Boolean {
        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val maskPaths = mutableListOf(
                "id", "command", "direction", "fileName", "mimeType",
                "sizeBytes", "transferSeq", "status", "isPreviewImage",
                "deviceName", "updatedAt"
            )
            val fields = JSONObject().apply {
                put("id", JSONObject().put("stringValue", "transfer"))
                put("command", JSONObject().put("stringValue", "NONE"))
                put("direction", JSONObject().put("stringValue", direction))
                put("fileName", JSONObject().put("stringValue", fileName.take(250)))
                put("mimeType", JSONObject().put("stringValue", mimeType.take(120)))
                put("sizeBytes", JSONObject().put("integerValue", sizeBytes.coerceAtLeast(0L).toString()))
                put("transferSeq", JSONObject().put("integerValue", transferSeq.coerceAtLeast(0L).toString()))
                put("status", JSONObject().put("stringValue", status))
                put("isPreviewImage", JSONObject().put("booleanValue", isPreviewImage))
                put("deviceName", JSONObject().put("stringValue", deviceName))
                put("updatedAt", JSONObject().put("timestampValue", isoFormat.format(Date())))
                if (base64Data != null) {
                    put("base64Data", JSONObject().put("stringValue", base64Data))
                    maskPaths.add("base64Data")
                }
                if (!errorMessage.isNullOrBlank()) {
                    put("errorMessage", JSONObject().put("stringValue", errorMessage.take(380)))
                    maskPaths.add("errorMessage")
                }
            }
            val mask = maskPaths.joinToString("&") { "updateMask.fieldPaths=$it" }
            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId/file_manager/transfer?$mask"
            val req = Request.Builder()
                .url(url)
                .patch(JSONObject().put("fields", fields).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "REST file_manager transfer failed: ${t.message}")
            false
        }
    }

    private fun triggerHapticFeedback(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(80)
                }
            }
        } catch (_: Exception) {
            // Ignore vibration errors if permission or hardware unavailable
        }
    }
}
