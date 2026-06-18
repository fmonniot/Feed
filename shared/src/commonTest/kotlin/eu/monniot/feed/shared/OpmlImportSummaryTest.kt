package eu.monniot.feed.shared

import eu.monniot.feed.shared.api.OpmlImportResult
import kotlin.test.Test
import kotlin.test.assertEquals

class OpmlImportSummaryTest {

    private fun result(
        imported: Int = 0,
        already_exists: Int = 0,
        failed: Int = 0,
        categories_created: Int = 0,
    ) = OpmlImportResult(
        total_feeds = imported + already_exists + failed,
        imported = imported,
        already_exists = already_exists,
        failed = failed,
        categories_created = categories_created,
        feeds = emptyList(),
    )

    @Test
    fun allImported() {
        assertEquals("Imported 5 feeds.", buildOpmlSummary(result(imported = 5)))
    }

    @Test
    fun singleImported() {
        assertEquals("Imported 1 feed.", buildOpmlSummary(result(imported = 1)))
    }

    @Test
    fun withAlreadyExists() {
        assertEquals(
            "Imported 3 feeds, 2 already existed.",
            buildOpmlSummary(result(imported = 3, already_exists = 2)),
        )
    }

    @Test
    fun withFailed() {
        assertEquals(
            "Imported 2 feeds, 1 failed.",
            buildOpmlSummary(result(imported = 2, failed = 1)),
        )
    }

    @Test
    fun withCategoriesCreated() {
        assertEquals(
            "Imported 4 feeds, 1 category created.",
            buildOpmlSummary(result(imported = 4, categories_created = 1)),
        )
    }

    @Test
    fun withMultipleCategories() {
        assertEquals(
            "Imported 3 feeds, 2 categories created.",
            buildOpmlSummary(result(imported = 3, categories_created = 2)),
        )
    }

    @Test
    fun multiClause() {
        assertEquals(
            "Imported 2 feeds, 1 already existed, 1 failed.",
            buildOpmlSummary(result(imported = 2, already_exists = 1, failed = 1)),
        )
    }

    @Test
    fun allAlreadyExist() {
        assertEquals(
            "3 feeds already existed.",
            buildOpmlSummary(result(already_exists = 3)),
        )
    }

    @Test
    fun zeroEverything() {
        assertEquals("0 feeds imported.", buildOpmlSummary(result()))
    }
}
