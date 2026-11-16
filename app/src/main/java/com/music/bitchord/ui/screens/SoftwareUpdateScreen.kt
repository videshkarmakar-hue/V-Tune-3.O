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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager
import com.music.bitchord.ui.components.IosSwitch
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.roundToInt

/**
 * Settings → Check for Updates, as the page of its own the design asks for:
 * the installed build, the release waiting to be installed, and the switches
 * that decide how that release gets here.
 *
 * This screen never lifts the update gate — [com.music.bitchord.ui.components.MandatoryUpdateHost]
 * owns that and draws it over the whole app. What the switches do is pick how
 * the gate gets its APK: automatically, or only once the button is tapped.
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
    val autoUpdateWifi by AppUpdateManager.autoUpdateWifi.collectAsState()
    val allowCellular by AppUpdateManager.allowCellularDownload.collectAsState()
    val frequency by AppUpdateManager.checkFrequency.collectAsState()

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

    // The gate has already checked at launch; this asks again on opening so the
    // version on the card is the answer for right now.
    LaunchedEffect(Unit) {
        AppUpdateManager.loadPreferences(app)
        AppUpdateManager.verifyInstalled(app)
        checking = true
        val result = runCatching { AppUpdateManager.checkForUpdates(app) }.getOrNull()
        checkFailed = result == AppUpdateManager.ManualCheck.Unreachable
        checking = false
    }

    // Back from the installer, or from the "install unknown apps" screen: finish
    // what was started and re-read the installed version.
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

            Spacer(Modifier.height(26.dp))

            UpdatePreferences(
                autoUpdateWifi = autoUpdateWifi,
                onAutoUpdateWifi = { AppUpdateManager.setAutoUpdateWifi(app, it) },
                allowCellular = allowCellular,
                onAllowCellular = { AppUpdateManager.setAllowCellularDownload(app, it) },
                frequency = frequency,
                onFrequency = { AppUpdateManager.setCheckFrequency(app, it) },
                lastCheckedMs = lastChecked,
                checking = checking,
            )

            // Room for the dock under the last card, as the design leaves.
            Spacer(Modifier.height(72.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("About updates") },
            text = {
                Text(
                    "V-Tune 3.O checks for a new release every time it opens. Updates are " +
                        "required — a published release blocks the app until it is installed, " +
                        "so there is no way to skip one.\n\n" +
                        "Android asks once for permission to install updates. Allow " +
                        "\"install unknown apps\" for V-Tune 3.O and the installer opens by " +
                        "itself from then on.",
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
            text = "Software Update",
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

/** The icon, the name and the build in hand — the thing being updated. */
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
        !hasApk -> "Update Found"
        else -> "Update Available"
    }

    val subtitle = when {
        info == null && checkFailed ->
            "Couldn't reach the update server. Check your connection and try again."
        info == null && checking -> "Looking for the newest release…"
        info == null -> "You're on the latest available version."
        ready != null -> "Downloaded. Finish in the installer to continue."
        downloading != null -> "Downloading the update. Keep V-Tune open until it finishes."
        failed != null -> failed.message.takeIf { it.isNotBlank() }
            ?: "The download couldn't be completed. Try again."
        !hasApk -> "This release has no direct install package for this device. Open the download page instead."
        else -> "This update is required. Download it to continue using V-Tune 3.O."
    }

    val primaryText = when {
        downloading != null -> "Downloading $percent%"
        ready != null -> stringResource(R.string.install_now)
        info != null && failed != null -> stringResource(R.string.try_again)
        info != null && hasApk -> "Download & Install"
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

        if (info != null) {
            Text(
                text = "Install may ask for permission to install from V-Tune 3.O.",
                color = Color(0xFF7C86A3),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun UpdatePreferences(
    autoUpdateWifi: Boolean,
    onAutoUpdateWifi: (Boolean) -> Unit,
    allowCellular: Boolean,
    onAllowCellular: (Boolean) -> Unit,
    frequency: AppUpdateManager.CheckFrequency,
    onFrequency: (AppUpdateManager.CheckFrequency) -> Unit,
    lastCheckedMs: Long,
    checking: Boolean,
) {
    Text(
        text = "UPDATE PREFERENCES",
        color = Color(0xFF9CA3BF),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3222430))
            .border(1.dp, Color(0x388A93BB), RoundedCornerShape(16.dp)),
    ) {
        PreferenceRow(
            title = "Auto-Update over Wi-Fi",
            subtitle = "Installs a new release without asking",
            trailing = {
                IosSwitch(checked = autoUpdateWifi, onCheckedChange = onAutoUpdateWifi)
            },
            onClick = { onAutoUpdateWifi(!autoUpdateWifi) },
        )

        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF232326))

        PreferenceRow(
            title = "Download over Cellular Data",
            subtitle = "Carrier data charges may apply",
            trailing = {
                IosSwitch(checked = allowCellular, onCheckedChange = onAllowCellular)
            },
            onClick = { onAllowCellular(!allowCellular) },
        )

        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF232326))

        PreferenceRow(
            title = "Check Frequency",
            subtitle = lastCheckedLabel(lastCheckedMs = lastCheckedMs, checking = checking),
            trailing = {
                FrequencyPill(frequency = frequency, onFrequency = onFrequency)
            },
            onClick = null,
        )
    }
}

@Composable
private fun PreferenceRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFF9CA3BF),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        trailing()
    }
}

@Composable
private fun FrequencyPill(
    frequency: AppUpdateManager.CheckFrequency,
    onFrequency: (AppUpdateManager.CheckFrequency) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF242634))
                .border(1.dp, Color(0x408A93BB), CircleShape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = frequency.label,
                color = Color(0xFFD7DCF0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF9CA3BF),
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            AppUpdateManager.CheckFrequency.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onFrequency(option)
                    },
                )
            }
        }
    }
}

private val AppUpdateManager.CheckFrequency.label: String
    get() = when (this) {
        AppUpdateManager.CheckFrequency.DAILY -> "Daily"
        AppUpdateManager.CheckFrequency.WEEKLY -> "Weekly"
        AppUpdateManager.CheckFrequency.MANUAL -> "Manual"
    }

@Composable
private fun lastCheckedLabel(lastCheckedMs: Long, checking: Boolean): String {
    val context = LocalContext.current
    if (checking) return "Checking for the latest version…"
    if (lastCheckedMs <= 0L) return "Last checked: Not yet"
    val df = DateFormat.getMediumDateFormat(context)
    val tf = DateFormat.getTimeFormat(context)
    val when_ = Date(lastCheckedMs)
    return "Last checked: ${df.format(when_)}, ${tf.format(when_)}"
}
