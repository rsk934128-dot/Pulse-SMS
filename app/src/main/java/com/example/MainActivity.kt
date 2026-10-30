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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.SmsViewModel
import com.example.ui.auth.AuthManager
import com.example.ui.components.ComposeDialog
import com.example.ui.components.WebSyncSheet
import com.example.ui.screens.ChatThreadScreen
import com.example.ui.screens.InboxScreen
import com.example.ui.screens.SettingsScreen
import com.example.ai.configureAppCheck
import com.example.telephony.SmsPermissionManager
import com.example.ui.components.SmsPermissionRationaleDialog
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import android.content.Intent
import com.example.telephony.SmsNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val _notificationThreadAddress = MutableStateFlow<String?>(null)
    val notificationThreadAddress: StateFlow<String?> = _notificationThreadAddress.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        SmsNotificationManager.createNotificationChannel(this)
        handleNotificationIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmsAppRoot(notificationThreadAddress = notificationThreadAddress)
                }
            }
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
    }
}

@Composable
fun SmsAppRoot(
    viewModel: SmsViewModel = viewModel(),
    notificationThreadAddress: StateFlow<String?>? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

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

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isCloudSyncing by viewModel.isCloudSyncing.collectAsStateWithLifecycle()

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
    var showSettings by remember { mutableStateOf(false) }

    // Silent Auto-Sign-In on App Startup
    LaunchedEffect(Unit) {
        AuthManager.attemptAutoSignIn(
            context = context,
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

    if (currentThreadAddress != null) {
        ChatThreadScreen(
            conversation = currentConversation,
            messages = activeThreadMessages,
            language = currentLanguage,
            onBack = { viewModel.closeThread() },
            onSendMessage = { recipient, body, simSlot ->
                viewModel.sendMessage(recipient, body, simSlot)
            },
            onDeleteMessage = { id ->
                viewModel.deleteMessage(id, currentThreadAddress!!)
            },
            onDeleteConversation = {
                viewModel.deleteConversation(currentThreadAddress!!)
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
            onCategorySelected = { viewModel.setCategory(it) },
            onSearchQueryChanged = { viewModel.setSearchQuery(it) },
            onConversationClicked = { viewModel.openThread(it) },
            onTogglePin = { address, isPinned -> viewModel.togglePin(address, isPinned) },
            onToggleArchive = { address, isArchived -> viewModel.toggleArchive(address, isArchived) },
            onDeleteConversation = { viewModel.deleteConversation(it) },
            onOpenCompose = { showCompose = true },
            onOpenWebSync = { showWebSync = true },
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
            onToggleConnection = { viewModel.toggleWebConnection() },
            onSimulateIncoming = { sender, body ->
                viewModel.simulateIncomingSms(sender, body)
            },
            onDismiss = { showWebSync = false }
        )
    }

    // Settings Bottom Sheet
    if (showSettings) {
        SettingsScreen(
            isDefaultSms = isDefaultSms,
            hasTelephonyPermissions = hasSmsPermissions,
            language = currentLanguage,
            currentUser = currentUser,
            isCloudSyncing = isCloudSyncing,
            onToggleLanguage = { viewModel.toggleLanguage() },
            onRequestDefaultSms = onRequestDefaultSms,
            onRequestPermissions = onRequestPermissions,
            onTriggerCloudSync = { viewModel.triggerCloudSyncNow() },
            onSignOut = {
                AuthManager.signOut(
                    context = context,
                    credentialManager = credentialManager,
                    onSignOutComplete = {
                        Toast.makeText(context, "Signed out of Google", Toast.LENGTH_SHORT).show()
                    },
                    scope = coroutineScope
                )
            },
            onAuthSuccess = {
                Toast.makeText(context, "Google Sign-In successful!", Toast.LENGTH_SHORT).show()
            },
            onAuthError = { errorMsg: String ->
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            },
            onDismiss = { showSettings = false }
        )
    }
}
