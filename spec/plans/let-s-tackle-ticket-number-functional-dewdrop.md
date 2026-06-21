# Plan: Remove Starring / Favorites End-to-End (#35)

**Date:** 2026-05-17 13:58 PDT

## Context

Starring leaked into the product from a generic design template. `spec/FEATURES.md` lists it under "Features explicitly NOT supported". Ticket #35 calls for removing it from all three layers: the Rust server, the shared KMP module, and both clients (Android + Web). This plan covers all removals in a single branch.

The work is purely subtractive — no new behaviour is added, only code deleted. The acceptance bar is: all four test suites pass after the deletion.

---

## Critical files

| File | What changes |
|---|---|
| `server/src/db.rs` | New migration v12 (drop indices + columns); remove `Article` struct fields, row mapper, `set_article_starred`, `get_starred_articles`, `get_starred_count`; remove `is_starred` guard in `delete_old_articles`; remove `is_starred` filter from `get_articles` / `get_articles_by_feed` |
| `server/src/api/types.rs` | Remove `StarRequest`, `StarResponse`, `StarredCountResponse`; remove `is_starred` from `ArticleQuery`; remove `starred` from `ArticleStats` |
| `server/src/api/handlers.rs` | Remove `set_article_starred_handler`, `get_starred_articles_handler`, `get_starred_count_handler`; remove `is_starred` param passing in `get_articles_handler` / `get_feed_articles_handler`; remove `starred_articles` from `get_stats_handler` |
| `server/src/main.rs` | Remove three route registrations + imports for starred handlers |
| `server/src/db_tests.rs` | Delete `test_get_starred_count`, `test_set_article_starred`, `test_get_starred_articles`; remove starred-filter assertions from `test_get_articles_with_filters`; remove starred-protection assertions from `test_delete_old_articles_starred_protected` |
| `spec/API_DOCUMENTATION.md` | Remove sections for `PUT /v1/articles/{id}/star`, `GET /v1/articles/starred`, `GET /v1/articles/starred-count`; remove `is_starred` query param docs |
| `shared/.../api/Models.kt` | Remove `is_starred`, `starred_at` from `Article`; remove `starred` from `ArticleStats`; delete `ArticleStarUpdateRequest` |
| `shared/.../api/FeedApi.kt` | Delete `getStarredArticles()` and `starArticle()` |
| `shared/.../FeedRepository.kt` | Remove `isStarred` from `ArticleItem`; remove `toggleStarred` and `getStarred` from the interface |
| `shared/.../FeedViewModel.kt` | Remove `_starredItems`, `starredItems`, `toggleStarred()`, `loadStarred()` |
| `shared/...test.../FeedViewModelStarredTest.kt` | **Delete entirely** |
| `shared/...test.../FeedViewModelPrefsTest.kt` | Remove `toggleStarred` / `getStarred` stubs from the fake repo |
| `shared/...test.../api/ArticleModelTest.kt` | Remove `"is_starred"` / `"starred_at"` fields from all JSON fixtures and corresponding assertions |
| `app/.../FeedRepository.kt` | Remove `toggleStarred()`, `getStarred()`, `_starredCache` |
| `app/.../FeedViewModel.kt` | Remove `toggleStarred()` delegate |
| `app/.../MainActivity.kt` | Remove `isStarred` local state, `onToggleStar` callback, `SavedTabPlaceholder` composable, and "Saved" tab wiring |
| `app/.../ui/reader/ReaderScreen.kt` | Remove `isStarred`/`onToggleStar` params from `ReaderScreen` and `ReaderTopBar`; remove ★ button; remove star preview functions |
| `app/.../ui/feed/ArticleRow.kt` | Remove `when { article.isStarred -> Text("★") }` block and `isStarred` field reference |
| `app/.../ui/feed/FeedScreen.kt` | Remove `isStarred` from preview data objects |
| `app/...test.../ui/reader/ReaderScreenTest.kt` | Remove `starButtonTogglesIsStarred()` test and `isStarred` parameter usages |
| `app/...test.../ui/feed/FeedScreenTest.kt` | Remove `isStarred` from fixture objects |
| `app/...test.../ToEntitiesTest.kt` | Remove `is_starred` / `starred_at` from JSON fixture strings |
| `web/.../Router.kt` | Remove `Route.Starred` data object; remove `"saved"` cases in `parseHash()`/`toHash()` |
| `web/.../Main.kt` | Remove `Route.Starred` comment/branch |
| `web/.../ui/feed/FeedScreen.kt` | Remove `Route.Starred` branch from route-dispatch block |
| `web/.../ui/feed/Sidebar.kt` | Remove "Starred" nav item, `starredCount`, and click handler |
| `web/.../ui/feed/ArticleList.kt` | Remove `if (item.isStarred)` ★ render block |
| `web/.../ui/feed/ReaderPane.kt` | Remove `starredItems.collect { … }` observer, `reader-star-btn`, and its click handler |
| `web/.../data/WebFeedRepository.kt` | Remove `toggleStarred()` and `getStarred()` |
| `web/...test.../RouterTest.kt` | Remove `hashSavedIsStarred()`, `toHashRoundTripStarred()`, `starredRoundTrip()` tests |
| `web/...test.../ArticleListSelectionTest.kt` | Remove `starredRowShowsStarIcon()` test; remove `isStarred` from non-starred fixtures |

