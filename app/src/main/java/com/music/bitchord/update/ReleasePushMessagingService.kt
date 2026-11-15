package com.music.bitchord.update

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives release push notifications (FCM topic, e.g. "app-updates") so users can be
 * notified of new published releases even when the app is closed/backgrounded.
 *
 * FCM may display notification-payload messages automatically in background states; this service
 * also handles data messages and ensures a release check/notification is triggered promptly.
 */
class ReleasePushMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed; subscribing to release update topic")
        subscribeToReleaseTopic()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val isReleasePush = data["type"] == RELEASE_TYPE ||
            !data["tag"].isNullOrBlank() ||
            !data["version"].isNullOrBlank() ||
            message.notification != null

        if (!isReleasePush) {
            Log.d(TAG, "Ignoring non-release FCM message: ${data.keys}")
            return
        }

        Log.i(TAG, "Received release FCM message tag=${data["tag"]} version=${data["version"]}")
        // Check latest release and show/update the app's release notification (and open-update flow).
        scope.launch {
            runCatching {
                ReleaseUpdateTracker.checkAndNotify(applicationContext, force = true)
            }.onFailure { t ->
                Log.w(TAG, "Failed to handle release push message", t)
            }
        }
    }

    companion object {
        private const val TAG = "ReleasePushFCM"
        const val DEFAULT_TOPIC = "app-updates"
        const val RELEASE_TYPE = "app_release_update"

        fun subscribeToReleaseTopic(topic: String = DEFAULT_TOPIC) {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(topic)
                    .addOnSuccessListener { Log.i(TAG, "Subscribed to FCM topic '$topic'") }
                    .addOnFailureListener { e -> Log.w(TAG, "Failed to subscribe to FCM topic '$topic'", e) }
            }.onFailure { e ->
                // Firebase may be unavailable if google-services.json/config is missing in this build.
                Log.w(TAG, "FCM topic subscription unavailable", e)
            }
        }
    }
}
