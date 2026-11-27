package com.example.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.QrPairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground microphone service (Play Policy compliant foregroundServiceType="microphone")
 * that manages active call audio recording and uploads recordings mapped to phone number + location.
 */
class CallRecorderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AND_UPLOAD -> {
                val overrideNum = intent.getStringExtra(EXTRA_PHONE_NUMBER)
                val overrideType = intent.getStringExtra(EXTRA_CALL_TYPE)
                val delayMs = intent.getLongExtra(EXTRA_STOP_DELAY_MS, 0L)
                tickerJob?.cancel()
                serviceScope.launch {
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }
                    if (CallRecorderManager.isCurrentlyRecording()) {
                        CallRecorderManager.stopAndUploadRecording(
                            context = applicationContext,
                            overrideNumber = overrideNum,
                            overrideCallType = overrideType
                        )
                    }
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_START_RECORDING, null -> {
                val number = intent?.getStringExtra(EXTRA_PHONE_NUMBER)?.trim().orEmpty().ifBlank { "Unknown" }
                val callType = intent?.getStringExtra(EXTRA_CALL_TYPE)?.trim().orEmpty().ifBlank { "MANUAL" }
                ensureNotificationChannel()
                startForegroundCompat(buildNotification(number, 0L))

                if (!CallRecorderManager.hasMicPermission(applicationContext)) {
                    serviceScope.launch {
                        QrPairingManager.publishRemoteCallRecorderState(
                            context = applicationContext,
                            status = "PERMISSION_NEEDED",
                            targetNumber = number,
                            errorMessage = "মোবাইলে মাইক্রোফোন পারমিশন অনুমতি দিন"
                        )
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                val started = if (CallRecorderManager.isCurrentlyRecording()) {
                    CallRecorderManager.updateActiveNumber(number, callType)
                    true
                } else {
                    CallRecorderManager.startRecording(applicationContext, number, callType)
                }

                if (!started) {
                    serviceScope.launch {
                        QrPairingManager.publishRemoteCallRecorderState(
                            context = applicationContext,
                            status = "ERROR",
                            targetNumber = number,
                            errorMessage = "মাইক্রোফোন রেকর্ডিং শুরু করা যায়নি"
                        )
                        stopForegroundCompat()
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                serviceScope.launch {
                    QrPairingManager.publishRemoteCallRecorderState(
                        context = applicationContext,
                        status = "RECORDING",
                        targetNumber = number,
                        durationSec = 0L,
                        errorMessage = ""
                    )
                }

                startTicker(number)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTicker(initialNumber: String) {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            var tick = 0
            while (isActive && CallRecorderManager.isCurrentlyRecording()) {
                CallRecorderManager.updateElapsedTick()
                val elapsed = CallRecorderManager.elapsedSec.value
                val num = CallRecorderManager.activeNumber.value.ifBlank { initialNumber }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, buildNotification(num, elapsed))
                if (tick % 4 == 0) {
                    QrPairingManager.publishRemoteCallRecorderState(
                        context = applicationContext,
                        status = "RECORDING",
                        targetNumber = num,
                        durationSec = elapsed,
                        errorMessage = ""
                    )
                }
                tick++
                delay(1000L)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForegroundCompat warning: ${t.message}")
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Call Audio Recorder",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active phone call recording mapped to number and location"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(number: String, elapsedSec: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CallRecorderService::class.java).apply {
            action = ACTION_STOP_AND_UPLOAD
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val min = elapsedSec / 60L
        val sec = elapsedSec % 60L
        val timeText = String.format("%02d:%02d", min, sec)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🎙️ কল রেকর্ড হচ্ছে ($timeText)")
            .setContentText("নাম্বার: $number • সেভ করে ওয়েব ম্যাপে পাঠাতে ট্যাপ করুন")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "⏹️ বন্ধ ও সেভ করুন", stopPi)
            .build()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallRecorderService"
        private const val CHANNEL_ID = "pulse_call_recorder"
        private const val NOTIFICATION_ID = 9402
        const val ACTION_START_RECORDING = "com.example.action.START_CALL_RECORDING"
        const val ACTION_STOP_AND_UPLOAD = "com.example.action.STOP_CALL_RECORDING"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALL_TYPE = "extra_call_type"
        const val EXTRA_STOP_DELAY_MS = "extra_stop_delay_ms"
    }
}
