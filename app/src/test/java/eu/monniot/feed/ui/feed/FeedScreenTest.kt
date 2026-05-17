package eu.monniot.feed.ui.feed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.monniot.feed.shared.ArticleItem
import eu.monniot.feed.shared.data.Density
import eu.monniot.feed.ui.theme.FeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose Robolectric tests for the Feed screen (Phase 8).
 *
 * These tests exercise pure UI logic — filtering, rendering, and navigation —
 * without a live repository or server.
 *
 * Note on Robolectric + Compose: Google Fonts resolution is not available under
 * Robolectric (no Play Services). The font falls back to the system default, which
 * is fine for structural tests. Tests that assert pixel-level font properties should
 * use instrumented tests instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------

    /** Four articles with minutesToRead 2, 9, 9, 14 (used in filterChipFiltersList). */
    private val fixtureArticles = listOf(
        ArticleItem(
            id = "1",
            title = "Short Article",
            description = "A short read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/1",
            feedTitle = "Feed One",
            minutesToRead = 2,
            isRead = false,
            isStarred = false,
            excerpt = "Short excerpt",
        ),
        ArticleItem(
            id = "2",
            title = "Medium Article A",
            description = "A medium read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/2",
            feedTitle = "Feed One",
            minutesToRead = 9,
            isRead = false,
            isStarred = false,
            excerpt = "Medium excerpt A",
        ),
        ArticleItem(
            id = "3",
            title = "Medium Article B",
            description = "Another medium read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/3",
            feedTitle = "Feed One",
            minutesToRead = 9,
            isRead = true,
            isStarred = false,
            excerpt = "Medium excerpt B",
        ),
        ArticleItem(
            id = "4",
            title = "Long Article",
            description = "A long read",
            pubDate = "Mon, 1 Jan 2024",
            source = "feed1",
            url = "https://example.com/4",
            feedTitle = "Feed One",
            minutesToRead = 14,
            isRead = false,
            isStarred = true,
            excerpt = "Long excerpt",
        ),
    )

    // ---------------------------------------------------------------------------
    // Test: filter chip shows only long-read articles
    // ---------------------------------------------------------------------------

    /**
     * Given 4 fixture articles with minutesToRead values 2, 9, 9, 14:
     * after tapping the "Long reads" chip, only the 14-min article should be visible.
     *
     * Validates [ArticleFilter.LongReads] filtering logic end-to-end through the
     * Composable layer.
     *
     * NOTE: LazyColumn under Robolectric only renders items that fit into the test
     * window, so we only assert on the first visible item before filtering, and then
     * on the specific items that should survive (or not) after filtering. The
     * pure-logic assertions in [longReadsFilterIncludesOnlyArticlesWith10PlusMinutes]
     * cover the predicate exhaustively without the viewport constraint.
     */
    @Test
    fun filterChipFiltersList() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        // Confirm the "All" chip is shown and the first article exists
        composeTestRule.onAllNodesWithText("Short Article").assertCountEquals(1)

        // Tap "Long reads" chip — only articles with minutesToRead >= 10 remain
        composeTestRule.onNodeWithText("Long reads").performClick()

        // "Long Article" (14 min) must still be in the composition
        composeTestRule.onAllNodesWithText("Long Article").assertCountEquals(1)

        // The short and medium articles (< 10 min) must be gone after filtering
        composeTestRule.onAllNodesWithText("Short Article").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Medium Article A").assertCountEquals(0)
        // Medium Article B might not have rendered before (viewport), but after
        // LongReads filter it definitely should not be present
        composeTestRule.onAllNodesWithText("Medium Article B").assertCountEquals(0)
    }

    // ---------------------------------------------------------------------------
    // Test: tapping a row triggers the navigation callback
    // ---------------------------------------------------------------------------

    /**
     * Verifies that tapping an article row invokes [onArticleClick] with the
     * correct article id and title.
     *
     * Phase 9: onArticleClick now receives (articleId, title) instead of (url, title)
     * so the navigation can route to reader/{articleId}.
     */
    @Test
    fun tappingRowNavigatesToReader() {
        var navigatedId: String? = null
        var navigatedTitle: String? = null

        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { id, title ->
                        navigatedId = id
                        navigatedTitle = title
                    },
                    onRefresh = {},
                )
            }
        }

        // Tap the first article row
        composeTestRule.onNodeWithText("Short Article").performClick()

        assertEquals("1", navigatedId)
        assertEquals("Short Article", navigatedTitle)
    }

    // ---------------------------------------------------------------------------
    // Test: filter chip logic (pure unit test — no Compose needed)
    // ---------------------------------------------------------------------------

    /**
     * Pure unit test for [ArticleFilter.LongReads] filter logic.
     * Validates the predicate directly without composing UI.
     */
    @Test
    fun longReadsFilterIncludesOnlyArticlesWith10PlusMinutes() {
        val filtered = fixtureArticles.filter { ArticleFilter.LongReads.matches(it) }
        assertEquals(1, filtered.size)
        assertEquals("Long Article", filtered[0].title)
        assertEquals(14, filtered[0].minutesToRead)
    }

    @Test
    fun shortReadsFilterIncludesOnlyArticlesWith5MinutesOrLess() {
        val filtered = fixtureArticles.filter { ArticleFilter.ShortReads.matches(it) }
        assertEquals(1, filtered.size)
        assertEquals("Short Article", filtered[0].title)
    }

    @Test
    fun unreadFilterExcludesReadArticles() {
        val filtered = fixtureArticles.filter { ArticleFilter.Unread.matches(it) }
        // id=3 is marked isRead=true, so it should be excluded
        assertTrue(filtered.none { it.id == "3" })
        assertEquals(3, filtered.size)
    }

    @Test
    fun allFilterShowsEverything() {
        val filtered = fixtureArticles.filter { ArticleFilter.All.matches(it) }
        assertEquals(4, filtered.size)
    }

    // ---------------------------------------------------------------------------
    // Test: feed screen header content
    // ---------------------------------------------------------------------------

    /**
     * Verifies the subtitle with correct unread/total counts.
     */
    @Test
    fun feedScreenShowsSubtitleWithCounts() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        // Subtitle: "3 unread · 4 total" — 3 unread because id=3 is read
        composeTestRule.onNodeWithText("3 unread · 4 total").assertIsDisplayed()
    }

    /**
     * Verifies that all five filter chips are rendered.
     */
    @Test
    fun allFilterChipsAreRendered() {
        composeTestRule.setContent {
            FeedTheme {
                FeedScreenContent(
                    articleItems = fixtureArticles,
                    isRefreshing = false,
                    density = Density.Regular,
                    onArticleClick = { _, _ -> },
                    onRefresh = {},
                )
            }
        }

        // "All" chip is the default — it's displayed
        composeTestRule.onNodeWithText("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unread").assertIsDisplayed()
        composeTestRule.onNodeWithText("Long reads").assertIsDisplayed()
        composeTestRule.onNodeWithText("Short reads").assertIsDisplayed()
        // "Today" appears in both the chip and the header; at least one is visible
        composeTestRule.onAllNodesWithText("Today").assertCountEquals(2) // chip + header
    }

    /**
     * Verifies that the tab bar (bottom navigation) remains visible across a
     * tab switch by testing the [MainTabShell] composable.
     *
     * NOTE: Testing full navigation with [MainTabShell] under Robolectric is
     * limited because [NavHost] requires Compose-navigation infrastructure that
     * is complex to set up without a real Activity context. This test is
     * documented as an instrumented-test candidate.
     *
     * TODO(phase-8): Move to app/src/androidTest/ for full navigation fidelity.
     */
    @Test
    @Ignore("TODO(phase-8): NavHost under Robolectric requires Activity context — move to androidTest/")
    fun tabBarPersistsAcrossTabSwitch() {
        // Documented limitation: requires Activity-level NavHost.
        // The filterChipFiltersList and tappingRowNavigatesToReader tests above
        // provide core behavioral coverage for Phase 8.
    }
}