---

## Implementation order

Work layer by layer so each test suite can be re-run as a checkpoint:

### 1. Server (Rust)

**Migration v12** — add inside `Database::new` after the v11 block:

```rust
if version < 12 {
    sqlx::query("DROP INDEX IF EXISTS idx_articles_starred_at").execute(&pool).await?;
    sqlx::query("DROP INDEX IF EXISTS idx_articles_is_starred").execute(&pool).await?;
    sqlx::query("ALTER TABLE articles DROP COLUMN starred_at").execute(&pool).await?;
    sqlx::query("ALTER TABLE articles DROP COLUMN is_starred").execute(&pool).await?;
    sqlx::query("INSERT INTO schema_version (version) VALUES (12)").execute(&pool).await?;
}
```

Note: SQLite `ALTER TABLE DROP COLUMN` requires SQLite ≥ 3.35.0 (2021-03-12). The indices must be dropped first because SQLite will refuse to drop a column that is referenced by an index.

Then, in order:
- `server/src/db.rs`: remove `Article` struct fields + row mapper columns; remove `set_article_starred`, `get_starred_articles`, `get_starred_count`; remove `is_starred = 0` guard in `delete_old_articles`; remove `is_starred` filter params in `get_articles` / `get_articles_by_feed`
- `server/src/api/types.rs`: remove three structs + field from `ArticleQuery` + field from `ArticleStats`
- `server/src/api/handlers.rs`: remove three handlers; remove `is_starred` passthrough; remove starred count from stats handler
- `server/src/main.rs`: remove three routes + imports
- `server/src/db_tests.rs`: remove/trim the five test functions identified above
- `spec/API_DOCUMENTATION.md`: remove three endpoint sections

Validate: `cd server && cargo test` — document new passing count.

### 2. Shared KMP

- `Models.kt`, `FeedApi.kt`, `FeedRepository.kt`, `FeedViewModel.kt` — remove as described above
- Delete `FeedViewModelStarredTest.kt`
- Patch `FeedViewModelPrefsTest.kt` and `ArticleModelTest.kt`

Validate: `./gradlew :shared:allTests` (should pass on both JS + wasmJs).

### 3. Android

- `app/.../FeedRepository.kt`, `FeedViewModel.kt`, `MainActivity.kt`
- `app/.../ui/reader/ReaderScreen.kt`, `ui/feed/ArticleRow.kt`, `ui/feed/FeedScreen.kt`
- Tests: `ReaderScreenTest.kt`, `FeedScreenTest.kt`, `ToEntitiesTest.kt`

Validate: `./gradlew :app:testDebugUnitTest`

### 4. Web

- `Router.kt`, `Main.kt`, `ui/feed/FeedScreen.kt`, `Sidebar.kt`, `ArticleList.kt`, `ReaderPane.kt`
- `data/WebFeedRepository.kt`
- Tests: `RouterTest.kt`, `ArticleListSelectionTest.kt`

Validate: `./gradlew :web:jsTest`

### 5. Final full-suite run

```sh
(cd server && cargo test) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

---

## Verification

After all deletions:

1. **Server**: `cargo test` passes; new baseline will be fewer than 97 (the tests being removed are: `test_get_starred_count`, `test_set_article_starred`, `test_get_starred_articles`, plus any sub-assertions in other tests). Note: `test_get_starred_articles` is currently `#[ignore]`'d — its removal resolves one of the #22 ignored-test tickets as a side effect.

2. **Shared**: `./gradlew :shared:allTests` — 16 tests per platform expected to still pass (starred tests were only in the deleted file).

3. **Android**: `./gradlew :app:testDebugUnitTest` — 50 tests expected (minus any star-only tests removed). Check actual count after.

4. **Web**: `./gradlew :web:jsTest` — 11 tests minus the 4 starred tests = 7 expected.

5. **Navigation sanity**: confirm web sidebar has exactly 4 items (Unread / All articles / Subscriptions / Settings) and Android bottom bar has 4 tabs (Unread / All / Feeds / Settings) matching `spec/FEATURES.md`.
