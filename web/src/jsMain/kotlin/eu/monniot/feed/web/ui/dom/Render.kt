package eu.monniot.feed.web.ui.dom

import kotlinx.browser.document
import kotlinx.html.TagConsumer
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

// kotlinx.html.dom.append is an extension on Node, but the DOM `ParentNode.append(vararg nodes)`
// member shadows it on HTMLElement and stringifies the lambda. Upcast to Node so the
// extension wins. See https://github.com/Kotlin/kotlinx.html/issues/280.

/**
 * Clears [parent] and appends the DOM tree produced by [block] into it.
 *
 * This is the primary rendering primitive: screens call it to mount their
 * content tree and replace whatever was previously rendered.
 */
fun render(parent: HTMLElement, block: TagConsumer<HTMLElement>.() -> Unit) {
    parent.innerHTML = ""
    (parent as Node).append(block)
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
    (el as Node).append(block)
}
