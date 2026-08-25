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
    var onversionchange: ((Event) -> Unit)?
    var onclose: ((Event) -> Unit)?
}

external class IDBTransaction : EventTarget {
    fun objectStore(name: String): IDBObjectStore
    var oncomplete: ((Event) -> Unit)?
    var onerror: ((Event) -> Unit)?
    var onabort: ((Event) -> Unit)?
    var error: dynamic
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

/**
 * Idempotently ensures every object store the app's single shared IndexedDB database
 * (`feed_articles`) needs exists. [IndexedDbArticleStore] and [IndexedDbFeedStore] both
 * open this same physical database at the same name/version, and IndexedDB fires
 * `onupgradeneeded` only on whichever [IDBFactory.open] call is first to observe an
 * out-of-date version — so the full schema has to be creatable from either store's
 * `open()`, not just one of them. Centralizing it here is what makes that true regardless
 * of call order.
 *
 * Every creation is guarded by [containsStore], so running this against an
 * already-up-to-date database (the common case: in production `IndexedDbArticleStore`
 * always opens first, so `IndexedDbFeedStore`'s own `onupgradeneeded` typically never even
 * fires) is a no-op that never touches, let alone drops, existing data.
 */
internal fun ensureFeedDbSchema(db: IDBDatabase) {
    if (!containsStore(db.objectStoreNames, IndexedDbArticleStore.STORE_ARTICLES)) {
        val store = db.createObjectStore(IndexedDbArticleStore.STORE_ARTICLES, js("({keyPath: 'id'})"))
        store.createIndex(IndexedDbArticleStore.INDEX_PUBLISHED_SEQ, arrayOf("published", "seq"))
        store.createIndex(IndexedDbArticleStore.INDEX_FEED_ID, "feed_id")
    }
    if (!containsStore(db.objectStoreNames, IndexedDbArticleStore.STORE_META)) {
        db.createObjectStore(IndexedDbArticleStore.STORE_META, js("({keyPath: 'key'})"))
    }
    if (!containsStore(db.objectStoreNames, IndexedDbArticleStore.STORE_PENDING_MUTATIONS)) {
        db.createObjectStore(IndexedDbArticleStore.STORE_PENDING_MUTATIONS, js("({keyPath: 'id'})"))
    }
    // BUG-63 part 1: persists the FeedMeta display subset so ArticleItem.feedTitle
    // resolves offline. See IndexedDbFeedStore.
    if (!containsStore(db.objectStoreNames, IndexedDbFeedStore.STORE_FEEDS)) {
        db.createObjectStore(IndexedDbFeedStore.STORE_FEEDS, js("({keyPath: 'id'})"))
    }
}

/** Check whether a DOMStringList contains a given name. */
internal fun containsStore(list: dynamic, name: String): Boolean =
    (list.contains(name) as Boolean?) ?: false
