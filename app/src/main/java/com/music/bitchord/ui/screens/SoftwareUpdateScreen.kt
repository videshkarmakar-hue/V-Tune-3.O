package com.music.bitchord.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun SoftwareUpdateScreen(
    onBack: (() -> Unit)? = null,
    onRemindLater: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    showBack: Boolean = onBack != null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pending by AppUpdateManager.pending.collectAsState()
    val download by AppUpdateManager.download.collectAsState()
    val lastChecked by AppUpdateManager.lastCheckedWallMs.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var userRequestedUpdate by remember { mutableStateOf(false) }

    fun runCheck(force: Boolean = true, notify: Boolean = true) {
        if (checking) return
        checking = true
        checkFailed = false
        scope.launch {
            val result = runCatching { AppUpdateManager.checkForUpdates(app) }.getOrNull()
            checkFailed = result == AppUpdateManager.ManualCheck.Unreachable
            checking = false
            if (notify) onCheckForUpdates?.invoke()
        }
    }

    LaunchedEffect(Unit) {
        AppUpdateManager.loadPreferences(app)
        AppUpdateManager.verifyInstalled(app)
        runCheck(force = true, notify = false)
        onCheckForUpdates?.invoke()
    }

    // Reset one-tap install intent if the pending update changes/disappears.
    LaunchedEffect(pending?.version) {
        if (pending == null) userRequestedUpdate = false
    }
    LaunchedEffect(download) {
        if (download is AppUpdateManager.DownloadState.Failed) {
            userRequestedUpdate = false
        }
    }

    // When the user taps Update, download and then install automatically once ready.
    LaunchedEffect(download, pending?.version, userRequestedUpdate) {
        val info = pending ?: return@LaunchedEffect
        val ready = download as? AppUpdateManager.DownloadState.Ready ?: return@LaunchedEffect
        if (!userRequestedUpdate) return@LaunchedEffect
        if (ready.file.name.contains(info.version) && ready.file.isFile) {
            userRequestedUpdate = false
            AppUpdateManager.installApk(app, ready.file)
        }
    }

    // Resume install after the user grants "install unknown apps" permission or
    // returns from the installer, and re-verify so the UI reflects the outcome.
    DisposableEffect(lifecycleOwner, app) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppUpdateManager.resumeInstallIfWaiting(app)
                AppUpdateManager.verifyInstalled(app)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bg = Brush.radialGradient(
        colors = listOf(
            Color(0xBF292B39),
            Color(0xF21E202B),
            Color(0xFF14151D),
        ),
        radius = 900f,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            UpdateHeader(showBack = showBack, onBack = onBack)

            AppIdentityBlock(
                installedVersion = BuildConfig.VERSION_NAME,
                hasUpdate = pending != null,
            )

            Spacer(Modifier.height(20.dp))

            UpdateStatusCard(
                pending = pending,
                download = download,
                checking = checking,
                checkFailed = checkFailed,
                userRequestedUpdate = userRequestedUpdate,
                onPrimary = {
                    val info = pending
                    if (info == null) {
                        runCheck(force = true, notify = true)
                        return@UpdateStatusCard
                    }
                    when (val state = download) {
                        is AppUpdateManager.DownloadState.Ready -> {
                            val expected = AppUpdateManager.pending.value
                            if (expected != null && state.file.name.contains(expected.version)) {
                                AppUpdateManager.installApk(app, state.file)
                            } else {
                                userRequestedUpdate = false
                                runCheck(force = true, notify = false)
                            }
                        }
                        is AppUpdateManager.DownloadState.Downloading -> Unit
                        is AppUpdateManager.DownloadState.Failed,
                        is AppUpdateManager.DownloadState.Idle -> {
                            if (!info.apkUrl.isNullOrBlank()) {
                                userRequestedUpdate = true
                                AppUpdateManager.startDownload(app)
                            } else {
                                userRequestedUpdate = false
                                AppUpdateManager.openReleasePage(app)
                            }
                        }
                    }
                },
                onSecondary = {
                    val info = pending ?: return@UpdateStatusCard
                    if (!info.releaseUrl.isNullOrBlank()) {
                        AppUpdateManager.openReleasePage(app)
                    } else {
                        runCheck(force = true, notify = true)
                    }
                },
                onCheckAgain = { runCheck(force = true, notify = true) },
            )

            Spacer(Modifier.height(16.dp))

            LastCheckedRow(lastCheckedMs = lastChecked, checking = checking)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UpdateHeader(showBack: Boolean, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (showBack) {
            IconButton(
                onClick = { onBack?.invoke() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF242634))
                    .border(1.dp, Color(0x388A93BB), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = Color(0xFFE2E6F5),
                )
            }
        } else {
            Spacer(Modifier.size(40.dp))
        }

        Text(
            text = stringResource(R.string.software_update),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun AppIdentityBlock(installedVersion: String, hasUpdate: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF373A4D), Color(0xFF292B39), Color(0xFF1A1C25)),
                        ),
                    )
                    .border(1.dp, Color(0x598A93BB), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (hasUpdate) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6C7BB8))
                        .border(2.dp, Color(0xFF14151D), CircleShape),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "V-Tune 3.O",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xB3242633))
                .border(1.dp, Color(0x338A93BB), CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Current: v$installedVersion",
                color = Color(0xFF9CA3BF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(
    pending: AppUpdateManager.UpdateInfo?,
    download: AppUpdateManager.DownloadState,
    checking: Boolean,
    checkFailed: Boolean,
    userRequestedUpdate: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    val downloading = download as? AppUpdateManager.DownloadState.Downloading
    val ready = download as? AppUpdateManager.DownloadState.Ready
    val failed = download as? AppUpdateManager.DownloadState.Failed
    val hasDirectInstall = !pending?.apkUrl.isNullOrBlank()

    val title = when {
        checking && pending == null -> stringResource(R.string.checking_for_update)
        pending != null -> "Version ${pending.version} available"
        checkFailed && pending == null -> stringResource(R.string.update_check_failed)
        pending == null -> stringResource(R.string.update_up_to_date)
        else -> "Version ${pending.version} available"
    }

    val statusLabel = when {
        checking -> "Checking"
        pending == null && checkFailed -> "Unavailable"
        pending == null -> "Up to date"
        ready != null && userRequestedUpdate -> "Installing"
        ready != null -> "Ready to install"
        downloading != null -> "Downloading"
        failed != null -> "Download failed"
        !hasDirectInstall -> "Update found"
        else -> "Update available"
    }

    val subtitle = when {
        pending == null && checkFailed ->
            "Couldn't reach the update server. Check your connection and try again."
        pending == null ->
            "You're on the latest available version."
        ready != null && userRequestedUpdate ->
            "Update downloaded. Opening the installer… If prompted, allow installation to continue."
        ready != null ->
            "The update is downloaded. Tap Install update to continue."
        downloading != null ->
            "Downloading the latest version. Keep V-Tune open until it finishes."
        failed != null ->
            failed.message.ifBlank { "The download couldn't be completed. Try again." }
        !hasDirectInstall ->
            "A newer version is available. A direct install package isn't available for this device right now, but you can download the update from the release page."
        else ->
            pending.notes?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(140)
                ?.ifBlank { null }
                ?: "Tap Update to download and install the latest version."
    }

    val primaryText = when {
        checking && pending == null -> stringResource(R.string.checking_for_update)
        pending == null -> stringResource(R.string.check_for_update)
        downloading != null -> "Downloading ${(downloading.fraction * 100).toInt()}%"
        ready != null && userRequestedUpdate -> "Opening installer…"
        ready != null -> "Install update"
        failed != null -> stringResource(R.string.try_again)
        !hasDirectInstall -> "Open download page"
        else -> "Update"
    }

    val primaryIcon = when {
        pending == null -> Icons.Rounded.Refresh
        downloading != null -> Icons.Rounded.Download
        ready != null -> Icons.Rounded.Download
        failed != null -> Icons.Rounded.Refresh
        !hasDirectInstall -> Icons.Rounded.OpenInNew
        else -> Icons.Rounded.Download
    }

    val primaryEnabled = when {
        downloading != null -> false
        checking && pending == null -> false
        ready != null && userRequestedUpdate -> false
        else -> true
    }

    val showSecondary = pending != null && (hasDirectInstall || failed != null)
    val secondaryText = when {
        failed != null -> "Open download page"
        hasDirectInstall -> "Open download page"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xD9292B39), Color(0xCC1E202C)),
                ),
            )
            .border(1.dp, Color(0x408A93BB), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = Color(0xFF9CA3BF),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x296C7BB8))
                    .border(1.dp, Color(0x598A93BB), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    statusLabel,
                    color = Color(0xFFB8C2EC),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (primaryEnabled) {
                            Brush.linearGradient(listOf(Color(0xFFF5F7FF), Color(0xFFE3E7FA)))
                        } else {
                            Brush.linearGradient(listOf(Color(0x88B8BED6), Color(0x889CA3BF)))
                        },
                    )
                    .clickable(enabled = primaryEnabled) { onPrimary() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        primaryIcon,
                        contentDescription = null,
                        tint = Color(0xFF161722),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        primaryText,
                        color = Color(0xFF161722),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }

            if (downloading != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x22FFFFFF)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(downloading.fraction.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(Color(0xFF7C8BD4)),
                    )
                }
                Text(
                    "Keep this screen open while the update downloads.",
                    color = Color(0xFF9CA3BF),
                    fontSize = 11.sp,
                )
            }

            if (showSecondary && secondaryText != null && downloading == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x33242633))
                        .border(1.dp, Color(0x408A93BB), CircleShape)
                        .clickable { onSecondary() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = Color(0xFFE2E6F5),
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            secondaryText,
                            color = Color(0xFFE2E6F5),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (pending != null && !hasDirectInstall && downloading == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x33242633))
                        .border(1.dp, Color(0x408A93BB), CircleShape)
                        .clickable(enabled = !checking) { onCheckAgain() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color(0xFFE2E6F5),
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Check again",
                            color = Color(0xFFE2E6F5),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            if (pending != null && (ready != null || hasDirectInstall)) {
                Text(
                    "Install may ask for permission to install from V-Tune 3.O.",
                    color = Color(0xFF9CA3BF),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun LastCheckedRow(lastCheckedMs: Long, checking: Boolean) {
    val context = LocalContext.current
    val text = when {
        checking -> "Checking for the latest version…"
        lastCheckedMs > 0L -> {
            val df = DateFormat.getMediumDateFormat(context)
            val tf = DateFormat.getTimeFormat(context)
            val d = Date(lastCheckedMs)
            "Last checked: ${df.format(d)}, ${tf.format(d)}"
        }
        else -> "Last checked: Not yet"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFF7C86A3),
            fontSize = 12.sp,
        )
    }
}
