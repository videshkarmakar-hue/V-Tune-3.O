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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
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
 * When a newer release is detected, the app blocks usage until the update is
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
    // Avoid re-launching the system installer in a loop for the same ready APK.
    // Auto-prompt once per ready file; re-prompt on resume or explicit user action.
    var promptedInstallForPath by remember { mutableStateOf<String?>(null) }
    var resumeRepromptTick by remember { mutableStateOf(0) }

    fun promptInstall(file: java.io.File) {
        promptedInstallForPath = file.absolutePath
        AppUpdateManager.installApk(context.findActivity() ?: context, file)
    }

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
    // Allow the ready installer prompt to be shown again after user returns (e.g. cancelled installer).
    DisposableEffect(lifecycleOwner, app) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    AppUpdateManager.verifyInstalled(app)
                    AppUpdateManager.resumeInstallIfWaiting(app)
                    if (AppUpdateManager.isSnoozed()) AppUpdateManager.snooze(app, durationMs = 0L)
                    resumeRepromptTick++
                    runCatching { AppUpdateManager.check(app, force = true) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While app is open, keep polling for newly published releases so updates are detected promptly.
    LaunchedEffect(app) {
        while (isActive) {
            delay(60_000L)
            if (AppUpdateManager.pending.value == null) {
                runCatching { AppUpdateManager.check(app, force = true) }
            }
        }
    }

    // Mandatory auto-update: if an update is pending, automatically download/install flow.
    LaunchedEffect(pending?.version, pending?.apkUrl, download, resumeRepromptTick) {
        val info = pending ?: return@LaunchedEffect
        if (AppUpdateManager.isSnoozed()) AppUpdateManager.snooze(app, durationMs = 0L)
        when (val d = download) {
            is AppUpdateManager.DownloadState.Downloading -> Unit
            is AppUpdateManager.DownloadState.Ready -> {
                // Auto-prompt once per ready file; re-prompt on resume or explicit user action.
                if (promptedInstallForPath != d.file.absolutePath) {
                    promptInstall(d.file)
                }
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
                        promptInstall(d.file)
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
                        } else {
                            forceCheck()
                        }
                    }
                }
            },
            onOpenRelease = {
                // Fallback only when no direct APK can be resolved; still required to update.
                AppUpdateManager.openReleasePage(context)
            },
        )
    }
}

@Composable
private fun MandatoryUpdateContent(
    modifier: Modifier = Modifier,
    version: String,
    notes: String?,
    hasApk: Boolean,
    download: AppUpdateManager.DownloadState,
    checking: Boolean,
    onPrimary: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    val downloading = download as? AppUpdateManager.DownloadState.Downloading
    val ready = download as? AppUpdateManager.DownloadState.Ready
    val failed = download as? AppUpdateManager.DownloadState.Failed

    val status = when {
        ready != null -> "Ready to install"
        downloading != null -> "Downloading ${(downloading.fraction * 100).toInt().coerceIn(0, 100)}%"
        failed != null -> "Download failed"
        checking -> "Checking update..."
        hasApk -> "Update required"
        else -> "Update required (preparing download)"
    }

    val primaryText = when {
        ready != null -> stringResource(R.string.install_now)
        downloading != null -> "Downloading ${(downloading.fraction * 100).toInt().coerceIn(0, 100)}%"
        failed != null -> stringResource(R.string.try_again)
        else -> stringResource(R.string.download_update)
    }

    val bg = Brush.verticalGradient(
        listOf(Color(0xFF14151D), Color(0xFF1E202B), Color(0xFF14151D))
    )

    Box(
        modifier = modifier
            .background(bg)
            .statusBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xCC1E202C))
                .border(1.dp, Color(0x408A93BB), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Update Required",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "V-Tune 3.O v$version is available. This update is required and cannot be skipped.",
                color = Color(0xFFB8BED6),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Installed: v${BuildConfig.VERSION_NAME}  →  Latest: v$version",
                color = Color(0xFF9CA3BF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x296C7BB8))
                    .border(1.dp, Color(0x598A93BB), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(status, color = Color(0xFFB8C2EC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            if (!notes.isNullOrBlank()) {
                Text(
                    text = notes.lineSequence().take(8).joinToString("\n").take(600),
                    color = Color(0xFF9CA3BF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (downloading != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x22FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(downloading.fraction.coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(Color(0xFF7C8BD4)),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(
                        if (downloading == null) Brush.linearGradient(listOf(Color(0xFFF5F7FF), Color(0xFFE3E7FA)))
                        else Brush.linearGradient(listOf(Color(0x88B8BED6), Color(0x889CA3BF)))
                    )
                    .clickable(enabled = downloading == null) { onPrimary() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF161722), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(primaryText, color = Color(0xFF161722), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            if (!hasApk && failed == null && ready == null) {
                Text(
                    text = "If direct download is unavailable for this release, open the release page to get the update.",
                    color = Color(0xFF9CA3BF),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3242633))
                        .border(1.dp, Color(0x338A93BB), CircleShape)
                        .clickable { onOpenRelease() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Open Release Page", color = Color(0xFFB8BED6), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }

            if (failed != null) {
                Text(
                    text = failed.message,
                    color = Color(0xFFE0A0A0),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                text = "The app will continue automatically after the update is installed.",
                color = Color(0xFF7C86A3),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
