package com.example
 
import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class PulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupGlobalExceptionHandler()
        initializeFirebaseSafely()
    }

    private fun initializeFirebaseSafely() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.i("PulseApplication", "FirebaseApp initialized successfully")
            }
            com.example.auth.FirebaseAuthenticator.getInstance().ensureInitialized(this)
        } catch (t: Throwable) {
            Log.e("PulseApplication", "FirebaseApp initialization error", t)
        }
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PulseApplication", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
