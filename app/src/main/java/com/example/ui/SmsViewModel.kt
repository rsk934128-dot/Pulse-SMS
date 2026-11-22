package com.example.ui

import android.app.Application
import android.net.Uri
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

    private var outgoingRequestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var sessionNotificationsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var notificationListenStartedMs: Long = 0L
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

        // Resume active web pairing session listener & sync if already paired
        _activePairingSessionId.value?.let { savedSessionId ->
            attachOutgoingWebRequestListener(savedSessionId)
            attachSessionNotificationsListener(savedSessionId)
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

    override fun onCleared() {
        outgoingRequestListener?.remove()
        outgoingRequestListener = null
        sessionNotificationsListener?.remove()
        sessionNotificationsListener = null
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
        _hasNotificationAccess.value = PulseNotificationListenerService.isNotificationAccessEnabled(appContext)
        _hasPostNotificationPermission.value = SmsNotificationManager.hasPostNotificationsPermission(appContext)
    }

    fun openNotificationAccessSettings() {
        PulseNotificationListenerService.openNotificationAccessSettings(getApplication())
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
        viewModelScope.launch(Dispatchers.IO) {
            val granted = com.example.telephony.SmsPermissionManager.hasEssentialSmsPermissions(getApplication())
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
        }
        setWebConnected(true)
        triggerCloudSyncNow()
        viewModelScope.launch {
            repository.emitSyncLog("WEB_PAIRING", "🎉 ওয়েবসাইটের সাথে ডিভাইস কানেক্টেড ও লাইভ এসএমএস ও নোটিফিকেশন সিঙ্ক সক্রিয় ($deviceName)")
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
