package com.music.bitchord.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reschedules periodic GitHub release checks after boot/app update when background checks are
 * enabled by the user. Ensures best-effort notifications continue across restarts (FCM push,
 * when configured, provides faster delivery).
 */
class ReleaseUpdateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { ReleaseUpdateScheduler.rescheduleIfEnabled(context.applicationContext) }
            }
        }
    }
}
