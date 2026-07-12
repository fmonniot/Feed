package eu.monniot.feed.ui.subs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.LocalFeedTypography
import eu.monniot.feed.ui.theme.SourceSerif4

// ---------------------------------------------------------------------------
// New / Rename / Delete category — bottom sheets (SUBS-1/13/14/15)
// ---------------------------------------------------------------------------

@Composable
internal fun NewCategorySheet(
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val colors = LocalFeedColors.current
    FeedBottomSheet(
        title = "New category",
        onDismiss = onDismiss,
        primaryLabel = "Create",
        primaryEnabled = name.isNotBlank(),
        onPrimaryClick = {
            if (name.isNotBlank()) {
                onConfirm(name.trim())
                onDismiss()
            }
        },
        testTagSuffix = "_new_category",
        primaryTestTag = "new_category_confirm",
        secondaryTestTag = "new_category_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Category name…",
            testTag = "new_category_input",
        )
        Text(
            text = "New categories appear in the list; move feeds in from each feed's ⋯ menu afterward.",
            fontFamily = IbmPlexSans,
            fontSize = 12.sp,
            lineHeight = (12 * 1.4).sp,
            color = colors.ink3,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
        )
    }
}

@Composable
internal fun RenameCategorySheet(
    category: Category,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    FeedBottomSheet(
        title = "Rename category",
        onDismiss = onDismiss,
        primaryLabel = "Rename",
        primaryEnabled = name.isNotBlank(),
        onPrimaryClick = {
            if (name.isNotBlank()) {
                onConfirm(name.trim())
                onDismiss()
            }
        },
        testTagSuffix = "_rename_category",
        primaryTestTag = "rename_category_confirm",
        secondaryTestTag = "rename_category_cancel",
    ) {
        SheetTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Category name…",
            testTag = "rename_category_input",
        )
    }
}

/**
 * Delete-category sheet (SUBS-15) — same reassign model as web: the feeds are
 * kept and re-filed to the chosen target; no feed is ever unsubscribed. An
 * empty category deletes directly with no reassign step.
 */
@Composable
internal fun DeleteCategorySheet(
    category: Category,
    feedCount: Int,
    otherCategories: List<Category>,
    onConfirm: (reassignTo: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFeedColors.current
    val typography = LocalFeedTypography.current

    if (feedCount == 0) {
        FeedBottomSheet(
            title = "Delete “${category.name}”?",
            onDismiss = onDismiss,
            primaryLabel = "Delete",
            primaryDanger = true,
            onPrimaryClick = {
                onConfirm(null)
                onDismiss()
            },
            testTagSuffix = "_delete_category",
            primaryTestTag = "delete_category_confirm",
            secondaryTestTag = "delete_category_cancel",
        ) {
            Text(
                text = "No feeds are filed under it.",
                fontFamily = SourceSerif4,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                lineHeight = (14 * 1.5).sp,
                color = colors.ink2,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
            )
        }
        return
    }

    var selected by remember(category.id) { mutableStateOf<Int?>(null) }
    val feedsWord = if (feedCount == 1) "feed" else "feeds"
    val verbClause = if (feedCount == 1) "is kept — pick where it goes" else "are kept — pick where they go"

    FeedBottomSheet(
        title = "Delete “${category.name}”?",
        onDismiss = onDismiss,
        primaryLabel = "Delete & move",
        primaryDanger = true,
        onPrimaryClick = {
            onConfirm(selected)
            onDismiss()
        },
        testTagSuffix = "_delete_category",
        primaryTestTag = "delete_category_confirm",
        secondaryTestTag = "delete_category_cancel",
    ) {
        Text(
            text = "The $feedCount $feedsWord $verbClause. Nothing is unsubscribed.",
            fontFamily = SourceSerif4,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            lineHeight = (14 * 1.5).sp,
            color = colors.ink2,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 6.dp),
        )
        Text(
            text = "MOVE ITS FEEDS TO",
            style = typography.folderLabel.copy(color = colors.ink3, letterSpacing = 0.1.sp),
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
        )
        otherCategories.forEach { cat ->
            SheetRadioRow(
                label = cat.name,
                active = selected == cat.id,
                onClick = { selected = cat.id },
                testTag = "delete_category_target_${cat.id}",
            )
        }
        SheetRadioRow(
            label = "Uncategorized",
            active = selected == null,
            trailingNote = "default",
            onClick = { selected = null },
            testTag = "delete_category_target_uncat",
        )
    }
}
