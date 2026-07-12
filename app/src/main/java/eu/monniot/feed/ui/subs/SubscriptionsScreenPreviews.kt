package eu.monniot.feed.ui.subs

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import eu.monniot.feed.ui.theme.FeedTheme

private val previewFeeds = listOf(
    FeedUiItem(id = 1, displayTitle = "Field Notes", rawCustomTitle = null, url = "fieldnotes.observer/feed", unreadCount = 4, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
    FeedUiItem(id = 2, displayTitle = "The Garden", rawCustomTitle = null, url = "okafor.garden/index.xml", unreadCount = 1, isPaused = false, errorCount = 0, fetchIntervalMinutes = 30, categoryId = 1),
    FeedUiItem(id = 3, displayTitle = "The Loop", rawCustomTitle = null, url = "theloop.cc/rss", unreadCount = 7, isPaused = false, errorCount = 0, fetchIntervalMinutes = 60, categoryId = 2),
    FeedUiItem(id = 4, displayTitle = "Frequencies", rawCustomTitle = "Freq.", url = "frequencies.fm/rss", unreadCount = 5, isPaused = true, errorCount = 0, fetchIntervalMinutes = 60, categoryId = null),
)

private val previewCategories = listOf(
    Category(id = 1, name = "Craft", position = 0),
    Category(id = 2, name = "Tech", position = 1),
)

@Preview(showBackground = true, name = "Subscriptions – with feeds")
@Composable
private fun SubscriptionsScreenPreview() {
    FeedTheme {
        SubscriptionsScreenContent(
            feeds = previewFeeds,
            categories = previewCategories,
            isLoading = false,
            errorMessage = null,
            addFeedError = null,
            addFeedLoading = false,
            onAddFeed = { _, _ -> },
            onRename = { _, _ -> },
            onSetCategory = { _, _ -> },
            onSetFeedInterval = { _, _ -> },
            onTogglePaused = { _, _ -> },
            onDelete = {},
            onErrorDismiss = {},
            onAddFeedErrorDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "Subscriptions – empty")
@Composable
private fun SubscriptionsScreenEmptyPreview() {
    FeedTheme {
        SubscriptionsScreenContent(
            feeds = emptyList(),
            categories = emptyList(),
            isLoading = false,
            errorMessage = null,
            addFeedError = null,
            addFeedLoading = false,
            onAddFeed = { _, _ -> },
            onRename = { _, _ -> },
            onSetCategory = { _, _ -> },
            onSetFeedInterval = { _, _ -> },
            onTogglePaused = { _, _ -> },
            onDelete = {},
            onErrorDismiss = {},
            onAddFeedErrorDismiss = {},
        )
    }
}
