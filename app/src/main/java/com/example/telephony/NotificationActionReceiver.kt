package com.example.telephony

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.data.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles interactive actions triggered directly from the SMS Heads-Up Push Notification:
 * - Copying OTP code to clipboard
 * - Marking conversation as read
 * - Replying directly via inline notification input
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationId = intent.getIntExtra(SmsNotificationManager.EXTRA_NOTIFICATION_ID, -1)
        val threadAddress = intent.getStringExtra(SmsNotificationManager.EXTRA_THREAD_ADDRESS)

        when (action) {
            SmsNotificationManager.ACTION_COPY_OTP -> {
                val otpCode = intent.getStringExtra(SmsNotificationManager.EXTRA_OTP_CODE) ?: return
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("OTP Code", otpCode)
                clipboard?.setPrimaryClip(clip)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "OTP কোড কপি করা হয়েছে: $otpCode", Toast.LENGTH_SHORT).show()
                }

                if (notificationId != -1) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
            }

            SmsNotificationManager.ACTION_MARK_READ -> {
                if (!threadAddress.isNullOrBlank()) {
                    val repository = SmsRepository(context)
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            repository.markThreadAsRead(threadAddress)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            if (notificationId != -1) {
                                NotificationManagerCompat.from(context).cancel(notificationId)
                            }
                            pendingResult.finish()
                        }
                    }
                }
            }

            SmsNotificationManager.ACTION_DIRECT_REPLY -> {
                val replyBundle = RemoteInput.getResultsFromIntent(intent)
                val replyText = replyBundle?.getCharSequence(SmsNotificationManager.KEY_TEXT_REPLY)?.toString()?.trim()

                if (!threadAddress.isNullOrBlank() && !replyText.isNullOrBlank()) {
                    val repository = SmsRepository(context)
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            repository.sendMessage(recipient = threadAddress, body = replyText)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "রিপ্লাই পাঠানো হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            if (notificationId != -1) {
                                NotificationManagerCompat.from(context).cancel(notificationId)
                            }
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }
}
