package com.music.bitchord.ui.components

// Update available dialog removed — no GitHub release checks or auto-updates.

import androidx.alertdialog.AlertDialogController
import androidx.compose material3.Alert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RoundingContainer

@Composable
fun UpdateAvailableDialog(
    version: String = "1.0.0",
    onDismiss: () -> Unit = {},
    onOpenReleasePage: () -> Unit = {},
) {
    Alert(
        onDismissRequest = onDismiss,
        title = { Text("Update Available") },
        text = { Text("Version $version is available.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        supportiveButton = { TextButton(onClick = onOpenReleasePage) { Text("Open Releases") } },
    )
}