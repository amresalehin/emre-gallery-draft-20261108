package com.amresalehin.emregallery.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

data class FaceFolderInfo(
    val path: String,
    val name: String,
    val photoCount: Int,
    val coverUri: String? = null
)

@Composable
fun FaceScanOptionsDialog(
    onDismiss: () -> Unit,
    onRescanAll: () -> Unit,
    onRemainingOnly: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rescan faces") },
        text = {
            Text("Choose whether to scan only photos that have not been indexed, or clear the existing face index and scan every photo again.")
        },
        confirmButton = {
            TextButton(onClick = onRescanAll) { Text("Rescan all") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onRemainingOnly) { Text("Remaining only") }
            }
        }
    )
}

@Composable
fun ModelDownloadRequiredDialog(
    onDismiss: () -> Unit,
    onDownloadModel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buffalo_L Model Required") },
        text = { Text("Face recognition uses the on-device Buffalo_L model (InsightFace SCRFD + ArcFace 512-d). Please download the model pack to enable face detection and identity clustering.") },
        confirmButton = {
            Button(onClick = onDownloadModel) { Text("Download model") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
