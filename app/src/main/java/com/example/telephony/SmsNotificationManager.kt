package com.example.telephony

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity

/**
 * Handles creation and dispatching of real-time Heads-Up Push Notifications
 * when incoming SMS messages arrive on the device.
 */
object SmsNotificationManager {
    private const val TAG = "SmsNotificationManager"

    const val CHANNEL_ID = "pulse_sms_channel_heads_up"
    private const val CHANNEL_NAME = "Incoming SMS & OTP Alerts"
    private const val CHANNEL_DESC = "Real-time Heads-Up display notifications for incoming SMS messages"

    const val CHANNEL_WEB_MIRROR_ID = "pulse_web_mirrored_notifications"
    private const val CHANNEL_WEB_MIRROR_NAME = "Web & Cross-Device Alerts"
    private const val CHANNEL_WEB_MIRROR_DESC = "Real-time Heads-Up notifications synced from paired Web Companion"

    const val EXTRA_THREAD_ADDRESS = "extra_thread_address"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_OTP_CODE = "extra_otp_code"
    const val EXTRA_OPEN_WEB_SYNC = "extra_open_web_sync"
    const val KEY_TEXT_REPLY = "key_text_reply"

    const val ACTION_MARK_READ = "com.example.telephony.ACTION_MARK_READ"
    const val ACTION_COPY_OTP = "com.example.telephony.ACTION_COPY_OTP"
    const val ACTION_DIRECT_REPLY = "com.example.telephony.ACTION_DIRECT_REPLY"

    /**
     * Initializes the High-Priority notification channel for Heads-Up alerts.
     */
    fun createNotificationChannel(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

                val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
                if (existingChannel == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = CHANNEL_DESC
                        enableLights(true)
                        lightColor = Color.BLUE
                        enableVibration(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                if (notificationManager.getNotificationChannel(CHANNEL_WEB_MIRROR_ID) == null) {
                    val webChannel = NotificationChannel(
                        CHANNEL_WEB_MIRROR_ID,
                        CHANNEL_WEB_MIRROR_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = CHANNEL_WEB_MIRROR_DESC
                        enableLights(true)
                        lightColor = Color.CYAN
                        enableVibration(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                        setShowBadge(true)
                    }
                    notificationManager.createNotificationChannel(webChannel)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createNotificationChannel notice: ${t.message}")
        }
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Dispatches a real-time Heads-Up display notification when a new SMS arrives.
     */
    fun showIncomingSmsNotification(
        context: Context,
        message: MessageEntity,
        conversation: ConversationEntity? = null
    ) {
        // Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Skipping notification.")
                return
            }
        }

        createNotificationChannel(context)

        val senderAddress = message.threadAddress
        val contactDisplayName = conversation?.contactName?.takeIf { it.isNotBlank() } ?: senderAddress
        val notificationId = Math.abs(senderAddress.hashCode())

        // 1. Content Intent: Click notification to open chat thread
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_THREAD_ADDRESS, senderAddress)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Title and body styling
        val title = if (message.otpCode != null) {
            "🔑 OTP: ${message.otpCode} • $contactDisplayName"
        } else {
            contactDisplayName
        }

        val largeAvatarBitmap = createAvatarBitmap(contactDisplayName)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setLargeIcon(largeAvatarBitmap)
            .setContentTitle(title)
            .setContentText(message.body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message.body)
                    .setBigContentTitle(title)
                    .setSummaryText(message.category)
            )
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setColor(0xFF1565C0.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        // 2. Action: Copy OTP Code (if extracted)
        if (message.otpCode != null) {
            val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_COPY_OTP
                putExtra(EXTRA_OTP_CODE, message.otpCode)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 100,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_notification_sms,
                "📋 Copy ${message.otpCode}",
                copyPendingIntent
            )
        }

        // 3. Action: Direct Reply inline
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply...")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DIRECT_REPLY
            putExtra(EXTRA_THREAD_ADDRESS, senderAddress)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 200,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_sms,
            "Reply",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        builder.addAction(replyAction)

        // 4. Action: Mark as Read
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_THREAD_ADDRESS, senderAddress)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 300,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            R.drawable.ic_notification_sms,
            "Mark as Read",
            markReadPendingIntent
        )

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException posting notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}", e)
        }
    }

    /**
     * Displays a high-priority Heads-Up Android notification synced in real time from Website
     * or triggered by the simultaneous dual-device test suite.
     */
    fun showMirroredWebNotification(
        context: Context,
        notifId: String,
        title: String,
        body: String,
        appName: String = "Pulse Web",
        category: String = "WEB_ALERT",
        isTest: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted. Skipping mirrored web notification.")
                return
            }
        }

        createNotificationChannel(context)

        val numericId = 50000 + Math.abs(notifId.hashCode() % 40000)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_WEB_SYNC, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            numericId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prefix = if (isTest) "🧪" else "🌐"
        val displayTitle = "$prefix $title"
        val subText = if (isTest) "$appName • Simultaneous Test" else "$appName • $category"

        val builder = NotificationCompat.Builder(context, CHANNEL_WEB_MIRROR_ID)
            .setSmallIcon(R.drawable.ic_notification_sms)
            .setLargeIcon(createAvatarBitmap(appName))
            .setContentTitle(displayTitle)
            .setContentText(body.ifBlank { displayTitle })
            .setSubText(subText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body.ifBlank { displayTitle })
                    .setBigContentTitle(displayTitle)
                    .setSummaryText(subText)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFF06B6D4.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        try {
            NotificationManagerCompat.from(context).notify(numericId, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException posting mirrored web notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting mirrored web notification: ${e.message}", e)
        }
    }

    /**
     * Cancels active notification for the given conversation thread.
     */
    fun cancelNotification(context: Context, threadAddress: String) {
        val notificationId = Math.abs(threadAddress.hashCode())
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.cancel(notificationId)
    }

    /**
     * Creates a colored circle avatar Bitmap containing the contact initial.
     */
    private fun createAvatarBitmap(name: String): Bitmap {
        val size = 120
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = intArrayOf(
            0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFE53935.toInt()
        )
        val colorIndex = Math.abs(name.hashCode()) % colors.size
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors[colorIndex]
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)

        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val yPos = (size / 2f - (textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(initial, size / 2f, yPos, textPaint)

        return bitmap
    }
}
