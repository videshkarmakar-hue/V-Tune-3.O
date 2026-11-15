package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.music.bitchord.data.AppUpdateManager
import com.music.bitchord.data.AppUpdateManager.DownloadState
import com.music.bitchord.ui.screens.SoftwareUpdateScreen
import kotlinx.coroutines.delay

/**
 * Update host mounted once at the activity root. Shows the Software Update screen
 * when a newer release is detected (and not snoozed), while keeping release checks
 * active in foreground/background flows.
 */
@Composable
fun MandatoryUpdateHost(
    navigationKey: Any? = Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val update by AppUpdateManager.pending.collectAsStateWithLifecycle()
    val download by AppUpdateManager.download.collectAsStateWithLifecycle()
    val snoozeUntil by AppUpdateManager.snoozeUntil.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AppUpdateManager.check(context, force = true) }
    LaunchedEffect(navigationKey) { AppUpdateManager.checkIfStale(context) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                AppUpdateManager.checkIfStale(context)
                delay(AppUpdateManager.FOREGROUND_CHECK_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(update?.version, update?.apkUrl) {
        val info = update ?: return@LaunchedEffect
        // Auto-start download when an installable APK is available and user has not snoozed.
        if (!AppUpdateManager.isSnoozed() && info.apkUrl != null && AppUpdateManager.download.value is DownloadState.Idle) {
            AppUpdateManager.startDownload(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppUpdateManager.verifyInstalled(context)
                AppUpdateManager.resumeInstallIfWaiting(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pending = update ?: return
    val snoozed = System.currentTimeMillis() < snoozeUntil
    if (snoozed) return

    BackHandler(enabled = true) { AppUpdateManager.snooze(context) }

    Dialog(
        onDismissRequest = { AppUpdateManager.snooze(context) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        SoftwareUpdateScreen(
            onBack = { AppUpdateManager.snooze(context) },
            onRemindLater = { AppUpdateManager.snooze(context) },
            showBack = true,
            modifier = modifier,
        )
    }
}
