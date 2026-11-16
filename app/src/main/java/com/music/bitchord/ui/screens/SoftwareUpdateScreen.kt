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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val scope = rememberCoroutineScope()
    val pending by AppUpdateManager.pending.collectAsState()
    val download by AppUpdateManager.download.collectAsState()
    val lastChecked by AppUpdateManager.lastCheckedWallMs.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }

    fun runCheck() {
        if (checking) return
        checking = true
        checkFailed = false
        scope.launch {
            val result = runCatching { AppUpdateManager.checkForUpdates(context) }.getOrNull()
            checkFailed = result == AppUpdateManager.ManualCheck.Unreachable
            checking = false
            onCheckForUpdates?.invoke()
        }
    }

    LaunchedEffect(Unit) {
        AppUpdateManager.loadPreferences(context.applicationContext)
        runCheck()
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
                lastCheckedMs = lastChecked,
                onPrimary = {
                    val info = pending
                    if (info == null) {
                        runCheck()
                        return@UpdateStatusCard
                    }
                    when (download) {
                        is AppUpdateManager.DownloadState.Ready ->
                            AppUpdateManager.installApk(context, (download as AppUpdateManager.DownloadState.Ready).file)
                        is AppUpdateManager.DownloadState.Downloading -> Unit
                        is AppUpdateManager.DownloadState.Failed,
                        is AppUpdateManager.DownloadState.Idle -> {
                            if (!info.apkUrl.isNullOrBlank()) {
                                AppUpdateManager.startDownload(context)
                            } else {
                                scope.launch {
                                    checking = true
                                    checkFailed = false
                                    val refreshed = runCatching { AppUpdateManager.check(context, force = true) }.getOrNull()
                                    checking = false
                                    checkFailed = refreshed == null
                                    val apkUrl = refreshed?.apkUrl ?: AppUpdateManager.pending.value?.apkUrl
                                    if (!apkUrl.isNullOrBlank()) {
                                        AppUpdateManager.startDownload(context)
                                    }
                                }
                            }
                        }
                    }
                },
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
                      
