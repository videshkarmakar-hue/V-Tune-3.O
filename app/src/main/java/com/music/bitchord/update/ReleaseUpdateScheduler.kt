package com.music.bitchord.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReleaseUpdateScheduler {

    // Periodic checks provide best-effort notifications when the app is closed.
    // (Exact-time delivery on release publish requires an external push service such as FCM.)
    private const val PERIOD_HOURS = 6L

    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<ReleaseUpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        runCatching {
            wm.enqueueUniquePeriodicWork(
                ReleaseUpdateCheckWorker.UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
        }
    }

    fun enqueueImmediateCheck(context: Context) {
        val app = context.applicationContext
        val wm = runCatching { WorkManager.getInstance(app) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<ReleaseUpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        runCatching {
            wm.enqueueUniqueWork(
                ReleaseUpdateCheckWorker.UNIQUE_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
