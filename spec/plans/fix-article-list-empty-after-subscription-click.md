# Fix: empty article list after clicking a subscription

**Date:** 2026-05-17 09:19 PDT

## Context

After adding a subscription, the sidebar shows e.g. "32" next to the feed (unread count), but clicking the feed in the sidebar opens an article list pane that stays empty — both on **web** and **Android**. The browser confirms the server responded with 32 articles for `GET /v1/articles?is_read=false`. So the API is fine; something between "response received" and "rendered list" is dropping the data.

**Root cause (verified):** The shared client model [`Article`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt#L66-L80) declares `val read_at: Long?` **without a default**, so kotlinx.serialization treats it as a required field. The Rust server's [`Article` struct](server/src/db.rs#L41-L59) does not have a `read_at` field at all — it is never serialized. Decoding the JSON response throws `MissingFieldException` inside [`WebFeedRepository.refresh()`](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt#L28-L50). The exception is swallowed by [`FeedViewModel.refresh()`'s `catch (_: Exception)`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L130-L142) — `uiState` becomes `Error("Could not refresh — showing cached articles")` but `_items` is never updated, so `articleItems` stays at `emptyList()` and the article-list filter `items.filter { it.feedId == selectedFeedId }` ([ArticleList.kt:129-133](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt#L129-L133)) yields nothing.

The sidebar's "32" works because it comes from `GET /v1/feeds`, whose `Feed` model happens to match what the server emits (extra fields like `etag`, `last_modified` are tolerated by `ignoreUnknownKeys = true`, and no required client field is missing). The unread counts in the sidebar are real; the article fetch is silently broken.

The same shared `Article` model is consumed by the Android client via `FeedApi`, so Android exhibits the same bug for the same reason.

## Recommended approach

Align the client `Article` model **strictly** with the server's actual response shape — no defaults for missing fields, no leniency. There are no deployed clients to keep compatible with, so the right move is to fix the contract, not to paper over the drift.

Concrete changes:

1. **Remove `read_at`** from the client `Article` (the server doesn't have it; no client code reads it).
2. **Remove `rank`** unused — also not in the server's `Article` struct (search results use a different shape; if/when search is wired up, a dedicated model goes with it).
3. **Add `fetched_at: Long?`** to match the server's struct (server: [`db.rs:56`](server/src/db.rs#L56); currently dropped by `ignoreUnknownKeys`).
4. **Make `title`, `content`, `link`, `published` nullable** (`String?` / `Long?`). The server declares these `Option<T>` ([`db.rs:45-48`](server/src/db.rs#L45-L48)) — feeds without a title/published date are legal and we should not throw on them.

Downstream consumers in [`WebFeedRepository`](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt) and [`app/src/main/java/eu/monniot/feed/FeedRepository.kt`](app/src/main/java/eu/monniot/feed/FeedRepository.kt) map these into `ArticleItem`/`RssItem`, whose fields are non-null. Substitute fallbacks at the mapping site rather than pushing nullability through the UI.

## Files to change

### 1. [shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt#L66-L80)

```kotlin
@Serializable
data class Article(
    val id: Int,
    val feed_id: Int,
    val guid: String,
    val title: String?,
    val content: String?,
    val link: String?,
    val author: String?,
    val published: Long?,
    val is_read: Boolean,
    val is_starred: Boolean,
    val starred_at: Long?,
    val fetched_at: Long?,
)
```

### 2. [web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt)

Both `refresh()` (lines 28–50) and `getStarred()` (lines 91–114) build `ArticleItem` from `Article`. Substitute fallbacks for the now-nullable fields:

- `title = article.title ?: "Untitled"`
- `description = article.content.orEmpty()`
- `url = article.link.orEmpty()`
- `pubDate = article.published?.let { getRelativeTime(epochSecondsToInstant(it)) } ?: ""`
- `minutesToRead = minutesToRead(article.content.orEmpty())`
- `excerpt = excerpt(article.content.orEmpty())`

### 3. [app/src/main/java/eu/monniot/feed/FeedRepository.kt](app/src/main/java/eu/monniot/feed/FeedRepository.kt)

Two mapper sites (around lines 45–51 and 195–210) need the same null handling. `article.published * 1000` and `dateFormat.format(...)` must handle `published == null` — fall back to an empty `pubDate` (and `timestamp = 0L`, or skip — match whatever current sort/render tolerates).

### 4. TODO.md (new entries — see below)

Two new tickets, added at the end of the appropriate section.

## TODO.md additions

### `#23 — Surface refresh / API errors in dev`

Add under **Miscellaneous**. Body:

> [`FeedViewModel.refresh()`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L130-L142) and most other action methods use `catch (_: Exception)` and surface only a generic "Could not refresh — showing cached articles" / "Failed to …" message. The actual exception (network failure, JSON drift, etc.) is dropped. The empty-article-list bug fixed alongside this ticket took an hour to diagnose because of this — a `MissingFieldException` from the JSON layer would have been a one-line giveaway.
>
> **Acceptance criteria**
> - Each `catch (_: Exception)` in `FeedViewModel` (and the repository layers) logs the exception (message + stack) before mapping to the user-facing message. For web, this means `console.error(…)`; for Android, `Log.e(…)`.
> - The user-facing message itself stays the same (we don't want raw stack traces in production UI).
> - A documented dev-mode toggle that swaps the user-facing message for the raw exception text is acceptable but not required.

### `#24 — Contract tests between client model and server JSON`

Add under **Miscellaneous**. Body:

> The shared client models ([`Models.kt`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt)) and the server's serialized response shapes ([`server/src/db.rs`](server/src/db.rs), [`server/src/api/types.rs`](server/src/api/types.rs)) drift independently. Today's bug: the client `Article` required a `read_at` field the server never emits → `MissingFieldException` swallowed by a generic catch → silently empty article list. With `ignoreUnknownKeys = true` only the "extra fields" direction is guarded; "missing fields" / "type changed" still blow up at runtime.
>
> **Motivation example.** A future change that flips a nullable field to non-nullable on the server (or vice versa) would currently only be discovered by a user noticing data is missing in the UI. A contract test would fail the build the moment the JSON shape diverges from what the client expects.
>
> **Acceptance criteria**
> - For each REST endpoint the client calls (articles list, feed list, categories, stats, …), there is a fixture JSON or programmatically-generated representative response, and a test that deserializes it into the client model without throwing.
> - Fixtures live somewhere both sides can read — e.g. `shared/src/commonTest/resources/` checked in, with a server-side test that writes the same fixtures from real Rust structs so the two can be diffed.
> - Suggested split: a `shared:contractTest` task runs the deserialization side; a `cargo test --test contract` runs the server-side serialization side and writes the fixtures; CI runs both and diffs.
> - Lower-effort alternative if the above is too much: a single test per endpoint that calls a small test server and decodes one real response. Catches the same class of bug but requires running the server.

## Verification

1. **New shared-module unit test** that locks in the JSON contract:
   - Add a test under `shared/src/commonTest/kotlin/eu/monniot/feed/shared/api/` that decodes a literal server-shaped JSON string (with `read_at`/`rank` absent and `fetched_at` present) into `Article` via the same `Json` config used by `FeedApi`. Asserts it doesn't throw and that fields round-trip.
   - Run: `./gradlew :shared:allTests` — expect previous count + 1 per platform (JS + wasmJs).
2. **Manual end-to-end check** (the original bug is in the request/response shape; this confirms the UI now lights up):
   - Server is already running on `127.0.0.1:3000` (admin/admin). Start the web client (`./gradlew :web:jsBrowserDevelopmentRun`).
   - Log in, observe the previously-added feed in the sidebar with its unread count.
   - Click the feed — article list now populates with N rows matching the sidebar count.
   - Repeat on Android via `./gradlew :app:installDebug`.
3. **Existing test sweep:** `./scripts/test-run.sh all` — confirm no regressions.

## Critical files

- [shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt) — the model fix
- [web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt) — web mapper
- [app/src/main/java/eu/monniot/feed/FeedRepository.kt](app/src/main/java/eu/monniot/feed/FeedRepository.kt) — Android mapper
- [TODO.md](TODO.md) — two new tickets #23, #24
- new test file under `shared/src/commonTest/.../api/`
