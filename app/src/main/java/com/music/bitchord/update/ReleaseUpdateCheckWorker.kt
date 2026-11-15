package com.music.bitchord.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ReleaseUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            ReleaseUpdateTracker.checkAndNotify(applicationContext, force = true)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Release update check failed", t)
            // Transient failures (network, etc.) should retry; avoid crashing WorkManager.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "ReleaseUpdateWorker"
        const val UNIQUE_PERIODIC_WORK = "release_update_periodic_check"
        const val UNIQUE_ONE_TIME_WORK = "release_update_one_time_check"
    }
}
