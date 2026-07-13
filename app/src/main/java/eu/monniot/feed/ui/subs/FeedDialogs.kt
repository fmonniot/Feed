package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.ToneErrFg

// ---------------------------------------------------------------------------
// Dialogs & sheets that aren't a per-feed overflow-menu action, plus
// DeleteConfirmDialog below. #124 put Move / Rename / Fetch interval / New
// category / Rename category / Delete category on the shared FeedBottomSheet
// shell; BUG-60 moved Change URL (BUG-56) onto it too. #135 finished the pass
// by converting the last two Material3 AlertDialogs in the app — this file's
// DeleteConfirmDialog (Delete/Unsubscribe confirm) and MainTabShell's
// MarkAllReadConfirmDialog — onto the same shell, plus the OPML-import-result
// dialog in SettingsScreen. Refresh / Pause act inline (no modal); Unsubscribe
// now opens this sheet instead of acting inline.
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

/**
 * #135 (previously an AlertDialog since it predates #124): confirm sheet for
 * both the broken-feed-row "Delete" action and the healthy-row overflow
 * menu's "Unsubscribe" — same destructive confirmation either way, so one
 * composable serves both call sites (see SubscriptionsScreen's feedForDelete).
 */
@Composable
internal fun DeleteConfirmDialog(
    feed: FeedUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "Delete Feed",
        onDismiss = onDismiss,
        primaryLabel = "Delete",
        primaryDanger = true,
        onPrimaryClick = onConfirm,
        testTagSuffix = "_delete_feed",
        primaryTestTag = "delete_feed_confirm",
        secondaryTestTag = "delete_feed_cancel",
    ) {
        Text(
            text = "Delete \"${feed.displayTitle}\"? This cannot be undone.",
            fontFamily = SourceSerif4,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            lineHeight = (14 * 1.5).sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
    }
}
