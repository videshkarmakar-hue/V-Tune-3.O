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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlin.math.roundToInt

/**
 * Settings → App Update.
 *
 * A manual updater: the user checks for a release, downloads it, and installs it
 * when they choose to. Nothing is forced on launch and nothing blocks the app.
 */
@Composable
fun SoftwareUpdateScreen(
    onBack: (() -> Unit)? = null,
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
    var showInfo by remember { mutableStateOf(false) }

    fun checkNow() {
        if (checking) return
        checking = true
        checkFailed = false
        scope.launch {
            val result = runCatching { AppUpdateManager.checkForUpdates(app) }.getOrNull()
            checkFailed = result == AppUpdateManager.ManualCheck.Unreachable
            checking = false
        }
    }

    // Check when the user opens this page, so the status is current.
    LaunchedEffect(Unit) {
        AppUpdateManager.loadPreferences(app)
        AppUpdateManager.verifyInstalled(app)
        checking = true
        val result = runCatching { AppUpdateManager.checkForUpdates(app) }.getOrNull()
        checkFailed = result == AppUpdateManager.ManualCheck.Unreachable
        checking = false
    }

    // Back from the installer or the install-permission screen: finish up and
    // re-read the installed version.
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

    val info = pending
    val primaryAction: () -> Unit = {
        when (val state = download) {
            is AppUpdateManager.DownloadState.Ready -> AppUpdateManager.installApk(context, state.file)
            is AppUpdateManager.DownloadState.Downloading -> Unit
            is AppUpdateManager.DownloadState.Failed,
            is AppUpdateManager.DownloadState.Idle -> {
                when {
                    info == null -> checkNow()
                    !info.apkUrl.isNullOrBlank() -> AppUpdateManager.startDownload(app)
                    else -> AppUpdateManager.openReleasePage(app)
                }
            }
        }
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
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 28.dp),
        ) {
            ScreenHeader(onBack = onBack, onInfo = { showInfo = true })

            AppIdentity()

            Spacer(Modifier.height(20.dp))

            UpdateCard(
                info = info,
                download = download,
                checking = checking,
                checkFailed = checkFailed,
                onPrimary = primaryAction,
            )

            Spacer(Modifier.height(14.dp))

            LastCheckedRow(lastCheckedMs = lastChecked, checking = checking)

            Spacer(Modifier.height(72.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("App Update") },
            text = {
                Text(
                    "Open this page to check for a new version. If one is available, " +
                        "tap Download and then Install to update V-Tune 3.O.\n\n" +
                        "Installing may ask once for permission to install apps from " +
                        "V-Tune 3.O. After you allow it, updates install from here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun ScreenHeader(onBack: (() -> Unit)?, onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderButton(onClick = onBack, enabled = onBack != null) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                tint = Color(0xFFE2E6F5),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "App Update",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        HeaderButton(onClick = onInfo, enabled = true) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = Color(0xFFB8BED6),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun HeaderButton(onClick: (() -> Unit)?, enabled: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF242634))
            .border(1.dp, Color(0x388A93BB), CircleShape)
            .clickable(enabled = enabled) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun AppIdentity() {
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
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6C7BB8))
                    .border(2.dp, Color(0xFF14151D), CircleShape),
            )
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
                text = "Current: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                color = Color(0xFF9CA3BF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    info: AppUpdateManager.UpdateInfo?,
    download: AppUpdateManager.DownloadState,
    checking: Boolean,
    checkFailed: Boolean,
    onPrimary: () -> Unit,
) {
    val downloading = download as? AppUpdateManager.DownloadState.Downloading
    val ready = download as? AppUpdateManager.DownloadState.Ready
    val failed = download as? AppUpdateManager.DownloadState.Failed
    val percent = ((downloading?.fraction ?: 0f) * 100f).roundToInt().coerceIn(0, 100)
    val hasApk = !info?.apkUrl.isNullOrBlank()

    val title = when {
        info != null -> "Version ${info.version}"
        checking -> stringResource(R.string.checking_for_update)
        checkFailed -> stringResource(R.string.update_check_failed)
        else -> stringResource(R.string.update_up_to_date)
    }

    val badge = when {
        checking && info == null -> "Checking"
        info == null && checkFailed -> "Unavailable"
        info == null -> "Up to date"
        ready != null -> "Ready to Install"
        downloading != null -> "Downloading"
        failed != null -> "Download Failed"
        else -> "Update Available"
    }

    val subtitle = when {
        info == null && checkFailed ->
            "Couldn't reach the update server. Check your connection and try again."
        info == null && checking -> "Looking for the newest release…"
        info == null -> "You're on the latest available version."
        ready != null -> "Downloaded. Tap Install to finish updating."
        downloading != null -> "Downloading the update. Keep this screen open until it finishes."
        failed != null -> failed.message.takeIf { it.isNotBlank() }
            ?: "The download couldn't be completed. Try again."
        !hasApk -> "A new version is available. Tap Update Download to get it."
        else -> "A new version is available. Download it when you're ready."
    }

    val primaryText = when {
        downloading != null -> "Downloading $percent%"
        ready != null -> stringResource(R.string.install_now)
        info != null && failed != null -> stringResource(R.string.try_again)
        info != null && hasApk -> stringResource(R.string.download_update)
        info != null -> stringResource(R.string.open_download_page)
        checking -> stringResource(R.string.checking_for_update)
        else -> stringResource(R.string.check_for_update)
    }

    val primaryIcon = when {
        info == null -> Icons.Rounded.Refresh
        failed != null -> Icons.Rounded.Refresh
        hasApk -> Icons.Rounded.Download
        else -> Icons.Rounded.Download
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xD9292B39), Color(0xCC1E202C))),
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
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
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
                    text = badge.uppercase(),
                    color = Color(0xFFB8C2EC),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(
                    if (downloading == null) {
                        Brush.linearGradient(listOf(Color(0xFFF5F7FF), Color(0xFFE3E7FA)))
                    } else {
                        Brush.linearGradient(listOf(Color(0x88B8BED6), Color(0x889CA3BF)))
                    },
                )
                .clickable(enabled = downloading == null) { onPrimary() },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    primaryIcon,
                    contentDescription = null,
                    tint = Color(0xFF161722),
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = primaryText,
                    color = Color(0xFF161722),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
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
        }

        if (info != null && hasApk) {
            Text(
                text = "Installing may ask for permission to install from V-Tune 3.O.",
                color = Color(0xFF7C86A3),
                fontSize = 11.sp,
            )
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
    Text(
        text = text,
        color = Color(0xFF7C86A3),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}
