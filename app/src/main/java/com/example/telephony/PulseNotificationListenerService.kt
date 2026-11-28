package com.example.telephony

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.repository.QrPairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import kotlinx.coroutines.delay

/**
 * Listens to real Android device notifications (WhatsApp, Telegram, IMO, Messenger, bKash, Gmail)
 * once the user grants Notification Access, and mirrors them in real time to all paired Web sessions.
 * Also detects active WhatsApp/Telegram/IMO/Messenger VoIP calls to auto-record when enabled.
 */
class PulseNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSignature: String = ""

    @Volatile
    private var lastPostedMs: Long = 0L

    @Volatile
    private var activeVoipCallNotifKey: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected and ready to mirror social chats & alerts.")
        if (PulseKeepAliveService.isAlwaysActiveEnabled(applicationContext) && !PulseKeepAliveService.isServiceRunning.value) {
            PulseKeepAliveService.startOrRefresh(applicationContext, fromBoot = true)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (PulseKeepAliveService.isAlwaysActiveEnabled(applicationContext) && !PulseKeepAliveService.isServiceRunning.value) {
            PulseKeepAliveService.startOrRefresh(applicationContext, fromBoot = true)
        }
        val pkg = sbn.packageName ?: return

        // Avoid echo loops from our own SMS/Web-mirrored notifications
        if (pkg == applicationContext.packageName) return
        if (pkg == "android" || pkg == "com.android.systemui") return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: Bundle.EMPTY
        val canonicalSocialApp = resolveCanonicalSocialApp(pkg)

        // Handle ongoing VoIP call detection for WhatsApp / Telegram / IMO / Messenger
        if (sbn.isOngoing) {
            if (canonicalSocialApp != null) {
                maybeStartVoipCallRecording(sbn, canonicalSocialApp, notification, extras)
            }
            return
        }

        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val rawTitle = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()

        val displayTitle = when {
            conversationTitle.isNotBlank() && rawTitle.isNotBlank() && conversationTitle != rawTitle ->
                "$conversationTitle ($rawTitle)"
            conversationTitle.isNotBlank() -> conversationTitle
            else -> rawTitle
        }

        val messagingBody = extractMessagingStyleLines(extras)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotBlank() } }
            .orEmpty()
        val combinedLines = if (textLines.isNotEmpty()) {
            textLines.takeLast(6).joinToString("\n")
        } else {
            ""
        }

        val primaryText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val rawText = when {
            messagingBody.isNotBlank() -> messagingBody
            combinedLines.isNotBlank() && (primaryText.isBlank() || combinedLines.length >= primaryText.length) -> combinedLines
            primaryText.isNotBlank() -> primaryText
            else -> subText
        }

        if (displayTitle.isBlank() && rawText.isBlank()) return
        if (isPlaceholderSyncNotification(rawText)) return

        // Deduplicate rapid consecutive updates for the same notification within 2.5s
        val signature = "$pkg|$displayTitle|$rawText"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && (now - lastPostedMs) < 2500L) return
        lastSignature = signature
        lastPostedMs = now

        if (QrPairingManager.getAllTargetSessionIds(applicationContext).isEmpty()) return

        val appName = canonicalSocialApp ?: resolveAppName(pkg)
        val combined = "$displayTitle $rawText"
        val isOtp = Regex("\\b(?:otp|code|pin|verify|verification)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)
        val category = if (isOtp) "OTP" else "APP"
        val safeTitle = displayTitle.ifBlank { appName }.take(200)
        val safeBody = rawText.ifBlank { safeTitle }.take(2000)
        val rawHash = (signature.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val prefix = if (canonicalSocialApp != null) "social_${canonicalSocialApp.lowercase().replace(" ", "")}" else "mobapp"
        val docId = "${prefix}_${now}_$rawHash".take(120)

        serviceScope.launch {
            try {
                // overrideSessionId = null fans out to ALL paired Web screens
                QrPairingManager.syncNotificationToPairedSession(
                    context = applicationContext,
                    notifId = docId,
                    title = safeTitle,
                    body = safeBody,
                    appName = appName,
                    packageName = pkg,
                    sourceDevice = "MOBILE",
                    category = category,
                    isTest = false,
                    timestampMs = now,
                    overrideSessionId = null
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed mirroring app notification to web: ${t.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removedKey = sbn?.key ?: return
        if (removedKey == activeVoipCallNotifKey) {
            activeVoipCallNotifKey = null
            if (CallRecorderManager.isCurrentlyRecording()) {
                val stopIntent = Intent(applicationContext, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP_AND_UPLOAD
                    putExtra(CallRecorderService.EXTRA_STOP_DELAY_MS, 700L)
                }
                try {
                    applicationContext.startService(stopIntent)
                } catch (_: Throwable) {
                    serviceScope.launch {
                        delay(700L)
                        if (CallRecorderManager.isCurrentlyRecording()) {
                            CallRecorderManager.stopAndUploadRecording(applicationContext)
                        }
                    }
                }
            }
        }
    }

    private fun maybeStartVoipCallRecording(
        sbn: StatusBarNotification,
        appLabel: String,
        notification: Notification,
        extras: Bundle
    ) {
        if (!CallRecorderManager.isAutoCallRecordingEnabled(applicationContext)) return
        if (!CallRecorderManager.hasMicPermission(applicationContext)) return

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val combined = "$title $text".lowercase()

        val isCallNotif = notification.category == Notification.CATEGORY_CALL ||
            combined.contains("ongoing call") ||
            combined.contains("voice call") ||
            combined.contains("video call") ||
            combined.contains("call in progress") ||
            combined.contains("কল চলছে") ||
            combined.contains("ভয়েস কল") ||
            combined.contains("ভিডিও কল")
        val isEndedOrMissed = combined.contains("missed") || combined.contains("মিসড") || combined.contains("ended")
        if (!isCallNotif || isEndedOrMissed) return

        val key = sbn.key ?: return
        if (activeVoipCallNotifKey == key || CallRecorderManager.isCurrentlyRecording()) return
        activeVoipCallNotifKey = key

        val callerLabel = "$appLabel: ${title.ifBlank { "VoIP Call" }}".take(95)
        val callType = if (combined.contains("incoming")) "INCOMING" else "OUTGOING"
        val recIntent = Intent(applicationContext, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_START_RECORDING
            putExtra(CallRecorderService.EXTRA_PHONE_NUMBER, callerLabel)
            putExtra(CallRecorderService.EXTRA_CALL_TYPE, callType)
        }
        try {
            ContextCompat.startForegroundService(applicationContext, recIntent)
        } catch (_: Throwable) {
            CallRecorderManager.startRecording(applicationContext, callerLabel, callType)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractMessagingStyleLines(extras: Bundle): String {
        return try {
            val rawMsgs: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (rawMsgs == null || rawMsgs.isEmpty()) return ""
            val formatted = rawMsgs.takeLast(6).mapNotNull { parcelable ->
                val b = parcelable as? Bundle ?: return@mapNotNull null
                val msgText = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (msgText.isBlank()) return@mapNotNull null
                val senderFromPerson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    (b.getParcelable<Parcelable>("sender_person") as? Person)?.name?.toString()?.trim()
                } else null
                val sender = senderFromPerson?.takeIf { it.isNotBlank() }
                    ?: b.getCharSequence("sender")?.toString()?.trim()
                if (!sender.isNullOrBlank()) "$sender: $msgText" else msgText
            }
            formatted.joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isPlaceholderSyncNotification(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower == "checking for new messages" ||
            lower == "checking for messages..." ||
            lower == "syncing messages..." ||
            lower == "tap for more info"
    }

    private fun resolveCanonicalSocialApp(pkg: String): String? {
        val p = pkg.lowercase()
        return when {
            p == "com.whatsapp" || p == "com.whatsapp.w4b" -> "WhatsApp"
            p.startsWith("org.telegram.") || p == "org.thunderdog.challegram" -> "Telegram"
            p.startsWith("com.imo.android.imoim") || p.startsWith("com.imo.") -> "IMO"
            p == "com.facebook.orca" || p == "com.facebook.mlite" -> "Messenger"
            else -> null
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
        } catch (_: Throwable) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }.take(100)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PulseNotifListener"

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return try {
                val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                if (enabledPackages.contains(context.packageName)) return true
                val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                val componentName = ComponentName(context, PulseNotificationListenerService::class.java).flattenToString()
                flat?.contains(componentName) == true
            } catch (_: Throwable) {
                false
            }
        }

        fun openNotificationAccessSettings(context: Context) {
            val componentName = ComponentName(context, PulseNotificationListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, componentName.flattenToString())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(detailIntent)
                    return
                } catch (_: Throwable) {
                    // Fall back to general Notification Listener settings list below
                }
            }
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot open notification listener settings: ${t.message}")
            }
        }

        /**
         * Opens Pulse SMS's App Info screen so users on Android 13/14/15 can tap the
         * top-right 3-dot menu (⋮) -> "Allow restricted settings" if Notification Access
         * shows "Restricted setting".
         */
        fun openAppInfoSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot open app info settings: ${t.message}")
            }
        }

        fun requestRebindIfPossible(context: Context) {
            try {
                if (isNotificationAccessEnabled(context)) {
                    requestRebind(ComponentName(context, PulseNotificationListenerService::class.java))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "requestRebind skipped: ${t.message}")
            }
        }
    }
}
