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

@Composable
private fun MandatoryUpdateCard(
    update: AppUpdateManager.UpdateInfo,
    download: DownloadState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isTelevision = LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_TYPE_MASK ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val focusRequester = remember { FocusRequester() }
    // A television sits across the room: a wider card and the set's own
    // near-black alert backdrop — the one the TV dialogs use — rather than a
    // phone-sized sheet fading the app out.
    val shape = RoundedCornerShape(if (isTelevision) 18.dp else 26.dp)
    val scrim = if (isTelevision) {
        Color(0xF2111113)
    } else {
        MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f)
    }
    val cardPad = if (isTelevision) 34.dp else 24.dp

    BackHandler(enabled = true) { }

    LaunchedEffect(Unit) {
        // D-pad first, so the one actionable control is reachable on a TV.
        if (isTelevision) runCatching { focusRequester.requestFocus() }
    }

    val downloading = download as? DownloadState.Downloading
    val ready = download as? DownloadState.Ready
    val failed = download as? DownloadState.Failed

    val onPrimary: () -> Unit = when {
        downloading != null -> ({})
        ready != null -> ({ AppUpdateManager.installApk(context, ready.file) })
        failed != null -> ({ AppUpdateManager.startDownload(context) })
        update.apkUrl == null -> ({ AppUpdateManager.openReleasePage(context) })
        else -> ({ AppUpdateManager.startDownload(context) })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scrim)
            // The scrim is part of the gate: a tap misses the card and does
            // nothing, rather than falling through to the page underneath.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val cardWidth = minOf(maxWidth * 0.88f, if (isTelevision) 560.dp else 420.dp)
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .heightIn(max = maxHeight * 0.9f)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape,
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = cardPad, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(
                        width = if (isTelevision) 72.dp else 54.dp,
                        height = if (isTelevision) 48.dp else 36.dp,
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.update_required_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.update_version_transition,
                        BuildConfig.VERSION_NAME,
                        update.version,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        downloading != null ->
                            stringResource(R.string.update_required_downloading, update.version)
                        ready != null ->
                            stringResource(R.string.update_required_ready, update.version)
                        failed != null ->
                            stringResource(R.string.update_required_failed)
                        update.apkUrl == null ->
                            stringResource(R.string.update_required_manual_body, update.version)
                        else ->
                            stringResource(R.string.update_required_body, update.version)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (downloading != null || failed != null) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                    ) {
                        if (downloading != null && downloading.fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(downloading.fraction)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }

                if (!update.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    AlertRule()
                    Text(
                        text = stringResource(R.string.whats_new),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // RichText leans on LocalContentColor; this card is a
                        // plain Column rather than a Surface, so provide it.
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                        ) {
                            RichText(style = RichTextStyle.Default) {
                                Markdown(update.notes)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onPrimary,
                    enabled = downloading == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    ),
                ) {
                    Text(
                        text = when {
                            downloading != null -> stringResource(
                                R.string.update_downloading_button,
                                (downloading.fraction * 100).toInt(),
                            )
                            ready != null -> stringResource(R.string.install_update)
                            failed != null -> stringResource(R.string.retry_update)
                            update.apkUrl == null -> stringResource(R.string.open_download_page)
                            else -> stringResource(R.string.update_now)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (ready != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.update_required_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
