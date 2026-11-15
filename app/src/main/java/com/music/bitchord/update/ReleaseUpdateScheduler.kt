package com.music.bitchord.update

import android.content.Context
import androidx.work.WorkManager

/**
 * Automatic/background release update checks are disabled.
 * Updates are performed manually from Settings > Software update.
 * This scheduler cancels any previously scheduled work.
 */
object ReleaseUpdateScheduler {

    fun applyBackgroundCheckSetting(context: Context, @Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        cancel(context)
    }

    fun rescheduleIfEnabled(context: Context) {
        cancel(context)
    }

    fun ensureScheduled(@Suppress("UNUSED_PARAMETER") context: Context) {
        // no-op: automatic checks removed
    }

    fun enqueueImmediateCheck(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") expedited: Boolean = true) {
        // no-op: automatic checks removed
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        runCatching {
            wm.cancelUniqueWork(ReleaseUpdateCheckWorker.UNIQUE_PERIODIC_WORK)
            wm.cancelUniqueWork(ReleaseUpdateCheckWorker.UNIQUE_ONE_TIME_WORK)
        }
    }
}
