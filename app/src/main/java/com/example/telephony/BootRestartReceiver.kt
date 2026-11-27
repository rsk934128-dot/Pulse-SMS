package com.example.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts PulseKeepAliveService automatically after device reboot, package updates,
 * task removal, or periodic watchdog wakeups when Always-Active mode is enabled.
 */
class BootRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val action = intent?.action ?: return

        val shouldHandle = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == ACTION_REVIVE_KEEPALIVE

        if (!shouldHandle) return
        if (!PulseKeepAliveService.isAlwaysActiveEnabled(appContext)) return
        if (PulseKeepAliveService.isServiceRunning.value) return

        PulseKeepAliveService.startOrRefresh(appContext, fromBoot = true)
    }

    companion object {
        const val ACTION_REVIVE_KEEPALIVE = "com.example.action.REVIVE_KEEPALIVE"
    }
}
