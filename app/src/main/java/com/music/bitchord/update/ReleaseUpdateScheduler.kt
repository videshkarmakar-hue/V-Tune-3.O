package com.music.bitchord.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the release check running without the app being open.
 *
 * Two pieces of work: a periodic check that fires every [PERIOD_HOURS] so a
 * release published while the app is closed still reaches the listener as a
 * notification, and a one-time check enqueued at launch (and after a boot or an
 * app update) so a fresh install hears about a new build on the same session
 * rather than waiting out the period.
 *
 * Both need a connected network; WorkManager holds the request until there is
 * one, so an offline start is not a failed check.
 */
object ReleaseUpdateScheduler {

    /** How often the background check looks for a newly published release. */
    private const val PERIOD_HOURS = 6L

    private val networkConstraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun applyBackgroundCheckSetting(context: Context, enabled: Boolean) {
        if (enabled) ensureScheduled(context) else cancel(context)
    }

    fun rescheduleIfEnabled(context: Context) {
        ensureScheduled(context)
    }

    /** Periodic work, replacing any earlier schedule, plus a check for right now. */
    fun ensureScheduled(context: Context) {
        val wm = workManager(context) ?: return
        runCatching {
            wm.enqueueUniquePeriodicWork(
                ReleaseUpdateCheckWorker.UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ReleaseUpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(networkConstraints)
                    .build(),
            )
        }
        enqueueImmediateCheck(context, expedited = false)
    }

    fun enqueueImmediateCheck(context: Context, expedited: Boolean = true) {
        val wm = workManager(context) ?: return
        runCatching {
            val request = OneTimeWorkRequestBuilder<ReleaseUpdateCheckWorker>()
                .setConstraints(networkConstraints)
                .apply {
                    if (expedited) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            wm.enqueueUniqueWork(
                ReleaseUpdateCheckWorker.UNIQUE_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    fun cancel(context: Context) {
        val wm = workManager(context) ?: return
        runCatching {
            wm.cancelUniqueWork(ReleaseUpdateCheckWorker.UNIQUE_PERIODIC_WORK)
            wm.cancelUniqueWork(ReleaseUpdateCheckWorker.UNIQUE_ONE_TIME_WORK)
        }
    }

    private fun workManager(context: Context): WorkManager? =
        runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull()
}
