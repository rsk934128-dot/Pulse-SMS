package com.example.data.repository

import android.content.Context
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

object QrPairingManager {
    private const val TAG = "QrPairingManager"
    private const val PREFS_NAME = "pulse_pairing_prefs"
    private const val KEY_ACTIVE_SESSION_ID = "active_pairing_session_id"
    private const val KEY_ACTIVE_DEVICE_NAME = "active_device_name"
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

    fun getActiveSessionId(context: Context): String? {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_ACTIVE_SESSION_ID, null)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun saveActiveSession(context: Context, sessionId: String, deviceName: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putString(KEY_ACTIVE_DEVICE_NAME, deviceName)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to persist active pairing session", t)
        }
    }

    fun clearActiveSession(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_ACTIVE_SESSION_ID).apply()
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
        val sessionId = extractSessionId(rawQrText)
        if (sessionId.isBlank()) {
            return PairingResult(false, "", "", "অবৈধ QR কোড ডাটা।")
        }

        val appContext = context.applicationContext ?: context
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

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

                    if (sessionId.contains("instant")) {
                        val instantPayload1 = payload.toMutableMap().apply { put("sessionId", "pulse_pair_instant_one_shot") }
                        val instantPayload2 = payload.toMutableMap().apply { put("sessionId", "pulse_pair_simulation_instant") }
                        fs.collection("pairing_sessions").document("pulse_pair_instant_one_shot").set(instantPayload1, SetOptions.merge())
                        fs.collection("pairing_sessions").document("pulse_pair_simulation_instant").set(instantPayload2, SetOptions.merge())
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
                sessionId = sessionId,
                deviceName = deviceName,
                userId = userId,
                userEmail = userEmail
            )
            if (anyWriteSucceeded && sessionId.contains("instant")) {
                writePairingSessionViaRest(projectId, dbId, "pulse_pair_instant_one_shot", deviceName, userId, userEmail)
                writePairingSessionViaRest(projectId, dbId, "pulse_pair_simulation_instant", deviceName, userId, userEmail)
            }
        }

        // Save active session so future incoming/outgoing SMS sync immediately in real time
        saveActiveSession(appContext, sessionId, deviceName)

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
        val activeSessionId = getActiveSessionId(context) ?: return false
        return syncMessagesToPairedSession(context, listOf(message), activeSessionId) > 0
    }

    /**
     * Batch-pushes SMS messages into `/pairing_sessions/{sessionId}/messages` on the named
     * Firestore database, falling back to Firestore REST API if Firebase SDK is unavailable.
     */
    suspend fun syncMessagesToPairedSession(
        context: Context,
        messages: List<MessageEntity>,
        overrideSessionId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext 0
        val appContext = context.applicationContext ?: context
        val sessionId = overrideSessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext 0
        val dbId = resolveDatabaseId(appContext)

        val targetSessions = if (sessionId.contains("instant")) {
            listOf(sessionId).distinct()
        } else {
            listOf(sessionId)
        }

        val hasFirebase = ensureFirebaseAvailable(appContext)
        val fs = if (hasFirebase) getNamedFirestore(appContext, dbId) else null

        var syncedCount = 0
        if (fs != null) {
            for (targetSession in targetSessions) {
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
                    syncedCount = messages.size
                } catch (e: Exception) {
                    Log.w(TAG, "Batch sync to pairing_sessions/$targetSession failed: ${e.message}")
                }
            }
        }

        if (syncedCount == 0) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            for (msg in messages.take(60)) {
                val docId = buildStableCloudMessageId(msg)
                if (writeMessageViaRest(projectId, dbId, sessionId, docId, msg)) {
                    syncedCount++
                }
            }
        }

        syncedCount
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
        val sessionId = overrideSessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
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

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
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
                    Log.w(TAG, "Firestore notification write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
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
        ok
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
        val sessionId = overrideSessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext 0
        val dbId = resolveDatabaseId(appContext)

        var syncedCount = 0
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
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
                    Log.w(TAG, "Batch sync call_logs failed: ${e.message}")
                }
            }
        }

        if (syncedCount == 0) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
            for (call in callLogs.take(40)) {
                if (writeCallLogViaRest(projectId, dbId, sessionId, call)) {
                    syncedCount++
                }
            }
        }
        syncedCount
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
        val sessionId = overrideSessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(100)
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

        var ok = false
        if (ensureFirebaseAvailable(appContext)) {
            val fs = getNamedFirestore(appContext, dbId)
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
                    Log.w(TAG, "Firestore call_recording write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
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
        ok
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
        val targetSession = sessionId?.takeIf { it.isNotBlank() } ?: getActiveSessionId(appContext) ?: return@withContext false
        val dbId = resolveDatabaseId(appContext)
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(100)

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
                    Log.w(TAG, "Firestore call_recorder state write error: ${e.message}")
                }
            }
        }

        if (!ok) {
            val projectId = resolveStringResource(appContext, "project_id") ?: DEFAULT_PROJECT_ID
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
        ok
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

            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/pairing_sessions/$sessionId" +
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
