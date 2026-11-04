package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Traverses context wrappers (such as ModalBottomSheet or Dialog theme wrappers)
 * until the host Activity is found.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
    customAuth: FirebaseAuth? = null
) {
    private val auth: FirebaseAuth? = customAuth ?: try {
        Firebase.auth
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseAuth initialization deferred: ${e.message}")
        null
    }

    private val _authStateFlow = MutableStateFlow<FirebaseUser?>(this.auth?.currentUser)
    val authStateFlow: StateFlow<FirebaseUser?> = _authStateFlow.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _authStateFlow.value = firebaseAuth.currentUser
    }

    init {
        this.auth?.addAuthStateListener(authListener)
    }

    val currentUser: FirebaseUser?
        get() = auth?.currentUser

    val currentUserId: String?
        get() = auth?.currentUser?.uid

    val currentUserEmail: String?
        get() = auth?.currentUser?.email

    val currentDisplayName: String?
        get() = auth?.currentUser?.displayName

    val isSignedIn: Boolean
        get() = auth?.currentUser != null

    /**
     * Retrieves the current user's structured profile, or null if unauthenticated.
     */
    fun getUserProfile(): AuthUser? {
        val user = auth?.currentUser ?: return null
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
        val currentAuth = auth
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
     * In Firebase + Google Identity, sign-up and sign-in seamlessly create or resolve the user account.
     */
    suspend fun signUpWithGoogle(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser> {
        return executeGoogleAuth(context, credentialManager)
    }

    /**
     * Internal implementation of the Google Credential Manager authentication flow.
     */
    /**
     * Internal implementation of the Google Credential Manager authentication flow.
     * Supports selecting ANY Google account on the device or adding a new account.
     */
    private suspend fun executeGoogleAuth(
        context: Context,
        credentialManager: CredentialManager
    ): Result<FirebaseUser> {
        val targetActivity = context.findActivity() ?: (context as? Activity)
            ?: return Result.failure(IllegalStateException("Context must be an Activity to display Google Sign-In UI"))

        val clientId = try {
            targetActivity.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            val resId = targetActivity.resources.getIdentifier("default_web_client_id", "string", targetActivity.packageName)
            if (resId != 0) targetActivity.getString(resId)
            else throw IllegalStateException("Resource string 'default_web_client_id' is missing.")
        }

        // Strategy 1: Interactive Google Account Chooser bottom sheet
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val primaryRequest = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val result = credentialManager.getCredential(targetActivity, primaryRequest)
            processCredentialResult(result.credential)
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled by user", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Primary GetSignInWithGoogleOption failed, attempting GetGoogleIdOption fallback", e)
            try {
                // Strategy 2: Explicit GetGoogleIdOption with filterByAuthorizedAccounts = false
                // This forces Google Play Services to list ALL accounts on the device,
                // and provides the 'Add account' button to sign in with any account.
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val fallbackResult = credentialManager.getCredential(targetActivity, fallbackRequest)
                processCredentialResult(fallbackResult.credential)
            } catch (fallbackCancel: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled by user in fallback", fallbackCancel)
                Result.failure(fallbackCancel)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Google Sign-In failed across both strategies", fallbackEx)
                val msg = fallbackEx.message ?: e.message ?: ""
                val userFriendlyMessage = when {
                    msg.contains("10:") || msg.contains("DEVELOPER_ERROR") ->
                        "গুগল সাইন-ইন কনফিগারেশন ত্রুটি (SHA-1 / OAuth Client ID)।"
                    msg.contains("16:") || msg.contains("No credential", ignoreCase = true) ->
                        "কোনো গুগল অ্যাকাউন্ট নির্বাচন করা হয়নি বা বাতিল করা হয়েছে।"
                    msg.contains("7:") || msg.contains("NETWORK", ignoreCase = true) ->
                        "নেটওয়ার্ক সংযোগ চেক করুন।"
                    else -> fallbackEx.localizedMessage ?: "সাইন-ইন ব্যর্থ হয়েছে"
                }
                Result.failure(Exception(userFriendlyMessage, fallbackEx))
            }
        }
    }

    private suspend fun processCredentialResult(credential: androidx.credentials.Credential): Result<FirebaseUser> {
        return if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            signInWithGoogleIdToken(googleIdToken)
        } else {
            Result.failure(IllegalStateException("Unexpected credential type: ${credential::class.java.name}"))
        }
    }

    /**
     * Completes Firebase authentication using a retrieved Google ID token.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser> {
        val currentAuth = auth ?: return Result.failure(IllegalStateException("Firebase Auth is not available in current environment"))
        return try {
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = currentAuth.signInWithCredential(authCredential).await()
            val user = authResult.user
            if (user != null) {
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
        val existingUser = auth?.currentUser
        if (existingUser != null) {
            return Result.success(existingUser)
        }
        if (auth == null) {
            return Result.success(null)
        }

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) context.getString(resId) else return Result.success(null)
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

        return try {
            val targetContext = context.findActivity() ?: context
            val result = credentialManager.getCredential(targetContext, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                signInWithGoogleIdToken(googleIdToken).map { it }
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Signs out the user from Firebase and clears Jetpack Credential Manager state.
     */
    suspend fun signOut(
        context: Context? = null,
        credentialManager: CredentialManager? = null
    ): Result<Unit> {
        return try {
            auth?.signOut()
            credentialManager?.clearCredentialState(ClearCredentialStateRequest())
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
