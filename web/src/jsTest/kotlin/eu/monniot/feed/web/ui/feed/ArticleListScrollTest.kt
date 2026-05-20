package eu.monniot.feed.web.ui.feed

import eu.monniot.feed.web.ui.dom.replace
import kotlinx.browser.document
import kotlinx.html.div
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that replacing the article rows div does not reset the scroll position
 * of the outer scroll container — the core invariant behind ticket #42's fix.
 *
 * The fix in Main.kt prevents a full DOM rebuild when navigating between feed-screen
 * routes, so the scrollable column element is never destroyed.  This test confirms
 * the complementary property: even if updateArticleListRows fires (via reactive
 * subscription), it uses replace("article-list-rows") which only touches children of
 * the inner rows div — the outer scroll container and its scrollTop are untouched.
 */
class ArticleListScrollTest {

    @Test
    fun scrollPositionPreservedWhenArticleRowsAreReplaced() {
        // Scrollable column (simulates #feed-screen-article-list)
        val scrollContainer = document.createElement("div") as HTMLElement
        scrollContainer.setAttribute("style", "height: 100px; overflow-y: scroll;")

        // Inner rows div (simulates #article-list-rows); use a unique id per test
        val rowsId = "test-article-list-rows-scroll"
        val rowsDiv = document.createElement("div") as HTMLElement
        rowsDiv.id = rowsId
        // 20 rows × 20px = 400px — tall enough to make the container scrollable
        rowsDiv.innerHTML = (1..20).joinToString("") { "<div style='height:20px;'>Row $it</div>" }

        scrollContainer.appendChild(rowsDiv)
        document.body!!.appendChild(scrollContainer)

        try {
            scrollContainer.scrollTop = 100.0
            val scrollBefore = scrollContainer.scrollTop

            // Simulate what updateArticleListRows does on selection change
            replace(rowsId) {
                (1..15).forEach { i ->
                    div { +"Article $i" }
                }
            }

            assertEquals(
                scrollBefore,
                scrollContainer.scrollTop,
                "Replacing inner rows must not reset the scroll container's scrollTop",
            )
        } finally {
            scrollContainer.remove()
        }
    }

    @Test
    fun scrollContainerElementIsNotDestroyedWhenRowsAreReplaced() {
        val scrollContainer = document.createElement("div") as HTMLElement
        val rowsId = "test-article-list-rows-identity"
        val rowsDiv = document.createElement("div") as HTMLElement
        rowsDiv.id = rowsId
        scrollContainer.appendChild(rowsDiv)
        document.body!!.appendChild(scrollContainer)

        try {
            // Mark the container with a sentinel attribute to detect if it is rebuilt
            scrollContainer.setAttribute("data-scroll-sentinel", "preserved")

            replace(rowsId) {
                div { +"new content" }
            }

            assertEquals(
                "preserved",
                scrollContainer.getAttribute("data-scroll-sentinel"),
                "Scroll container element must not be rebuilt when only the inner rows div is replaced",
            )
        } finally {
            scrollContainer.remove()
        }
    }
}
