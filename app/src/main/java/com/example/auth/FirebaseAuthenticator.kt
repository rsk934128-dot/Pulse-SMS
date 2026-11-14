package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Traverses context wrappers (such as ModalBottomSheet or Dialog theme wrappers)
 * until the host Activity is found.
 */
fun Context.findActivity(): Activity? {
    if (this is Activity) return this
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return CurrentActivityHolder.currentActivity
}

/**
 * Data representation of an authenticated user profile.
 */
data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String? = null
)

/**
 * FirebaseAuthenticator handles user login, sign-up, session observation,
 * and sign-out using Firebase Authentication and Jetpack Credential Manager.
 */
class FirebaseAuthenticator(
    private val customAuth: FirebaseAuth? = null
) {
    @Volatile
    private var cachedAuth: FirebaseAuth? = customAuth

    @Volatile
    private var listenerAttached = false

    private val interactiveAuthInProgress = AtomicBoolean(false)

    private val _authStateFlow = MutableStateFlow<FirebaseUser?>(null)
    val authStateFlow: StateFlow<FirebaseUser?> = _authStateFlow.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _authStateFlow.value = firebaseAuth.currentUser
    }

    init {
        resolveAuth(null)
    }

    /**
     * Lazily initializes FirebaseApp (when a Context is provided) and resolves FirebaseAuth,
     * ensuring an earlier deferred initialization never locks the singleton into a null state.
     */
    @Synchronized
    fun ensureInitialized(context: Context? = null): FirebaseAuth? {
        return resolveAuth(context)
    }

    @Synchronized
    private fun resolveAuth(context: Context?): FirebaseAuth? {
        if (customAuth != null) {
            cachedAuth = customAuth
            if (!listenerAttached) {
                customAuth.addAuthStateListener(authListener)
                listenerAttached = true
            }
            _authStateFlow.value = customAuth.currentUser
            return customAuth
        }

        if (context != null) {
            val appContext = context.applicationContext
            try {
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    FirebaseApp.initializeApp(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "FirebaseApp.initializeApp notice: ${t.message}")
            }
        }

        val resolved = cachedAuth ?: try {
            Firebase.auth
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseAuth initialization deferred: ${t.message}")
            null
        }

        if (resolved != null) {
            cachedAuth = resolved
            if (!listenerAttached) {
                resolved.addAuthStateListener(authListener)
                listenerAttached = true
            }
            _authStateFlow.value = resolved.currentUser
        }
        return resolved
    }

    private val activeAuth: FirebaseAuth?
        get() = resolveAuth(null)

    val currentUser: FirebaseUser?
        get() = activeAuth?.currentUser

    val currentUserId: String?
        get() = activeAuth?.currentUser?.uid

    val currentUserEmail: String?
        get() = activeAuth?.currentUser?.email

    val currentDisplayName: String?
        get() = activeAuth?.currentUser?.displayName

    val isSignedIn: Boolean
        get() = activeAuth?.currentUser != null

    /**
     * Retrieves the current user's structured profile, or null if unauthenticated.
     */
    fun getUserProfile(): AuthUser? {
        val user = activeAuth?.currentUser ?: return null
        return AuthUser(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName,
            photoUrl = user.photoUrl?.toString()
        )
    }

    /**
     * Observes Firebase Authentication state changes as a Flow.
     */
    fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val currentAuth = activeAuth
        if (currentAuth == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        currentAuth.addAuthStateListener(listener)
        trySend(currentAuth.currentUser)
        awaitClose {
            currentAuth.removeAuthStateListener(listener)
        }
    }

    /**
     * Authenticates existing user or registers a new user via Google Sign-In using Credential Manager.
     */
    suspend fun signInWithGoogle(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser> {
        return executeGoogleAuth(context, credentialManager)
    }

    /**
     * Signs up a user via Google Sign-In using Credential Manager.
     */
    suspend fun signUpWithGoogle(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser> {
        return executeGoogleAuth(context, credentialManager)
    }

    private fun resolveServerClientId(context: Context): String? {
        return try {
            context.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve default_web_client_id", e)
            null
        }
    }

    /**
     * Internal implementation of the Google Credential Manager authentication flow.
     * Uses GetSignInWithGoogleOption for interactive account selection and falls back
     * to GetGoogleIdOption (with filterByAuthorizedAccounts = false) only when primary
     * fails with a non-cancellation error.
     */
    private suspend fun executeGoogleAuth(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser> {
        val targetActivity = (context as? Activity)
            ?: context.findActivity()
            ?: CurrentActivityHolder.currentActivity
            ?: return Result.failure(IllegalStateException("Activity context unavailable to display Google Sign-In UI."))

        ensureInitialized(targetActivity)
            ?: return Result.failure(IllegalStateException("Firebase Authentication failed to initialize. Check google-services.json configuration."))

        val clientId = resolveServerClientId(targetActivity)
            ?: return Result.failure(IllegalStateException("Google Sign-In configuration missing: default_web_client_id not found."))

        interactiveAuthInProgress.set(true)
        return try {
            // Strategy 1: Interactive Google Account Chooser bottom sheet
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
            val primaryRequest = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            try {
                val result = credentialManager.getCredential(targetActivity, primaryRequest)
                processCredentialResult(result.credential, targetActivity)
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
                Result.failure(e)
            } catch (primaryEx: Exception) {
                Log.w(TAG, "Primary GetSignInWithGoogleOption failed, attempting GetGoogleIdOption fallback", primaryEx)
                try {
                    // Strategy 2: Explicit GetGoogleIdOption with filterByAuthorizedAccounts = false
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(false)
                        .build()

                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()

                    val fallbackResult = credentialManager.getCredential(targetActivity, fallbackRequest)
                    processCredentialResult(fallbackResult.credential, targetActivity)
                } catch (fallbackCancel: GetCredentialCancellationException) {
                    Log.w(TAG, "Google Sign-In cancelled by user in fallback: ${fallbackCancel.message}", fallbackCancel)
                    Result.failure(fallbackCancel)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Google Sign-In failed across both strategies", fallbackEx)
                    val msg = fallbackEx.message ?: primaryEx.message ?: ""
                    val userFriendlyMessage = when {
                        msg.contains("10:") || msg.contains("DEVELOPER_ERROR") ->
                            "গুগল সাইন-ইন কনফিগারেশন ত্রুটি (SHA-1 / OAuth Client ID মেলেনি)।"
                        fallbackEx is NoCredentialException || primaryEx is NoCredentialException ||
                            msg.contains("16:") || msg.contains("No credential", ignoreCase = true) ->
                            "ডিভাইসে কোনো গুগল অ্যাকাউন্ট পাওয়া যায়নি। ফোনের Settings থেকে একটি Google অ্যাকাউন্ট যোগ করে আবার চেষ্টা করুন।"
                        msg.contains("7:") || msg.contains("NETWORK", ignoreCase = true) ->
                            "নেটওয়ার্ক সংযোগ চেক করুন।"
                        else -> fallbackEx.localizedMessage ?: primaryEx.localizedMessage ?: "সাইন-ইন ব্যর্থ হয়েছে"
                    }
                    Result.failure(Exception(userFriendlyMessage, fallbackEx))
                }
            }
        } finally {
            interactiveAuthInProgress.set(false)
        }
    }

    private suspend fun processCredentialResult(
        credential: Credential,
        context: Context? = null
    ): Result<FirebaseUser> {
        return if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            signInWithGoogleIdToken(googleIdToken, context)
        } else {
            Result.failure(IllegalStateException("Unexpected credential type: ${credential::class.java.name}"))
        }
    }

    /**
     * Completes Firebase authentication using a retrieved Google ID token.
     */
    suspend fun signInWithGoogleIdToken(
        idToken: String,
        context: Context? = null
    ): Result<FirebaseUser> {
        val currentAuth = resolveAuth(context)
            ?: return Result.failure(IllegalStateException("Firebase Auth is not available in current environment"))
        return try {
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = withContext(NonCancellable) {
                currentAuth.signInWithCredential(authCredential).await()
            }
            val user = authResult.user
            if (user != null) {
                _authStateFlow.value = user
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Firebase Auth succeeded but returned null user"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign in with Google credential", e)
            Result.failure(e)
        }
    }

    /**
     * Attempts silent auto-sign-in on app start for previously authorized Google accounts.
     */
    suspend fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser?> {
        val resolvedAuth = ensureInitialized(context)
        val existingUser = resolvedAuth?.currentUser
        if (existingUser != null) {
            _authStateFlow.value = existingUser
            return Result.success(existingUser)
        }
        if (resolvedAuth == null || interactiveAuthInProgress.get()) {
            return Result.success(null)
        }

        val clientId = resolveServerClientId(context) ?: return Result.success(null)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        return try {
            val targetContext = context.findActivity() ?: context
            if (interactiveAuthInProgress.get()) return Result.success(null)
            val result = credentialManager.getCredential(targetContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                signInWithGoogleIdToken(googleIdToken, targetContext).map { it }
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clears Jetpack Credential Manager state BEFORE signing out of Firebase inside NonCancellable
     * so unmounting the calling Composable does not interrupt credential state cleanup.
     */
    suspend fun signOut(
        context: Context? = null,
        credentialManager: CredentialManager? = null
    ): Result<Unit> = withContext(NonCancellable) {
        try {
            try {
                credentialManager?.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            }
            resolveAuth(context)?.signOut()
            _authStateFlow.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error during sign out", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "FirebaseAuthenticator"

        @Volatile
        private var INSTANCE: FirebaseAuthenticator? = null

        fun getInstance(auth: FirebaseAuth? = null): FirebaseAuthenticator {
            if (auth != null) {
                return FirebaseAuthenticator(auth).also { INSTANCE = it }
            }
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseAuthenticator().also { INSTANCE = it }
            }
        }
    }
}
