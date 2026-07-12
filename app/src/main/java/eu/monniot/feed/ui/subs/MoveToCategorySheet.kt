package eu.monniot.feed.ui.subs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors

// ---------------------------------------------------------------------------
// Move to category — bottom sheet (SUBS-10)
// ---------------------------------------------------------------------------

/** A selectable target in the Move / Delete-reassign sheets — the real categories plus the locked "Uncategorized". */
private data class SheetCategoryOption(val id: Int?, val name: String, val locked: Boolean = false)

private fun categoryOptions(categories: List<Category>): List<SheetCategoryOption> =
    categories.map { SheetCategoryOption(it.id, it.name) } + SheetCategoryOption(null, "Uncategorized", locked = true)

@Composable
internal fun MoveToCategorySheet(
    feed: FeedUiItem,
    categories: List<Category>,
    onMove: (categoryId: Int?) -> Unit,
    onNewCategoryRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val options = remember(categories) { categoryOptions(categories) }
    // Normalize a stale categoryId (e.g. the feed's category was deleted on
    // another client and this list has refreshed but the feed hasn't) to null
    // — same fallback the grouped list uses (`it.categoryId !in knownCategoryIds`)
    // — so this sheet agrees with the list's Uncategorized bucket instead of
    // pre-selecting a dangling id that matches no radio row.
    val currentCategoryId = remember(feed.categoryId, categories) {
        feed.categoryId?.takeIf { id -> categories.any { it.id == id } }
    }
    var selected by remember(feed.id) { mutableStateOf(currentCategoryId) }

    FeedBottomSheet(
        title = "Move “${feed.displayTitle}”",
        onDismiss = onDismiss,
        primaryLabel = "Move",
        onPrimaryClick = {
            onMove(selected)
            onDismiss()
        },
        testTagSuffix = "_move",
        primaryTestTag = "move_confirm",
        secondaryTestTag = "move_cancel",
    ) {
        options.forEach { opt ->
            SheetRadioRow(
                label = opt.name,
                active = selected == opt.id,
                trailingNote = when {
                    opt.id == currentCategoryId -> "current"
                    opt.locked -> "default"
                    else -> null
                },
                onClick = { selected = opt.id },
                testTag = "move_option_${opt.id ?: "uncat"}",
            )
        }
        Text(
            text = "+ New category…",
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            color = colors.ink2,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNewCategoryRequested)
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .testTag("move_new_category"),
        )
    }
}
