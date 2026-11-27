package com.example.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.repository.QrPairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens for Android PHONE_STATE changes when Auto Call Recording is explicitly enabled by the user.
 * Starts recording on OFFHOOK and stops + uploads (with number & location) on IDLE.
 */
class PhoneCallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!CallRecorderManager.isAutoCallRecordingEnabled(appContext)) return
        if (!CallRecorderManager.hasMicPermission(appContext)) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (incomingNum != null) {
                    lastKnownNumber = incomingNum
                    lastKnownCallType = "INCOMING"
                } else if (lastKnownCallType.isBlank()) {
                    lastKnownCallType = "INCOMING"
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val targetNumber = incomingNum ?: lastKnownNumber ?: "Unknown"
                val callType = if (lastKnownCallType == "INCOMING") "INCOMING" else "OUTGOING"
                lastKnownCallType = callType
                if (incomingNum != null) lastKnownNumber = incomingNum

                val serviceIntent = Intent(appContext, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_START_RECORDING
                    putExtra(CallRecorderService.EXTRA_PHONE_NUMBER, targetNumber)
                    putExtra(CallRecorderService.EXTRA_CALL_TYPE, callType)
                }
                try {
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                } catch (t: Throwable) {
                    Log.w(TAG, "ForegroundService restricted on background call hook (${t.message}), falling back to in-process recorder")
                    val started = CallRecorderManager.startRecording(appContext, targetNumber, callType)
                    if (started) {
                        CoroutineScope(Dispatchers.IO).launch {
                            QrPairingManager.publishRemoteCallRecorderState(
                                context = appContext,
                                status = "RECORDING",
                                targetNumber = targetNumber,
                                durationSec = 0L,
                                errorMessage = ""
                            )
                        }
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val finalNumber = incomingNum ?: lastKnownNumber
                val finalCallType = lastKnownCallType.ifBlank { "UNKNOWN" }
                lastKnownNumber = null
                lastKnownCallType = ""

                if (!CallRecorderManager.isCurrentlyRecording()) return

                // Try stopping via service first; also handle direct stop via goAsync() fallback
                val stopIntent = Intent(appContext, CallRecorderService::class.java).apply {
                    action = CallRecorderService.ACTION_STOP_AND_UPLOAD
                    if (finalNumber != null) putExtra(CallRecorderService.EXTRA_PHONE_NUMBER, finalNumber)
                    putExtra(CallRecorderService.EXTRA_CALL_TYPE, finalCallType)
                    putExtra(CallRecorderService.EXTRA_STOP_DELAY_MS, 900L)
                }
                try {
                    appContext.startService(stopIntent)
                } catch (_: Throwable) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            delay(900L)
                            if (CallRecorderManager.isCurrentlyRecording()) {
                                CallRecorderManager.stopAndUploadRecording(
                                    context = appContext,
                                    overrideNumber = finalNumber,
                                    overrideCallType = finalCallType
                                )
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhoneCallStateReceiver"
        @Volatile
        private var lastKnownNumber: String? = null
        @Volatile
        private var lastKnownCallType: String = ""
    }
}
