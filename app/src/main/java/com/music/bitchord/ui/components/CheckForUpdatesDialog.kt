@Composable
fun CheckForUpdatesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check for Updates") },
        text = { /* Updates check removed */ },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        supportiveButton = { TextButton(onClick = {}) { Text("Check for Update") } },
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // Current Version
            Text(
                text = "V-Tune 3.O",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                textAlign = Center,
            )

            Text(
                text = "Current Version: 1.0.0",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
            )

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // Update Status
            Text(
                text = "Up-to-Date",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
            )

            Text(
                text = "V-Tune 3.O is up-to-date. You already have the latest version.",
                style = MaterialTheme.typography.body2,
                textAlign = Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // What's New Section
            Text(
                text = "What's New",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
                textAlign = Center,
            )

            Text(
                text = "New features and improvements are regularly added to V-Tune 3.O.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // Update Preferences
            Text(
                text = "Update Preferences",
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.W600,
                textAlign = Center,
            )

            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Auto-Update over Wi-Fi
                Box(modifier = Modifier.size(80.dp)) {
                    Text(
                        "Wi-Fi Only",
                        style = MaterialTheme.typography.caption,
                    )
                }

                // Check over Cellular (disabled)
                Box(modifier = Modifier.size(80.dp)) {
                    Text(
                        "Cellular",
                        style = MaterialTheme.typography.caption,
                    )
                }
            }

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // Close button
            AlertAction(
                onClick = onDismiss,
                label = "Close",
                emphasized = false,
            )
        }
    }
}