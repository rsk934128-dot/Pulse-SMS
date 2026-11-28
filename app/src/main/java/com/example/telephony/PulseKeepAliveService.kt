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
import android.graphics.Bitmap
import android.graphics.Matrix
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
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
class PulseKeepAliveService : Service(), LifecycleOwner {

    private val serviceLifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle
        get() = serviceLifecycleRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class SessionListenerBundle(
        val sessionId: String,
        var outgoingRequestListener: ListenerRegistration? = null,
        var sessionNotificationsListener: ListenerRegistration? = null,
        var remoteMicListener: ListenerRegistration? = null,
        var remoteCameraListener: ListenerRegistration? = null,
        var remoteCallRecorderListener: ListenerRegistration? = null,
        var callLogSyncRequestListener: ListenerRegistration? = null,
        var dialRequestListener: ListenerRegistration? = null,
        var fileManagerListener: ListenerRegistration? = null,
        var fileTransferListener: ListenerRegistration? = null,
        var lastHandledCallSyncSeq: Long = 0L,
        var lastProcessedMicCommandSeq: Long = -1L,
        var lastProcessedCameraCommandSeq: Long = -1L,
        var lastProcessedCallRecorderCommandSeq: Long = -1L,
        var lastProcessedFileManagerSeq: Long = -1L,
        var lastProcessedFileTransferSeq: Long = -1L,
        val notificationListenStartedMs: Long = System.currentTimeMillis()
    ) {
        fun removeAll() {
            try { outgoingRequestListener?.remove() } catch (_: Throwable) {}
            try { sessionNotificationsListener?.remove() } catch (_: Throwable) {}
            try { remoteMicListener?.remove() } catch (_: Throwable) {}
            try { remoteCameraListener?.remove() } catch (_: Throwable) {}
            try { remoteCallRecorderListener?.remove() } catch (_: Throwable) {}
            try { callLogSyncRequestListener?.remove() } catch (_: Throwable) {}
            try { dialRequestListener?.remove() } catch (_: Throwable) {}
            try { fileManagerListener?.remove() } catch (_: Throwable) {}
            try { fileTransferListener?.remove() } catch (_: Throwable) {}
        }
    }

    private var repository: SmsRepository? = null
    private var pairingPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var currentBoundSessionId: String? = null
    private var activeCameraSessionId: String? = null
    private var activeMicSessionId: String? = null
    private val sessionListenersMap = mutableMapOf<String, SessionListenerBundle>()
    private var callLogObserver: ContentObserver? = null
    private var presenceHeartbeatJob: Job? = null
    private var ownLocationRequestListener: ListenerRegistration? = null

    private var micStreamJob: Job? = null
    private var micWakeLock: PowerManager.WakeLock? = null
    private var micUploadInFlight = false

    private var boundCamera: Camera? = null
    private var cameraProviderRef: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraWakeLock: PowerManager.WakeLock? = null
    private var cameraWatchdogJob: Job? = null
    private val cameraUploadInFlight = AtomicBoolean(false)
    private val cameraPendingHd = AtomicBoolean(false)
    private val cameraLastUploadAtMs = AtomicLong(0L)
    private var cameraHdSeqCounter = 0

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        _isServiceRunning.value = true
        try {
            serviceLifecycleRegistry.currentState = Lifecycle.State.CREATED
            serviceLifecycleRegistry.currentState = Lifecycle.State.STARTED
            serviceLifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } catch (_: Throwable) {}
        repository = SmsRepository(applicationContext)
        SmsNotificationManager.createNotificationChannel(applicationContext)
        ensureKeepAliveChannel()
        registerPairingWatcher()
        scheduleWatchdogAlarm(applicationContext)
        startDevicePresenceHeartbeat()
        attachLocationRequestWatcher()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false

