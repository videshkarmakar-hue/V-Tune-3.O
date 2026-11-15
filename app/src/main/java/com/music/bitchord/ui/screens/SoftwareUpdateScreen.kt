package com.music.bitchord.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager
import com.music.bitchord.data.settings.AppSettings
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
    val scope = rememberCoroutineScope()
    val pending by AppUpdateManager.pending.collectAsState()
    val download by AppUpdateManager.download.collectAsState()
    val autoWifi by AppUpdateManager.autoUpdateWifi.collectAsState()
    val allowCellular by AppUpdateManager.allowCellularDownload.collectAsState()
    val frequency by AppUpdateManager.checkFrequency.collectAsState()
    val lastChecked by AppUpdateManager.lastCheckedWallMs.collectAsState()
    val backgroundChecks by AppSettings.backgroundReleaseChecks.collectAsStateWithLifecycle()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort: checks run regardless; notifications post when granted */ }

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
                onPrimary = {
                    val info = pending
                    if (info == null) {
                        onCheckForUpdates?.invoke()
                        return@UpdateStatusCard
                    }
                    when (val d = download) {
                        is AppUpdateManager.DownloadState.Ready -> AppUpdateManager.installApk(context, d.file)
                        is AppUpdateManager.DownloadState.Downloading -> Unit
                        is AppUpdateManager.DownloadState.Failed -> AppUpdateManager.startDownload(context)
                        else -> {
                            if (info.apkUrl != null) {
                                AppUpdateManager.startDownload(context)
                            } else {
                                scope.launch {
                                    val refreshed = AppUpdateManager.check(context, force = true)
                                    if (refreshed?.apkUrl != null) {
                                        AppUpdateManager.startDownload(context)
                                    } else {
                                        AppUpdateManager.openReleasePage(context)
                                    }
                                }
                            }
                        }
                    }
                },
                onRemindLater = onRemindLater,
            )

            Spacer(Modifier.height(28.dp))

            UpdatePreferencesSection(
                autoWifi = autoWifi,
                allowCellular = allowCellular,
                frequency = frequency,
                lastCheckedMs = lastChecked,
                backgroundChecks = backgroundChecks,
                onAutoWifi = { AppUpdateManager.setAutoUpdateWifi(context, it) },
                onAllowCellular = { AppUpdateManager.setAllowCellularDownload(context, it) },
                onFrequency = { AppUpdateManager.setCheckFrequency(context, it) },
                onBackgroundChecks = { enabled ->
                    AppSettings.setBackgroundReleaseChecks(context, enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
            )

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

        IconButton(
            onClick = { },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF242634))
                .border(1.dp, Color(0x388A93BB), CircleShape),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = Color(0xFFB8BED6),
            )
        }
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
                            listOf(Color(0xFF373A4D), Color(0xFF292B39), Color(0xFF1A1C25))
                        )
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
    onPrimary: () -> Unit,
    onRemindLater: (() -> Unit)?,
) {
    val downloading = download as? AppUpdateManager.DownloadState.Downloading
    val ready = download as? AppUpdateManager.DownloadState.Ready
    val failed = download as? AppUpdateManager.DownloadState.Failed

    val title = if (pending != null) "Version ${pending.version}" else "You're up to date"
    val statusLabel = when {
        pending == null -> "Up to date"
        ready != null -> "Ready to Install"
        downloading != null -> "Downloading"
        failed != null -> "Download failed"
        else -> "Update available"
    }
    val primaryText = when {
        pending == null -> stringResource(R.string.check_for_update)
        downloading != null -> "Downloading ${(downloading.fraction * 100).toInt()}%"
        ready != null -> stringResource(R.string.install_now)
        failed != null -> stringResource(R.string.try_again)
        else -> stringResource(R.string.download_update)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xD9292B39), Color(0xCC1E202C))
                )
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
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (pending?.notes?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pending.notes.lineSequence().firstOrNull().orEmpty().take(120),
                        color = Color(0xFF9CA3BF),
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
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
                        if (downloading == null) Brush.linearGradient(listOf(Color(0xFFF5F7FF), Color(0xFFE3E7FA)))
                        else Brush.linearGradient(listOf(Color(0x88B8BED6), Color(0x889CA3BF)))
                    )
                    .clickable(enabled = downloading == null) { onPrimary() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF161722), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(primaryText, color = Color(0xFF161722), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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

            if (onRemindLater != null && pending != null && ready == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xB3242633))
                        .border(1.dp, Color(0x338A93BB), CircleShape)
                        .clickable { onRemindLater() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.remind_me_later),
                        color = Color(0xFFB8BED6),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatePreferencesSection(
    autoWifi: Boolean,
    allowCellular: Boolean,
    frequency: AppUpdateManager.CheckFrequency,
    lastCheckedMs: Long,
    backgroundChecks: Boolean,
    onAutoWifi: (Boolean) -> Unit,
    onAllowCellular: (Boolean) -> Unit,
    onFrequency: (AppUpdateManager.CheckFrequency) -> Unit,
    onBackgroundChecks: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
    val lastCheckedText = if (lastCheckedMs > 0L) {
        val df = DateFormat.getMediumDateFormat(context)
        val tf = DateFormat.getTimeFormat(context)
        val d = Date(lastCheckedMs)
        "${df.format(d)}, ${tf.format(d)}"
    } else "Not checked yet"

    Column {
        Text(
            text = "UPDATE PREFERENCES",
            color = Color(0xFF9CA3BF),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 10.dp, start = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xB3222430))
                .border(1.dp, Color(0x388A93BB), RoundedCornerShape(16.dp)),
        ) {
            PrefToggleRow(
                title = stringResource(R.string.background_release_checks_title),
                subtitle = buildString {
                    append(stringResource(R.string.background_release_checks_subtitle))
                    if (backgroundChecks && !notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        append(" • ")
                        append(stringResource(R.string.notification_permission_needed_for_updates))
                    }
                },
                checked = backgroundChecks,
                onCheckedChange = onBackgroundChecks,
                showDivider = true,
            )
            PrefToggleRow(
                title = "Auto-Update over Wi-Fi",
                subtitle = "Installs updates when device is charging",
                checked = autoWifi,
                onCheckedChange = onAutoWifi,
                showDivider = true,
            )
            PrefToggleRow(
                title = "Download over Cellular Data",
                subtitle = "Carrier data charges may apply",
                checked = allowCellular,
                onCheckedChange = onAllowCellular,
                showDivider = true,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Check Frequency", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("Last checked: $lastCheckedText", color = Color(0xFF9CA3BF), fontSize = 11.sp)
                    }
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF242634))
                            .border(1.dp, Color(0x408A93BB), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(frequencyLabel(frequency), color = Color(0xFFD7DCF0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Color(0xFF7C86A3), modifier = Modifier.size(16.dp))
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppUpdateManager.CheckFrequency.entries.forEach { opt ->
                            val selected = opt == frequency
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color(0xFF6C7BB8) else Color(0xFF242634))
                                    .border(1.dp, Color(0x408A93BB), CircleShape)
                                    .clickable { onFrequency(opt); expanded = false }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(frequencyLabel(opt), color = if (selected) Color.White else Color(0xFFB8BED6), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color(0xFF9CA3BF), fontSize = 11.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF6C7BB8),
                    uncheckedThumbColor = Color(0xFF5D627A),
                    uncheckedTrackColor = Color(0xFF1F212D),
                    uncheckedBorderColor = Color(0x408A93BB),
                ),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF232326)),
            )
        }
    }
}

private fun frequencyLabel(f: AppUpdateManager.CheckFrequency): String = when (f) {
    AppUpdateManager.CheckFrequency.DAILY -> "Daily"
    AppUpdateManager.CheckFrequency.WEEKLY -> "Weekly"
    AppUpdateManager.CheckFrequency.MANUAL -> "Manual"
}
