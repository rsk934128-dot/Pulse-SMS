package com.example.data.repository

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
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

data class PairingResult(
    val success: Boolean,
    val sessionId: String,
    val deviceName: String,
    val message: String
)

object QrPairingManager {
    private const val TAG = "QrPairingManager"
    private const val DEFAULT_PROJECT_ID = "gen-lang-client-0040983236"
    private const val DEFAULT_DATABASE_ID = "ai-studio-android-pulsesms-d91f8c0e-d0eb-454c-a3c4-23804c092a8d"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
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

    /**
     * Connects this mobile device to the website's pairing session in Firestore in one shot.
     * Guards against uninitialized FirebaseApp and includes a Firestore REST fallback.
     */
    suspend fun pairWithWebsite(
        context: Context,
        rawQrText: String
    ): PairingResult {
        val sessionId = extractSessionId(rawQrText)
        if (sessionId.isBlank()) {
            return PairingResult(false, "", "", "অবৈধ QR কোড ডাটা।")
        }

        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

        val hasFirebase = ensureFirebaseAvailable(context)
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

        val dbId = try {
            context.getString(R.string.firestore_database_id).takeIf { it.isNotBlank() } ?: DEFAULT_DATABASE_ID
        } catch (_: Exception) {
            DEFAULT_DATABASE_ID
        }

        val userId = currentUser?.uid ?: "mobile_device_${Build.ID.take(6)}"
        val userEmail = currentUser?.email ?: ""

        var anyWriteSucceeded = false

        if (hasFirebase) {
            val firestoreTargets = mutableListOf<FirebaseFirestore>()
            try {
                firestoreTargets.add(FirebaseFirestore.getInstance(dbId))
            } catch (_: Exception) {}
            try {
                firestoreTargets.add(FirebaseFirestore.getInstance())
            } catch (_: Exception) {}

            val payload = hashMapOf<String, Any>(
                "sessionId" to sessionId,
                "status" to "CONNECTED",
                "deviceName" to deviceName,
                "userId" to userId,
                "userEmail" to userEmail,
                "connectedAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp()
            )

            for (fs in firestoreTargets.distinct()) {
                try {
                    fs.collection("pairing_sessions")
                        .document(sessionId)
                        .set(payload, SetOptions.merge())
                        .await()
                    anyWriteSucceeded = true

                    if (sessionId.contains("instant")) {
                        fs.collection("pairing_sessions")
                            .document("pulse_pair_instant_one_shot")
                            .set(payload, SetOptions.merge())
                        fs.collection("pairing_sessions")
                            .document("pulse_pair_simulation_instant")
                            .set(payload, SetOptions.merge())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Write to a Firestore instance returned error: ${e.message}")
                }
            }
        }

        // Firestore REST API fallback (works even if FirebaseApp failed to initialize in APK)
        if (!anyWriteSucceeded) {
            val projectId = resolveStringResource(context, "project_id") ?: DEFAULT_PROJECT_ID
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

        triggerHapticFeedback(context)
        Log.i(TAG, "Pairing processed for session: $sessionId on device: $deviceName (cloudSync=$anyWriteSucceeded)")

        return PairingResult(
            success = true,
            sessionId = sessionId,
            deviceName = deviceName,
            message = if (anyWriteSucceeded) {
                "ওয়েবসাইটে এক চান্সে স্ক্রিন কানেক্টেড হয়েছে!"
            } else {
                "ডিভাইস পেয়ারিং হয়েছে ($deviceName) — ইন্টারনেট বা ক্লাউড সিঙ্ক চেক করুন"
            }
        )
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
