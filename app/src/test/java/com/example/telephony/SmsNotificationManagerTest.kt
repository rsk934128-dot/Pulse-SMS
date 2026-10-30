package com.example.telephony

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsNotificationManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun createNotificationChannel_registersHighImportanceChannel() {
        SmsNotificationManager.createNotificationChannel(context)

        val channel = notificationManager.getNotificationChannel(SmsNotificationManager.CHANNEL_ID)
        assertNotNull("Channel must be created", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
    }

    @Test
    fun showIncomingSmsNotification_postsNotification() {
        SmsNotificationManager.createNotificationChannel(context)

        val message = MessageEntity(
            id = 1L,
            threadAddress = "+8801712345678",
            senderAddress = "+8801712345678",
            recipientAddress = "Me",
            body = "Your verification code is 849201. Do not share it.",
            timestamp = System.currentTimeMillis(),
            isIncoming = true,
            status = "RECEIVED",
            category = "OTP",
            isRead = false,
            otpCode = "849201"
        )
        val conversation = ConversationEntity(
            address = "+8801712345678",
            contactName = "Bank Security",
            snippet = message.body,
            timestamp = message.timestamp,
            unreadCount = 1,
            category = "OTP"
        )

        SmsNotificationManager.showIncomingSmsNotification(context, message, conversation)

        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        val notifications = shadowNotificationManager.allNotifications
        assertTrue("Notification should be posted", notifications.isNotEmpty())

        val notification = notifications.first()
        val title = notification.extras.getString("android.title")
        assertNotNull(title)
        assertTrue("Title should contain OTP and sender", title!!.contains("849201") && title.contains("Bank Security"))
    }

    @Test
    fun cancelNotification_clearsNotification() {
        val address = "+8801999888777"
        val message = MessageEntity(
            id = 2L,
            threadAddress = address,
            senderAddress = address,
            recipientAddress = "Me",
            body = "Hello there!",
            timestamp = System.currentTimeMillis(),
            isIncoming = true,
            status = "RECEIVED",
            category = "PERSONAL",
            isRead = false
        )

        SmsNotificationManager.showIncomingSmsNotification(context, message)
        val shadowManager = Shadows.shadowOf(notificationManager)
        val id = Math.abs(address.hashCode())

        SmsNotificationManager.cancelNotification(context, address)
        val isCancelled = shadowManager.allNotifications.none { it.extras.getString("android.title")?.contains(address) == true }
        assertTrue("Notification should be cancelled", isCancelled)
    }

    @Test
    fun notificationActionReceiver_copiesOtpCode() {
        val receiver = NotificationActionReceiver()
        val intent = Intent(SmsNotificationManager.ACTION_COPY_OTP).apply {
            putExtra(SmsNotificationManager.EXTRA_OTP_CODE, "554433")
            putExtra(SmsNotificationManager.EXTRA_NOTIFICATION_ID, 12345)
        }

        receiver.onReceive(context, intent)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("554433", clip?.getItemAt(0)?.text?.toString())
    }
}
