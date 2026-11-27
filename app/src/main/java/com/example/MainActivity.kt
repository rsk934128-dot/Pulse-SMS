package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import com.example.ai.configureAppCheck
import com.example.auth.CurrentActivityHolder
import com.example.auth.findActivity
import com.example.telephony.SmsNotificationManager
import com.example.telephony.SmsPermissionManager
import com.example.ui.SmsViewModel
import com.example.ui.auth.AuthManager
import com.example.ui.components.CameraQrScannerDialog
import com.example.ui.components.ComposeDialog
import com.example.ui.components.RemoteCameraBridgeOverlay
import com.example.ui.components.RemoteMicBridgeOverlay
import com.example.ui.components.SmsPermissionRationaleDialog
import com.example.ui.components.WebSyncSheet
import com.example.ui.screens.ChatThreadScreen
import com.example.ui.screens.InboxScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val _notificationThreadAddress = MutableStateFlow<String?>(null)
    val notificationThreadAddress: StateFlow<String?> = _notificationThreadAddress.asStateFlow()

    private val _openWebSyncFromNotification = MutableStateFlow(false)
    val openWebSyncFromNotification: StateFlow<Boolean> = _openWebSyncFromNotification.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        try {
            CurrentActivityHolder.currentActivity = this
            try {
                configureAppCheck(this, intent)
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "configureAppCheck notice", t)
            }
            try {
                AuthManager.authenticator.ensureInitialized(this)
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "FirebaseAuthenticator init notice", t)
            }
            handleNotificationIntent(intent)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "onCreate init error", t)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                SmsNotificationManager.createNotificationChannel(this@MainActivity)
            } catch (t: Throwable) {
                android.util.Log.e("MainActivity", "createNotificationChannel notice", t)
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmsAppRoot(
                        notificationThreadAddress = notificationThreadAddress,
                        openWebSyncSignal = openWebSyncFromNotification,
                        onConsumedWebSyncSignal = { _openWebSyncFromNotification.value = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CurrentActivityHolder.currentActivity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (CurrentActivityHolder.currentActivity === this) {
            CurrentActivityHolder.currentActivity = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val address = intent?.getStringExtra(SmsNotificationManager.EXTRA_THREAD_ADDRESS)
        if (!address.isNullOrBlank()) {
            _notificationThreadAddress.value = address
        }
        if (intent?.getBooleanExtra(SmsNotificationManager.EXTRA_OPEN_WEB_SYNC, false) == true) {
            _openWebSyncFromNotification.value = true
        }
    }
}

@Composable
fun SmsAppRoot(
    viewModel: SmsViewModel = viewModel(),
    notificationThreadAddress: StateFlow<String?>? = null,
    openWebSyncSignal: StateFlow<Boolean>? = null,
    onConsumedWebSyncSignal: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { (context as? android.app.Activity) ?: context.findActivity() }
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(activity, context) {
        CredentialManager.create(activity ?: context)
    }

    // Auto-open conversation when user taps on Heads-Up SMS notification
    LaunchedEffect(notificationThreadAddress) {
        notificationThreadAddress?.collect { address ->
            if (!address.isNullOrBlank()) {
                viewModel.openThread(address)
            }
        }
    }

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val totalUnreadCount by viewModel.totalUnreadCount.collectAsStateWithLifecycle()
    val isDefaultSms by viewModel.isDefaultSmsApp.collectAsStateWithLifecycle()
    val isWebConnected by viewModel.isWebConnected.collectAsStateWithLifecycle()
    val syncLogs by viewModel.syncLogs.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val activePairingSessionId by viewModel.activePairingSessionId.collectAsStateWithLifecycle()
    val mirroredNotifications by viewModel.mirroredNotifications.collectAsStateWithLifecycle()
    val latestIncomingWebNotification by viewModel.latestIncomingWebNotification.collectAsStateWithLifecycle()
    val hasNotificationAccess by viewModel.hasNotificationAccess.collectAsStateWithLifecycle()
    val hasPostNotificationPermission by viewModel.hasPostNotificationPermission.collectAsStateWithLifecycle()
    val isRemoteCameraActive by viewModel.isRemoteCameraActive.collectAsStateWithLifecycle()
    val remoteCameraLens by viewModel.remoteCameraLens.collectAsStateWithLifecycle()
    val isRemoteTorchOn by viewModel.isRemoteTorchOn.collectAsStateWithLifecycle()
    val remoteCameraFrameCount by viewModel.remoteCameraFrameCount.collectAsStateWithLifecycle()
    val remoteCameraPendingHdCapture by viewModel.remoteCameraPendingHdCapture.collectAsStateWithLifecycle()
    val remoteCameraPermissionNeeded by viewModel.remoteCameraPermissionNeeded.collectAsStateWithLifecycle()
    val hasCameraPermission by viewModel.hasCameraPermission.collectAsStateWithLifecycle()
    val isRemoteMicActive by viewModel.isRemoteMicActive.collectAsStateWithLifecycle()
    val remoteMicChunkCount by viewModel.remoteMicChunkCount.collectAsStateWithLifecycle()
    val remoteMicPermissionNeeded by viewModel.remoteMicPermissionNeeded.collectAsStateWithLifecycle()
    val hasMicPermission by viewModel.hasMicPermission.collectAsStateWithLifecycle()
    val hasCallLogPermission by viewModel.hasCallLogPermission.collectAsStateWithLifecycle()
    val callLogPermissionNeeded by viewModel.callLogPermissionNeeded.collectAsStateWithLifecycle()
    val syncedCallLogsCount by viewModel.syncedCallLogsCount.collectAsStateWithLifecycle()
    val autoCallRecordingEnabled by viewModel.autoCallRecordingEnabled.collectAsStateWithLifecycle()
    val isCallRecordingNow by viewModel.isCallRecordingNow.collectAsStateWithLifecycle()
    val callRecordingDurationSec by viewModel.callRecordingDurationSec.collectAsStateWithLifecycle()
    val callRecordingTargetNumber by viewModel.callRecordingTargetNumber.collectAsStateWithLifecycle()
    val recordedCallsCount by viewModel.recordedCallsCount.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val callRecorderPermissionNeeded by viewModel.callRecorderPermissionNeeded.collectAsStateWithLifecycle()
    val recentCallNumbers by viewModel.recentCallNumbers.collectAsStateWithLifecycle()

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isCloudSyncing by viewModel.isCloudSyncing.collectAsStateWithLifecycle()
    val profileAvatar by viewModel.profileAvatar.collectAsStateWithLifecycle()

    val smartReplies by viewModel.smartReplies.collectAsStateWithLifecycle()
    val aiSummary by viewModel.aiSummary.collectAsStateWithLifecycle()
    val aiSafetyReport by viewModel.aiSafetyReport.collectAsStateWithLifecycle()
    val isAiLoading by viewModel.isAiLoading.collectAsStateWithLifecycle()
    val aiErrorMessage by viewModel.aiErrorMessage.collectAsStateWithLifecycle()

    val currentThreadAddress by viewModel.currentThreadAddress.collectAsStateWithLifecycle()
    val activeThreadMessages by viewModel.activeThreadMessages.collectAsStateWithLifecycle()
    val currentConversation by viewModel.currentConversation.collectAsStateWithLifecycle()

    var showCompose by remember { mutableStateOf(false) }
    var showWebSync by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(openWebSyncSignal) {
        openWebSyncSignal?.collect { open ->
            if (open) {
                showWebSync = true
                onConsumedWebSyncSignal()
            }
        }
    }

    LaunchedEffect(latestIncomingWebNotification?.id) {
        if (latestIncomingWebNotification != null) {
            delay(6500)
            viewModel.dismissWebNotificationBanner()
        }
    }

    // Stagger silent Auto-Sign-In until after first Compose frame & SurfaceSyncGroup finish
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(900)
        AuthManager.attemptAutoSignIn(
            context = activity ?: context,
            credentialManager = credentialManager,
            onAuthSuccess = { },
            onUnauthenticated = { },
            scope = coroutineScope
        )
    }

    val hasSmsPermissions by viewModel.hasSmsPermissions.collectAsStateWithLifecycle()
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    // Lifecycle-aware auto-refresh when app resumes from background or device settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Runtime Permission Launcher for Remote Camera Streaming
    val remoteCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onCameraPermissionResultForRemote(granted)
    }

    // On-Demand Runtime Permission Launcher for Remote Microphone Streaming
    val remoteMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResultForRemote(granted)
    }

    // On-Demand Least-Privilege Permission Launcher for Call History Web Sync
    val callLogPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onCallLogPermissionResult(granted)
        if (granted) {
            Toast.makeText(
                context,
                if (currentLanguage == "BN") "কল হিস্টরি পারমিশন চালু হয়েছে — ওয়েবে সিঙ্ক হচ্ছে!" else "Call log permission granted — syncing to web!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val onSyncCallLogsOrRequestPermission: () -> Unit = {
        if (hasCallLogPermission) {
            viewModel.syncDeviceCallLogsNow(promptIfMissing = false) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    // On-Demand Least-Privilege Permission Launcher for Call Recording & Coarse Map Location
    val callRecorderPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onCallRecorderPermissionsResult(results)
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission
        val locOk = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission
        if (micOk) {
            Toast.makeText(
                context,
                if (currentLanguage == "BN") {
                    if (locOk) "✅ কল রেকর্ডিং ও লোকেশন ম্যাপ পারমিশন চালু হয়েছে!" else "✅ মাইক্রোফোন চালু হয়েছে!"
                } else {
                    if (locOk) "✅ Call recording & location map permissions granted!" else "✅ Microphone permission granted!"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val requestCallRecorderAndLocationPermissions: () -> Unit = {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        callRecorderPermissionsLauncher.launch(perms)
    }

    LaunchedEffect(isRemoteCameraActive, isRemoteMicActive, remoteCameraPermissionNeeded, remoteMicPermissionNeeded) {
        if (isRemoteCameraActive && showQrScanner) {
            showQrScanner = false
        }
        if (isRemoteCameraActive || isRemoteMicActive || remoteCameraPermissionNeeded || remoteMicPermissionNeeded) {
            if (showWebSync) showWebSync = false
            if (showSettings) showSettings = false
        }
    }

    // Runtime Permission Launcher for SMS Read, Receive, Send, and Contacts
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val readGranted = results[Manifest.permission.READ_SMS] == true
        val receiveGranted = results[Manifest.permission.RECEIVE_SMS] == true
        val allGranted = results.values.all { it }

        viewModel.refreshPermissionState()

        if (allGranted || (readGranted && receiveGranted)) {
            Toast.makeText(
                context,
                if (currentLanguage == "BN") "এসএমএস পারমিশন সক্রিয় হয়েছে!" else "SMS permissions granted!",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Show educational rationale dialog if denied
            showPermissionRationaleDialog = true
        }
    }

    // Default SMS Role Launcher
    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.checkDefaultSmsStatus()
    }

    val onRequestDefaultSms: () -> Unit = {
        val intent = viewModel.getDefaultSmsIntent()
        if (intent != null) {
            try {
                roleLauncher.launch(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val onRequestPermissions: () -> Unit = {
        permissionLauncher.launch(SmsPermissionManager.getAllRequiredPermissions())
    }

    // Auto-prompt essential telephony and SMS permissions only after first frame renders cleanly
    var hasPromptedStartupPermissions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(450)
        if (!hasPromptedStartupPermissions && !SmsPermissionManager.hasEssentialSmsPermissions(context)) {
            hasPromptedStartupPermissions = true
            onRequestPermissions()
        }
    }

    val activeThread = currentThreadAddress
    if (activeThread != null) {
        ChatThreadScreen(
            conversation = currentConversation,
            messages = activeThreadMessages,
            language = currentLanguage,
            onBack = { viewModel.closeThread() },
            onSendMessage = { recipient, body, simSlot ->
                viewModel.sendMessage(recipient, body, simSlot)
            },
            onDeleteMessage = { id ->
                viewModel.deleteMessage(id, activeThread)
            },
            onDeleteConversation = {
                viewModel.deleteConversation(activeThread)
            },
            smartReplies = smartReplies,
            aiSummary = aiSummary,
            aiSafetyReport = aiSafetyReport,
            isAiLoading = isAiLoading,
            aiErrorMessage = aiErrorMessage,
            onRequestSmartReplies = { viewModel.generateSmartRepliesForThread(activeThreadMessages) },
            onRequestSummary = { viewModel.summarizeThread(activeThreadMessages) },
            onRequestSafetyCheck = { msg -> viewModel.analyzeMessageSafety(msg) },
            onDismissSummary = { viewModel.dismissSummary() },
            onDismissSafetyReport = { viewModel.dismissSafetyReport() },
            onPolishDraft = { draft, tone, callback -> viewModel.polishDraft(draft, tone, callback) }
        )
    } else {
        InboxScreen(
            conversations = conversations,
            selectedCategory = selectedCategory,
            searchQuery = searchQuery,
            totalUnreadCount = totalUnreadCount,
            isDefaultSms = isDefaultSms,
            hasTelephonyPermissions = hasSmsPermissions,
            isWebConnected = isWebConnected,
            language = currentLanguage,
            currentUser = currentUser,
            profileAvatar = profileAvatar,
            activity = activity,
            authScope = coroutineScope,
            onAuthSuccess = {
                Toast.makeText(
                    context,
                    if (currentLanguage == "BN") "গুগল সাইন-ইন সফল হয়েছে! ক্লাউড সিঙ্ক সক্রিয়।" else "Google Sign-In successful! Cloud sync active.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onAuthError = { errorMsg ->
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            },
            onCategorySelected = { viewModel.setCategory(it) },
            onSearchQueryChanged = { viewModel.setSearchQuery(it) },
            onConversationClicked = { viewModel.openThread(it) },
            onTogglePin = { address, isPinned -> viewModel.togglePin(address, isPinned) },
            onToggleArchive = { address, isArchived -> viewModel.toggleArchive(address, isArchived) },
            onDeleteConversation = { viewModel.deleteConversation(it) },
            onOpenCompose = { showCompose = true },
            onOpenWebSync = { showWebSync = true },
            onOpenQrScanner = {
                if (isRemoteCameraActive) viewModel.stopRemoteCamera()
                showQrScanner = true
            },
            onConnectToWeb = {
                viewModel.connectToWeb { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onToggleLanguage = { viewModel.toggleLanguage() },
            onOpenSettings = { showSettings = true },
            onRequestDefaultSms = onRequestDefaultSms,
            onRequestPermissions = onRequestPermissions,
            onOpenRationale = { showPermissionRationaleDialog = true },
            onSyncDeviceSms = { viewModel.syncDeviceSms() }
        )
    }

    // Educational SMS Permission Rationale Dialog
    if (showPermissionRationaleDialog) {
        SmsPermissionRationaleDialog(
            language = currentLanguage,
            onDismiss = { showPermissionRationaleDialog = false },
            onRequestPermissions = onRequestPermissions
        )
    }

    // Compose SMS Bottom Sheet
    if (showCompose) {
        ComposeDialog(
            language = currentLanguage,
            onSendMessage = { recipient, body, simSlot ->
                viewModel.sendMessage(recipient, body, simSlot)
                viewModel.openThread(recipient)
            },
            onDismiss = { showCompose = false }
        )
    }

    // Web Sync Bridge / SMS Simulator Bottom Sheet
    if (showWebSync) {
        WebSyncSheet(
            isConnected = isWebConnected,
            syncLogs = syncLogs,
            language = currentLanguage,
            activePairingSessionId = activePairingSessionId,
            hasNotificationAccess = hasNotificationAccess,
            hasPostNotificationPermission = hasPostNotificationPermission,
            isRemoteCameraActive = isRemoteCameraActive,
            isRemoteMicActive = isRemoteMicActive,
            hasCallLogPermission = hasCallLogPermission,
            syncedCallLogsCount = syncedCallLogsCount,
            autoCallRecordingEnabled = autoCallRecordingEnabled,
            isCallRecordingNow = isCallRecordingNow,
            callRecordingDurationSec = callRecordingDurationSec,
            callRecordingTargetNumber = callRecordingTargetNumber,
            recordedCallsCount = recordedCallsCount,
            hasLocationPermission = hasLocationPermission,
            recentCallNumbers = recentCallNumbers,
            mirroredNotifications = mirroredNotifications,
            onToggleConnection = { viewModel.toggleWebConnection() },
            onSimulateIncoming = { sender, body ->
                viewModel.simulateIncomingSms(sender, body)
            },
            onOpenQrScanner = {
                if (isRemoteCameraActive) viewModel.stopRemoteCamera()
                showWebSync = false
                showQrScanner = true
            },
            onConnectToWeb = {
                viewModel.connectToWeb { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onOpenNotificationAccessSettings = {
                viewModel.openNotificationAccessSettings()
            },
            onOpenAppInfoSettings = {
                viewModel.openAppInfoSettings()
            },
            onToggleRemoteCamera = {
                if (isRemoteCameraActive) {
                    viewModel.stopRemoteCamera()
                } else {
                    showWebSync = false
                    if (!hasCameraPermission) {
                        remoteCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        viewModel.startRemoteCameraFromMobile()
                    }
                }
            },
            onToggleRemoteMic = {
                if (isRemoteMicActive) {
                    viewModel.stopRemoteMic()
                } else {
                    showWebSync = false
                    if (!hasMicPermission) {
                        remoteMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.startRemoteMicFromMobile()
                    }
                }
            },
            onSyncCallLogsOrRequestPermission = onSyncCallLogsOrRequestPermission,
            onToggleAutoCallRecording = { enabled ->
                if (!viewModel.toggleAutoCallRecording(enabled) && enabled) {
                    requestCallRecorderAndLocationPermissions()
                }
            },
            onStartManualCallRecording = { number ->
                viewModel.startManualCallRecording(targetNumber = number, callType = "MANUAL") { ok, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (!ok && !hasMicPermission) {
                        requestCallRecorderAndLocationPermissions()
                    }
                }
            },
            onStopManualCallRecording = {
                viewModel.stopManualCallRecording { _, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
            onRequestCallLocationPermissions = requestCallRecorderAndLocationPermissions,
            onRequestPermissions = onRequestPermissions,
            onSendSimultaneousTestNotification = {
                viewModel.sendSimultaneousTestNotification { _, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showWebSync = false }
        )
    }

    // Camera QR Code Scanner Dialog (Instant Screen Pairing)
    if (showQrScanner) {
        val hostComponentActivity = (activity as? ComponentActivity)
            ?: (context.findActivity() as? ComponentActivity)
            ?: (CurrentActivityHolder.currentActivity as? ComponentActivity)
        CameraQrScannerDialog(
            activity = hostComponentActivity,
            language = currentLanguage,
            onPairingSuccess = { sessionId, deviceName ->
                viewModel.onQrPairingCompleted(sessionId, deviceName)
                Toast.makeText(
                    context,
                    if (currentLanguage == "BN") "🎉 এক চান্সে ওয়েব স্ক্রিন কানেক্টেড হয়েছে! ($deviceName)"
                    else "🎉 Web screen connected in 1 shot! ($deviceName)",
                    Toast.LENGTH_LONG
                ).show()
            },
            onDismiss = { showQrScanner = false }
        )
    }

    // Settings Bottom Sheet
    if (showSettings) {
        val hostActivity = activity ?: context.findActivity() ?: CurrentActivityHolder.currentActivity
        SettingsScreen(
            activity = hostActivity,
            actionScope = coroutineScope,
            isDefaultSms = isDefaultSms,
            hasTelephonyPermissions = hasSmsPermissions,
            hasNotificationAccess = hasNotificationAccess,
            hasCallLogPermission = hasCallLogPermission,
            language = currentLanguage,
            currentUser = currentUser,
            isCloudSyncing = isCloudSyncing,
            profileAvatar = profileAvatar,
            onSelectPresetAvatar = { viewModel.selectPresetAvatar(it) },
            onSaveCustomAvatarUri = { uri, callback -> viewModel.saveCustomAvatarFromUri(uri, callback) },
            onResetAvatar = { viewModel.resetProfileAvatar() },
            onToggleLanguage = { viewModel.toggleLanguage() },
            onRequestDefaultSms = onRequestDefaultSms,
            onRequestPermissions = onRequestPermissions,
            onRequestCallLogSync = onSyncCallLogsOrRequestPermission,
            onTriggerCloudSync = { viewModel.triggerCloudSyncNow() },
            onSignOut = {
                val targetContext = hostActivity ?: context
                AuthManager.signOut(
                    context = targetContext,
                    credentialManager = credentialManager,
                    onSignOutComplete = {
                        Toast.makeText(
                            context,
                            if (currentLanguage == "BN") "গুগল অ্যাকাউন্ট সাইন-আউট হয়েছে। এখন যেকোনো নতুন অ্যাকাউন্ট দিয়ে লগইন করতে পারবেন।" else "Signed out. You can now sign in with any account.",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    scope = coroutineScope
                )
            },
            onAuthSuccess = {
                Toast.makeText(
                    context,
                    if (currentLanguage == "BN") "গুগল সাইন-ইন সফল হয়েছে! ক্লাউড সিঙ্ক সক্রিয়।" else "Google Sign-In successful! Cloud sync active.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onAuthError = { errorMsg: String ->
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            },
            onOpenQrScanner = {
                if (isRemoteCameraActive) viewModel.stopRemoteCamera()
                showSettings = false
                showQrScanner = true
            },
            onOpenWebSync = {
                showSettings = false
                showWebSync = true
            },
            onOpenNotificationAccessSettings = {
                viewModel.openNotificationAccessSettings()
            },
            onOpenAppInfoSettings = {
                viewModel.openAppInfoSettings()
            },
            onSendSimultaneousTestNotification = {
                viewModel.sendSimultaneousTestNotification { _, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    // Live In-App Heads-Up Banner whenever a mirrored Web notification arrives
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = latestIncomingWebNotification != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            latestIncomingWebNotification?.let { notif ->
                Card(
                    onClick = {
                        viewModel.dismissWebNotificationBanner()
                        showWebSync = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "🌐 ${notif.title}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                                if (notif.body.isNotBlank()) {
                                    Text(
                                        text = notif.body,
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 12.sp,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.dismissWebNotificationBanner() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss notification banner",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
        }
    }

    val hostCameraActivity = (activity as? ComponentActivity)
        ?: (context.findActivity() as? ComponentActivity)
        ?: (CurrentActivityHolder.currentActivity as? ComponentActivity)
    RemoteCameraBridgeOverlay(
        activity = hostCameraActivity,
        isActive = isRemoteCameraActive,
        lens = remoteCameraLens,
        isTorchOn = isRemoteTorchOn,
        frameCount = remoteCameraFrameCount,
        pendingHdCapture = remoteCameraPendingHdCapture,
        permissionNeeded = remoteCameraPermissionNeeded,
        hasCameraPermission = hasCameraPermission,
        language = currentLanguage,
        onRequestCameraPermission = {
            remoteCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onDismissPermissionPrompt = { viewModel.dismissRemoteCameraPermissionPrompt() },
        onSwitchLens = { viewModel.switchRemoteCameraLens() },
        onToggleTorch = { viewModel.toggleRemoteCameraTorch() },
        onStopCamera = { viewModel.stopRemoteCamera() },
        onFrameCaptured = { base64Jpeg, w, h, isHd ->
            viewModel.onRemoteCameraFrameCaptured(base64Jpeg, w, h, isHd)
        },
        onHardwareError = { err ->
            viewModel.onRemoteCameraHardwareError(err)
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
    )

    RemoteMicBridgeOverlay(
        isActive = isRemoteMicActive,
        chunkCount = remoteMicChunkCount,
        permissionNeeded = remoteMicPermissionNeeded,
        hasMicPermission = hasMicPermission,
        isCameraOverlayVisible = isRemoteCameraActive,
        language = currentLanguage,
        onRequestMicPermission = {
            remoteMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onDismissPermissionPrompt = { viewModel.dismissRemoteMicPermissionPrompt() },
        onStopMic = { viewModel.stopRemoteMic() },
        onAudioChunkCaptured = { base64Pcm, sampleRate, audioLevel ->
            viewModel.onRemoteMicChunkCaptured(base64Pcm, sampleRate, audioLevel)
        },
        onHardwareError = { err ->
            viewModel.onRemoteMicHardwareError(err)
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
        }
    )

    if (callLogPermissionNeeded) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCallLogPermissionPrompt() },
            title = {
                Text(
                    text = if (currentLanguage == "BN") "📞 কল হিস্টরি পারমিশন দিন" else "📞 Allow Call History Access",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (currentLanguage == "BN") {
                        "ওয়েবসাইট থেকে ফোনের ইনকামিং, আউটগোয়িং ও মিসড কল হিস্টরি দেখার জন্য কল লগ (READ_CALL_LOG) পারমিশন প্রয়োজন।"
                    } else {
                        "Call Log permission is required to view incoming, outgoing, and missed calls on your paired website."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissCallLogPermissionPrompt()
                        callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                    }
                ) {
                    Text(if (currentLanguage == "BN") "অনুমতি দিন" else "Allow Call Log")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCallLogPermissionPrompt() }) {
                    Text(if (currentLanguage == "BN") "পরে" else "Later")
                }
            }
        )
    }

    if (callRecorderPermissionNeeded) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCallRecorderPermissionPrompt() },
            title = {
                Text(
                    text = if (currentLanguage == "BN") "🎙️ কল রেকর্ডিং ও ম্যাপ পারমিশন দিন" else "🎙️ Call Recorder & Map Permissions",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (currentLanguage == "BN") {
                        "কল রেকর্ড করে নাম্বার ও লোকেশন ম্যাপসহ ওয়েবসাইটে পাঠানোর জন্য মাইক্রোফোন (RECORD_AUDIO), কল স্টেট (READ_PHONE_STATE) ও লোকেশন (ACCESS_COARSE_LOCATION) পারমিশন প্রয়োজন।"
                    } else {
                        "Microphone, Phone State, and Coarse Location permissions are needed to record calls and map them to phone numbers on the website."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissCallRecorderPermissionPrompt()
                        requestCallRecorderAndLocationPermissions()
                    }
                ) {
                    Text(if (currentLanguage == "BN") "অনুমতি দিন" else "Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCallRecorderPermissionPrompt() }) {
                    Text(if (currentLanguage == "BN") "পরে" else "Later")
                }
            }
        )
    }
}
