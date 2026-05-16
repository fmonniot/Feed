package eu.monniot.feed.web.ui.dom

import kotlinx.browser.document
import kotlinx.html.TagConsumer
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement

/**
 * Clears [parent] and appends the DOM tree produced by [block] into it.
 *
 * This is the primary rendering primitive: screens call it to mount their
 * content tree and replace whatever was previously rendered.
 */
fun render(parent: HTMLElement, block: TagConsumer<HTMLElement>.() -> Unit) {
    parent.innerHTML = ""
    parent.append(block)
}

/**
 * Finds the element with [id], clears it, and appends the DOM tree produced
 * by [block] into it.  Silently no-ops if the element is not found.
 *
 * Useful for partial re-renders (e.g. updating a list inside an already-
 * rendered shell) without touching the surrounding DOM.
 */
fun replace(id: String, block: TagConsumer<HTMLElement>.() -> Unit) {
    val el = document.getElementById(id) as? HTMLElement ?: return
    el.innerHTML = ""
    el.append(block)
}
