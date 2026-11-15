package com.music.bitchord.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager

/**
 * Tracks published releases for update availability and shows a system notification when a
 * newer release is detected (including when checked from background work while the app
 * is not in the foreground).
 *
 * "Push"-style immediacy for releases published while the app is closed depends on an
 * external push transport (e.g. FCM) if configured; periodic background checks provide
 * best-effort delivery within Android scheduling/power limits.
 */
object ReleaseUpdateTracker {

    private const val PREFS = "vtune_release_update_tracker"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val CHANNEL_ID = "app_release_updates"
    private const val NOTIFICATION_ID = 41021

    suspend fun checkAndNotify(context: Context, force: Boolean = true): AppUpdateManager.UpdateInfo? {
        val app = context.applicationContext
        val info = runCatching { AppUpdateManager.check(app, force = force) }.getOrNull() ?: return null

        // Only notify for a version we haven't already notified about.
        val lastNotified = prefs(app).getString(KEY_LAST_NOTIFIED_VERSION, null)
        if (lastNotified == info.version) return info

        if (postNotification(app, info)) {
            prefs(app).edit().putString(KEY_LAST_NOTIFIED_VERSION, info.version).apply()
        }
        return info
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun postNotification(context: Context, info: AppUpdateManager.UpdateInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission not granted; avoid throwing. Next check (after grant or in-app open) can notify.
            return false
        }

        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_UPDATE, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(R.string.app_update_notification_title)
        val text = context.getString(R.string.app_update_notification_text, info.version)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val releaseUrl = info.releaseUrl
        if (releaseUrl.isNotBlank()) {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val viewPending = PendingIntent.getActivity(
                context,
                1002,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, context.getString(R.string.app_update_notification_action_view), viewPending)
        }

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.app_update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.app_update_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    const val EXTRA_OPEN_UPDATE = "open_release_update"
}
