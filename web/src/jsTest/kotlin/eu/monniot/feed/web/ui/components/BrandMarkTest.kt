package eu.monniot.feed.web.ui.components

import kotlinx.browser.document
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrandMarkTest {

    /**
     * Creates a throwaway <div> host, renders brandMark() into it, and
     * asserts the expected DOM shape of the "Feed." wordmark:
     *
     *   div[data-component="brand-mark"]
     *     span[data-part="wordmark"].type-brand  textContent="Feed"
     *     span[data-part="dot"]                  accent dot (sibling, after wordmark)
     */
    @Test
    fun brandMarkDOMShape() {
        val host = document.createElement("div") as HTMLElement
        host.append { brandMark() }

        // Wrapper
        val wrapper = host.querySelector("[data-component='brand-mark']") as? HTMLElement
        assertNotNull(wrapper, "brand-mark wrapper element not found")

        // Wordmark
        val wordmark = wrapper.querySelector("[data-part='wordmark']") as? HTMLElement
        assertNotNull(wordmark, "wordmark element not found inside brand-mark")
        assertEquals("Feed", wordmark.textContent, "wordmark text must be 'Feed'")

        // Accent dot
        val dot = wrapper.querySelector("[data-part='dot']") as? HTMLElement
        assertNotNull(dot, "dot element not found inside brand-mark")
    }

    @Test
    fun brandMarkDotIsSiblingAfterWordmark() {
        val host = document.createElement("div") as HTMLElement
        host.append { brandMark() }

        val wrapper = host.querySelector("[data-component='brand-mark']") as? HTMLElement
        assertNotNull(wrapper)
        // The dot must follow the wordmark as a sibling (trailing "." in "Feed.").
        val children = wrapper.children
        assertEquals(2, children.length, "brand-mark should have exactly two children")
        assertEquals("wordmark", (children.item(0) as HTMLElement).getAttribute("data-part"))
        assertEquals("dot", (children.item(1) as HTMLElement).getAttribute("data-part"))
    }

    @Test
    fun brandMarkWrapperHasFlexLayout() {
        val host = document.createElement("div") as HTMLElement
        host.append { brandMark() }

        val wrapper = host.querySelector("[data-component='brand-mark']") as? HTMLElement
        assertNotNull(wrapper)
        // The inline style must set display:flex so wordmark and dot sit side-by-side,
        // bottoms flush.
        val style = wrapper.getAttribute("style") ?: ""
        assertTrue(style.contains("flex"), "Expected 'flex' in brand-mark style, got: $style")
        assertTrue(
            style.contains("flex-end"),
            "Expected 'align-items: flex-end' (baseline-flush) in brand-mark style, got: $style"
        )
    }

    @Test
    fun brandMarkDotIsAccentCircle() {
        val host = document.createElement("div") as HTMLElement
        host.append { brandMark() }

        val dot = host.querySelector("[data-part='dot']") as? HTMLElement
        assertNotNull(dot)
        val style = dot.getAttribute("style") ?: ""
        assertTrue(style.contains("3px"), "Expected '3px' (dot size) in dot style, got: $style")
        assertTrue(
            style.contains("border-radius: 50%"),
            "Expected 'border-radius: 50%' in dot style, got: $style"
        )
        assertTrue(
            style.contains("--feed-accent"),
            "Expected '--feed-accent' color in dot style, got: $style"
        )
    }

    @Test
    fun brandMarkWordmarkHasTypeBrandClass() {
        val host = document.createElement("div") as HTMLElement
        host.append { brandMark() }

        val wordmark = host.querySelector("[data-part='wordmark']") as? HTMLElement
        assertNotNull(wordmark)
        assertTrue(
            wordmark.classList.contains("type-brand"),
            "Expected 'type-brand' class on wordmark, classes: ${wordmark.className}"
        )
    }
}
