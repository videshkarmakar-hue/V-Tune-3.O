@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("V-TUNE 3.O") },
        text = { /* About content will be shown below */ },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        supportiveButton = { TextButton(onClick = {}) { Text("V-TUNE 3.O Website") } },
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // App Name
            Text(
                text = "V-TUNE 3.O",
                style = MaterialTheme.typography.h4,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // Description
            Text(
                text = "Best Spotify Music & YouTube Music Alternative for Low-End Devices.",
                style = MaterialTheme.typography.body2,
                textAlign = Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Description line 2
            Text(
                text = "V-Tune 3.O is a modern music platform designed to make music streaming simple, smooth, and accessible for everyone.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // APP INFORMATION section
            Text(
                text = "APP INFORMATION",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
                textAlign = Center,
            )

            // App info grid
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Application
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Application", style = MaterialTheme.typography.caption)
                    Text("V-Tune 3.O", style = MaterialTheme.typography.h5)
                }

                // Version
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Version", style = MaterialTheme.typography.caption)
                    Text("1.0.0", style = MaterialTheme.typography.h5)
                }

                // Build
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Build", style = MaterialTheme.typography.caption)
                    Text("100", style = MaterialTheme.typography.h5)
                }

                // Platform
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Platform", style = MaterialTheme.typography.caption)
                    Text("Android", style = MaterialTheme.typography.body1)
                }
            }

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // ABOUT V-TUNE 3.O section
            Text(
                text = "ABOUT V-TUNE 3.O:",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
                textAlign = Center,
            )

            Text(
                text = "V-Tune 3.O is built to deliver a lightweight and modern music streaming experience, especially for devices with limited hardware capabilities.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "The product focuses on efficient performance, intuitive navigation, and a clean visual experience, making music discovery and listening feel simple and accessible.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "Designed with low-end devices in mind, V-Tune 3.O aims to reduce unnecessary complexity while keeping the listening experience smooth and enjoyable.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Divider
            Divider(modifier = Modifier.padding(8.dp))

            // LEGAL & SUPPORT section
            Text(
                text = "LEGAL & SUPPORT:",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
                textAlign = Center,
            )

            Text(
                text = "V-Tune 3.O is committed to providing a transparent, secure, and reliable experience for every user.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
            )

            // Resources grid
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Privacy Policy
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Privacy Policy", style = MaterialTheme.typography.caption)
                    Text("Learn how V-Tune 3.O collects, uses, and protects user information.", style = MaterialTheme.typography.body1)
                }

                // Terms & Conditions
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Terms & Conditions", style = MaterialTheme.typography.caption)
                    Text("Review the terms governing the use of the V-Tune 3.O application and services.", style = MaterialTheme.typography.body1)
                }

                // Open Source Licenses
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Open Source Licenses", style = MaterialTheme.typography.caption)
                    Text("View the open-source software and components used within V-Tune 3.O.", style = MaterialTheme.typography.body1)
                }

                // Third-Party Licenses
                Column(verticalAlignment = Alignment.CenterEnd) {
                    Text("Third-Party Licenses", style = MaterialTheme.typography.caption)
                    Text("Review licenses and acknowledgements for third-party technologies and services.", style = MaterialTheme.typography.body1)
                }
            }

            // Contact Support row
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Contact Support", style = MaterialTheme.typography.body1)
                Text("Get in touch with the V-Tune 3.O support team for assistance.", style = MaterialTheme.typography.body1)
            }

            // Report a Problem row
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Report a Problem", style = MaterialTheme.typography.body1)
                Text("Report bugs, technical issues, or unexpected behaviour.", style = MaterialTheme.typography.body1)
            }

            // Copyright Information row
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Copyright Information", style = MaterialTheme.typography.body1)
                Text("View copyright notices and intellectual-property information associated with V-Tune 3.O.", style = MaterialTheme.typography.body1)
            }

            // VERSION & BUILD section
            Divider(modifier = Modifier.padding(8.dp))

            Text(
                text = "V-Tune 3.O 1.0.0",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                textAlign = Center,
            )

            Text(
                text = "Build 100",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
            )

            // Copyright
            Text(
                text = "© 2026 Videsh Karmakar. All rights reserved.",
                style = MaterialTheme.typography.caption,
                textAlign = Center,
            )

            // V-TUNE 3.O tagline
            Divider(modifier = Modifier.padding(8.dp))

            Text(
                text = "V-TUNE 3.O",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                textAlign = Center,
            )

            Text(
                text = "Best Spotify Music & YouTube Music Alternative for Low-End Devices.",
                style = MaterialTheme.typography.body1,
                textAlign = Center,
            )
        }
    }
}