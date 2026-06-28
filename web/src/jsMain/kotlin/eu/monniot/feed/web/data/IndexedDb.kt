@file:Suppress("NOTHING_TO_INLINE")

package eu.monniot.feed.web.data

import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget

/**
 * Minimal Kotlin/JS external declarations for the IndexedDB API.
 *
 * Only the subset needed by [IndexedDbArticleStore] is declared here.
 * The browser stdlib (`org.w3c.dom`) does not include IndexedDB types,
 * so we provide typed wrappers over the raw JS objects.
 */

external class IDBFactory {
    fun open(name: String, version: Int): IDBOpenDBRequest
    fun deleteDatabase(name: String): IDBOpenDBRequest
}

external class IDBOpenDBRequest : EventTarget {
    var result: dynamic
    var error: dynamic
    var onupgradeneeded: ((Event) -> Unit)?
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

external class IDBDatabase : EventTarget {
    val objectStoreNames: dynamic
    fun transaction(storeNames: Array<String>, mode: String): IDBTransaction
    fun createObjectStore(name: String, options: dynamic = definedExternally): IDBObjectStore
    fun close()
}

external class IDBTransaction : EventTarget {
    fun objectStore(name: String): IDBObjectStore
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
}

external class IDBObjectStore : EventTarget {
    val indexNames: dynamic
    fun put(value: dynamic): IDBRequest
    fun get(key: dynamic): IDBRequest
    fun delete(key: dynamic): IDBRequest
    fun clear(): IDBRequest
    fun count(query: dynamic = definedExternally): IDBRequest
    fun openCursor(range: dynamic = definedExternally, direction: String = definedExternally): IDBRequest
    fun createIndex(name: String, keyPath: dynamic, options: dynamic = definedExternally): dynamic
    fun index(name: String): IDBIndex
}

external class IDBIndex {
    fun openCursor(range: dynamic = definedExternally, direction: String = definedExternally): IDBRequest
    fun count(query: dynamic = definedExternally): IDBRequest
}

external class IDBRequest : EventTarget {
    var result: dynamic
    var error: dynamic
    var onsuccess: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
}

external class IDBCursor {
    val key: dynamic
    val value: dynamic
    fun `continue`()
}

external class IDBKeyRange {
    companion object {
        fun bound(lower: dynamic, upper: dynamic, lowerOpen: Boolean = definedExternally, upperOpen: Boolean = definedExternally): IDBKeyRange
        fun only(value: dynamic): IDBKeyRange
        fun lowerBound(lower: dynamic, open: Boolean = definedExternally): IDBKeyRange
        fun upperBound(upper: dynamic, open: Boolean = definedExternally): IDBKeyRange
    }
}

/** Access `window.indexedDB` (or its prefixed variants). */
inline fun getIndexedDB(): IDBFactory =
    js("(window.indexedDB || window.mozIndexedDB || window.webkitIndexedDB || window.msIndexedDB)")
        .unsafeCast<IDBFactory>()
