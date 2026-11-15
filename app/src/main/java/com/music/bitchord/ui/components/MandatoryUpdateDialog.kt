package com.music.bitchord.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Non-closable, non-skippable mandatory update gate.
 *
 * When a newer GitHub release is detected, the app blocks usage until the update is
 * downloaded and installed. Users cannot dismiss, snooze, or skip this flow.
 */
@Composable
fun MandatoryUpdateHost(
    navigationKey: Any? = Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val pending by AppUpdateManager.pending.collectAsState()
    val download by AppUpdateManager.download.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var resolvingApk by remember { mutableStateOf(false) }

    fun forceCheck() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { AppUpdateManager.check(app, force = true) }
            checking = false
        }
    }

    // Initial load + immediate check so updates are detected as soon as the app opens.
    LaunchedEffect(Unit) {
        AppUpdateManager.loadPreferences(app)
        AppUpdateManager.verifyInstalled(app)
        AppUpdateManager.resumeInstallIfWaiting(app)
        // Clear any previous snooze; mandatory updates cannot be deferred.
        if (AppUpdateManager.isSnoozed()) AppUpdateManager.snooze(app, durationMs = 0L)
        runCatching { AppUpdateManager.check(app, force = true) }
    }

    // Re-check when navigation/app state key changes while in use.
    LaunchedEffect(navigationKey) {
        AppUpdateManager.verifyInstalled(app)
        AppUpdateManager.resumeInstallIfWaiting(app)
        runCatching { AppUpdateManager.checkIfStale(app) }
    }

    // Resume/foreground: verify install result, resume permission-gated install, and check again.
    DisposableEffect(lifecycleOwner, app) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    AppUpdateManager.verifyInstalled(app)
                    AppUpdateManager.resumeInstallIfWaiting(app)
                    if (AppUpdateManager.isSnoozed()) AppUpdateManager.snooze(app, durationMs = 0L)
                    runCatching { AppUpdateManager.check(app, force = true) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While app is open, keep polling GitHub so newly published releases are detected promptly.
    LaunchedEffect(app) {
        while (isActive) {
            delay(60_000L)
            if (AppUpdateManager.pending.value == null) {
                runCatching { AppUpdateManager.check(app, force = true) }
            }
        }
    }

    // Mandatory auto-update: if an update is pending, automatically download/install flow.
    LaunchedEffect(pending?.version, pending?.apkUrl, download) {
        val info = pending ?: return@LaunchedEffect
        if (AppUpdateManager.isSnoozed()) AppUpdateManager.snooze(app, durationMs = 0L)
        when (val d = download) {
            is AppUpdateManager.DownloadState.Downloading -> Unit
            is AppUpdateManager.DownloadState.Ready -> {
                // Directly continue to installer; user confirms in system installer if required.
                AppUpdateManager.installApk(context.findActivity() ?: context, d.file)
            }
            is AppUpdateManager.DownloadState.Idle,
            is AppUpdateManager.DownloadState.Failed -> {
                if (!info.apkUrl.isNullOrBlank()) {
                    AppUpdateManager.startDownload(app)
                } else if (!resolvingApk) {
                    resolvingApk = true
                    val refreshed = runCatching { AppUpdateManager.check(app, force = true) }.getOrNull()
                    resolvingApk = false
                    if (!refreshed?.apkUrl.isNullOrBlank()) {
                        AppUpdateManager.startDownload(app)
                    }
                }
            }
        }
    }

    val info = pending ?: return

    Dialog(
        onDismissRequest = { /* non-closable mandatory update */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        MandatoryUpdateContent(
            modifier = modifier.fillMaxSize(),
            version = info.version,
            notes = info.notes,
            hasApk = !info.apkUrl.isNullOrBlank(),
            download = download,
            checking = checking || resolvingApk,
            onPrimary = {
                val d = AppUpdateManager.download.value
                val p = AppUpdateManager.pending.value
                when (d) {
                    is AppUpdateManager.DownloadState.Ready ->
                        AppUpdateManager.installApk(context.findActivity() ?: context, d.file)
                    is AppUpdateManager.DownloadState.Downloading -> Unit
                    is AppUpdateManager.DownloadState.Failed -> {
                        if (!p?.apkUrl.isNullOrBlank()) {
                            AppUpdateManager.startDownload(app)
                        } else {
                            forceCheck()
                        }
                    }
                    is AppUpdateManager.DownloadState.Idle -> {
                        if (!p?.apkUrl.isNullOrBlank()) {
                            AppUpdateManager.startDownload(app)
      
