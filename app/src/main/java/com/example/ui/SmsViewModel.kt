package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.remote.CloudMessage
import com.example.data.remote.FirebaseSyncRepository
import com.example.data.repository.MirroredNotificationItem
import com.example.data.repository.ProfileAvatarManager
import com.example.data.repository.ProfileAvatarSelection
import com.example.data.repository.QrPairingManager
import com.example.data.repository.SmsRepository
import com.example.data.repository.WebSyncEvent
import android.content.Intent
import com.example.telephony.CallLogSyncHelper
import com.example.telephony.CallRecorderManager
import com.example.telephony.CallRecorderService
import com.example.telephony.PulseNotificationListenerService
import com.example.telephony.SmsNotificationManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.example.auth.FirebaseAuthenticator
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SmsViewModel(
    application: Application,
    val authenticator: FirebaseAuthenticator
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        FirebaseAuthenticator.getInstance()
    )

    private val repository = SmsRepository(application)
    private val firebaseSyncRepo = FirebaseSyncRepository(application)

    val selectedCategory = MutableStateFlow("ALL")
    val searchQuery = MutableStateFlow("")
    val currentLanguage = MutableStateFlow("BN") // "BN" or "EN"
    val selectedSimSlot = MutableStateFlow(0) // 0 for SIM 1, 1 for SIM 2

    private val _isDefaultSmsApp = MutableStateFlow(false)
    val isDefaultSmsApp: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    private val _hasSmsPermissions = MutableStateFlow(false)
    val hasSmsPermissions: StateFlow<Boolean> = _hasSmsPermissions.asStateFlow()

    private val _isDeviceSmsSyncing = MutableStateFlow(false)
    val isDeviceSmsSyncing: StateFlow<Boolean> = _isDeviceSmsSyncing.asStateFlow()

    private val _currentThreadAddress = MutableStateFlow<String?>(null)
    val currentThreadAddress: StateFlow<String?> = _currentThreadAddress.asStateFlow()

    private val _isWebConnected = MutableStateFlow(true)
    val isWebConnected: StateFlow<Boolean> = _isWebConnected.asStateFlow()

    private val _syncLogs = MutableStateFlow<List<WebSyncEvent>>(emptyList())
    val syncLogs: StateFlow<List<WebSyncEvent>> = _syncLogs.asStateFlow()

    // Firebase Auth & Cloud Sync States
    val currentUser: StateFlow<FirebaseUser?> = authenticator.authStateFlow

    private val _isCloudSyncing = MutableStateFlow(false)
    val isCloudSyncing: StateFlow<Boolean> = _isCloudSyncing.asStateFlow()

    private val _profileAvatar = MutableStateFlow(ProfileAvatarManager.loadSelection(application))
    val profileAvatar: StateFlow<ProfileAvatarSelection> = _profileAvatar.asStateFlow()

    private val _activePairingSessionId = MutableStateFlow(
        QrPairingManager.getActiveSessionId(application)
    )
    val activePairingSessionId: StateFlow<String?> = _activePairingSessionId.asStateFlow()

    private val _mirroredNotifications = MutableStateFlow<List<MirroredNotificationItem>>(emptyList())
    val mirroredNotifications: StateFlow<List<MirroredNotificationItem>> = _mirroredNotifications.asStateFlow()

    private val _latestIncomingWebNotification = MutableStateFlow<MirroredNotificationItem?>(null)
    val latestIncomingWebNotification: StateFlow<MirroredNotificationItem?> = _latestIncomingWebNotification.asStateFlow()

    private val _hasNotificationAccess = MutableStateFlow(
        PulseNotificationListenerService.isNotificationAccessEnabled(application)
    )
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    private val _hasPostNotificationPermission = MutableStateFlow(
        SmsNotificationManager.hasPostNotificationsPermission(application)
    )
    val hasPostNotificationPermission: StateFlow<Boolean> = _hasPostNotificationPermission.asStateFlow()

    // Remote Mobile Camera Stream States (viewed & controlled from paired Website)
    private val _isRemoteCameraActive = MutableStateFlow(false)
    val isRemoteCameraActive: StateFlow<Boolean> = _isRemoteCameraActive.asStateFlow()

    private val _remoteCameraLens = MutableStateFlow("BACK") // "BACK" or "FRONT"
    val remoteCameraLens: StateFlow<String> = _remoteCameraLens.asStateFlow()

    private val _isRemoteTorchOn = MutableStateFlow(false)
    val isRemoteTorchOn: StateFlow<Boolean> = _isRemoteTorchOn.asStateFlow()

    private val _remoteCameraFrameCount = MutableStateFlow(0)
    val remoteCameraFrameCount: StateFlow<Int> = _remoteCameraFrameCount.asStateFlow()

    private val _remoteCameraPermissionNeeded = MutableStateFlow(false)
    val remoteCameraPermissionNeeded: StateFlow<Boolean> = _remoteCameraPermissionNeeded.asStateFlow()

    private val _remoteCameraPendingHdCapture = MutableStateFlow(false)
    val remoteCameraPendingHdCapture: StateFlow<Boolean> = _remoteCameraPendingHdCapture.asStateFlow()

    private val _remoteCameraError = MutableStateFlow<String?>(null)
    val remoteCameraError: StateFlow<String?> = _remoteCameraError.asStateFlow()

    private val _hasCameraPermission = MutableStateFlow(
        ContextCompat.checkSelfPermission(application, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    )
    val hasCameraPermission: StateFlow<Boolean> = _hasCameraPermission.asStateFlow()

    // Call History Web Sync States
    private val _hasCallLogPermission = MutableStateFlow(
        CallLogSyncHelper.hasCallLogPermission(application)
    )
    val hasCallLogPermission: StateFlow<Boolean> = _hasCallLogPermission.asStateFlow()

    private val _callLogPermissionNeeded = MutableStateFlow(false)
    val callLogPermissionNeeded: StateFlow<Boolean> = _callLogPermissionNeeded.asStateFlow()

    private val _syncedCallLogsCount = MutableStateFlow(0)
    val syncedCallLogsCount: StateFlow<Int> = _syncedCallLogsCount.asStateFlow()

    private val _isCallLogSyncing = MutableStateFlow(false)
    val isCallLogSyncing: StateFlow<Boolean> = _isCallLogSyncing.asStateFlow()

    // Remote Mobile Microphone Stream States (listened from paired Website)
    private val _isRemoteMicActive = MutableStateFlow(false)
    val isRemoteMicActive: StateFlow<Boolean> = _isRemoteMicActive.asStateFlow()

    private val _remoteMicChunkCount = MutableStateFlow(0)
    val remoteMicChunkCount: StateFlow<Int> = _remoteMicChunkCount.asStateFlow()

    private val _remoteMicPermissionNeeded = MutableStateFlow(false)
    val remoteMicPermissionNeeded: StateFlow<Boolean> = _remoteMicPermissionNeeded.asStateFlow()

    private val _remoteMicError = MutableStateFlow<String?>(null)
    val remoteMicError: StateFlow<String?> = _remoteMicError.asStateFlow()

    private val _hasMicPermission = MutableStateFlow(
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    // Call Recording & Number-Mapped Location States
    private val _autoCallRecordingEnabled = MutableStateFlow(
        CallRecorderManager.isAutoCallRecordingEnabled(application)
    )
    val autoCallRecordingEnabled: StateFlow<Boolean> = _autoCallRecordingEnabled.asStateFlow()

    val isCallRecordingNow: StateFlow<Boolean> = CallRecorderManager.isRecording
    val callRecordingDurationSec: StateFlow<Long> = CallRecorderManager.elapsedSec
    val callRecordingTargetNumber: StateFlow<String> = CallRecorderManager.activeNumber
    val recordedCallsCount: StateFlow<Int> = CallRecorderManager.uploadedCount

    private val _hasLocationPermission = MutableStateFlow(
        CallRecorderManager.hasLocationPermission(application)
    )
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _callRecorderPermissionNeeded = MutableStateFlow(false)
    val callRecorderPermissionNeeded: StateFlow<Boolean> = _callRecorderPermissionNeeded.asStateFlow()

    private val _recentCallNumbers = MutableStateFlow<List<String>>(emptyList())
    val recentCallNumbers: StateFlow<List<String>> = _recentCallNumbers.asStateFlow()

    private var outgoingRequestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var sessionNotificationsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var remoteCameraListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var remoteMicListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var remoteCallRecorderListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var callLogSyncRequestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var callLogObserver: ContentObserver? = null
    private var fallbackCallRecorderTickerJob: kotlinx.coroutines.Job? = null
    private var pendingAutoCallRecordEnable = false
    private var pendingRemoteCallRecordNumber: String? = null
    private var lastHandledCallSyncSeq: Long = 0L
    private var notificationListenStartedMs: Long = 0L
    private var cameraListenStartedMs: Long = 0L
    private var lastProcessedCameraCommandSeq: Long = -1L
    private var lastProcessedMicCommandSeq: Long = -1L
    private var lastProcessedCallRecorderCommandSeq: Long = -1L
    private var frameUploadInFlight = false
    private var micUploadInFlight = false
    private var hdSequenceCounter = 0
    private val processedWebRequestIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val processedIncomingNotifIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Gemini AI Service and State
    private val aiService = com.example.ai.GeminiAiService.getInstance()

    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies: StateFlow<List<String>> = _smartReplies.asStateFlow()

    private val _aiSummary = MutableStateFlow<String?>(null)
    val aiSummary: StateFlow<String?> = _aiSummary.asStateFlow()

    private val _aiSafetyReport = MutableStateFlow<com.example.ai.SpamSafetyReport?>(null)
    val aiSafetyReport: StateFlow<com.example.ai.SpamSafetyReport?> = _aiSafetyReport.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiErrorMessage = MutableStateFlow<String?>(null)
    val aiErrorMessage: StateFlow<String?> = _aiErrorMessage.asStateFlow()

    init {
        authenticator.ensureInitialized(getApplication())

        CallRecorderManager.getUploadedCount(getApplication())

        // Resume active web pairing session listener & sync if already paired
        _activePairingSessionId.value?.let { savedSessionId ->
            attachOutgoingWebRequestListener(savedSessionId)
            attachSessionNotificationsListener(savedSessionId)
            attachRemoteCameraListener(savedSessionId)
            attachRemoteMicListener(savedSessionId)
            attachRemoteCallRecorderListener(savedSessionId)
            attachCallLogBridge(savedSessionId)
            triggerCloudSyncNow()
        }

        // Track Firebase auth state and sync profile
        viewModelScope.launch {
            authenticator.authStateFlow.collect { user ->
                if (user != null) {
                    repository.emitSyncLog("AUTH", "User authenticated: ${user.email}")
                    try {
                        firebaseSyncRepo.saveUserProfile(
                            displayName = user.displayName ?: "User",
                            email = user.email ?: ""
                        )
                        triggerCloudSyncNow()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.webSyncEvents.collect { event ->
                _syncLogs.value = listOf(event) + _syncLogs.value.take(29)
            }
        }
        viewModelScope.launch {
            repository.emitSyncLog("WEB_SOCKET", "Real-time sync engine initialized on port 5000 (ws://localhost:5000)")
        }
    }

    private fun attachOutgoingWebRequestListener(sessionId: String) {
        outgoingRequestListener?.remove()
        outgoingRequestListener = QrPairingManager.listenToOutgoingWebRequests(
            context = getApplication(),
            sessionId = sessionId
        ) { requestId, recipient, body ->
            if (!processedWebRequestIds.add(requestId)) return@listenToOutgoingWebRequests
            viewModelScope.launch {
                val res = repository.sendMessage(recipient, body, selectedSimSlot.value)
                QrPairingManager.markOutgoingWebRequestStatus(
                    context = getApplication(),
                    sessionId = sessionId,
                    requestId = requestId,
                    status = if (res.isSuccess) "SENT" else "FAILED"
                )
                repository.emitSyncLog("WEB_OUTGOING", "ওয়েব থেকে '$recipient'-এ এসএমএস পাঠানো হয়েছে")
            }
        }
    }

    private fun attachSessionNotificationsListener(sessionId: String) {
        sessionNotificationsListener?.remove()
        notificationListenStartedMs = System.currentTimeMillis()
        sessionNotificationsListener = QrPairingManager.listenToSessionNotifications(
            context = getApplication(),
            sessionId = sessionId
        ) { items ->
            _mirroredNotifications.value = items
            val appContext = getApplication<Application>()
            items.forEach { item ->
                if (item.sourceDevice == "WEB") {
                    val isRecentOrTest = item.isTest || item.createdAtMs >= (notificationListenStartedMs - 20_000L)
                    if (isRecentOrTest &&
                        !QrPairingManager.isWebNotificationSeen(appContext, item.id) &&
                        processedIncomingNotifIds.add(item.id)
                    ) {
                        QrPairingManager.markWebNotificationSeen(appContext, item.id)
                        SmsNotificationManager.showMirroredWebNotification(
                            context = appContext,
                            notifId = item.id,
                            title = item.title,
                            body = item.body,
                            appName = item.appName,
                            category = item.category,
                            isTest = item.isTest
                        )
                        _latestIncomingWebNotification.value = item

                        viewModelScope.launch {
                            repository.emitSyncLog("WEB_NOTIF", "🌐 ওয়েব থেকে নোটিফিকেশন: ${item.title}")
                            if (item.id.startsWith("ping_")) {
                                val ackId = "ack_${item.id}".take(120)
                                val deviceModel = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
                                QrPairingManager.syncNotificationToPairedSession(
                                    context = appContext,
                                    notifId = ackId,
                                    title = "✅ মোবাইল থেকে অটো-রিপ্লাই ভেরিফাইড",
                                    body = "$deviceModel-এ ওয়েব নোটিফিকেশন পৌঁছেছে! দুই ডিভাইসেই সিঙ্ক সক্রিয়।",
                                    appName = "Pulse Android",
                                    packageName = appContext.packageName,
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
    }

    private fun attachRemoteCameraListener(sessionId: String) {
        remoteCameraListener?.remove()
        cameraListenStartedMs = System.currentTimeMillis()
        lastProcessedCameraCommandSeq = -1L
        remoteCameraListener = QrPairingManager.listenToRemoteCameraCommands(
            context = getApplication(),
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedCameraCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedCameraCommandSeq) return@listenToRemoteCameraCommands

            // On initial attach, honor any STARTING command that is recent (allowing clock skew or pending serverTimestamp)
            if (isFirstSeen) {
                lastProcessedCameraCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = (remoteCmd.command == "START_BACK" || remoteCmd.command == "START_FRONT") &&
                    remoteCmd.status == "STARTING" &&
                    withinWindow
                if (!isFreshStart) return@listenToRemoteCameraCommands
            } else {
                lastProcessedCameraCommandSeq = seq
            }

            handleRemoteCameraCommand(sessionId, remoteCmd.command)
        }
    }

    private fun handleRemoteCameraCommand(sessionId: String, command: String) {
        val appContext = getApplication<Application>()
        val hasCamPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when (command) {
            "START_BACK", "START_FRONT" -> {
                val targetLens = if (command == "START_FRONT") "FRONT" else "BACK"
                _remoteCameraLens.value = targetLens
                if (targetLens == "FRONT") _isRemoteTorchOn.value = false
                if (!hasCamPerm) {
                    _isRemoteCameraActive.value = false
                    _remoteCameraPermissionNeeded.value = true
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteCameraState(
                            context = appContext,
                            sessionId = sessionId,
                            status = "PERMISSION_NEEDED",
                            lens = targetLens,
                            torchOn = false,
                            errorMessage = "মোবাইলে ক্যামেরা পারমিশন অনুমতি দিন"
                        )
                        repository.emitSyncLog("REMOTE_CAM", "📷 ওয়েবসাইট থেকে ক্যামেরা রিকোয়েস্ট — মোবাইলে পারমিশন প্রয়োজন")
                    }
                } else {
                    _remoteCameraPermissionNeeded.value = false
                    _remoteCameraError.value = null
                    _isRemoteCameraActive.value = true
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteCameraState(
                            context = appContext,
                            sessionId = sessionId,
                            status = "ACTIVE",
                            lens = targetLens,
                            torchOn = _isRemoteTorchOn.value,
                            errorMessage = ""
                        )
                        repository.emitSyncLog("REMOTE_CAM", "📷 ওয়েবসাইট থেকে মোবাইল ক্যামেরা ($targetLens) ওপেন করা হয়েছে")
                    }
                }
            }
            "STOP" -> {
                stopRemoteCamera(sessionIdOverride = sessionId)
            }
            "TOGGLE_TORCH" -> {
                if (_remoteCameraLens.value == "BACK") {
                    val nextTorch = !_isRemoteTorchOn.value
                    _isRemoteTorchOn.value = nextTorch
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteCameraState(
                            context = appContext,
                            sessionId = sessionId,
                            torchOn = nextTorch
                        )
                    }
                }
            }
            "CAPTURE_HD" -> {
                if (!hasCamPerm) {
                    _remoteCameraPermissionNeeded.value = true
                } else {
                    if (!_isRemoteCameraActive.value) {
                        _isRemoteCameraActive.value = true
                    }
                    _remoteCameraPendingHdCapture.value = true
                }
            }
        }
    }

    fun startRemoteCameraFromMobile(lens: String = "BACK") {
        val appContext = getApplication<Application>()
        val hasCamPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        _remoteCameraLens.value = lens
        if (lens == "FRONT") _isRemoteTorchOn.value = false
        if (!hasCamPerm) {
            _remoteCameraPermissionNeeded.value = true
            return
        }
        _remoteCameraPermissionNeeded.value = false
        _remoteCameraError.value = null
        _isRemoteCameraActive.value = true
        viewModelScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = appContext,
                status = "ACTIVE",
                lens = lens,
                torchOn = _isRemoteTorchOn.value,
                errorMessage = ""
            )
            repository.emitSyncLog("REMOTE_CAM", "📷 মোবাইল থেকে ওয়েবসাইটে লাইভ ক্যামেরা শেয়ার চালু হয়েছে")
        }
    }

    fun stopRemoteCamera(sessionIdOverride: String? = null) {
        _isRemoteCameraActive.value = false
        _isRemoteTorchOn.value = false
        _remoteCameraPendingHdCapture.value = false
        _remoteCameraPermissionNeeded.value = false
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = appContext,
                sessionId = sessionIdOverride,
                status = "STOPPED",
                torchOn = false
            )
            repository.emitSyncLog("REMOTE_CAM", "⏹️ রিমোট ক্যামেরা স্ট্রিম বন্ধ করা হয়েছে")
        }
    }

    fun switchRemoteCameraLens() {
        val nextLens = if (_remoteCameraLens.value == "BACK") "FRONT" else "BACK"
        _remoteCameraLens.value = nextLens
        if (nextLens == "FRONT") _isRemoteTorchOn.value = false
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = appContext,
                lens = nextLens,
                torchOn = _isRemoteTorchOn.value
            )
        }
    }

    fun toggleRemoteCameraTorch() {
        if (_remoteCameraLens.value != "BACK") return
        val nextTorch = !_isRemoteTorchOn.value
        _isRemoteTorchOn.value = nextTorch
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = appContext,
                torchOn = nextTorch
            )
        }
    }

    fun dismissRemoteCameraPermissionPrompt() {
        _remoteCameraPermissionNeeded.value = false
    }

    fun onCameraPermissionResultForRemote(granted: Boolean) {
        _hasCameraPermission.value = granted
        _remoteCameraPermissionNeeded.value = false
        val appContext = getApplication<Application>()
        if (granted) {
            _remoteCameraError.value = null
            _isRemoteCameraActive.value = true
            viewModelScope.launch {
                QrPairingManager.publishRemoteCameraState(
                    context = appContext,
                    status = "ACTIVE",
                    lens = _remoteCameraLens.value,
                    torchOn = _isRemoteTorchOn.value,
                    errorMessage = ""
                )
            }
        } else {
            _isRemoteCameraActive.value = false
            viewModelScope.launch {
                QrPairingManager.publishRemoteCameraState(
                    context = appContext,
                    status = "PERMISSION_NEEDED",
                    errorMessage = "ক্যামেরা পারমিশন ডিনাই করা হয়েছে"
                )
            }
        }
    }

    fun onRemoteCameraFrameCaptured(base64Jpeg: String, width: Int, height: Int, isHd: Boolean) {
        if (!_isRemoteCameraActive.value) return
        if (frameUploadInFlight && !isHd) return
        frameUploadInFlight = true
        val appContext = getApplication<Application>()
        val nextFrameSeq = _remoteCameraFrameCount.value + 1
        _remoteCameraFrameCount.value = nextFrameSeq
        if (isHd) {
            _remoteCameraPendingHdCapture.value = false
            hdSequenceCounter++
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                QrPairingManager.publishRemoteCameraState(
                    context = appContext,
                    status = "ACTIVE",
                    lens = _remoteCameraLens.value,
                    torchOn = _isRemoteTorchOn.value,
                    frameBase64 = if (!isHd) base64Jpeg else null,
                    hdFrameBase64 = if (isHd) base64Jpeg else null,
                    frameSeq = nextFrameSeq,
                    hdSeq = if (isHd) hdSequenceCounter else null,
                    width = width,
                    height = height,
                    errorMessage = ""
                )
            } finally {
                frameUploadInFlight = false
            }
        }
    }

    fun onRemoteCameraHardwareError(error: String) {
        _remoteCameraError.value = error
        _isRemoteCameraActive.value = false
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteCameraState(
                context = appContext,
                status = "ERROR",
                torchOn = false,
                errorMessage = error
            )
        }
    }

    private fun attachRemoteMicListener(sessionId: String) {
        remoteMicListener?.remove()
        lastProcessedMicCommandSeq = -1L
        remoteMicListener = QrPairingManager.listenToRemoteMicCommands(
            context = getApplication(),
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedMicCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedMicCommandSeq) return@listenToRemoteMicCommands

            if (isFirstSeen) {
                lastProcessedMicCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" &&
                    remoteCmd.status == "STARTING" &&
                    withinWindow
                if (!isFreshStart) return@listenToRemoteMicCommands
            } else {
                lastProcessedMicCommandSeq = seq
            }

            handleRemoteMicCommand(sessionId, remoteCmd.command)
        }
    }

    private fun handleRemoteMicCommand(sessionId: String, command: String) {
        val appContext = getApplication<Application>()
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        when (command) {
            "START" -> {
                if (!hasMicPerm) {
                    _isRemoteMicActive.value = false
                    _remoteMicPermissionNeeded.value = true
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteMicState(
                            context = appContext,
                            sessionId = sessionId,
                            status = "PERMISSION_NEEDED",
                            errorMessage = "মোবাইলে মাইক্রোফোন পারমিশন অনুমতি দিন"
                        )
                        repository.emitSyncLog("REMOTE_MIC", "🎙️ ওয়েবসাইট থেকে মাইক্রোফোন রিকোয়েস্ট — মোবাইলে পারমিশন প্রয়োজন")
                    }
                } else {
                    _remoteMicPermissionNeeded.value = false
                    _remoteMicError.value = null
                    _remoteMicChunkCount.value = 0
                    _isRemoteMicActive.value = true
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteMicState(
                            context = appContext,
                            sessionId = sessionId,
                            status = "ACTIVE",
                            sampleRate = 16000,
                            errorMessage = ""
                        )
                        repository.emitSyncLog("REMOTE_MIC", "🎙️ ওয়েবসাইট থেকে মোবাইল মাইক্রোফোন লাইভ অডিও চালু হয়েছে")
                    }
                }
            }
            "STOP" -> {
                stopRemoteMic(sessionIdOverride = sessionId)
            }
        }
    }

    fun startRemoteMicFromMobile() {
        val appContext = getApplication<Application>()
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            _remoteMicPermissionNeeded.value = true
            return
        }
        _remoteMicPermissionNeeded.value = false
        _remoteMicError.value = null
        _remoteMicChunkCount.value = 0
        _isRemoteMicActive.value = true
        viewModelScope.launch {
            QrPairingManager.publishRemoteMicState(
                context = appContext,
                status = "ACTIVE",
                sampleRate = 16000,
                errorMessage = ""
            )
            repository.emitSyncLog("REMOTE_MIC", "🎙️ মোবাইল থেকে ওয়েবসাইটে লাইভ মাইক্রোফোন শেয়ার চালু হয়েছে")
        }
    }

    fun stopRemoteMic(sessionIdOverride: String? = null) {
        _isRemoteMicActive.value = false
        _remoteMicPermissionNeeded.value = false
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteMicState(
                context = appContext,
                sessionId = sessionIdOverride,
                status = "STOPPED",
                audioLevel = 0
            )
            repository.emitSyncLog("REMOTE_MIC", "⏹️ রিমোট মাইক্রোফোন স্ট্রিম বন্ধ করা হয়েছে")
        }
    }

    fun dismissRemoteMicPermissionPrompt() {
        _remoteMicPermissionNeeded.value = false
    }

    fun onMicPermissionResultForRemote(granted: Boolean) {
        _hasMicPermission.value = granted
        _remoteMicPermissionNeeded.value = false
        val appContext = getApplication<Application>()
        if (granted) {
            _remoteMicError.value = null
            _remoteMicChunkCount.value = 0
            _isRemoteMicActive.value = true
            viewModelScope.launch {
                QrPairingManager.publishRemoteMicState(
                    context = appContext,
                    status = "ACTIVE",
                    sampleRate = 16000,
                    errorMessage = ""
                )
            }
        } else {
            _isRemoteMicActive.value = false
            viewModelScope.launch {
                QrPairingManager.publishRemoteMicState(
                    context = appContext,
                    status = "PERMISSION_NEEDED",
                    errorMessage = "মাইক্রোফোন পারমিশন ডিনাই করা হয়েছে"
                )
            }
        }
    }

    fun onRemoteMicChunkCaptured(base64Pcm: String, sampleRate: Int, audioLevel: Int) {
        if (!_isRemoteMicActive.value) return
        if (micUploadInFlight) return
        micUploadInFlight = true
        val appContext = getApplication<Application>()
        val nextChunkSeq = _remoteMicChunkCount.value + 1
        _remoteMicChunkCount.value = nextChunkSeq
        viewModelScope.launch(Dispatchers.IO) {
            try {
                QrPairingManager.publishRemoteMicState(
                    context = appContext,
                    status = "ACTIVE",
                    audioBase64 = base64Pcm,
                    chunkSeq = nextChunkSeq,
                    sampleRate = sampleRate,
                    audioLevel = audioLevel,
                    errorMessage = ""
                )
            } finally {
                micUploadInFlight = false
            }
        }
    }

    fun onRemoteMicHardwareError(error: String) {
        _remoteMicError.value = error
        _isRemoteMicActive.value = false
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            QrPairingManager.publishRemoteMicState(
                context = appContext,
                status = "ERROR",
                audioLevel = 0,
                errorMessage = error
            )
        }
    }

    private fun attachCallLogBridge(sessionId: String) {
        val appContext = getApplication<Application>()
        val granted = CallLogSyncHelper.hasCallLogPermission(appContext)
        _hasCallLogPermission.value = granted

        callLogObserver?.let {
            CallLogSyncHelper.unregisterCallLogObserver(appContext, it)
            callLogObserver = null
        }
        if (granted) {
            callLogObserver = CallLogSyncHelper.registerCallLogObserver(appContext) {
                syncDeviceCallLogsNow(promptIfMissing = false)
            }
        }

        callLogSyncRequestListener?.remove()
        callLogSyncRequestListener = QrPairingManager.listenToCallLogSyncRequests(
            context = appContext,
            sessionId = sessionId
        ) { syncSeq ->
            if (syncSeq > lastHandledCallSyncSeq) {
                lastHandledCallSyncSeq = syncSeq
                syncDeviceCallLogsNow(promptIfMissing = true)
            }
        }

        syncDeviceCallLogsNow(promptIfMissing = false)
    }

    fun syncDeviceCallLogsNow(
        promptIfMissing: Boolean = false,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val activeSession = _activePairingSessionId.value ?: QrPairingManager.getActiveSessionId(appContext)
            val granted = CallLogSyncHelper.hasCallLogPermission(appContext)
            _hasCallLogPermission.value = granted

            if (!granted) {
                if (activeSession != null) {
                    QrPairingManager.publishCallLogPermissionStatus(appContext, activeSession, "NEEDED")
                }
                if (promptIfMissing) {
                    _callLogPermissionNeeded.value = true
                }
                val msg = if (currentLanguage.value == "BN") {
                    "কল হিস্টরি দেখাতে ফোনে কল লগ পারমিশন দিন।"
                } else {
                    "Please grant Call Log permission on your phone."
                }
                onComplete?.invoke(false, msg)
                return@launch
            }

            if (activeSession == null) {
                val msg = if (currentLanguage.value == "BN") {
                    "কল হিস্টরি ওয়েবে দেখতে আগে QR বা ১-ক্লিকে পেয়ার করুন!"
                } else {
                    "Pair with website via QR or 1-Click first!"
                }
                onComplete?.invoke(false, msg)
                return@launch
            }

            if (_isCallLogSyncing.value) return@launch
            _isCallLogSyncing.value = true
            try {
                QrPairingManager.publishCallLogPermissionStatus(appContext, activeSession, "GRANTED")
                val calls = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    CallLogSyncHelper.readRecentCallLogs(appContext, limit = 80)
                }
                val synced = QrPairingManager.syncCallLogsToPairedSession(appContext, calls, activeSession)
                _syncedCallLogsCount.value = synced
                _recentCallNumbers.value = calls.mapNotNull { call ->
                    call.number.trim().takeIf { it.isNotBlank() && it != "Private / Unknown" }
                }.distinct().take(6)
                repository.emitSyncLog("CALL_LOG", "📞 ওয়েবসাইটে $synced টি কল হিস্টরি সিঙ্ক হয়েছে")
                val msg = if (currentLanguage.value == "BN") {
                    "✅ ওয়েবসাইটে $synced টি কল হিস্টরি সিঙ্ক হয়েছে!"
                } else {
                    "✅ Synced $synced call history entries to web!"
                }
                onComplete?.invoke(true, msg)
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Call log sync error"
                onComplete?.invoke(false, msg)
            } finally {
                _isCallLogSyncing.value = false
            }
        }
    }

    fun onCallLogPermissionResult(granted: Boolean) {
        _hasCallLogPermission.value = granted
        _callLogPermissionNeeded.value = false
        val appContext = getApplication<Application>()
        if (granted) {
            val activeSession = _activePairingSessionId.value ?: QrPairingManager.getActiveSessionId(appContext)
            if (activeSession != null && callLogObserver == null) {
                callLogObserver = CallLogSyncHelper.registerCallLogObserver(appContext) {
                    syncDeviceCallLogsNow(promptIfMissing = false)
                }
            }
            syncDeviceCallLogsNow(promptIfMissing = false)
        } else {
            viewModelScope.launch {
                val activeSession = _activePairingSessionId.value ?: QrPairingManager.getActiveSessionId(appContext)
                if (activeSession != null) {
                    QrPairingManager.publishCallLogPermissionStatus(appContext, activeSession, "NEEDED")
                }
            }
        }
    }

    fun dismissCallLogPermissionPrompt() {
        _callLogPermissionNeeded.value = false
    }

    private fun attachRemoteCallRecorderListener(sessionId: String) {
        remoteCallRecorderListener?.remove()
        lastProcessedCallRecorderCommandSeq = -1L
        remoteCallRecorderListener = QrPairingManager.listenToRemoteCallRecorderCommands(
            context = getApplication(),
            sessionId = sessionId
        ) { remoteCmd ->
            val seq = remoteCmd.commandSeq
            val isFirstSeen = lastProcessedCallRecorderCommandSeq < 0L
            if (!isFirstSeen && seq <= lastProcessedCallRecorderCommandSeq) return@listenToRemoteCallRecorderCommands

            if (isFirstSeen) {
                lastProcessedCallRecorderCommandSeq = seq
                val withinWindow = remoteCmd.updatedAtMs == 0L ||
                    kotlin.math.abs(System.currentTimeMillis() - remoteCmd.updatedAtMs) <= 300_000L
                val isFreshStart = remoteCmd.command == "START" &&
                    remoteCmd.status == "STARTING" &&
                    withinWindow
                if (!isFreshStart) return@listenToRemoteCallRecorderCommands
            } else {
                lastProcessedCallRecorderCommandSeq = seq
            }

            handleRemoteCallRecorderCommand(sessionId, remoteCmd.command, remoteCmd.targetNumber)
        }
    }

    private fun handleRemoteCallRecorderCommand(sessionId: String, command: String, targetNumber: String) {
        val appContext = getApplication<Application>()
        when (command) {
            "START" -> {
                val safeTarget = targetNumber.ifBlank { "Manual Recording" }
                if (!CallRecorderManager.hasMicPermission(appContext)) {
                    pendingRemoteCallRecordNumber = safeTarget
                    _callRecorderPermissionNeeded.value = true
                    viewModelScope.launch {
                        QrPairingManager.publishRemoteCallRecorderState(
                            context = appContext,
                            sessionId = sessionId,
                            status = "PERMISSION_NEEDED",
                            targetNumber = safeTarget,
                            errorMessage = "মোবাইলে মাইক্রোফোন ও লোকেশন পারমিশন দিন"
                        )
                        repository.emitSyncLog("CALL_RECORDING", "🎙️ ওয়েবসাইট থেকে কল রেকর্ডিং রিকোয়েস্ট ($safeTarget) — পারমিশন প্রয়োজন")
                    }
                } else {
                    startManualCallRecording(targetNumber = safeTarget, callType = "MANUAL")
                }
            }
            "STOP" -> {
                stopManualCallRecording()
            }
        }
    }

    fun toggleAutoCallRecording(enabled: Boolean): Boolean {
        val appContext = getApplication<Application>()
        if (enabled && (!CallRecorderManager.hasMicPermission(appContext) || !CallRecorderManager.hasPhoneStatePermission(appContext))) {
            pendingAutoCallRecordEnable = true
            _callRecorderPermissionNeeded.value = true
            return false
        }
        CallRecorderManager.setAutoCallRecordingEnabled(appContext, enabled)
        _autoCallRecordingEnabled.value = enabled
        viewModelScope.launch {
            repository.emitSyncLog(
                "CALL_RECORDING",
                if (enabled) "📞 অটো কল রেকর্ডিং ও নাম্বার-ম্যাপ সিঙ্ক চালু হয়েছে"
                else "⏹️ অটো কল রেকর্ডিং বন্ধ করা হয়েছে"
            )
        }
        return true
    }

    fun startManualCallRecording(
        targetNumber: String,
        callType: String = "MANUAL",
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val appContext = getApplication<Application>()
        val safeNumber = targetNumber.trim().ifBlank { "Manual Recording" }.take(100)
        if (!CallRecorderManager.hasMicPermission(appContext)) {
            pendingRemoteCallRecordNumber = safeNumber
            _callRecorderPermissionNeeded.value = true
            onComplete?.invoke(
                false,
                if (currentLanguage.value == "BN") "রেকর্ডিং শুরু করতে মাইক্রোফোন পারমিশন দিন।" else "Grant Microphone permission to record."
            )
            return
        }

        val serviceIntent = Intent(appContext, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_START_RECORDING
            putExtra(CallRecorderService.EXTRA_PHONE_NUMBER, safeNumber)
            putExtra(CallRecorderService.EXTRA_CALL_TYPE, callType)
        }

        var launchedService = false
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
            launchedService = true
        } catch (_: Throwable) {
            launchedService = false
        }

        if (!launchedService) {
            val started = CallRecorderManager.startRecording(appContext, safeNumber, callType)
            if (!started) {
                onComplete?.invoke(false, "রেকর্ডিং শুরু করা যায়নি।")
                return
            }
            startFallbackCallRecorderTicker(appContext, safeNumber)
        }

        viewModelScope.launch {
            QrPairingManager.publishRemoteCallRecorderState(
                context = appContext,
                status = "RECORDING",
                targetNumber = safeNumber,
                durationSec = 0L,
                errorMessage = ""
            )
            repository.emitSyncLog("CALL_RECORDING", "🎙️ কল রেকর্ডিং শুরু হয়েছে ($safeNumber)")
        }
        onComplete?.invoke(
            true,
            if (currentLanguage.value == "BN") "🎙️ রেকর্ডিং শুরু হয়েছে ($safeNumber)" else "🎙️ Recording started ($safeNumber)"
        )
    }

    private fun startFallbackCallRecorderTicker(appContext: Application, safeNumber: String) {
        fallbackCallRecorderTickerJob?.cancel()
        fallbackCallRecorderTickerJob = viewModelScope.launch {
            var tick = 0
            while (CallRecorderManager.isCurrentlyRecording()) {
                CallRecorderManager.updateElapsedTick()
                if (tick % 4 == 0) {
                    QrPairingManager.publishRemoteCallRecorderState(
                        context = appContext,
                        status = "RECORDING",
                        targetNumber = CallRecorderManager.activeNumber.value.ifBlank { safeNumber },
                        durationSec = CallRecorderManager.elapsedSec.value,
                        errorMessage = ""
                    )
                }
                tick++
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    fun stopManualCallRecording(onComplete: ((Boolean, String) -> Unit)? = null) {
        val appContext = getApplication<Application>()
        fallbackCallRecorderTickerJob?.cancel()
        fallbackCallRecorderTickerJob = null
        try {
            appContext.stopService(Intent(appContext, CallRecorderService::class.java))
        } catch (_: Throwable) {
        }
        viewModelScope.launch {
            val result = CallRecorderManager.stopAndUploadRecording(appContext)
            if (result.success) {
                val locText = if (result.hasLocation) "📍 ${result.locationLabel ?: "GPS Mapped"}" else "লোকেশন ছাড়া"
                repository.emitSyncLog(
                    "CALL_RECORDING",
                    "✅ কল রেকর্ডিং (${result.phoneNumber}, ${result.durationSec}s, $locText) ওয়েবসাইটে পাঠানো হয়েছে"
                )
            } else {
                repository.emitSyncLog("CALL_RECORDING", "⚠️ কল রেকর্ডিং আপলোড ত্রুটি: ${result.message}")
            }
            onComplete?.invoke(result.success, result.message)
        }
    }

    fun dismissCallRecorderPermissionPrompt() {
        _callRecorderPermissionNeeded.value = false
        pendingAutoCallRecordEnable = false
        pendingRemoteCallRecordNumber = null
    }

    fun onCallRecorderPermissionsResult(results: Map<String, Boolean>) {
        val appContext = getApplication<Application>()
        _hasMicPermission.value = CallRecorderManager.hasMicPermission(appContext)
        _hasLocationPermission.value = CallRecorderManager.hasLocationPermission(appContext)
        _hasCallLogPermission.value = CallLogSyncHelper.hasCallLogPermission(appContext)
        _callRecorderPermissionNeeded.value = false

        val phoneGranted = CallRecorderManager.hasPhoneStatePermission(appContext)
        if (pendingAutoCallRecordEnable && _hasMicPermission.value && phoneGranted) {
            pendingAutoCallRecordEnable = false
            CallRecorderManager.setAutoCallRecordingEnabled(appContext, true)
            _autoCallRecordingEnabled.value = true
        } else {
            pendingAutoCallRecordEnable = false
        }

        val queuedNumber = pendingRemoteCallRecordNumber
        pendingRemoteCallRecordNumber = null
        if (queuedNumber != null && _hasMicPermission.value) {
            startManualCallRecording(targetNumber = queuedNumber, callType = "MANUAL")
        }

        if (_hasCallLogPermission.value) {
            syncDeviceCallLogsNow(promptIfMissing = false)
        }
    }

    override fun onCleared() {
        outgoingRequestListener?.remove()
        outgoingRequestListener = null
        sessionNotificationsListener?.remove()
        sessionNotificationsListener = null
        remoteCameraListener?.remove()
        remoteCameraListener = null
        remoteMicListener?.remove()
        remoteMicListener = null
        remoteCallRecorderListener?.remove()
        remoteCallRecorderListener = null
        fallbackCallRecorderTickerJob?.cancel()
        fallbackCallRecorderTickerJob = null
        callLogSyncRequestListener?.remove()
        callLogSyncRequestListener = null
        callLogObserver?.let {
            CallLogSyncHelper.unregisterCallLogObserver(getApplication(), it)
        }
        callLogObserver = null
        super.onCleared()
    }

    val totalUnreadCount: StateFlow<Int> = repository.getTotalUnreadCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val conversations: StateFlow<List<ConversationEntity>> = combine(
        selectedCategory,
        searchQuery
    ) { category, query ->
        Pair(category, query)
    }.flatMapLatest { (category, query) ->
        if (query.isNotBlank()) {
            repository.searchConversationsFlow(query)
        } else if (category == "ALL") {
            repository.getActiveConversationsFlow()
        } else if (category == "ARCHIVE") {
            repository.getArchivedConversationsFlow()
        } else {
            repository.getConversationsByCategoryFlow(category)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeThreadMessages: StateFlow<List<MessageEntity>> = _currentThreadAddress.flatMapLatest { address ->
        if (address == null) flowOf(emptyList())
        else repository.getMessagesForThreadFlow(address)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentConversation: StateFlow<ConversationEntity?> = _currentThreadAddress.flatMapLatest { address ->
        if (address == null) flowOf(null)
        else repository.getConversationFlow(address)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun checkDefaultSmsStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _isDefaultSmsApp.value = repository.isDefaultSmsApp()
        }
    }

    fun refreshNotificationBridgeState() {
        val appContext = getApplication<Application>()
        val accessGranted = PulseNotificationListenerService.isNotificationAccessEnabled(appContext)
        _hasNotificationAccess.value = accessGranted
        if (accessGranted) {
            PulseNotificationListenerService.requestRebindIfPossible(appContext)
        }
        _hasPostNotificationPermission.value = SmsNotificationManager.hasPostNotificationsPermission(appContext)
    }

    fun openNotificationAccessSettings() {
        PulseNotificationListenerService.openNotificationAccessSettings(getApplication())
    }

    fun openAppInfoSettings() {
        PulseNotificationListenerService.openAppInfoSettings(getApplication())
    }

    fun dismissWebNotificationBanner() {
        _latestIncomingWebNotification.value = null
    }

    fun sendSimultaneousTestNotification(
        customTitle: String? = null,
        customBody: String? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val now = System.currentTimeMillis()
            val notifId = "mobtest_${now}_${(100..999).random()}"
            val deviceLabel = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
            val title = customTitle?.takeIf { it.isNotBlank() } ?: "🧪 মোবাইল + ওয়েব সেম-টাইম টেস্ট"
            val body = customBody?.takeIf { it.isNotBlank() }
                ?: "$deviceLabel থেকে পাঠানো লাইভ টেস্ট নোটিফিকেশন — মোবাইল ও ওয়েবসাইটে একসাথে প্রদর্শিত!"

            // 1. Trigger immediate real Android Heads-Up Notification on Mobile
            SmsNotificationManager.showMirroredWebNotification(
                context = appContext,
                notifId = notifId,
                title = title,
                body = body,
                appName = "Pulse Test Bridge",
                category = "TEST",
                isTest = true
            )

            // 2. Push to paired Website session simultaneously
            val activeSession = _activePairingSessionId.value ?: QrPairingManager.getActiveSessionId(appContext)
            if (activeSession == null) {
                repository.emitSyncLog("NOTIF_TEST", "📱 মোবাইলে টেস্ট নোটিফিকেশন দেখানো হয়েছে (ওয়েব পেয়ার করা নেই)")
                onComplete(false, if (currentLanguage.value == "BN") "মোবাইলে দেখানো হয়েছে, তবে ওয়েবসাইট আগে QR/১-ক্লিকে পেয়ার করুন!" else "Shown on mobile; pair with website first to mirror!")
                return@launch
            }

            val synced = QrPairingManager.syncNotificationToPairedSession(
                context = appContext,
                notifId = notifId,
                title = title,
                body = body,
                appName = deviceLabel,
                packageName = appContext.packageName,
                sourceDevice = "MOBILE",
                category = "TEST",
                isTest = true,
                timestampMs = now,
                overrideSessionId = activeSession
            )

            if (synced) {
                repository.emitSyncLog("NOTIF_TEST", "✅ সেম-টাইম টেস্ট নোটিফিকেশন মোবাইল ও ওয়েবে পাঠানো হয়েছে!")
                onComplete(true, if (currentLanguage.value == "BN") "✅ মোবাইল ও ওয়েবসাইটে একসাথে নোটিফিকেশন পাঠানো হয়েছে!" else "✅ Sent to both Mobile and Web simultaneously!")
            } else {
                onComplete(false, if (currentLanguage.value == "BN") "ওয়েব ক্লাউডে পাঠাতে ব্যর্থ — ইন্টারনেট চেক করুন।" else "Failed pushing to web cloud; check internet.")
            }
        }
    }

    fun refreshPermissionState() {
        refreshNotificationBridgeState()
        val appContext = getApplication<Application>()
        _hasCameraPermission.value = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        _hasMicPermission.value = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        _hasLocationPermission.value = CallRecorderManager.hasLocationPermission(appContext)
        val callLogGranted = CallLogSyncHelper.hasCallLogPermission(appContext)
        _hasCallLogPermission.value = callLogGranted
        val activeSession = _activePairingSessionId.value ?: QrPairingManager.getActiveSessionId(appContext)
        if (callLogGranted && activeSession != null) {
            if (callLogObserver == null) {
                callLogObserver = CallLogSyncHelper.registerCallLogObserver(appContext) {
                    syncDeviceCallLogsNow(promptIfMissing = false)
                }
            }
            syncDeviceCallLogsNow(promptIfMissing = false)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val granted = com.example.telephony.SmsPermissionManager.hasEssentialSmsPermissions(appContext)
            _hasSmsPermissions.value = granted
            _isDefaultSmsApp.value = repository.isDefaultSmsApp()
            if (granted && !_isDeviceSmsSyncing.value) {
                syncDeviceSms()
            }
        }
    }

    fun syncDeviceSms() {
        viewModelScope.launch {
            _isDeviceSmsSyncing.value = true
            try {
                val count = repository.syncDeviceSmsMessages()
                if (count > 0) {
                    repository.emitSyncLog("SYSTEM_SMS", "Imported $count carrier SMS messages into app")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isDeviceSmsSyncing.value = false
            }
        }
    }

    fun getDefaultSmsIntent() = repository.getDefaultSmsIntent()

    fun setCategory(category: String) {
        selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSimSlot(slot: Int) {
        selectedSimSlot.value = slot
    }

    fun toggleLanguage() {
        currentLanguage.value = if (currentLanguage.value == "BN") "EN" else "BN"
    }

    fun selectPresetAvatar(presetId: String) {
        _profileAvatar.value = ProfileAvatarManager.selectPreset(getApplication(), presetId)
    }

    fun saveCustomAvatarFromUri(uri: Uri, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = ProfileAvatarManager.saveAvatarFromUri(getApplication(), uri)
            result.onSuccess { selection ->
                _profileAvatar.value = selection
                onComplete(true, null)
            }.onFailure { err ->
                onComplete(false, err.localizedMessage)
            }
        }
    }

    fun resetProfileAvatar() {
        _profileAvatar.value = ProfileAvatarManager.resetToDefault(getApplication())
    }

    fun toggleWebConnection() {
        val newState = !_isWebConnected.value
        setWebConnected(newState)
    }

    fun setWebConnected(connected: Boolean) {
        _isWebConnected.value = connected
        viewModelScope.launch {
            repository.emitSyncLog(
                if (connected) "WEB_SOCKET" else "STATUS",
                if (connected) "Web screen connected via Camera QR Scan (Pulse Live Sync Active)"
                else "Web client disconnected from Android bridge"
            )
        }
    }

    fun onQrPairingCompleted(sessionId: String, deviceName: String) {
        if (sessionId.isNotBlank()) {
            QrPairingManager.saveActiveSession(getApplication(), sessionId, deviceName)
            _activePairingSessionId.value = sessionId
            attachOutgoingWebRequestListener(sessionId)
            attachSessionNotificationsListener(sessionId)
            attachRemoteCameraListener(sessionId)
            attachRemoteMicListener(sessionId)
            attachRemoteCallRecorderListener(sessionId)
            attachCallLogBridge(sessionId)
        }
        setWebConnected(true)
        triggerCloudSyncNow()
        viewModelScope.launch {
            repository.emitSyncLog("WEB_PAIRING", "🎉 ওয়েবসাইটের সাথে ডিভাইস কানেক্টেড ও লাইভ এসএমএস, নোটিফিকেশন, ক্যামেরা, মাইক ও কল লগ ব্রিজ সক্রিয় ($deviceName)")
        }
    }

    fun connectToWeb(
        sessionId: String = "pulse_pair_instant_one_shot",
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                repository.emitSyncLog("WEB_PAIRING", "Connecting to website pairing session: $sessionId...")
                val result = com.example.data.repository.QrPairingManager.pairWithWebsite(getApplication(), sessionId)
                if (result.success) {
                    onQrPairingCompleted(result.sessionId, result.deviceName)
                    onComplete(true, result.message)
                } else {
                    repository.emitSyncLog("WEB_PAIRING", "পেয়ারিং ব্যর্থ: ${result.message}")
                    onComplete(false, result.message)
                }
            } catch (e: Exception) {
                repository.emitSyncLog("WEB_PAIRING", "Pairing error: ${e.message}")
                onComplete(false, e.localizedMessage ?: "Failed to connect to web")
            }
        }
    }

    fun openThread(address: String) {
        _currentThreadAddress.value = address
        viewModelScope.launch {
            repository.markThreadAsRead(address)
        }
    }

    fun closeThread() {
        _currentThreadAddress.value = null
        _smartReplies.value = emptyList()
        _aiSummary.value = null
        _aiSafetyReport.value = null
        _aiErrorMessage.value = null
    }

    fun generateSmartRepliesForThread(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        val lastIncoming = messages.lastOrNull { it.isIncoming } ?: messages.last()
        val contextSnippet = messages.takeLast(4).joinToString("\n") { 
            "${if (it.isIncoming) "Sender" else "Me"}: ${it.body}" 
        }

        viewModelScope.launch {
            _isAiLoading.value = true
            _aiErrorMessage.value = null
            try {
                val result = aiService.generateSmartReplies(contextSnippet, lastIncoming.body)
                result.onSuccess { replyResult ->
                    _smartReplies.value = replyResult.suggestions
                    repository.emitSyncLog("GEMINI_AI", "Generated ${replyResult.suggestions.size} smart replies with Gemini 3.8 Flash")
                }.onFailure { err ->
                    _aiErrorMessage.value = err.localizedMessage ?: "Failed to generate smart replies"
                }
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun summarizeThread(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        val threadText = messages.takeLast(15).joinToString("\n") { msg ->
            "${if (msg.isIncoming) msg.senderAddress else "Me"} [${msg.category}]: ${msg.body}"
        }

        viewModelScope.launch {
            _isAiLoading.value = true
            _aiErrorMessage.value = null
            _aiSummary.value = ""
            try {
                aiService.streamThreadSummary(threadText).collect { chunk ->
                    _aiSummary.value = (_aiSummary.value ?: "") + chunk
                }
                repository.emitSyncLog("GEMINI_AI", "Thread summarized via Gemini streaming")
            } catch (e: Exception) {
                _aiErrorMessage.value = e.localizedMessage ?: "Summary failed"
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun analyzeMessageSafety(message: MessageEntity) {
        viewModelScope.launch {
            _isAiLoading.value = true
            _aiErrorMessage.value = null
            try {
                val result = aiService.analyzeMessageSafety(message.senderAddress, message.body)
                result.onSuccess { report ->
                    _aiSafetyReport.value = report
                    repository.emitSyncLog("GEMINI_AI", "Safety check completed: Risk=${report.riskLevel}")
                }.onFailure { err ->
                    _aiErrorMessage.value = err.localizedMessage ?: "Safety check failed"
                }
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun dismissSafetyReport() {
        _aiSafetyReport.value = null
    }

    fun dismissSummary() {
        _aiSummary.value = null
    }

    fun polishDraft(draft: String, tone: String, onResult: (String) -> Unit) {
        if (draft.isBlank()) return
        viewModelScope.launch {
            _isAiLoading.value = true
            _aiErrorMessage.value = null
            val lang = if (currentLanguage.value == "BN") "Bengali" else "English"
            val result = aiService.polishDraftMessage(draft, tone, lang)
            result.onSuccess { polished ->
                onResult(polished)
                repository.emitSyncLog("GEMINI_AI", "Draft polished with Gemini ($tone tone)")
            }.onFailure { err ->
                _aiErrorMessage.value = err.localizedMessage ?: "Draft polish failed"
            }
            _isAiLoading.value = false
        }
    }

    fun sendMessage(recipient: String, text: String, simSlot: Int = selectedSimSlot.value) {
        viewModelScope.launch {
            val result = repository.sendMessage(recipient, text, simSlot)
            if (result.isSuccess && _isWebConnected.value) {
                repository.emitSyncLog("WEB_SOCKET", "Broadcasted OUTGOING SMS to paired Web Client in real time")
            }
        }
    }

    fun simulateIncomingSms(sender: String, body: String) {
        viewModelScope.launch {
            repository.handleIncomingSms(sender, body)
            if (_isWebConnected.value) {
                repository.emitSyncLog("WEB_SOCKET", "Broadcasted INCOMING SMS to paired Web Client in real time")
            }
        }
    }

    fun triggerCloudSyncNow() {
        viewModelScope.launch {
            val activeSession = _activePairingSessionId.value
                ?: com.example.data.repository.QrPairingManager.getActiveSessionId(getApplication())
            val signedInUser = currentUser.value
            if (signedInUser == null && activeSession == null) return@launch

            _isCloudSyncing.value = true
            try {
                repository.emitSyncLog("FIRESTORE", "রিয়েল-টাইম ক্লাউড ও ওয়েব ইনবক্স সিঙ্ক হচ্ছে...")
                // Refresh device inbox first if permission is available
                repository.syncDeviceSmsMessages(syncCloudAfter = false)
                val messages = repository.getRecentMessages(100)

                var pairedSynced = 0
                if (activeSession != null && messages.isNotEmpty()) {
                    pairedSynced = com.example.data.repository.QrPairingManager.syncMessagesToPairedSession(
                        context = getApplication(),
                        messages = messages,
                        overrideSessionId = activeSession
                    )
                }

                var userSynced = 0
                if (signedInUser != null) {
                    try {
                        firebaseSyncRepo.saveUserProfile(
                            displayName = signedInUser.displayName ?: "User",
                            email = signedInUser.email ?: ""
                        )
                        val batchRes = firebaseSyncRepo.syncMessagesBatch(messages)
                        userSynced = batchRes.getOrDefault(0)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val totalReported = maxOf(pairedSynced, userSynced)
                repository.emitSyncLog(
                    "FIRESTORE",
                    "সফলভাবে $totalReported টি এসএমএস ওয়েব ইনবক্স ও ফায়ারবেস ক্লাউডে সিঙ্ক হয়েছে।"
                )
            } catch (e: Exception) {
                repository.emitSyncLog("FIRESTORE", "Cloud sync failed: ${e.message}")
            } finally {
                _isCloudSyncing.value = false
            }
        }
    }

    fun togglePin(address: String, isPinned: Boolean) {
        viewModelScope.launch {
            repository.setPinned(address, !isPinned)
        }
    }

    fun toggleArchive(address: String, isArchived: Boolean) {
        viewModelScope.launch {
            repository.setArchived(address, !isArchived)
        }
    }

    fun deleteConversation(address: String) {
        viewModelScope.launch {
            repository.deleteConversation(address)
            if (_currentThreadAddress.value == address) {
                _currentThreadAddress.value = null
            }
        }
    }

    fun deleteMessage(id: Long, address: String) {
        viewModelScope.launch {
            repository.deleteMessage(id, address)
        }
    }
}