        if (action == ACTION_STOP_KEEPALIVE) {
            setAlwaysActiveEnabled(applicationContext, false)
            cancelWatchdogAlarm(applicationContext)
            stopBackgroundMicInternal(publishStopped = true)
            stopBackgroundCameraInternal(publishStopped = true)
            CoroutineScope(Dispatchers.IO).launch {
                QrPairingManager.registerDevicePresence(applicationContext, isOnline = false)
            }
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
        startForegroundSafe(
            buildKeepAliveNotification(
                sessionId = currentBoundSessionId,
                micActive = _isBackgroundMicStreaming.value,
                cameraActive = _isBackgroundCameraStreaming.value
            ),
            fromBoot = fromBoot
        )

        refreshAllSessionListeners()

        when (action) {
            ACTION_START_BG_MIC -> startBackgroundMicInternal()
            ACTION_STOP_BG_MIC -> stopBackgroundMicInternal(publishStopped = true)
            ACTION_START_BG_CAMERA -> {
                val lens = intent?.getStringExtra(EXTRA_CAMERA_LENS)?.takeIf { it == "FRONT" || it == "BACK" } ?: "BACK"
                startBackgroundCameraInternal(lens)
            }
            ACTION_STOP_BG_CAMERA -> stopBackgroundCameraInternal(publishStopped = true)
            ACTION_SWITCH_BG_CAMERA -> switchBackgroundCameraLensInternal()
            ACTION_TORCH_BG_CAMERA -> toggleBackgroundTorchInternal()
            ACTION_CAPTURE_HD_BG_CAMERA -> triggerBackgroundHdCaptureInternal()
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
        if (activeInstance === this) {
            activeInstance = null
        }
        _isServiceRunning.value = false
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        try { ownLocationRequestListener?.remove() } catch (_: Throwable) {}
        ownLocationRequestListener = null
        stopBackgroundMicInternal(publishStopped = false)
        stopBackgroundCameraInternal(publishStopped = false)
        try {
            serviceLifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        } catch (_: Throwable) {}
        detachSessionListeners()
        unregisterPairingWatcher()
        if (isAlwaysActiveEnabled(applicationContext)) {
            scheduleImmediateRevive(applicationContext, 2000L)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachLocationRequestWatcher() {
        try { ownLocationRequestListener?.remove() } catch (_: Throwable) {}
        ownLocationRequestListener = QrPairingManager.listenToOwnDeviceLocationRequests(applicationContext) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val primary = QrPairingManager.getActiveSessionId(applicationContext)
                    QrPairingManager.registerDevicePresence(
                        context = applicationContext,
                        overrideSessionId = primary,
                        isOnline = true,
                        includeFreshLocation = true
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private fun startDevicePresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val primary = QrPairingManager.getActiveSessionId(applicationContext)
                    QrPairingManager.registerDevicePresence(
                        context = applicationContext,
                        overrideSessionId = primary,
                        isOnline = true
                    )
                } catch (_: Throwable) {}
                delay(45_000L)
            }
        }
    }

    private fun registerPairingWatcher() {
        val prefs = applicationContext.getSharedPreferences(PAIRING_PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACTIVE_PAIRING_SESSION_ID || key == QrPairingManager.KEY_PAIRED_SESSIONS_JSON) {
                refreshAllSessionListeners()
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
        sessionListenersMap.values.forEach { it.removeAll() }
        sessionListenersMap.clear()
        callLogObserver?.let {
            CallLogSyncHelper.unregisterCallLogObserver(applicationContext, it)
        }
        callLogObserver = null
    }

    private fun refreshAllSessionListeners() {
        val primary = QrPairingManager.getActiveSessionId(applicationContext)
        val desiredSessions = QrPairingManager.getAllTargetSessionIds(applicationContext)
        currentBoundSessionId = primary ?: desiredSessions.firstOrNull()

        val desiredSet = desiredSessions.toSet()
        val toRemove = sessionListenersMap.keys.filter { it !in desiredSet }
        toRemove.forEach { sid ->
            sessionListenersMap.remove(sid)?.removeAll()
        }

        desiredSessions.forEach { sid ->
            if (!sessionListenersMap.containsKey(sid)) {
                attachSingleSessionBundle(sid)
            }
        }

        if (desiredSessions.isNotEmpty() && callLogObserver == null && CallLogSyncHelper.hasCallLogPermission(applicationContext)) {
            callLogObserver = CallLogSyncHelper.registerCallLogObserver(applicationContext) {
                serviceScope.launch(Dispatchers.IO) {
                    val calls = CallLogSyncHelper.readRecentCallLogs(applicationContext, limit = 80)
                    QrPairingManager.syncCallLogsToPairedSession(applicationContext, calls)
                }
            }
        } else if (desiredSessions.isEmpty()) {
            callLogObserver?.let { CallLogSyncHelper.unregisterCallLogObserver(applicationContext, it) }
            callLogObserver = null
        }

        updateNotificationOnly()
    }

    private fun attachSingleSessionBundle(sessionId: String) {
        val bundle = SessionListenerBundle(sessionId = sessionId)
        sessionListenersMap[sessionId] = bundle

        val repo = repository ?: SmsRepository(applicationContext).also { repository = it }

        // 1. Outgoing Web SMS Listener
        bundle.outgoingRequestListener = QrPairingManager.listenToOutgoingWebRequests(
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
                repo.emitSyncLog("WEB_OUTGOING", "ওয়েব/ডিভাইস থেকে '$recipient'-এ ব্যাকগ্রাউন্ডে এসএমএস পাঠানো হয়েছে")
            }
        }

        // 2. Incoming Web Notifications Mirror
        bundle.sessionNotificationsListener = QrPairingManager.listenToSessionNotifications(
            context = applicationContext,
            sessionId = sessionId
        ) { items ->
            items.forEach { item ->
                if (item.sourceDevice == "WEB") {
                    val isRecentOrTest = item.isTest || item.createdAtMs >= (bundle.notificationListenStartedMs - 20_000L)
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
                                val deviceModel = QrPairingManager.getDeviceDisplayName()
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

        // 3. Call Log Sync Requests
        bundle.callLogSyncRequestListener = QrPairingManager.listenToCallLogSyncRequests(
            context = applicationContext,
            sessionId = sessionId
        ) { syncSeq ->
            if (syncSeq > bundle.lastHandledCallSyncSeq) {
                bundle.lastHandledCallSyncSeq = syncSeq
                if (CallLogSyncHelper.hasCallLogPermission(applicationContext)) {
                    serviceScope.launch(Dispatchers.IO) {
                        val calls = CallLogSyncHelper.readRecentCallLogs(applicationContext, limit = 80)
                        QrPairingManager.syncCallLogsToPairedSession(applicationContext, calls, sessionId)
                    }
                }
            }
        }

        // 4. Remote Call Recorder Commands in Background
        bundle.remoteCallRecorderListener = QrPairingManager.listenToRemoteCallRecorderCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = bundle.lastProcessedCallRecorderCommandSeq < 0L
            if (!isFirstSeen && seq <= bundle.lastProcessedCallRecorderCommandSeq) return@listenToRemoteCallRecorderCommands
            if (isFirstSeen) {
                bundle.lastProcessedCallRecorderCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" && remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteCallRecorderCommands
            } else {
                bundle.lastProcessedCallRecorderCommandSeq = seq
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
        bundle.remoteMicListener = QrPairingManager.listenToRemoteMicCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = bundle.lastProcessedMicCommandSeq < 0L
            if (!isFirstSeen && seq <= bundle.lastProcessedMicCommandSeq) return@listenToRemoteMicCommands
            if (isFirstSeen) {
                bundle.lastProcessedMicCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" && remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteMicCommands
            } else {
                bundle.lastProcessedMicCommandSeq = seq
            }

            when (remoteCmd.command) {
                "START" -> {
                    activeMicSessionId = sessionId
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

        // 6. Remote Camera Streaming in Background & Foreground (CameraX ImageAnalysis inside Service)
        bundle.remoteCameraListener = QrPairingManager.listenToRemoteCameraCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = bundle.lastProcessedCameraCommandSeq < 0L
            if (!isFirstSeen && seq <= bundle.lastProcessedCameraCommandSeq) return@listenToRemoteCameraCommands
            if (isFirstSeen) {
                bundle.lastProcessedCameraCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = (remoteCmd.command == "START_BACK" || remoteCmd.command == "START_FRONT") &&
                    remoteCmd.status == "STARTING" && withinWindow
                if (!isFreshStart) return@listenToRemoteCameraCommands
            } else {
                bundle.lastProcessedCameraCommandSeq = seq
            }

            when (remoteCmd.command) {
                "START_BACK", "START_FRONT" -> {
                    activeCameraSessionId = sessionId
                    val lens = if (remoteCmd.command == "START_FRONT") "FRONT" else "BACK"
                    if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startBackgroundCameraInternal(lens)
                    } else {
                        _isBackgroundCameraStreaming.value = false
                        serviceScope.launch {
                            QrPairingManager.publishRemoteCameraState(
                                context = applicationContext,
                                sessionId = sessionId,
                                status = "PERMISSION_NEEDED",
                                lens = lens,
                                torchOn = false,
                                errorMessage = "মোবাইলে ক্যামেরা পারমিশন অনুমতি দিন"
                            )
                        }
                        if (!isUiForeground) {
                            showWakeActionNotification(
                                title = "📷 ওয়েবসাইট থেকে ক্যামেরা রিকোয়েস্ট",
                                body = "ক্যামেরা চালু করতে এখানে ট্যাপ করে পারমিশন দিন।",
                                cameraLensToStart = lens
                            )
                        }
                    }
                }
                "STOP" -> stopBackgroundCameraInternal(publishStopped = true)
                "TOGGLE_TORCH" -> toggleBackgroundTorchInternal()
                "CAPTURE_HD" -> triggerBackgroundHdCaptureInternal()
            }
        }

        // 7. Remote Website / Peer Device Call Dialer Requests (Works 24/7 in Background & Foreground!)
        bundle.dialRequestListener = QrPairingManager.listenToDialRequests(
            context = applicationContext,
            sessionId = sessionId
        ) { req ->
            val ageMs = kotlin.math.abs(System.currentTimeMillis() - req.createdAtMs)
            if (ageMs > 300_000L) return@listenToDialRequests
            if (!tryMarkDialRequestHandled(req.id)) return@listenToDialRequests

            val outcome = CallDialerHelper.placeCall(
                context = applicationContext,
                rawNumber = req.phoneNumber,
                simSlotIndex = req.simSlot,
                fromBackground = !isUiForeground,
                openDialerOnly = false
            )
            val statusToReport = when (outcome.mode) {
                "DIRECT_CALL" -> "DIALING"
                "DIALER_OPENED" -> "OPENED_DIALER"
                "PERMISSION_NEEDED" -> "PERMISSION_NEEDED"
                else -> "FAILED"
            }
            serviceScope.launch {
                QrPairingManager.markDialRequestStatus(
                    context = applicationContext,
                    sessionId = sessionId,
                    requestId = req.id,
                    status = statusToReport,
                    errorMessage = if (outcome.success) "" else outcome.message
                )
                repo.emitSyncLog("WEB_CALL", "📞 রিমোট কল ডায়াল (${req.phoneNumber}): ${outcome.message}")
            }
        }

        // 8. Remote File Manager Directory Browsing & Commands (24/7 Background + Foreground)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val initialSnap = DeviceFileManagerHelper.browseDirectory(applicationContext)
                QrPairingManager.publishFileManagerSnapshot(applicationContext, sessionId, initialSnap)
            } catch (_: Throwable) {}
        }

        bundle.fileManagerListener = QrPairingManager.listenToFileManagerCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = bundle.lastProcessedFileManagerSeq < 0L
            if (!isFirstSeen && seq <= bundle.lastProcessedFileManagerSeq) return@listenToFileManagerCommands
            if (isFirstSeen) {
                bundle.lastProcessedFileManagerSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isActionable = remoteCmd.command != "NONE" && withinWindow
                if (!isActionable) return@listenToFileManagerCommands
            } else {
                bundle.lastProcessedFileManagerSeq = seq
            }

            if (remoteCmd.command == "NONE") return@listenToFileManagerCommands
            serviceScope.launch(Dispatchers.IO) {
                when (remoteCmd.command) {
                    "LIST_ROOTS", "OPEN_DIR", "REFRESH" -> {
                        val snap = DeviceFileManagerHelper.browseDirectory(
                            context = applicationContext,
                            targetRootId = remoteCmd.targetRootId,
                            targetPath = remoteCmd.targetPath
                        )
                        QrPairingManager.publishFileManagerSnapshot(applicationContext, sessionId, snap)
                    }
                    "DELETE_FILE" -> {
                        val fId = remoteCmd.targetFileId
                        if (!fId.isNullOrBlank()) {
                            DeviceFileManagerHelper.deleteFileEntry(
                                context = applicationContext,
                                rootId = remoteCmd.targetRootId,
                                fileId = fId
                            )
                        }
                        val snap = DeviceFileManagerHelper.browseDirectory(
                            context = applicationContext,
                            targetRootId = remoteCmd.targetRootId,
                            targetPath = remoteCmd.targetPath
                        )
                        QrPairingManager.publishFileManagerSnapshot(applicationContext, sessionId, snap)
                    }
                    "CREATE_FOLDER" -> {
                        val folderName = remoteCmd.targetName
                        if (!folderName.isNullOrBlank()) {
                            DeviceFileManagerHelper.createSubfolder(
                                context = applicationContext,
                                rootId = remoteCmd.targetRootId,
                                targetPath = remoteCmd.targetPath,
                                folderName = folderName
                            )
                        }
                        val snap = DeviceFileManagerHelper.browseDirectory(
                            context = applicationContext,
                            targetRootId = remoteCmd.targetRootId,
                            targetPath = remoteCmd.targetPath
                        )
                        QrPairingManager.publishFileManagerSnapshot(applicationContext, sessionId, snap)
                    }
                }
            }
        }

        // 9. Remote File Transfer (Web <-> Phone Preview, Download, and Upload)
        bundle.fileTransferListener = QrPairingManager.listenToFileTransferCommands(
            context = applicationContext,
            sessionId = sessionId
        ) { req ->
            val seq = req.commandSeq
            val isFirstSeen = bundle.lastProcessedFileTransferSeq < 0L
            if (!isFirstSeen && seq <= bundle.lastProcessedFileTransferSeq) return@listenToFileTransferCommands
            if (isFirstSeen) {
                bundle.lastProcessedFileTransferSeq = seq
                val withinWindow = req.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - req.updatedAtMs) <= 180_000L
                val isActionable = (req.command == "FETCH_FILE" || req.command == "PUSH_FILE") && withinWindow
                if (!isActionable) return@listenToFileTransferCommands
            } else {
                bundle.lastProcessedFileTransferSeq = seq
            }

            if (req.command == "FETCH_FILE") {
                val targetFileId = req.targetFileId ?: return@listenToFileTransferCommands
                serviceScope.launch(Dispatchers.IO) {
                    val payload = DeviceFileManagerHelper.prepareFileForWeb(
                        context = applicationContext,
                        rootId = req.targetRootId,
                        fileId = targetFileId
                    )
                    QrPairingManager.publishFileTransferResult(
                        context = applicationContext,
                        sessionId = sessionId,
                        status = payload.status,
                        direction = "PHONE_TO_WEB",
                        fileName = payload.fileName,
                        mimeType = payload.mimeType,
                        sizeBytes = payload.sizeBytes,
                        base64Data = payload.base64Data,
                        transferSeq = seq,
                        isPreviewImage = payload.isPreviewImage,
                        errorMessage = payload.errorMessage
                    )
                }
            } else if (req.command == "PUSH_FILE" && !req.base64Data.isNullOrBlank()) {
                serviceScope.launch(Dispatchers.IO) {
                    val res = DeviceFileManagerHelper.saveWebUploadedFile(
                        context = applicationContext,
                        targetRootId = req.targetRootId,
                        targetPath = req.targetPath,
                        fileName = req.fileName,
                        mimeType = req.mimeType,
                        base64Data = req.base64Data
                    )
                    QrPairingManager.publishFileTransferResult(
                        context = applicationContext,
                        sessionId = sessionId,
                        status = if (res.success) "SAVED" else "ERROR",
                        direction = "WEB_TO_PHONE",
                        fileName = res.savedName,
                        mimeType = req.mimeType,
                        sizeBytes = 0L,
                        base64Data = null,
                        transferSeq = seq,
                        isPreviewImage = false,
                        errorMessage = if (res.success) null else res.message
                    )
                    val refreshed = DeviceFileManagerHelper.browseDirectory(
                        context = applicationContext,
                        targetRootId = req.targetRootId,
                        targetPath = req.targetPath
                    )
                    QrPairingManager.publishFileManagerSnapshot(applicationContext, sessionId, refreshed)
                }
            }
        }
    }

    private fun startBackgroundCameraInternal(targetLens: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val alreadyRunningSameLens = _isBackgroundCameraStreaming.value &&
            _backgroundCameraLens.value == targetLens &&
            boundCamera != null
        if (alreadyRunningSameLens) {
            return
        }

        _backgroundCameraLens.value = targetLens
        if (targetLens == "FRONT") {
            _backgroundCameraTorchOn.value = false
        }
        _backgroundCameraFrameCount.value = 0
        _isBackgroundCameraStreaming.value = true

        // Promote FGS notification + camera type while running
        startForegroundSafe(
            buildKeepAliveNotification(
                sessionId = currentBoundSessionId,
                micActive = _isBackgroundMicStreaming.value,
                cameraActive = true
            ),
            fromBoot = false
        )
        acquireCameraWakeLock()

        try {
            if (serviceLifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
                serviceLifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
        } catch (_: Throwable) {}

        serviceScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = applicationContext,
                sessionId = activeCameraSessionId ?: currentBoundSessionId,
                status = "ACTIVE",
                lens = targetLens,
                torchOn = _backgroundCameraTorchOn.value,
                errorMessage = ""
            )
            repository?.emitSyncLog("REMOTE_CAM", "📷 ব্যাকগ্রাউন্ড ও ওয়েবসাইট থেকে মোবাইল ক্যামেরা ($targetLens) চালু হয়েছে")
        }

        bindBackgroundCameraX(targetLens)
        startCameraFrameWatchdog(targetLens)
    }

