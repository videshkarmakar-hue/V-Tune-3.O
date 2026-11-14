package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateManager
import com.music.bitchord.data.AppUpdateManager.DownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The mandatory update gate. Mounted once at the activity root — phone and TV
 * alike — and drawn in its own window so no page, sheet or alert can sit above
 * it.
 *
 * The card itself has no dismiss affordance and the dialog refuses both Back
 * and an outside tap. The only exit is [AppUpdateManager.pending] becoming
 * null, which happens when the package manager reports that the installed build
 * has caught up.
 *
 * [navigationKey] changes whenever the visible section changes. That re-checks
 * the release without turning every tab tap into a request — the manager holds
 * the result for its own TTL.
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

    // Every cold start checks the release immediately.
    LaunchedEffect(Unit) { AppUpdateManager.check(context, force = true) }

    // Every navigation asks again, within the manager's cache window.
    LaunchedEffect(navigationKey) { AppUpdateManager.checkIfStale(context) }

    // A release published while the app is already open is still enforced.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(AppUpdateManager.FOREGROUND_CHECK_INTERVAL_MS)
                AppUpdateManager.checkIfStale(context)
            }
        }
    }

    // Returning from the installer (or from the unknown-sources screen) is the
    // moment the installed version can be trusted again.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AppUpdateManager.verifyInstalled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pending = update ?: return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        MandatoryUpdateCard(
            update = pending,
            download = download,
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
    val scope = rememberCoroutineScope()
    val isTelevision = LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_TYPE_MASK ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(26.dp)

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
        failed != null -> ({ scope.launch { AppUpdateManager.downloadApk(context) } })
        update.apkUrl == null -> ({ AppUpdateManager.openReleasePage(context) })
        else -> ({ scope.launch { AppUpdateManager.downloadApk(context) } })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f))
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
            val cardWidth = minOf(maxWidth * 0.88f, 420.dp)
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .heightIn(max = maxHeight * 0.9f)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape,
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(width = 54.dp, height = 36.dp),
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
