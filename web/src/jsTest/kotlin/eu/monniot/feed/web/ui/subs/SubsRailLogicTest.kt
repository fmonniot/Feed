package eu.monniot.feed.web.ui.subs

import eu.monniot.feed.shared.FeedUiItem
import eu.monniot.feed.shared.api.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for the #123 rail/pane selection + reorder logic — no DOM,
 * no coroutines, no ViewModel needed. These back the SUBS-1 rail contract
 * (All feeds · categories · Uncategorized last) and the SUBS-10 reorder
 * contract (persisted via [eu.monniot.feed.shared.FeedViewModel.reorderCategories]).
 */
class SubsRailLogicTest {

    private fun feed(id: Int, categoryId: Int?, position: Int = 0) = FeedUiItem(
        id = id,
        displayTitle = "Feed $id",
        rawCustomTitle = null,
        url = "https://example.com/$id",
        unreadCount = 0,
        isPaused = false,
        errorCount = 0,
        fetchIntervalMinutes = 60,
        categoryId = categoryId,
        position = position,
    )

    private val craft = Category(id = 1, name = "Craft", position = 0)
    private val tech = Category(id = 2, name = "Tech", position = 1)
    private val categories = listOf(craft, tech)

    // -------------------------------------------------------------------------
    // feedsForSelection (SUBS-1)
    // -------------------------------------------------------------------------

    @Test
    fun allSelectionReturnsEveryFeed() {
        val feeds = listOf(feed(1, 1), feed(2, 2), feed(3, null))
        val result = feedsForSelection(feeds, categories, RailSelection.All)
        assertEquals(3, result.size)
    }

    @Test
    fun categorySelectionReturnsOnlyItsFeeds() {
        val feeds = listOf(feed(1, 1), feed(2, 2), feed(3, 1))
        val result = feedsForSelection(feeds, categories, RailSelection.Cat(1))
        assertEquals(setOf(1, 3), result.map { it.id }.toSet())
    }

    @Test
    fun uncategorizedAbsorbsNullCategoryFeeds() {
        val feeds = listOf(feed(1, 1), feed(2, null))
        val result = feedsForSelection(feeds, categories, RailSelection.Uncategorized)
        assertEquals(listOf(2), result.map { it.id })
    }

    @Test
    fun uncategorizedAbsorbsFeedsWhoseCategoryNoLongerExists() {
        // Safety net: a feed pointing at a category id that isn't in the live list
        // (e.g. deleted) must still show up somewhere, not vanish.
        val feeds = listOf(feed(1, 999))
        val result = feedsForSelection(feeds, categories, RailSelection.Uncategorized)
        assertEquals(listOf(1), result.map { it.id })
    }

    // -------------------------------------------------------------------------
    // railSelectionTitle / paneCountLabel / initialRailSelection
    // -------------------------------------------------------------------------

    @Test
    fun titleForAllIsAllFeeds() {
        assertEquals("All feeds", railSelectionTitle(RailSelection.All, categories))
    }

    @Test
    fun titleForCategoryIsItsName() {
        assertEquals("Tech", railSelectionTitle(RailSelection.Cat(2), categories))
    }

    @Test
    fun titleForUncategorized() {
        assertEquals("Uncategorized", railSelectionTitle(RailSelection.Uncategorized, categories))
    }

    @Test
    fun initialSelectionPicksLowestPositionCategory() {
        val unordered = listOf(tech, craft) // tech pos=1, craft pos=0
        assertEquals(RailSelection.Cat(1), initialRailSelection(unordered))
    }

    @Test
    fun initialSelectionFallsBackToAllWhenNoCategories() {
        assertEquals(RailSelection.All, initialRailSelection(emptyList()))
    }

    // -------------------------------------------------------------------------
    // filterCategories (rail "Filter categories…" box)
    // -------------------------------------------------------------------------

    @Test
    fun filterCategoriesMatchesSubstringCaseInsensitive() {
        val result = filterCategories(categories, "tech")
        assertEquals(listOf(tech), result)
    }

    @Test
    fun filterCategoriesBlankReturnsAll() {
        assertEquals(categories, filterCategories(categories, "  "))
    }

    // -------------------------------------------------------------------------
    // reorderedCategoryIds (SUBS-10 reorder — persisted via reorderCategories)
    // -------------------------------------------------------------------------

    @Test
    fun reorderMovesDraggedCategoryBeforeTarget() {
        val a = Category(id = 1, name = "A", position = 0)
        val b = Category(id = 2, name = "B", position = 1)
        val c = Category(id = 3, name = "C", position = 2)
        // Drag C onto A: C should land immediately before A.
        val result = reorderedCategoryIds(listOf(a, b, c), draggedId = 3, targetId = 1)
        assertEquals(listOf(3, 1, 2), result)
    }

