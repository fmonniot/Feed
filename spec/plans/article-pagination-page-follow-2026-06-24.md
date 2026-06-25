# Article list page-follow — close the >50-unread gap left by BUG-22

**Date:** 2026-06-24 19:16 PDT
**Status:** Ready to execute (sized for a Sonnet session). All decisions are locked below.
**Architecture follow-up:** the larger "local-mirror" rework is **out of scope** here and tracked as **#95** in TICKETS.md. Do this bug fix first.

## Context

The per-subscription badge counts unread articles **uncapped**
(`SELECT COUNT(*) … WHERE is_read = 0`, [server/src/db.rs:1230-1231](../../server/src/db.rs#L1230-L1231)),
but every client article fetch stops at the server's default `limit = 50`
([server/src/api/types.rs:128-130](../../server/src/api/types.rs#L128-L130)) and no client passes a
`limit`/`offset` or follows further pages. PR 72 (BUG-22) switched the per-feed view from the
global `/v1/articles` to `/v1/feeds/{id}/articles`, which fixed the *dilution* case but **not** a feed
with **>50 unread**: the badge shows 73, the list shows 50.

This change adds a single page-follow loop and routes both client fetch paths through it. No server
change, no API-model change, no `FeedRepository` interface change, no `FeedViewModel` change.

## Scope

**In scope**
- One shared page-follow loop on `FeedApi` (`shared/`).
- Route `refresh()` and `refreshForFeed()` in both client repos through it (`app/`, `web/`).
- Tests proving multi-page following and badge==list for a >50-unread feed.

**Out of scope (do NOT do here — tracked in #95)**
- Persistent web store / IndexedDB, incremental `since`-based sync, deletion reconciliation.
- Reverting PR 72 / the per-feed endpoint. **Keep it.**
- Changing the `clearAll()` vs merge semantics (see locked decision 4).
- UI infinite scroll. Not needed — the loop fills the local store up front; the list renders whatever the store holds.

## Locked decisions

1. **Keep PR 72.** Two scopes (global + per-feed), but **one** loop implementation. Do not revert the per-feed endpoint.
2. **Stop condition = short page + safety cap** (details below). No reliance on server pagination metadata — the server sends `total: None` ([server/src/api/types.rs:41-47](../../server/src/api/types.rs#L41-L47)).
3. **`is_read` filter stays unfiltered** (read + unread), exactly as today. The shared store feeds both the "Unread" tab (`filter { !isRead }` client-side) and the "All" tab ([app/.../FeedScreen.kt:166-169](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L166-L169)). Filtering server-side would break the All tab. This change is literally "same data as today, but follow pages past 50."
4. **Preserve existing store-write semantics.** `refreshForFeed` keeps `clearAll()` + insert (the "isolates to selected feed" test depends on it — [FeedRepositoryArticleCountTest.kt:137-160](../../app/src/test/java/eu/monniot/feed/integration/FeedRepositoryArticleCountTest.kt#L137-L160)); `refresh()` keeps its current insert/merge. Only the *fetch* changes from one page to all pages. Reconciling the asymmetry belongs to #95.
5. **"Already-seen" early stop is NOT implemented here.** It requires a merge model + deletion handling and risks skipping backfilled older articles; it's a #95 concern.

## The loop and its exact stop condition

The server returns articles `ORDER BY published DESC LIMIT ? OFFSET ?` ([server/src/db.rs:1498](../../server/src/db.rs#L1498))
with no `has_more`/`total`. `limit` binds straight into SQL with no server-side clamp
([server/src/db.rs:1498-1503](../../server/src/db.rs#L1498-L1503)), so any `pageSize` is honored.

```
acc = []
offset = 0
loop:
    page = fetchPage(limit = pageSize, offset = offset)
    acc += page
    if page.size < pageSize: break        # (1) short page = definitive end of list
    if acc.size >= maxArticles: break      # (2) safety cap — log a warning
    offset += pageSize
return acc
```

1. **Short page = end.** `page.size < pageSize` is guaranteed by SQL `LIMIT` to mean no further rows.
   An exactly-full final page is handled: the next request returns 0 rows (`0 < pageSize`) and stops.
2. **Safety cap.** Bounds memory/requests for a pathological feed. Retention already bounds the corpus;
   this is belt-and-suspenders. Log a warning (via `eu.monniot.feed.shared.util.Logger`) when hit.

Defaults: `pageSize = 100`, `maxArticles = 2000`. Both are parameters (the loop methods take them) so
tests can drive small pages.

## Implementation

### Step 1 — `shared/`: add the loop to `FeedApi`

File: [shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/FeedApi.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/FeedApi.kt)

Keep the existing single-page `getArticles`/`getFeedArticles`. Add constants and the loop + two
public entry points:

```kotlin
private const val ARTICLE_PAGE_SIZE = 100
private const val ARTICLE_MAX = 2000

/** Page-follow the global article list; returns every article (read + unread) up to [maxArticles]. */
suspend fun getAllArticles(
    isRead: Boolean? = null,
    pageSize: Int = ARTICLE_PAGE_SIZE,
    maxArticles: Int = ARTICLE_MAX,
): List<Article> = followPages(pageSize, maxArticles) { limit, offset ->
    getArticles(isRead = isRead, limit = limit, offset = offset).data
}

/** Page-follow one feed's article list; returns every article for [feedId] up to [maxArticles]. */
suspend fun getAllFeedArticles(
    feedId: Int,
    isRead: Boolean? = null,
    pageSize: Int = ARTICLE_PAGE_SIZE,
    maxArticles: Int = ARTICLE_MAX,
): List<Article> = followPages(pageSize, maxArticles) { limit, offset ->
    getFeedArticles(feedId, isRead = isRead, limit = limit, offset = offset).data
}

private suspend fun followPages(
    pageSize: Int,
    maxArticles: Int,
    fetchPage: suspend (limit: Int, offset: Int) -> List<Article>,
): List<Article> {
    val acc = mutableListOf<Article>()
    var offset = 0
    while (true) {
        val page = fetchPage(pageSize, offset)
        acc += page
        if (page.size < pageSize) break
        if (acc.size >= maxArticles) {
            Logger.w("FeedApi", "Article page-follow hit cap ($maxArticles); list may be truncated")
            break
        }
        offset += pageSize
    }
    return acc
}
```

(Add the `Logger` import — `eu.monniot.feed.shared.util.Logger`. Confirm it exposes `w`; if only `e`/`d` exist, use the closest level.)

### Step 2 — `app/`: route the Android repo through the loop

File: [app/src/main/java/eu/monniot/feed/FeedRepository.kt:167-180](../../app/src/main/java/eu/monniot/feed/FeedRepository.kt#L167-L180)

Change only the fetch calls; keep `clearAll()`/insert structure identical:

```kotlin
override suspend fun refresh() {
    val articles = api.getAllArticles()          // was api.getArticles().data
    val feedTitlesById = api.getFeeds().data
        .associate { it.id to (it.custom_title ?: it.title ?: it.url) }
    rssItemDao.insertAll(toEntities(articles, feedTitlesById))
}

override suspend fun refreshForFeed(feedId: Int) {
    val articles = api.getAllFeedArticles(feedId)  // was api.getFeedArticles(feedId).data
    val feedTitlesById = api.getFeeds().data
        .associate { it.id to (it.custom_title ?: it.title ?: it.url) }
    rssItemDao.clearAll()
    rssItemDao.insertAll(toEntities(articles, feedTitlesById))
}
```

### Step 3 — `web/`: route the web repo through the loop

File: [web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt:31-39](../../web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt#L31-L39)

```kotlin
override suspend fun refresh() {
    val articles = feedApi.getAllArticles()         // was feedApi.getArticles().data
    val feedsById = feedApi.getFeeds().data.associateBy { it.id }
    _items.value = toArticleItems(articles, feedsById)
}

override suspend fun refreshForFeed(feedId: Int) {
    val articles = feedApi.getAllFeedArticles(feedId) // was feedApi.getFeedArticles(feedId).data
    val feedsById = feedApi.getFeeds().data.associateBy { it.id }
    _items.value = toArticleItems(articles, feedsById)
}
```

## Tests (required — CLAUDE.md testing rule)

### Test A — multi-page following, direct API (real server)

Add to [FeedRepositoryArticleCountTest.kt](../../app/src/test/java/eu/monniot/feed/integration/FeedRepositoryArticleCountTest.kt)
(it already has `ServerRule` + `MockRssServer` + login wired). Drive a **small `pageSize`** so a modest
seed crosses page boundaries:

```kotlin
@Test
fun `getAllFeedArticles follows pages past one page`() = runTest {
    rss.enqueueRssFeedWithItems("Paged Feed", itemCount = 50)   // verify MockRssServer supports 50+
    val feedId = repository.addFeed(rss.baseUrl).id

    // pageSize 20 over 50 items ⇒ 3 pages (20 + 20 + 10), loop must return all 50
    val all = feedApi.getAllFeedArticles(feedId, pageSize = 20)
    assertEquals(50, all.size)
    assertTrue(all.all { it.feed_id == feedId })
}
```

### Test B — badge == list for a >50-unread feed (real server, default pageSize)

Proves the user-facing `refreshForFeed` path (default `pageSize = 100`) returns everything the badge
counts. Seed **>100** so it crosses a default page boundary (or assert the count equals the badge,
which alone proves the 50-cap is gone):

```kotlin
@Test
fun `refreshForFeed returns all unread when a feed has more than 50`() = runTest {
    rss.enqueueRssFeedWithItems("Big Feed", itemCount = 120)
    val feedId = repository.addFeed(rss.baseUrl).id

    val badge = repository.getFeeds().first { it.id == feedId }.unread_count ?: 0
    repository.refreshForFeed(feedId)
    val items = repository.items.first()

    assertEquals("badge must equal list count for a >50-unread feed", badge, items.size)
    assertTrue("must exceed the old 50 cap", items.size > 50)
}
```

If `MockRssServer.enqueueRssFeedWithItems` can't produce 100+ items easily, lower Test B to `itemCount = 73`
(still proves the 50-cap is gone via badge==list) and rely on Test A for true multi-page coverage.

### Test C (optional but recommended) — stop-condition edges, no server

A MockEngine unit test of `followPages` via `FeedApi`, asserting: exactly-full final page terminates
(server returns one full page then an empty page ⇒ 2 requests, no infinite loop); and the cap halts a
server that always returns full pages. Place alongside the existing shared MockEngine tests
(`shared/src/commonTest/...`), or in `app/.../FeedApiTest`-style harness with `install(ContentNegotiation){ json(...) }`.

## Validation

Run the full suite; require 0 failures, 0 ignored beyond the 2 known `@Ignore`d gesture tests:

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

## Done criteria

- `FeedApi.getAllArticles` / `getAllFeedArticles` exist and are the only article-fetch path used by both repos.
- A feed with >50 unread shows badge == list on both clients (Test B green).
- Multi-page following is proven (Test A green).
- No change to the `FeedRepository` interface, `FeedViewModel`, server, or API models.
- Full suite green.
