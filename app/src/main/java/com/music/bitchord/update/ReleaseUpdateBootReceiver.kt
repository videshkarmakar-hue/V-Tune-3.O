package com.music.bitchord.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Automatic background/boot update checks are disabled; updates are manual from Settings.
 */
class ReleaseUpdateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // No-op: manual updates only.
    }
}
