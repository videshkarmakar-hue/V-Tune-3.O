package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Previously showed automatic update popups. Updates are now manual-only from Settings.
 * Kept as a no-op to preserve the call site.
 */
@Composable
fun MandatoryUpdateHost(
    navigationKey: Any? = Unit,
    modifier: Modifier = Modifier,
) {
}
