package com.example.auth

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Singleton holder that retains a weak reference to the active Activity.
 * This guarantees that CredentialManager and Google Identity Services can always
 * obtain an Activity context, even when invoked from deep inside ModalBottomSheet,
 * Dialog, or popup context wrappers where ContextWrapper.baseContext chain may terminate
 * before reaching the host Activity.
 */
object CurrentActivityHolder {
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    var currentActivity: Activity?
        get() = activityRef?.get()
        set(value) {
            activityRef = if (value != null) WeakReference(value) else null
        }
}
