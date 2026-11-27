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
import com.example.data.repository.QrPairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens to real Android device notifications (WhatsApp, Messenger, bKash, Gmail, system alerts)
 * once the user grants Notification Access, and mirrors them in real time to the paired Website.
 */
class PulseNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSignature: String = ""

    @Volatile
    private var lastPostedMs: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected and ready to mirror notifications.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListenerService disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Avoid echo loops from our own SMS/Web-mirrored notifications
        if (pkg == applicationContext.packageName) return
        if (pkg == "android" || pkg == "com.android.systemui") return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotBlank() } }
            .orEmpty()
        val combinedLines = if (textLines.isNotEmpty()) {
            textLines.takeLast(3).joinToString(" • ")
        } else {
            ""
        }

        val primaryText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        val rawText = when {
            combinedLines.isNotBlank() && (primaryText.isBlank() || combinedLines.length > primaryText.length) -> combinedLines
            primaryText.isNotBlank() -> primaryText
            else -> subText
        }

        if (rawTitle.isBlank() && rawText.isBlank()) return

        // Deduplicate rapid consecutive updates for the same notification within 3s
        val signature = "$pkg|$rawTitle|$rawText"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && (now - lastPostedMs) < 3000L) return
        lastSignature = signature
        lastPostedMs = now

        val activeSession = QrPairingManager.getActiveSessionId(applicationContext) ?: return

        val appName = resolveAppName(pkg)
        val combined = "$rawTitle $rawText"
        val isOtp = Regex("\\b(?:otp|code|pin|verify|verification)\\b", RegexOption.IGNORE_CASE).containsMatchIn(combined)
        val category = if (isOtp) "OTP" else "APP"
        val safeTitle = rawTitle.ifBlank { appName }.take(200)
        val safeBody = rawText.ifBlank { safeTitle }.take(2000)
        val rawHash = (signature.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val docId = "mobapp_${now}_$rawHash".take(120)

        serviceScope.launch {
            try {
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
                    overrideSessionId = activeSession
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed mirroring app notification to web: ${t.message}")
            }
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
