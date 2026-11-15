package com.music.bitchord.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReleaseUpdateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                ReleaseUpdateScheduler.ensureScheduled(context)
                ReleaseUpdateScheduler.enqueueImmediateCheck(context)
            }
        }
    }
}
