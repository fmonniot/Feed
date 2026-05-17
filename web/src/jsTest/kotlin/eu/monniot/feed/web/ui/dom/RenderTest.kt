package eu.monniot.feed.web.ui.dom

import kotlinx.browser.document
import kotlinx.html.TagConsumer
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.p
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for [render] and [replace].
 *
 * The DOM `ParentNode.append(vararg nodes: dynamic)` member shadows the
 * `kotlinx.html.dom.append` extension when a pre-typed
 * `TagConsumer<HTMLElement>.() -> Unit` value is passed (member functions
 * win over extensions). The member then stringifies the lambda and inserts
 * the JS source as a text node — producing visible function-source text on
 * the page.
 *
 * Inline lambdas (e.g. `host.append { brandMark() }`) hide the bug because
 * the receiver-typed body forces extension resolution. These tests pass
 * the block as a stored value to exercise the broken path.
 */
class RenderTest {

    @Test
    fun renderBuildsDomNodesNotTextSource() {
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            val block: TagConsumer<HTMLElement>.() -> Unit = {
                h1 { +"Hello" }
                p { id = "p1"; +"World" }
            }

            render(host, block)

            val h1El = host.querySelector("h1") as? HTMLElement
            assertNotNull(h1El, "Expected <h1> child after render")
            assertEquals("Hello", h1El.textContent)

            val pEl = host.querySelector("#p1") as? HTMLElement
            assertNotNull(pEl, "Expected <p id=\"p1\"> child after render")
            assertEquals("World", pEl.textContent)

            // The smoking-gun: if the kotlinx.html extension didn't win,
            // host.textContent would contain Kotlin/JS lambda source like
            // "TagConsumer" or "onTagStart_". Assert that didn't happen.
            val text = host.textContent ?: ""
            assertTrue(
                !text.contains("onTagStart") && !text.contains("TagConsumer"),
                "render() inserted lambda source as text instead of DOM nodes: $text",
            )
        } finally {
            host.remove()
        }
    }

    @Test
    fun renderClearsExistingContentFirst() {
        val host = document.createElement("div") as HTMLElement
        host.innerHTML = "<span>stale</span>"
        document.body!!.appendChild(host)
        try {
            val block: TagConsumer<HTMLElement>.() -> Unit = {
                h1 { +"Fresh" }
            }

            render(host, block)

            assertEquals(null, host.querySelector("span"), "Stale child should be cleared")
            assertNotNull(host.querySelector("h1"), "Fresh child should be appended")
        } finally {
            host.remove()
        }
    }

    @Test
    fun replaceRendersIntoElementById() {
        val host = document.createElement("div") as HTMLElement
        host.id = "render-test-target"
        host.innerHTML = "<em>old</em>"
        document.body!!.appendChild(host)
        try {
            val block: TagConsumer<HTMLElement>.() -> Unit = {
                p { +"new" }
            }

            replace("render-test-target", block)

            assertEquals(null, host.querySelector("em"), "Old content should be cleared")
            val pEl = host.querySelector("p") as? HTMLElement
            assertNotNull(pEl, "Replaced <p> should be present")
            assertEquals("new", pEl.textContent)

            val text = host.textContent ?: ""
            assertTrue(
                !text.contains("onTagStart") && !text.contains("TagConsumer"),
                "replace() inserted lambda source as text: $text",
            )
        } finally {
            host.remove()
        }
    }

    @Test
    fun replaceNoOpsWhenIdMissing() {
        // Should not throw.
        replace("definitely-not-in-the-dom") {
            h1 { +"unused" }
        }
    }
}