    private fun bindBackgroundCameraX(targetLens: String) {
        try {
            val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
            providerFuture.addListener({
                if (!_isBackgroundCameraStreaming.value) return@addListener
                try {
                    val cameraProvider = providerFuture.get()
                    cameraProviderRef = cameraProvider

                    val preferredSelector = if (targetLens == "FRONT") {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    val selector = when {
                        cameraProvider.hasCamera(preferredSelector) -> preferredSelector
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw IllegalStateException("ডিভাইসে কোনো ক্যামেরা পাওয়া যায়নি")
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
                        cameraExecutor = Executors.newSingleThreadExecutor()
                    }
                    val exec = cameraExecutor ?: return@addListener

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(exec) { imageProxy ->
                                processCameraFrameForBackgroundStream(
                                    imageProxy = imageProxy,
                                    isFront = _backgroundCameraLens.value == "FRONT"
                                )
                            }
                        }

                    cameraProvider.unbindAll()
                    val cam = cameraProvider.bindToLifecycle(
                        this@PulseKeepAliveService,
                        selector,
                        imageAnalysis
                    )
                    boundCamera = cam
                    try {
                        cam.cameraControl.enableTorch(_backgroundCameraTorchOn.value && targetLens == "BACK")
                    } catch (_: Throwable) {}

                    cam.cameraInfo.cameraState.observe(this@PulseKeepAliveService) { state ->
                        val camError = state.error
                        if (camError != null && _isBackgroundCameraStreaming.value) {
                            val code = camError.code
                            Log.w(TAG, "Background CameraState error code=$code")
                            if (code == CameraState.ERROR_CAMERA_DISABLED ||
                                code == CameraState.ERROR_CAMERA_FATAL_ERROR ||
                                code == CameraState.ERROR_CAMERA_IN_USE ||
                                code == CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED
                            ) {
                                handleBackgroundCameraBlocked(
                                    targetLens = _backgroundCameraLens.value,
                                    reason = if (code == CameraState.ERROR_CAMERA_DISABLED) {
                                        "অ্যান্ড্রয়েড সিস্টেম ব্যাকগ্রাউন্ড ক্যামেরা আটকে দিয়েছে — অ্যাপ খুলে ক্যামেরা চালু করুন"
                                    } else {
                                        "ক্যামেরা অন্য অ্যাপে ব্যবহার হচ্ছে বা ব্যাকগ্রাউন্ডে ব্লক করা আছে"
                                    }
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Background CameraX bind failed: ${t.message}", t)
                    handleBackgroundCameraBlocked(
                        targetLens = targetLens,
                        reason = t.localizedMessage ?: "ব্যাকগ্রাউন্ড ক্যামেরা চালু করা যায়নি"
                    )
                }
            }, ContextCompat.getMainExecutor(applicationContext))
        } catch (t: Throwable) {
            Log.e(TAG, "CameraProvider background init failed: ${t.message}", t)
            handleBackgroundCameraBlocked(
                targetLens = targetLens,
                reason = t.localizedMessage ?: "CameraProvider error"
            )
        }
    }

    private fun startCameraFrameWatchdog(targetLens: String) {
        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = serviceScope.launch {
            delay(4500L)
            if (_isBackgroundCameraStreaming.value && _backgroundCameraFrameCount.value == 0 && !isUiForeground) {
                Log.w(TAG, "Background camera produced 0 frames after 4.5s; posting tap-to-open fallback notification")
                showWakeActionNotification(
                    title = "📷 ক্যামেরা খুলতে ট্যাপ করুন",
                    body = "ব্যাকগ্রাউন্ডে ক্যামেরা শুরু হতে সময় নিচ্ছে — এখানে ট্যাপ করে ওপেন করুন।",
                    cameraLensToStart = targetLens
                )
            }
        }
    }

    private fun handleBackgroundCameraBlocked(targetLens: String, reason: String) {
        stopBackgroundCameraInternal(publishStopped = false)
        serviceScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = applicationContext,
                sessionId = currentBoundSessionId,
                status = "ERROR",
                lens = targetLens,
                torchOn = false,
                errorMessage = reason
            )
        }
        if (!isUiForeground) {
            showWakeActionNotification(
                title = "📷 ক্যামেরা খুলতে এখানে ট্যাপ করুন",
                body = reason,
                cameraLensToStart = targetLens
            )
        }
    }

    private fun processCameraFrameForBackgroundStream(
        imageProxy: ImageProxy,
        isFront: Boolean
    ) {
        try {
            val isHdRequested = cameraPendingHd.compareAndSet(true, false)
            val now = System.currentTimeMillis()
            val prev = cameraLastUploadAtMs.get()
            if (!isHdRequested && (now - prev) < 420L) {
                return
            }
            cameraLastUploadAtMs.set(now)

            val rawBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()

            val matrix = Matrix().apply {
                if (rotation != 0f) postRotate(rotation)
                if (isFront) postScale(-1f, 1f)
            }

            val orientedBitmap = if (rotation != 0f || isFront) {
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }
            if (orientedBitmap !== rawBitmap) rawBitmap.recycle()

            val maxDim = if (isHdRequested) 1200 else 460
            val quality = if (isHdRequested) 82 else 56

            val scale = minOf(
                maxDim.toFloat() / orientedBitmap.width.coerceAtLeast(1),
                maxDim.toFloat() / orientedBitmap.height.coerceAtLeast(1),
                1f
            )
            val targetW = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)

            val finalBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(orientedBitmap, targetW, targetH, true).also {
                    if (it !== orientedBitmap) orientedBitmap.recycle()
                }
            } else {
                orientedBitmap
            }

            val baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val w = finalBitmap.width
            val h = finalBitmap.height
            finalBitmap.recycle()

            val jpegBytes = baos.toByteArray()
            if (isUiForeground) {
                _backgroundCameraPreviewJpeg.value = jpegBytes
            } else if (_backgroundCameraPreviewJpeg.value != null) {
                _backgroundCameraPreviewJpeg.value = null
            }

            if (cameraUploadInFlight.get() && !isHdRequested) {
                return
            }
            cameraUploadInFlight.set(true)

            val nextSeq = _backgroundCameraFrameCount.value + 1
            _backgroundCameraFrameCount.value = nextSeq
            if (isHdRequested) {
                cameraHdSeqCounter++
            }
            val hdSeqVal = if (isHdRequested) cameraHdSeqCounter else null
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$b64"
            val lensVal = _backgroundCameraLens.value
            val torchVal = _backgroundCameraTorchOn.value

            serviceScope.launch(Dispatchers.IO) {
                try {
                    QrPairingManager.publishRemoteCameraState(
                        context = applicationContext,
                        sessionId = activeCameraSessionId ?: currentBoundSessionId,
                        status = "ACTIVE",
                        lens = lensVal,
                        torchOn = torchVal,
                        frameBase64 = if (!isHdRequested) dataUri else null,
                        hdFrameBase64 = if (isHdRequested) dataUri else null,
                        frameSeq = nextSeq,
                        hdSeq = hdSeqVal,
                        width = w,
                        height = h,
                        errorMessage = ""
                    )
                } finally {
                    cameraUploadInFlight.set(false)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Background camera frame skipped: ${t.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun switchBackgroundCameraLensInternal() {
        val nextLens = if (_backgroundCameraLens.value == "BACK") "FRONT" else "BACK"
        _backgroundCameraLens.value = nextLens
        if (nextLens == "FRONT") {
            _backgroundCameraTorchOn.value = false
        }
        serviceScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = applicationContext,
                sessionId = currentBoundSessionId,
                lens = nextLens,
                torchOn = _backgroundCameraTorchOn.value
            )
        }
        if (_isBackgroundCameraStreaming.value) {
            bindBackgroundCameraX(nextLens)
        }
    }

    private fun toggleBackgroundTorchInternal() {
        if (_backgroundCameraLens.value != "BACK") return
        val nextTorch = !_backgroundCameraTorchOn.value
        _backgroundCameraTorchOn.value = nextTorch
        try {
            boundCamera?.cameraControl?.enableTorch(nextTorch)
        } catch (_: Throwable) {}
        serviceScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = applicationContext,
                sessionId = currentBoundSessionId,
                torchOn = nextTorch
            )
        }
    }

    private fun triggerBackgroundHdCaptureInternal() {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        cameraPendingHd.set(true)
        if (!_isBackgroundCameraStreaming.value) {
            startBackgroundCameraInternal(_backgroundCameraLens.value)
        }
    }

    private fun stopBackgroundCameraInternal(publishStopped: Boolean) {
        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = null
        cameraPendingHd.set(false)
        cameraUploadInFlight.set(false)
        _isBackgroundCameraStreaming.value = false
        _backgroundCameraTorchOn.value = false
        _backgroundCameraPreviewJpeg.value = null
        try {
            boundCamera?.cameraControl?.enableTorch(false)
        } catch (_: Throwable) {}
        boundCamera = null
        try {
            cameraProviderRef?.unbindAll()
        } catch (_: Throwable) {
            try {
                val future = ProcessCameraProvider.getInstance(applicationContext)
                if (future.isDone) {
                    future.get()?.unbindAll()
                }
            } catch (_: Throwable) {}
        }
        try {
            cameraExecutor?.shutdown()
        } catch (_: Throwable) {}
        cameraExecutor = null
        releaseCameraWakeLock()
        updateNotificationOnly()
        if (publishStopped) {
            serviceScope.launch {
                QrPairingManager.publishRemoteCameraState(
                    context = applicationContext,
                    sessionId = currentBoundSessionId,
                    status = "STOPPED",
                    torchOn = false
                )
            }
        }
    }

    private fun acquireCameraWakeLock() {
        try {
            if (cameraWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                cameraWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseSMS:KeepAliveCameraLock")
                    ?.apply { setReferenceCounted(false) }
            }
            if (cameraWakeLock?.isHeld == false) {
                cameraWakeLock?.acquire(30 * 60 * 1000L) // Safety timeout 30 min
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Camera WakeLock acquire skipped: ${t.message}")
        }
    }

    private fun releaseCameraWakeLock() {
        try {
            if (cameraWakeLock?.isHeld == true) {
                cameraWakeLock?.release()
            }
        } catch (_: Throwable) {}
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
        startForegroundSafe(
            buildKeepAliveNotification(
                sessionId = currentBoundSessionId,
                micActive = true,
                cameraActive = _isBackgroundCameraStreaming.value
            ),
            fromBoot = false
        )

        _isBackgroundMicStreaming.value = true
        _backgroundMicChunkCount.value = 0
        _backgroundMicAudioLevel.value = 0

        acquireMicWakeLock()

        serviceScope.launch {
            QrPairingManager.publishRemoteMicState(
                context = applicationContext,
                sessionId = activeMicSessionId ?: currentBoundSessionId,
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
                                            sessionId = activeMicSessionId ?: currentBoundSessionId,
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
        val hasCamera = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !fromBoot) {
            var fgTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasMic) fgTypes = fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasCamera) fgTypes = fgTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

            if (fgTypes != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
                try {
                    startForeground(NOTIFICATION_ID, notification, fgTypes)
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "Combined FGS ($fgTypes) fallback: ${t.message}")
                }
            }
            if (hasMic) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "dataSync|microphone FGS fallback: ${t.message}")
                }
            }
            if (hasCamera) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "dataSync|camera FGS fallback: ${t.message}")
                }
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
            nm.notify(
                NOTIFICATION_ID,
                buildKeepAliveNotification(
                    sessionId = currentBoundSessionId,
                    micActive = _isBackgroundMicStreaming.value,
                    cameraActive = _isBackgroundCameraStreaming.value
                )
            )
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
                    description = "Keeps SMS, Call Logs, Notifications, Camera, Mic & Call Recorder active in background"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildKeepAliveNotification(
        sessionId: String?,
        micActive: Boolean,
        cameraActive: Boolean = false
    ): Notification {
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

        val title = when {
            cameraActive && micActive -> "📷🎙️ Pulse লাইভ ক্যামেরা ও মাইক চালু"
            cameraActive -> "📷 Pulse লাইভ ক্যামেরা ব্যাকগ্রাউন্ডে চালু"
            micActive -> "🎙️ Pulse লাইভ মাইক ও ব্যাকগ্রাউন্ড চালু"
            else -> "🟢 Pulse SMS ব্যাকগ্রাউন্ডে সবসময় সক্রিয়"
        }
        val connectedCount = sessionListenersMap.size.coerceAtLeast(if (sessionId != null) 1 else 0)
        val text = when {
            cameraActive -> "অ্যাপের বাইরে থেকেও ওয়েবসাইটে লাইভ ক্যামেরা ও সিঙ্ক চলছে"
            micActive -> "অ্যাপ বন্ধ থাকলেও ওয়েবসাইটে লাইভ মাইক্রোফোন ও সিঙ্ক চলছে"
            connectedCount > 1 -> "$connectedCount টি ওয়েব/ডিভাইস সেশন একসাথে লাইভ সংযুক্ত আছে"
            sessionId != null -> "ওয়েব পেয়ারিং, ক্যামেরা, মাইক, এসএমএস ও কল রেকর্ডিং লাইভ আছে"
            else -> "২৪/৭ ব্যাকগ্রাউন্ড সার্ভিস চালু • পেয়ার করার জন্য প্রস্তুত"
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
        const val ACTION_START_BG_CAMERA = "com.example.action.START_BG_CAMERA"
        const val ACTION_STOP_BG_CAMERA = "com.example.action.STOP_BG_CAMERA"
        const val ACTION_SWITCH_BG_CAMERA = "com.example.action.SWITCH_BG_CAMERA"
        const val ACTION_TORCH_BG_CAMERA = "com.example.action.TORCH_BG_CAMERA"
        const val ACTION_CAPTURE_HD_BG_CAMERA = "com.example.action.CAPTURE_HD_BG_CAMERA"
        const val EXTRA_FROM_BOOT = "extra_from_boot"
        const val EXTRA_START_REMOTE_CAMERA_LENS = "extra_start_remote_camera_lens"
        const val EXTRA_CAMERA_LENS = "extra_camera_lens"

        @Volatile
        private var activeInstance: PulseKeepAliveService? = null

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isBackgroundMicStreaming = MutableStateFlow(false)
        val isBackgroundMicStreaming: StateFlow<Boolean> = _isBackgroundMicStreaming.asStateFlow()

        private val _backgroundMicChunkCount = MutableStateFlow(0)
        val backgroundMicChunkCount: StateFlow<Int> = _backgroundMicChunkCount.asStateFlow()

        private val _backgroundMicAudioLevel = MutableStateFlow(0)
        val backgroundMicAudioLevel: StateFlow<Int> = _backgroundMicAudioLevel.asStateFlow()

        private val _isBackgroundCameraStreaming = MutableStateFlow(false)
        val isBackgroundCameraStreaming: StateFlow<Boolean> = _isBackgroundCameraStreaming.asStateFlow()

        private val _backgroundCameraLens = MutableStateFlow("BACK")
        val backgroundCameraLens: StateFlow<String> = _backgroundCameraLens.asStateFlow()

        private val _backgroundCameraTorchOn = MutableStateFlow(false)
        val backgroundCameraTorchOn: StateFlow<Boolean> = _backgroundCameraTorchOn.asStateFlow()

        private val _backgroundCameraFrameCount = MutableStateFlow(0)
        val backgroundCameraFrameCount: StateFlow<Int> = _backgroundCameraFrameCount.asStateFlow()

        private val _backgroundCameraPreviewJpeg = MutableStateFlow<ByteArray?>(null)
        val backgroundCameraPreviewJpeg: StateFlow<ByteArray?> = _backgroundCameraPreviewJpeg.asStateFlow()

        @Volatile
        var isUiForeground: Boolean = false
            private set

        private val processedWebRequestIds = Collections.synchronizedSet(mutableSetOf<String>())
        private val processedIncomingNotifIds = Collections.synchronizedSet(mutableSetOf<String>())
        private val processedDialRequestIds = Collections.synchronizedSet(mutableSetOf<String>())

        fun setUiForeground(inForeground: Boolean) {
            isUiForeground = inForeground
        }

        fun tryMarkWebRequestHandled(requestId: String): Boolean = processedWebRequestIds.add(requestId)
        fun tryMarkIncomingNotifHandled(notifId: String): Boolean = processedIncomingNotifIds.add(notifId)
        fun tryMarkDialRequestHandled(requestId: String): Boolean = processedDialRequestIds.add(requestId)

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

        fun startBackgroundCamera(context: Context, lens: String = "BACK"): Boolean {
            val appContext = context.applicationContext ?: context
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_START_BG_CAMERA
                putExtra(EXTRA_FROM_BOOT, false)
                putExtra(EXTRA_CAMERA_LENS, lens)
            }
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun stopBackgroundCamera(context: Context) {
            val appContext = context.applicationContext ?: context
            activeInstance?.let { inst ->
                try {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        inst.stopBackgroundCameraInternal(publishStopped = true)
                        return
                    }
                } catch (_: Throwable) {}
            }
            if (!_isServiceRunning.value) {
                _isBackgroundCameraStreaming.value = false
                _backgroundCameraTorchOn.value = false
                _backgroundCameraPreviewJpeg.value = null
                return
            }
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_STOP_BG_CAMERA
            }
            try {
                appContext.startService(intent)
            } catch (_: Throwable) {}
        }

        fun switchBackgroundCameraLens(context: Context) {
            val appContext = context.applicationContext ?: context
            if (!_isServiceRunning.value) return
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_SWITCH_BG_CAMERA
            }
            try {
                appContext.startService(intent)
            } catch (_: Throwable) {}
        }

        fun toggleBackgroundCameraTorch(context: Context) {
            val appContext = context.applicationContext ?: context
            if (!_isServiceRunning.value) return
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_TORCH_BG_CAMERA
            }
            try {
                appContext.startService(intent)
            } catch (_: Throwable) {}
        }

        fun captureHdBackgroundCamera(context: Context) {
            val appContext = context.applicationContext ?: context
            if (!_isServiceRunning.value) return
            val intent = Intent(appContext, PulseKeepAliveService::class.java).apply {
                action = ACTION_CAPTURE_HD_BG_CAMERA
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

        suspend fun syncFileManagerSnapshotToSessions(
            context: Context,
            targetRootId: String? = null,
            targetPath: String? = null
        ): DirectoryBrowseResult {
            val appContext = context.applicationContext ?: context
            val snapshot = DeviceFileManagerHelper.browseDirectory(appContext, targetRootId, targetPath)
            val sessions = QrPairingManager.getAllTargetSessionIds(appContext)
            sessions.forEach { sid ->
                try {
                    QrPairingManager.publishFileManagerSnapshot(appContext, sid, snapshot)
                } catch (_: Throwable) {}
            }
            return snapshot
        }
    }
}
