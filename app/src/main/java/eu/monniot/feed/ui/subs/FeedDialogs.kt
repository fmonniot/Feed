package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
// Dialogs that stay inline AlertDialogs (not bottom sheets) — only Unsubscribe
// confirm now. #124 calls out Move / Rename / Fetch interval / New category /
// Rename category / Delete category as bottom sheets; BUG-60 moved Change URL
// (BUG-56) onto the same FeedBottomSheet shell for overflow-menu consistency.
// Refresh / Pause / Unsubscribe still act as inline AlertDialogs.
// ---------------------------------------------------------------------------

/**
 * BUG-56 (bottom-sheeted in BUG-60): sheet to change a feed's source URL,
 * reachable from the overflow menu regardless of the feed's health status.
 * Calls `PUT /v1/feeds/{id}` (via [onConfirm] -> `FeedViewModel.updateFeedUrl`),
 * which revalidates the new URL server-side; a `400` surfaces as an inline
 * error and keeps the sheet open so the user can correct it.
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
    val trimmed = url.trim()

    FeedBottomSheet(
        title = "Change Feed URL",
        // Reviewer follow-up (parity with AddFeedSheet): a scrim tap, back
        // press, or Cancel must not close the sheet while the PUT request is
        // in flight, or the eventual onSuccess/onError lands on state of an
        // already-dismissed composable and is silently dropped.
        onDismiss = { if (!isSaving) onDismiss() },
        primaryLabel = "Save",
        primaryEnabled = trimmed.isNotBlank() && !isSaving,
        onPrimaryClick = {
            if (trimmed.isNotBlank() && !isSaving) {
                isSaving = true
                onConfirm(
                    trimmed,
                    { isSaving = false; onDismiss() },
                    { msg -> isSaving = false; error = msg },
                )
            }
        },
        secondaryEnabled = !isSaving,
        testTagSuffix = "_change_url",
        primaryTestTag = "change_url_save",
    ) {
        SheetTextField(
            value = url,
            onValueChange = { url = it; error = null },
            placeholder = "Feed URL…",
            testTag = "change_url_input",
            enabled = !isSaving,
        )
        if (error != null) {
            Text(
                text = error!!,
                color = ToneErrFg,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 22.dp, end = 22.dp, top = 4.dp)
                    .testTag("change_url_error"),
            )
        }
    }
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
