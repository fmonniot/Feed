package eu.monniot.feed.ui.subs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.monniot.feed.shared.FeedUiItem

// ---------------------------------------------------------------------------
// Rename feed — bottom sheet (SUBS-4)
// ---------------------------------------------------------------------------

@Composable
internal fun RenameFeedSheet(
    feed: FeedUiItem,
    onConfirm: (customTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(feed.id) { mutableStateOf(feed.displayTitle) }
    FeedBottomSheet(
        title = "Rename feed",
        onDismiss = onDismiss,
        primaryLabel = "Rename",
        // Always enabled: blanking the field is the way to clear a custom
        // title and revert to the server-provided name (onConfirm(null)),
        // matching the pre-#124 RenameDialog behavior.
        primaryEnabled = true,
        onPrimaryClick = {
            onConfirm(name.ifBlank { null })
            onDismiss()
        },
        testTagSuffix = "_rename_feed",
        primaryTestTag = "rename_feed_confirm",
        secondaryTestTag = "rename_feed_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Custom title…",
            testTag = "rename_feed_input",
        )
    }
}
