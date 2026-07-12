package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.ui.theme.FeedButton
import eu.monniot.feed.ui.theme.FeedTextButton
import eu.monniot.feed.ui.theme.ToneErrFg

// ---------------------------------------------------------------------------
// Dialogs that stay inline AlertDialogs (not bottom sheets) — Unsubscribe
// confirm and Change URL. #124 only calls out Move / Rename / Fetch interval
// / New category / Rename category / Delete category as bottom sheets;
// Refresh / Pause / Unsubscribe act inline, and Change URL (BUG-56) is an
// existing overflow action outside the #124 bottom-sheet set.
// ---------------------------------------------------------------------------

/**
 * BUG-56: dialog to change a feed's source URL, reachable from the overflow
 * menu regardless of the feed's health status. Calls `PUT /v1/feeds/{id}`
 * (via [onConfirm] -> `FeedViewModel.updateFeedUrl`), which revalidates the
 * new URL server-side; a `400` surfaces as an inline error and keeps the
 * dialog open so the user can correct it.
 */
@Composable
internal fun ChangeUrlDialog(
    feed: FeedUiItem,
    onConfirm: (newUrl: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(feed.id) { mutableStateOf(feed.url) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Feed URL") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().testTag("change_url_input"),
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = ToneErrFg,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("change_url_error"),
                    )
                }
            }
        },
        confirmButton = {
            FeedButton(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isNotBlank() && !isSaving) {
                        isSaving = true
                        onConfirm(
                            trimmed,
                            { isSaving = false; onDismiss() },
                            { msg -> isSaving = false; error = msg },
                        )
                    }
                },
                label = "Save",
                modifier = Modifier.testTag("change_url_save"),
            )
        },
        dismissButton = {
            FeedTextButton(onClick = onDismiss, label = "Cancel")
        },
    )
}

@Composable
internal fun DeleteConfirmDialog(
    feed: FeedUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Feed") },
        text = { Text("Delete \"${feed.displayTitle}\"? This cannot be undone.") },
        confirmButton = {
            FeedButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
                ),
                label = "Delete",
            )
        },
        dismissButton = {
            FeedTextButton(onClick = onDismiss, label = "Cancel")
        },
    )
}
