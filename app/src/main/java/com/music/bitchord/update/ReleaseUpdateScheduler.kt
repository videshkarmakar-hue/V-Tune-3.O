package com.music.bitchord.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.music.bitchord.data.settings.AppSettings
import java.util.concurrent.TimeUnit

object ReleaseUpdateScheduler {

    // More responsive best-effort periodic checks (WorkManager/Doze still apply).
    // Exact-time delivery for published releases is provided by FCM push when configured.
    private const val PERIOD_HOURS = 4L
    private const val FLEX_HOURS = 2L

    fun applyBackgroundCheckSetting(context: Context, enabled: Boolean) {
        if (enabled) {
            ensureScheduled(context)
            // Run a catch-up check soon after enabling so the user gets timely feedback.
            enqueueImmediateCheck(context, expedited = true)
        } else {
            cancel(context)
        }
    }

    fun rescheduleIfEnabled(context: Context) {
        val app = context.applicationContext
        if (AppSettings.backgroundReleaseChecks.value) {
            ensureScheduled(app)
            // After boot/app update, try to check promptly once network is available.
            enqueueImmediateCheck(app, expedited = false)
        } else {
            cancel(app)
        }
    }

    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<ReleaseUpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        runCatching {
            wm.enqueueUniquePeriodicWork(
                ReleaseUpdateCheckWorker.UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
        }
    }

    fun enqueueImmediateCheck(context: Context, expedited: Boolean = true) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val builder = OneTimeWorkRequestBuilder<ReleaseUpdateCheckWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)

        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        runCatching {
            wm.enqueueUniqueWork(
                ReleaseUpdateCheckWorker.UNIQUE_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                builder.build(),
            )
        }
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