    @Test
    fun reorderDraggedOntoItselfIsNoOp() {
        val a = Category(id = 1, name = "A", position = 0)
        val b = Category(id = 2, name = "B", position = 1)
        val result = reorderedCategoryIds(listOf(a, b), draggedId = 1, targetId = 1)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun reorderUnknownIdsIsNoOp() {
        val a = Category(id = 1, name = "A", position = 0)
        val b = Category(id = 2, name = "B", position = 1)
        val result = reorderedCategoryIds(listOf(a, b), draggedId = 99, targetId = 1)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun reorderRespectsExistingPositionNotListOrder() {
        // categories passed out of position order; result must still follow position.
        val a = Category(id = 1, name = "A", position = 2)
        val b = Category(id = 2, name = "B", position = 0)
        val c = Category(id = 3, name = "C", position = 1)
        // True order by position is B(0), C(1), A(2). Drag A onto B → A, B, C.
        val result = reorderedCategoryIds(listOf(a, b, c), draggedId = 1, targetId = 2)
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun reorderDraggingFirstCategoryOntoLastMovesItActuallyLast() {
        // Review fix: "always insert before target" made it impossible to ever
        // land a category in the last position via a single drag — dragging A
        // (first) onto C (last) used to yield [B, A, C] (still second-to-last).
        // Dragging downward (dragged started above target) must now insert
        // *after* the target instead, so a single gesture can reach last place.
        val a = Category(id = 1, name = "A", position = 0)
        val b = Category(id = 2, name = "B", position = 1)
        val c = Category(id = 3, name = "C", position = 2)
        val result = reorderedCategoryIds(listOf(a, b, c), draggedId = 1, targetId = 3)
        assertEquals(listOf(2, 3, 1), result, "dragging the first category onto the last row must make it last")
    }

    @Test
    fun paneCountLabelDelegatesCorrectly() {
        assertTrue(paneCountLabel(3, 3, false) == "3 feeds")
        assertTrue(paneCountLabel(1, 1, false) == "1 feed")
        assertTrue(paneCountLabel(5, 2, true) == "showing 2 of 5")
    }

    // -------------------------------------------------------------------------
    // reorderedFeedIds (ticket #133 reorder — persisted via reorderFeeds)
    // -------------------------------------------------------------------------

    @Test
    fun feedReorderMovesDraggedFeedBeforeTarget() {
        val a = feed(id = 1, categoryId = 1, position = 0)
        val b = feed(id = 2, categoryId = 1, position = 1)
        val c = feed(id = 3, categoryId = 1, position = 2)
        // Drag C onto A: C should land immediately before A.
        val result = reorderedFeedIds(listOf(a, b, c), draggedId = 3, targetId = 1)
        assertEquals(listOf(3, 1, 2), result)
    }

    @Test
    fun feedReorderDraggedOntoItselfIsNoOp() {
        val a = feed(id = 1, categoryId = 1, position = 0)
        val b = feed(id = 2, categoryId = 1, position = 1)
        val result = reorderedFeedIds(listOf(a, b), draggedId = 1, targetId = 1)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun feedReorderUnknownIdsIsNoOp() {
        val a = feed(id = 1, categoryId = 1, position = 0)
        val b = feed(id = 2, categoryId = 1, position = 1)
        val result = reorderedFeedIds(listOf(a, b), draggedId = 99, targetId = 1)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun feedReorderRespectsExistingPositionNotListOrder() {
        // feeds passed out of position order; result must still follow position.
        val a = feed(id = 1, categoryId = 1, position = 2)
        val b = feed(id = 2, categoryId = 1, position = 0)
        val c = feed(id = 3, categoryId = 1, position = 1)
        // True order by position is B(0), C(1), A(2). Drag A onto B → A, B, C.
        val result = reorderedFeedIds(listOf(a, b, c), draggedId = 1, targetId = 2)
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun feedReorderDraggingFirstFeedOntoLastMovesItActuallyLast() {
        // Mirrors reorderDraggingFirstCategoryOntoLastMovesItActuallyLast: dragging
        // downward (dragged started above target) must insert *after* the target,
        // so a single gesture can reach the last position.
        val a = feed(id = 1, categoryId = 1, position = 0)
        val b = feed(id = 2, categoryId = 1, position = 1)
        val c = feed(id = 3, categoryId = 1, position = 2)
        val result = reorderedFeedIds(listOf(a, b, c), draggedId = 1, targetId = 3)
        assertEquals(listOf(2, 3, 1), result, "dragging the first feed onto the last row must make it last")
    }
}
