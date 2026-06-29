# Bug backlog

**Date:** 2026-06-09 18:34 PDT

Findings from a full-project bug review (server, shared, app, web). This file drives
fix sessions: each bug is self-contained — symptom, root cause, fix direction, and
the validation required to call it done.

## How to use this file (instructions for a fix session)

1. Pick the highest-priority bug whose status is `OPEN`. One bug per session unless
   bugs are explicitly grouped (see "Pairs well with" notes).
2. Set its status to `IN PROGRESS (<branch name>)` before starting, `FIXED` when done.
3. **Every fix needs a test** (see CLAUDE.md "Testing requirement"). Each bug below
   names the test suite to extend. Run the relevant suite(s) via
   `./scripts/test-run.sh [server|android|shared|web]` and confirm the expected
   counts in CLAUDE.md still pass (plus your new tests).
4. Verify the root cause before fixing — line numbers were correct as of the date
   above and may have drifted. If investigation shows a finding is wrong, mark it
   `INVALID` with a one-line explanation instead of fixing it.
5. Keep fixes minimal and scoped to the bug. If you spot adjacent problems, add them
   to this file rather than expanding the diff.

Severity: **P1** = security or broken core behavior · **P2** = wrong results,
data-integrity, or significant UX failures · **P3** = robustness, leaks, polish.

Session order is in [NEXT.md](NEXT.md) — P-levels here describe severity only.

---

## P1 — Security / broken core behavior

### BUG-1: XSS bypass in web HTML sanitizer (`javascript:` check defeated by whitespace)

- **Status:** FIXED
- **Module:** `web/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/HtmlSanitizer.kt`
  (lines ~106 and ~115, the `startsWith("javascript:")` checks);
  injection site `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt:234`
  (`unsafe { +sanitized }` into `innerHTML`).
- **Symptom:** A malicious or compromised feed can execute script in the web client.
  Article body HTML is sanitized then injected via `innerHTML`. The `javascript:`
  scheme check is a plain lowercase `startsWith` on the raw attribute value.
- **Root cause:** Browsers strip leading whitespace and embedded tab/newline/control
  characters when parsing URLs, so `href=" javascript:alert(1)"` or
  `href="jav<TAB>ascript:alert(1)"` (literal tab in the value) pass the prefix check
  but execute as `javascript:` URLs.
- **Fix direction:** Replace the denylist with a scheme **allowlist**: parse the URL
  value after removing ASCII control chars and trimming; allow only `http:`,
  `https:`, protocol-relative, and relative URLs for `<a href>`; `http:`/`https:`
  (and optionally `data:image/`) for `<img src>`. Consider rewriting the sanitizer
  on top of `DOMParser` (walk a real DOM tree, rebuild allowed nodes) instead of
  regex over strings — regex sanitizers are structurally prone to bypasses.
- **Validation:** Extend the web Karma suite (`./gradlew :web:jsTest`) — there are
  existing sanitizer tests to add cases to: leading-space scheme, embedded tab/newline
  in scheme, mixed case, `data:text/html`, plus regression cases for currently-allowed
  markup. 257 tests passed before this change.

### BUG-2: Per-feed `fetch_interval_minutes` is never honored for healthy feeds

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/scheduler.rs:24-45` (`should_skip_feed`),
  `server/src/scheduler_tests.rs` (tests codify the current wrong behavior).
- **Symptom:** Every non-paused feed with `error_count == 0` is fetched on every
  30-minute scheduler tick, regardless of its configured interval. A feed set to
  6 hours fetches every 30 min; one set to 5 min fetches only every 30 min. The API
  validates/stores the value and both UIs expose it, so it looks functional but isn't.
- **Root cause:** `should_skip_feed` returns `false` immediately when
  `error_count == 0`; the interval is only used as the backoff base after errors.
- **Fix direction:** When `error_count == 0` and `last_fetched` is `Some`, skip if
  `now - last_fetched < fetch_interval_minutes * 60`. Keep the existing backoff
  branch for `error_count > 0`. Intervals below the 30-min tick can't be fully
  honored — document that floor (or leave as-is; the 5-min API minimum then just
  means "every tick").
- **Validation:** Update/extend `server/src/scheduler_tests.rs`: healthy feed inside
  its interval → skipped; outside → fetched; `last_fetched = None` → fetched.
  `cd server && cargo test`.

### BUG-3: `getParseError` 404 handling is dead code → stale parse-error shown for wrong feed

- **Status:** FIXED
- **Module:** `shared/`
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/FeedApi.kt:89-93`;
  `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt:406-418`
  (`loadParseError`). Context: `expectSuccess = true` in both
  `HttpClientFactory.android.kt:30` and `HttpClientFactory.js.kt:17`.
- **Symptom:** Open the parse-error inspector for feed A, then for feed B which has
  no parse error: B shows A's raw body and error details.
- **Root cause:** With `expectSuccess = true`, Ktor throws `ClientRequestException`
  on the 404 before `FeedApi.getParseError`'s `response.status == NotFound` check
  runs — the `null` return path is unreachable. `FeedViewModel.loadParseError`
  catches the exception, only logs it, and never resets `_parseError`, so the
  previous feed's value persists.
- **Fix:** `FeedApi.getParseError` now catches `ClientRequestException` and returns
  null on 404, re-throwing on other statuses. `FeedViewModel.loadParseError` clears
  `_parseError` before the fetch so failures also leave null, not stale state.
- **Tests:** `FeedViewModelParseErrorTest` (5 tests) and `FeedApiParseErrorTest`
  (2 tests) in `shared/src/commonTest/`. 169 passed, 0 failed.

---

## P2 — Wrong results / data integrity / major UX

### BUG-4: `/v1/logs` returns wrong/old lines when the active log file is short

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/api/handlers.rs` (`get_logs_handler`).
- **Symptom:** Right after log rotation (current file has fewer lines than
  requested), the endpoint drops the newest entries entirely and returns the tail
  of an older file instead.
- **Root cause:** Files are iterated newest-first and lines appended in that order,
  so `all_lines` = newest file's lines followed by older files' lines. The final
  `.rev().take(n).rev()` takes the tail of the vector — the *oldest* content.
- **Fix:** Collect per-file line vectors newest-first, then reverse to
  oldest-to-newest order before flattening and taking the last N lines.
  Preserves the per-file 1 MB tail cap.
- **Validated by:** previously `test_get_logs_handler_tail` in `server/src/main.rs`.
- **Note:** Now moot — the `/v1/logs` endpoint, the file appender, and that test were
  removed under ticket #74 (observability moved to journald-native stdout logging).
  The buggy code path no longer exists.

### BUG-5: Client `Feed.title` non-nullable vs server `Option<String>` → feed list can permanently fail to load

- **Status:** FIXED
- **Module:** `shared/` (model) + `server/` (optional hardening)
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt:34`
  (`val title: String`); server side `server/src/db.rs:19` (`title: Option<String>`),
  null-title creation paths: `server/src/api/handlers.rs` `add_feed_handler`
  (failure between `get_or_create_feed` and `update_feed_metadata`) and OPML import
  (`let _ = update_feed_metadata(...)` ~line 827).
- **Symptom:** If any feed row has `NULL` title, the server serializes
  `"title": null` and `getFeeds()` deserialization throws on both clients — every
  feed-list load fails ("Could not load feeds") with no recovery short of editing
  the DB.
- **Root cause:** Nullability mismatch between Rust model and Kotlin model.
- **Fix direction:** Make `title: String?` in the Kotlin `Feed` model and update the
  display fallback (`custom_title ?: title ?: url`) in `FeedViewModel.loadFeeds` and
  the web/app repositories. Optionally also harden the server (coalesce to URL or
  "Untitled Feed" at the API boundary).
- **Validation:** Shared test deserializing a feeds payload containing
  `"title": null` (`./gradlew :shared:allTests`). Check `FeedUiItem.displayTitle`
  fallback in `FeedViewModel` tests. Android JVM integration tests
  (`./gradlew :app:testDebugUnitTest`) must still pass (177 before).

### BUG-6: Article retention deletes unread articles and never deletes undated ones

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/db.rs` (`delete_old_articles`);
  `server/src/scheduler.rs` (hardcoded `RETENTION_DAYS: i64 = 90`).
- **Fix:** Changed deletion query to `COALESCE(published, fetched_at) < cutoff AND is_read = 1`.
  This (a) ages undated articles by `fetched_at` so they no longer accumulate forever,
  and (b) exempts unread articles from deletion — the safer default for a single-user
  reader where an unread article is content the user hasn't seen yet. The client-side
  "Keep articles" preference remains a separate gap (see BUG-12).
- **Tests:** Four new tests in `server/src/db_tests.rs` covering: read old article
  deleted, unread old article exempt, undated-but-old article deleted when read,
  recent articles kept regardless of read status.

### BUG-7: Android: transient network failure at startup forces login screen; session state not persisted

- **Status:** FIXED
- **Module:** `app/` + `shared/`
- **Files:** `app/src/main/java/eu/monniot/feed/FeedApplication.kt:49-63`
  (`probeSession`, and `SessionManager()` constructed without `settings` at line 37);
  related: `shared/.../FeedViewModel.kt:211` (`onApiError` nulls the modal username
  when blank).
- **Symptom:** Starting the app offline (or with the server briefly down) shows the
  login screen even though a valid session cookie and cached articles exist —
  contradicting the offline-cache design. Secondary: because the username is never
  persisted on Android, a mid-session 401 after process restart sets
  `_sessionExpiredUsername` to null → no SESSION EXPIRED modal, no inline error;
  refresh silently does nothing.
- **Root cause:** `probeSession()` maps *any* exception (including connectivity
  errors) to logged-out, and Android's `SessionManager` has no `Settings` backing so
  `isLoggedIn`/`username` reset on every process start.
- **Fix direction:** Pass the existing `SharedPreferencesSettings` into
  `SessionManager(...)` (as web's `Main.kt` does). In `probeSession`, only treat an
  actual 401 (`ClientRequestException` with status 401) as logged-out; on
  connectivity errors keep the persisted state.
- **Validation:** Android JVM tests (`./gradlew :app:testDebugUnitTest`): probe
  throwing a non-HTTP exception keeps persisted logged-in state; 401 clears it;
  session/username survive a simulated restart (new `SessionManager` over the same
  settings). 177 passing before.

### BUG-8: Android filter chips "Today" and "Long reads" can never match

- **Status:** FIXED
- **Resolution:** Resolved by removing the filter chips entirely in ticket #65
  (`ticket/65-remove-filter-chips`). The broken `ArticleFilter.matches` predicate
  and all chip-based UI were deleted; the broken code path no longer exists.
- **Module:** `app/` (+ `shared/` model field)
- **Symptom:** On Android, the "Today" chip always showed an empty list and
  "Long reads" was always empty / "Short reads" matched everything.
- **Root cause:** `Today` parsed `article.pubDate` with `toLongOrNull()`, but
  `pubDate` is a formatted string — never an epoch. `LongReads`/`ShortReads`
  used `minutesToRead`, which the Android repository never computed.

---

## P3 — Robustness / leaks / polish

### BUG-9: ParseFailed response doesn't reset the consecutive-410 counter

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/fetcher.rs:192-242` (`ParseFailed` branch of `process_feed`);
  contract documented at `server/src/db.rs:924` (`reset_feed_410_count`: "called on
  any non-410 response").
- **Symptom:** A feed marked dead (≥14 consecutive 410s) that starts responding 200
  with malformed content keeps `feed_status = "dead"` instead of `"parse_error"`.
- **Root cause:** Only the `Parsed` and `NotModified` branches call
  `reset_feed_410_count`; `ParseFailed` (a non-410 response) does not.
- **Fix direction:** Call `db.reset_feed_410_count(feed.id)` in the `ParseFailed`
  branch.
- **Validation:** New test in `server/src/fetcher_tests.rs` (use
  `MockFeedServer`/`TestDatabase` from `test_utils.rs`): seed
  `consecutive_410_count >= 14`, serve a 200 with garbage body, assert counter reset
  and status `parse_error`. `cd server && cargo test`.

### BUG-10: `get_or_create_feed` swallows real DB errors

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/db.rs:716-726`.
- **Symptom:** Any error from the SELECT (pool timeout, I/O) is treated as
  "not found" and triggers an INSERT; real failures surface as confusing UNIQUE
  violations or duplicate-insert attempts.
- **Root cause:** `Err(_) => self.add_feed(url).await` matches all errors.
- **Fix direction:** Match `sqlx::Error::RowNotFound` specifically (or use
  `fetch_optional`); propagate other errors.
- **Validation:** `server/src/db_tests.rs`: existing-URL returns existing id; new
  URL inserts. `cd server && cargo test`.

### BUG-11: Web: `hashchange` listener leak on every FeedScreen mount

- **Status:** FIXED
- **Module:** `web/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt:235`
  (`onRouteChange { ... }` inside `renderFeedScreen`);
  `web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt:73-76` (`onRouteChange` has
  no removal mechanism).
- **Symptom:** Navigating Feed → Settings → Feed repeatedly accumulates `window`
  hashchange listeners holding stale closures/detached DOM. Mostly invisible
  (stale handlers no-op on null querySelector) but a leak and a latent
  double-handling bug.
- **Root cause:** `window.addEventListener` per mount, never removed; the
  `feedScreenScope` cancellation pattern covers coroutines but not this listener.
- **Fix direction:** Make `onRouteChange` return an unsubscribe function; store it
  alongside `feedScreenScope` and invoke it on remount. Or route the inspector
  updates through the existing single listener in `Main.kt`.
- **Validation:** Web Karma test (`./gradlew :web:jsTest`) asserting unsubscribe
  stops callbacks; manual check that the parse-error inspector still opens/closes
  via hash navigation.

### BUG-12: "Refresh interval" and "Keep articles" preferences are decorative

- **Status:** FIXED — both knobs are now wired end-to-end by the fetch-and-retention
  work ([spec/plans/fetch-and-retention-policy.md](spec/plans/fetch-and-retention-policy.md),
  PRs #44–#51). "Keep articles" persists via `PUT /v1/settings/retention` and drives the
  3 AM sweep (#37); "Refresh interval" drives a client-side auto-poll timer (#38); and the
  primary refresh gesture now reaches upstream via `POST /v1/feeds/refresh`.
- **Module:** `app/` + `web/` (+ `server/` if wiring retention)
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/data/UserPrefs.kt`
  (`RefreshInterval`, `KeepArticles`); settings UIs in
  `app/.../ui/settings/SettingsScreen.kt` and `web/.../ui/SettingsScreen.kt`;
  no consumer anywhere. On Android nothing triggers `refresh()` automatically at
  all (pull-to-refresh only); server retention is hardcoded 90 days
  (`server/src/scheduler.rs:141`).
- **Symptom:** Users set 15m/1h/6h refresh and 30d/90d/1y/forever retention; nothing
  changes behavior.
- **Fix direction:** Decide per setting: implement (Android WorkManager periodic
  sync + web `setInterval`-style timer honoring `RefreshInterval`; a server config
  or API for retention honoring `KeepArticles`) or remove the controls until built.
  Implementing refresh-interval is a reasonable single session; retention wiring
  touches server config and may deserve its own plan in `spec/plans/`.
- **Validation:** Depends on choice; at minimum a ViewModel/scheduler test proving
  the selected interval drives refresh scheduling, suites per module touched.

### BUG-13: First-run "no feeds" pane shows for users who have feeds

- **Status:** FIXED
- **Module:** `app/` + `web/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt:369`
  (`feedCount == 0` branch; `feeds` is only populated when the Subscriptions tab
  calls `loadFeeds()` — also means the parse-error snackbar on article tabs can't
  trigger before that);
  `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt:244-260`
  (overlay shown whenever `feeds.isEmpty()`, including before `loadFeeds()`
  returns → flash on every mount).
- **Root cause:** "Not loaded yet" is indistinguishable from "loaded and empty"
  (`emptyList()` initial state), and Android's article tabs never trigger
  `loadFeeds()`.
- **Fix direction:** Track feeds-loaded state (e.g. nullable list or a
  `feedsLoaded: Boolean` flow in the shared `FeedViewModel`); only show first-run
  when loaded-and-empty. Call `loadFeeds()` from the article screens (or once after
  login) on Android.
- **Validation:** Shared `FeedViewModel` test for the loaded/empty distinction
  (`./gradlew :shared:allTests`); Robolectric test that the first-run pane doesn't
  render before feeds load (`./gradlew :app:testDebugUnitTest`).

### BUG-14: Android cookie storage drops `Max-Age`; blocking I/O in init

- **Status:** FIXED
- **Module:** `shared/` (androidMain)
- **Files:** `shared/src/androidMain/kotlin/eu/monniot/feed/shared/api/DataStoreCookiesStorage.kt`
  (serialize/deserialize lines ~76-105 omit `maxAge`; `init` block lines ~27-32 uses
  `runBlocking` over DataStore).
- **Symptom:** The server's session cookie uses `Max-Age` only (no `Expires`), so
  persisted cookies never expire client-side; the client can't distinguish
  expired-from-invalid (server JWT validation masks this). Separately, the
  `runBlocking` DataStore read runs on whatever thread constructs the storage
  (app startup) — first-frame jank/ANR risk.
- **Fix:** `addCookie` now converts `maxAge` to an absolute `expires` timestamp
  (`GMTDate() + maxAge * 1000`) before persisting. The `runBlocking` init block
  was replaced with lazy suspend loading via `ensureLoaded()` called from `get()`
  and `addCookie()` under the existing mutex.
- **Validation:** 7 new Robolectric tests in `DataStoreCookiesStorageTest` cover
  Max-Age conversion, round-trip persistence, expired-cookie filtering, and lazy
  loading. `./gradlew :app:testDebugUnitTest`.

### BUG-15: OPML import quirks (dropped children, wrong "already exists", N² scans)

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/api/handlers.rs` (`import_opml_handler`),
  `server/src/db.rs` (`get_or_create_feed`).
- **Symptom / root cause:** (a) An outline with both `xmlUrl` and children was treated
  as a feed and its children silently dropped. (b) "Already exists" was inferred from
  `title.is_some()`, so a feed from a previously-failed import reported
  `already_exists` instead of being repaired. (c) `get_all_feeds()` was re-fetched
  inside the per-feed loop — N² on large OPML files. Also `update_feed_metadata`
  errors were discarded (`let _ =`), which was one of the NULL-title sources feeding
  BUG-5.
- **Fix:** (a) `process_outlines` now processes an outline as a feed if it has
  `xmlUrl` AND recurses into its children independently, so children are never
  dropped. (b) `get_or_create_feed` returns `(feed_id, was_created)` and the handler
  uses `was_created` to determine imported-vs-already_exists instead of title
  sniffing. (c) The `get_all_feeds()` call was removed entirely — existence is
  determined by the `get_or_create_feed` result. (d) `update_feed_metadata` errors
  are logged via `tracing::warn!` instead of silently discarded.
- **Validation:** `test_opml_folder_with_xmlurl_children_not_dropped`,
  `test_opml_reimport_all_already_exists`, `test_opml_feedly_full_import_counts`,
  `test_opml_duplicate_url_in_same_import`, `test_opml_category_assignment`,
  `test_opml_malformed_returns_400`. `cd server && cargo test`.

### BUG-16: `ServerConfigScreen` shows "Saved" before any save

- **Status:** FIXED
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/MainActivity.kt:346-349`
  (`LaunchedEffect(currentUrl)` sets `savedNote` when `input == currentUrl`, which
  is true on first composition).
- **Fix direction:** Gate the note on an explicit "user pressed Save" flag rather
  than inferring from `input == currentUrl`; also handle the save-that-normalizes-
  to-same-URL case (currently shows no confirmation).
- **Validation:** Robolectric UI test: note absent on open, present after Save.
  `./gradlew :app:testDebugUnitTest`.

### BUG-17: `getRelativeTime` grammar and future timestamps

- **Status:** FIXED
- **Module:** `shared/`
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/util/RelativeTime.kt`,
  `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/SidebarFooter.kt`.
- **Symptom:** "1 minutes ago" (no singular forms); future-dated articles (feeds
  sometimes post-date entries) display as "… ago" because of `abs()`. Also, the web
  sidebar footer shows "Synced just now ago" — `RelativeTime.format` returns `"just now"`
  and the caller appends `" ago"` unconditionally, producing a redundant suffix.
- **Fix:** `getRelativeTime` now returns self-contained strings ("1 minute ago",
  "5 minutes ago", "just now", "in 2 hours"). Removed `abs()` — future timestamps
  beyond ±60 s return "in N unit(s)"; small skew (≤60 s) returns "just now".
  `SidebarFooter.kt` updated to remove the redundant `" ago"` suffix.
- **Validation:** 29 new tests added to `RelativeTimeTest`; shared-js: 161 passed,
  0 failed. `./gradlew :shared:allTests`.

---

### BUG-18: Android login screen flashes briefly on every app launch

- **Status:** FIXED
- **Module:** `app/` + `shared/`
- **Files:** `app/src/main/java/eu/monniot/feed/MainActivity.kt` (initial navigation
  destination); `shared/.../SessionManager.kt` (no persisted `isLoggedIn` on Android).
- **Symptom:** Even with a valid session cookie and cached articles, the login screen
  appears for a moment on every cold start before the article list loads. Visually
  jarring.
- **Root cause:** Android's `SessionManager` is constructed without `Settings` backing
  (see BUG-7), so `isLoggedIn` starts `false` on every process start. Navigation
  defaults to `LoginScreen` until `probeSession` resolves. Fixing BUG-7 (persisting
  session state) should eliminate the flash as a side-effect.
- **Fix direction:** Fix BUG-7 first — persisting `isLoggedIn` means the correct screen
  is known immediately. If a flash persists after BUG-7, hold on a loading/splash state
  until initial auth state resolves rather than defaulting to login.
- **Validation:** Robolectric test: `MainActivity` with a pre-populated `SessionManager`
  navigates directly to `FeedScreen` on launch, not `LoginScreen`. Pairs naturally with
  BUG-7's session-persistence tests. `./gradlew :app:testDebugUnitTest`.

---

### BUG-20: Android article list briefly flashes "no articles" on every app launch

- **Status:** FIXED
- **Module:** `app/` + `shared/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt` (empty-state
  branch rendered when article list is empty); `shared/.../FeedViewModel.kt`
  (initial `articles` state is `emptyList()`).
- **Symptom:** On cold start, after navigating past login, the article list screen
  briefly shows the "no articles" empty state before the cached articles appear.
  Visually jarring, especially since the articles are already in the local Room DB.
- **Root cause:** `FeedViewModel.articles` starts as `emptyList()`, so the UI
  renders the empty-state screen immediately. The Room query hasn't returned yet, even
  though articles are available locally. "Not loaded yet" is indistinguishable from
  "loaded and empty" — the same structural problem as BUG-13 (now fixed for feeds).
- **Fix:** Changed `FeedViewModel.articleItems` from `StateFlow<List<ArticleItem>>`
  to `StateFlow<List<ArticleItem>?>` with `null` initial value ("not loaded yet")
  instead of `emptyList()`. Updated `FeedScreenContent` to accept an `articlesLoaded`
  flag and suppress the empty-state pane while articles are still loading. Updated all
  web consumers to handle nullable `articleItems` with safe calls.
- **Validation:** 3 new shared KMP tests in `FeedViewModelArticlesLoadedTest`
  (`./gradlew :shared:allTests`; 187 passed). 2 new Robolectric tests in
  `FeedScreenTest` (`./gradlew :app:testDebugUnitTest`; 240 non-integration passed).

---

### BUG-19: Android Settings → Import OPML → Choose does nothing

- **Status:** FIXED
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt`
  (Import OPML row button handler).
- **Symptom:** Tapping the file chooser in Settings → Import OPML opens no file
  picker. Nothing happens.
- **Root cause:** `onClick = { /* OPML import — future */ }` — no launcher registered,
  no `launch()` called.
- **Fix:** Registered `rememberLauncherForActivityResult(GetContent())` in `SettingsScreen`.
  On URI result, reads the file via `ContentResolver`, calls `viewModel.importOpml(text)`.
  Added `onChooseOpml` callback to the stateless `SettingsScreenContent` composable and
  exposed `opmlImportStatus` hint on the row. Delegated `importOpml`/`opmlImportStatus`/
  `clearOpmlImportStatus` from `FeedViewModel` to the shared `FeedViewModel`.
- **Validation:** 3 new Robolectric tests in `SettingsScreenTest`: click triggers callback,
  status hint displayed, default hint shown when null. `./gradlew :app:testDebugUnitTest`.

### BUG-21: Code blocks not rendering nicely in reader ("Mixed-Reality Tour Guide" article)

- **Status:** FIXED
- **Module:** `web/` + `app/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/HtmlSanitizer.kt`,
  `web/src/jsMain/resources/css/tokens.css`,
  `app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt`.
- **Symptom:** The article "Building a Mixed-Reality Tour Guide with Android XR, the Geospatial API, and Gemini"
  (and potentially others with embedded code blocks) displays code blocks with poor formatting in the web reader.
  Android reader also affected — code rendered as plain inline text.
- **Root cause (dual):**
  1. **Web HTML sanitizer** (`HtmlSanitizer.kt`): `ALLOWED_TAGS` did not include `pre`, `code`, `samp`, or `kbd`.
     These tags were stripped by `stripUnknownTags`, so code block structure was lost (text content preserved as inline).
  2. **Web CSS** (`tokens.css`): No CSS rules existed for `pre` or `code` elements — even if the tags survived, they
     would render without monospace font, whitespace preservation, or visual distinction.
  3. **Android HTML converter** (`ReaderScreen.kt`): `htmlToAnnotatedString` had no `when` branch for `pre`, `code`,
     `samp`, or `kbd` — they fell through to the generic `else` branch, losing monospace styling.
- **Fix:**
  1. Added `pre`, `code`, `samp`, `kbd` to `ALLOWED_TAGS` in `HtmlSanitizer.kt`.
  2. Added `--feed-font-mono` CSS custom property and code block CSS rules scoped to `[data-reader-body]` in `tokens.css`:
     monospace font, `white-space: pre`, `overflow-x: auto`, background/border/padding for `<pre>`, inline pill styling
     for `<code>` not inside `<pre>`, subtle raised look for `<kbd>`.
  3. Added `pre`, `code`/`samp`/`kbd` handling in `htmlToAnnotatedString`: `pre` blocks preserve whitespace and apply
     monospace 14sp style; inline `code`/`samp`/`kbd` tags apply monospace 14sp style.
- **Validation:** 5 new web sanitizer tests (`preTagIsPreserved`, `codeTagIsPreserved`, `preCodeBlockIsPreservedIntact`,
  `sampAndKbdTagsArePreserved`, `inlineCodeInsideParagraphIsPreserved`) in `ReaderPaneSanitizerTest.kt`.
  3 new Android converter tests (`htmlConverterPreservesPreCodeBlock`, `htmlConverterPreservesInlineCode`,
  `htmlConverterPreservesKbdTag`) in `ReaderScreenTest.kt`. CSS styling is manual-verification only.
  `./gradlew :web:jsTest` — 354 passed, 0 failed. `:app:testDebugUnitTest` — ReaderScreenTest 14 passed, 0 failed.

### BUG-22: Article count mismatch between subscriptions feed cell and article list

- **Status:** FIXED
- **Module:** `shared/` + `app/` + `web/`
- **Symptom:** The subscriptions screen shows a feed with 23 new articles, but when tapping into that
  feed's article list, only 2 articles appear. The displayed count and actual retrievable articles disagree.
- **Root cause:** Both clients (Android and web) fetched a global top-50 article list via
  `GET /v1/articles` (no feed filter), then filtered client-side by `feedId`. When a specific feed
  had more unread articles than appeared in the global page, the article list showed fewer items
  than the subscriptions badge counted. The server already had `GET /v1/feeds/{feedId}/articles`
  but the clients never used it.
- **Fix:** Added `getFeedArticles(feedId)` to `FeedApi`, `refreshForFeed(feedId)` to the
  `FeedRepository` interface (implemented in both Android and Web repos), and wired
  `FeedViewModel.selectFeed()` to call `refreshForFeed` so selecting a feed loads that feed's
  articles from the server instead of filtering a stale global list.

---

### BUG-23: Android shows repetitive "couldn't be parsed" error messages

- **Status:** OPEN
- **Module:** `app/`
- **Symptom:** The Android app displays many transient error messages (snackbars or toasts) saying "a feed couldn't be parsed" or similar. With multiple feeds in error state, the user sees a repeated barrage of these notifications, which is distracting and does not provide actionable context.
- **Root cause:** The app currently surfaces parse errors (and other feed status errors) via snackbars or inline notifications, rather than deferring to the persistent sidebar/feed-list status indicator. Each sync cycle that encounters a parse-error feed triggers a new notification, even if the error is pre-existing and hasn't changed.
- **Fix direction:** This is a design issue paired with ticket #79 (feed error explanations). When #79 lands, replace the inline error notifications with a reliance on the persistent error badge + explanation in the Feeds tab and subscriptions list. Stop showing transient snackbars for feed sync errors; instead, make the persistent badge the sole source of error visibility. For critical errors that demand immediate attention (e.g., session expiration), keep the notification path; for recurring feed-state errors (parse fail, dead feed), suppress the snackbar and rely on the badge.
- **Validation:** After #79 lands:
  - Create a test feed that always fails to parse.
  - Launch the app and perform a sync; confirm **no** snackbar/toast appears for the parse error.
  - Navigate to the Feeds tab and subscriptions screen; confirm the error badge and explanation are visible.
  - Verify that the badge persists and updates correctly across syncs without creating new transient notifications.
  - `./gradlew :app:testDebugUnitTest` (add a test that seeds a parse-error feed and verifies no snackbar is shown).

### BUG-24: Server URL control should move to login page; unavailable when logged in

- **Status:** FIXED
- **Module:** `app/` + `web/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/login/LoginScreen.kt` (server URL section);
  `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/LoginScreen.kt` (server URL section).
- **Symptom:** The server URL control appeared in Settings on both platforms, where it was only meaningful when the user was not connected to a server.
- **Root cause:** The server URL input was placed in the general settings panel rather than colocated with authentication.
- **Fix:** Moved the server URL input to the login screen on both platforms as a collapsible section. Removed the standalone `ServerConfigScreen` route on Android and the "Server URL" row from Settings. The URL is pre-filled with the current value and editable before login. On Android, Logout in Settings already navigates to the login screen where the URL can be changed.
- **Validation:** Android Robolectric tests: `serverUrlToggleExistsOnLoginScreen`, `expandingServerUrlToggleRevealsUrlInput`, `serverUrlInputShowsCurrentUrl`, `applyButtonFiresServerUrlChangeCallback`, `serverUrlErrorIsDisplayedWhenProvided`, `serverUrlRowIsAbsentFromSettings`. Web Karma tests: `LoginServerUrlTest` (4 tests). `./gradlew :app:testDebugUnitTest` and `./gradlew :web:jsTest`.

---

_Below: code-side findings from the 2026-06-21 story-board accuracy audit
([spec/plans/storyboard-accuracy-audit-2026-06-21.md](spec/plans/storyboard-accuracy-audit-2026-06-21.md)).
The spec-document follow-ups from that audit stay in the plan file._

### BUG-25: Android renders no serif font — serif/sans split absent (P2)

- **Status:** OPEN
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/theme/Type.kt:19-71`
  (`GoogleFontsProvider`, `SourceSerif4`, `IbmPlexSans`). No bundled font assets exist
  under `app/src/main/res/font/`.
- **Symptom:** Every piece of Android text renders in the system sans-serif fallback —
  login H1, list/screen headers, article-row titles, reader H1 and body, subscription
  avatar letters. The serif/sans (content vs. chrome) distinction that VISUAL_SPEC calls
  "the central typographic move" is entirely absent. Confirmed in
  `build/.shots/android/{login,unread,reader,feeds}.png` (all sans).
- **Root cause:** Source Serif 4 and IBM Plex Sans are loaded via **downloadable Google
  Fonts** (`GoogleFont.Provider` against `com.google.android.gms`). When Play-Services
  font delivery is unavailable / not-yet-fetched, the family resolves to the platform
  default sans. VISUAL_SPEC §Typography explicitly requires **bundling** the fonts rather
  than relying on Google.
- **Fix direction:** Bundle Source Serif 4 (400/500/600 + italic) and IBM Plex Sans
  (400/500/600) as `res/font` resources (or downloadable-with-bundled-fallback) and point
  the `SourceSerif4` / `IbmPlexSans` `FontFamily` declarations at them, preserving the
  weights/italic the type ramp uses.
- **Validation:** Robolectric test asserting a serif-styled style
  (`FeedTypographyDefaults.articleH1.fontFamily`) resolves to the bundled family, not the
  system fallback. Manual: launch on an emulator **without** Play Services and confirm
  serif headlines render. `./gradlew :app:testDebugUnitTest`.

### BUG-26: Android Server-URL editor uses Material components + full-pill button (P3)

- **Status:** FIXED
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/MainActivity.kt` (`ServerConfigScreen`).
- **Symptom:** The Server-URL editor used a Material outlined `TextField` and a
  fully-rounded (999px) pill **Save** button. VISUAL_SPEC's no-Material rule (§Palette)
  forbids Material components, and §Radii (L169) reserves the full pill for the tab-bar
  glyph only — everything else is 4px.
- **Root cause:** Screen built with stock Material3 components instead of the Paper
  primitives used elsewhere.
- **Fix:** Rebuilt with Paper primitives: `BasicTextField` with `panel` background, 1px
  `border`, 4px radius, and `ink`/`ink3` text colors; primary button with `ink` background,
  `onAccent` text, 4px radius (matching LoginScreen). Replaced Material `Scaffold`/
  `TopAppBar` with a plain Paper-styled header row. All color references now use
  `LocalFeedColors` instead of `MaterialTheme.colorScheme`.
- **Validation:** Two new Robolectric tests (`inputFieldUsesPaperBasicTextField`,
  `placeholderShownWhenInputIsEmpty`) plus 5 pre-existing BUG-16 tests all pass.
  `./gradlew :app:testDebugUnitTest`.

### BUG-27: Copy & visual-label drift across Android + web (P3)

- **Status:** FIXED
- **Module:** `app/` + `web/`
- **Files:** `app/.../ui/settings/SettingsScreen.kt` (row labels, subtitle, Logout colour);
  `app/.../ui/feed/FeedScreen.kt` + list header ("All Articles" title/tab; Unread
  subtitle); `app/.../ui/login/LoginScreen.kt` (subtitle copy, "SHOW" casing);
  `app/.../ui/subs/SubscriptionsScreen.kt` (search placeholder);
  `web/.../ui/subs/SubscriptionsScreen.kt:357,379` (placeholder + count).
- **Symptom:** A cluster of small copy/label/colour divergences from the still-valid
  VISUAL_SPEC contract (behaviour unaffected):
  - Android Settings labels "Font size" / "Density" / "About Feed" vs spec
    "Reader font size" / "Article-list density" / "About"; subtitle "Personal · this
    device" vs "Signed in as {username}".
  - Android "All Articles" (tab + header) vs spec "All".
  - Android Unread subtitle uses the All-format "N unread · N total" instead of "N articles".
  - Android login subtitle uses the web two-sentence copy vs the one-sentence Android copy.
  - Android Logout label not tinted `accent` (destructive treatment missing).
  - Android "SHOW" all-caps vs "Show".
  - Android Feeds search placeholder "Search subscriptions…" vs spec "Search or paste a
    URL…"; web placeholder "Search subscriptions or paste a URL…" vs "Search
    subscriptions…" — the two placeholders are effectively swapped/merged.
  - Web subscriptions count "N feeds" vs spec "N of M".
- **Root cause:** Incremental copy edits never reconciled against VISUAL_SPEC §Copy /
  the per-screen tables.
- **Fix direction:** One alignment pass. For each line, match the spec string. If a
  shipped string is genuinely preferred over the spec, leave it and record it in the
  audit plan's spec-side list instead (so the doc moves, not the code) — but pick one
  source of truth per line.
- **Validation:** Per-platform UI tests asserting the corrected strings (Robolectric for
  app, Karma for web). `./gradlew :app:testDebugUnitTest :web:jsTest`.

### BUG-28: Web sidebar feeds not nested under their folder headers (P3)

- **Status:** FIXED
- **Module:** `web/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt:275-297`
  (`updateFeedList`).
- **Symptom:** When the server returns categories, the sidebar renders **all category
  headers in one block and then all feeds flat below**, instead of grouping each folder's
  feeds under its own header (VISUAL_SPEC §Web·Sidebar L321: "Per folder: header … Per
  feed row…"). Not visible in current shots — the seed feeds are uncategorised, so no
  headers render.
- **Root cause:** `updateFeedList` loops `categories` (emitting headers) and then loops
  the full `feeds` list separately; feeds are never partitioned by their category id.
- **Fix direction:** Group feeds by category id and render header → that folder's feeds →
  next header. Decide placement of uncategorised feeds (no header / "UNKNOWN" / top).
- **Validation:** Re-shoot the sidebar with categorised feeds to confirm the defect
  visually first. Web Karma test asserting feeds render under their matching category
  header. `./gradlew :web:jsTest`.

### BUG-29: Web UI shows server URL chooser (regression from BUG-24; CORS blocks it)

- **Status:** OPEN
- **Module:** `web/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/LoginScreen.kt` (server URL section — TBD: confirm location)
- **Symptom:** The web login screen displays a server URL input/chooser, which cannot work due to CORS restrictions. The web client must use the DNS origin it is served from and cannot be configured to connect to a different server at runtime.
- **Root cause:** During BUG-24 implementation (moving server URL control from Settings to login page), the web UI was incorrectly given the same server chooser as the Android client. Android needs this (standalone app); web cannot use it (browser same-origin policy).
- **Fix direction:** Remove the server URL input from the web login screen entirely. The web client should infer the server URL from `window.location.origin` or detect it via DNS. Keep the Android server URL chooser on Android's login screen unchanged.
- **Validation:** Web Karma tests (`./gradlew :web:jsTest`): confirm the login screen renders without a server URL input field; login flow still completes successfully. Manual: visit the web UI and verify no server URL control appears.

### BUG-30: Android: feeds not fetched automatically after first login

- **Status:** FIXED
- **Module:** `shared/`
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt` (login flow)
- **Symptom:** On first login to the Android app, the user is taken to the feed list screen but sees no feeds (empty state). No automatic fetch is triggered. The user must manually pull-to-refresh to load feeds. Creates the impression the app is broken.
- **Root cause:** `FeedViewModel.login()` called `restartPoll()` after a successful login, but `restartPoll()` starts a delayed loop (`delay(minutes)` before the first `pollReadOnce()`). Unlike `setActive(true)` (which does an immediate `pollReadOnce()` before starting the loop), `login()` never triggered an immediate article refresh. The `FeedScreen`'s `LaunchedEffect` calls `loadFeeds()` (subscription metadata), but no one called `refresh()` to load the actual articles.
- **Fix:** Added a `refresh()` call in `FeedViewModel.login()` immediately after `restartPoll()`. This triggers an upstream pull followed by a re-read, so articles appear right after login — the same behavior as pull-to-refresh.
- **Validation:** Two new KMP tests in `FeedViewModelLoginRefreshTest`: `loginTriggersImmediateRefresh` (verifies `refreshUpstream` and `refresh` are called after login) and `loginRefreshSetsLastSyncTime` (verifies `lastSyncTime` is set). Both pass on JS browser target.

### BUG-31: Android: Feeds header misaligned vertically with other headers

- **Status:** OPEN
- **Module:** `android/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt` (Feeds header row + layout)
- **Symptom:** The "Feeds" header at the top of the subscriptions pane renders at a different vertical position than the "Unread" / "All" / "Settings" headers elsewhere in the UI, causing visual misalignment that is distracting to the user.
- **Root cause:** TBD — investigate the header row composition in `FeedScreen` and compare the padding/height/alignment properties used for the Feeds header vs. other headers in the app (e.g., Settings screen header).
- **Fix direction:** Audit all header rows in the UI, identify which one is correct per VISUAL_SPEC, and align the Feeds header row to use the same padding, height, and vertical alignment (e.g., `Arrangement.Center` vs. explicit padding).
- **Validation:** Robolectric UI test or visual regression test asserting the Feeds header vertical position matches a reference header (e.g., Settings header). Manual verification: take a screenshot of the Subscriptions tab and Settings screen; measure or visually confirm alignment. `./gradlew :app:testDebugUnitTest`.

### BUG-32: Android reader can't open the original article URL externally (READ-5 gap) (P3)

- **Status:** OPEN
- **Module:** `android/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt`
  (reader top bar `ReaderTopBar` ~L475-479; footer URL `Text` ~L393-402)
- **Symptom:** On Android there is **no way to open an article's original page in an
  external browser**. The reader top bar shows only `↩` / `Aa` / `⎙`, and the `⎙` Share
  button is a Phase-9 stub (`onShare = { /* stub */ }`, ReaderScreen.kt:259). The footer
  URL at the bottom of the reader is a plain, non-clickable `Text` — tapping it does
  nothing. FEATURES.md **READ-5** (Platforms: `both`) requires that tapping `↗ Open` (or
  the URL in the reader footer) opens the article URL in an external browser intent, and
  that "the footer URL itself is a clickable anchor." Web satisfies this
  (`window.open(article.url, …)` + a real `<a>` footer link); Android does not.
- **Root cause:** The Android reader was never given an external-open affordance. There
  is no `↗ Open` button in `ReaderTopBar`, and the footer-URL `Text` has no `clickable`
  modifier / `Intent(ACTION_VIEW)` (or `LocalUriHandler`) wiring.
- **Fix direction:** Add an external-open path for `article.url`. Either (a) add an
  `↗ Open` button to the reader action group, and/or (b) make the footer-URL `Text`
  clickable; both should fire an `ACTION_VIEW` intent (or `LocalUriHandler.openUri`) for
  `article.url`. Match the web behaviour (READ-5) and keep the footer URL visually an
  anchor. (Separately note: the `⎙` Share stub is out of scope here — no READ-5 dependency.)
- **Validation:** Robolectric UI test (`./gradlew :app:testDebugUnitTest`): render the
  reader for an article with a known `url`, assert an external-open affordance exists
  (an `↗ Open` node and/or a clickable footer URL), and assert clicking it invokes the
  open-uri callback with that URL (inject a fake URI handler / open lambda so no real
  Intent fires under Robolectric). Confirm the Android suite still passes (0 new
  failures). Manual: tap the footer URL / Open and confirm the system browser launches.

---

## #95 local-mirror sync — post-landing review findings

**Date:** 2026-06-28. Findings from a full review of the local-mirror article sync work
(tickets #97–#105, see [spec/plans/local-mirror-sync-95.md](spec/plans/local-mirror-sync-95.md)).
The feature landed and its own test matrix (T1–T15) is largely green; these are follow-ups
the review surfaced. None block the feature shipping; BUG-33/34/35 are the substantive ones.

### BUG-33: Concurrent `SyncEngine.sync()` runs can corrupt the cursor (no serialization) (P2)

- **Status:** FIXED
- **Module:** `shared/`
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/sync/SyncEngine.kt:38-67`
  (the `sync()` loop); callers in
  `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt:336` (`refresh()`),
  `:384` (`pollReadOnce()`), `:495` (post-login).
- **Symptom:** Two sync loops can run at once and double-apply pages / persist a stale
  cursor. `SyncEngine.sync()` has **no concurrency guard** — it reads `store.cursor()`,
  loops issuing `api.sync(since=cursor)`, and writes `store.setCursor()` with nothing
  serializing two interleaved invocations.
- **Root cause:** `FeedViewModel.refresh()` guards itself with `_isRefreshing`
  (FeedViewModel.kt:315) before calling `repository.refresh()` → `syncEngine.sync()`, but
  `pollReadOnce()` (the auto-poll loop, fired repeatedly from `:411`) and the post-login
  path (`:495`) call `repository.refresh()` **without** that guard. So a background poll
  tick can overlap a manual pull-to-refresh, running two `sync()` loops against one store.
  The plan's concurrency note (§3.3) only reasons about server-side SQLite write
  serialization, not two client loops sharing a cursor.
- **Fix direction:** Serialize inside `SyncEngine` — wrap the loop body in a
  `kotlinx.coroutines.sync.Mutex` (`mutex.withLock { … }`) so overlapping calls run
  sequentially (the second resumes from the now-advanced persisted cursor). Alternatively
  document that all callers must funnel through a single guarded entry point and route
  `pollReadOnce`/post-login through the `_isRefreshing` gate.
- **Validation:** New `SyncEngineTest` case: launch two `engine.sync()` coroutines against
  one `FakeArticleStore` whose `sync` API suspends on a gate; assert each page is applied
  exactly once and the final persisted cursor is correct. `./gradlew :shared:allTests`.

### BUG-34: Unread badge exceeds the visible list above 50 unread (fixed window vs. global count) (P2)

- **Status:** FIXED
- **Module:** `shared/` (affects both Android and web UIs)
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt:119`
  (`DEFAULT_PAGE_SIZE = 50`), `:133` (`observePage(filter, 0 until DEFAULT_PAGE_SIZE)`);
  `app/src/test/java/eu/monniot/feed/integration/SyncWiringIntegrationTest.kt:85,113,127`
  (queries a wider `0..99` window than the app uses).
- **Symptom:** The plan's "badge == list **by construction**" (§4.4) does not hold in the
  shipping UI once a tab has more than 50 unread articles. The UI observes a single **fixed**
  50-row window (`observePage(filter, 0 until 50)`), but `observeUnreadCount` is a global
  `COUNT(*)`. With 55 unread the badge shows 55 while the list shows 50 — badge ≠ list.
- **Root cause:** §4.0 calls for a `PagingSource`-backed windowed read that can scroll to
  reach all rows; the implementation is a fixed first-page window of 50 with no paging
  growth. The badge legitimately counts a superset the list truncates. The #103 acceptance
  test masks this by observing a wider `0..99` window than production (`0 until 50`) ever
  uses, so it asserts 55 == 55 and passes against a window the app never opens.
- **Fix direction:** Either (a) make the list a true growing/scroll-paged observation
  (Android `PagingSource`/`Paging 3`, web range-query that extends the window on scroll) so
  the list can reach every unread row and badge == list is restored; or (b) accept the
  capped window as the launch design, update §4.0/§4.4 and the interface KDoc to stop
  claiming "by construction" equality, and add a test at the **production** window size
  (`0 until 50`) asserting the intended relationship (badge ≥ list, `list == min(unread, 50)`).
  Related: per-feed `observePageByFeed` returns all read-states while `observeUnreadCountByFeed`
  counts unread — pin that intended split with a test that seeds a *read* article in the feed.
- **Validation:** Shared test at the real window size asserting the chosen contract; if (a),
  an integration test seeding >50 unread and scrolling to confirm the list reaches all rows.
  `./gradlew :shared:allTests :app:testDebugUnitTest`.

### BUG-35: `markRead` / `deleteByFeedId` store methods are unvalidated on both real backends (P2)

- **Status:** FIXED
- **Module:** `app/` + `web/`
- **Files:** `app/src/main/java/eu/monniot/feed/store/ArticleStoreDao.kt:26-30`
  (`markRead`, `deleteByFeedId`) — no case in `RoomArticleStoreTest.kt`;
  `web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt:162-195`
  (`markRead` does `awaitRequest(store.get(id))` then a second `store.put` on the same
  transaction; `deleteByFeedId` cursor-deletes per row) — no case in
  `IndexedDbArticleStoreTest.kt`.
- **Symptom:** The two store methods that drive the **optimistic local read-toggle**
  (`SharedFeedRepository.markAsRead/markAsUnread` → `store.markRead`) and the **local
  feed-delete cascade** (`deleteFeed` → `store.deleteByFeedId`) — the paths that make the
  badge update instantly — have no direct test against either real backend. Per CLAUDE.md's
  testing requirement they are currently unvalidated DB code.
- **Root cause:** Coverage gap. Extra risk on web: IndexedDB auto-commits a transaction when
  it has no pending requests and control returns to the event loop, so `markRead`'s
  suspend-then-write (`get`, await, `put`) is exactly the pattern that can hit a closed
  transaction (`TransactionInactiveError`) if the coroutine resume isn't synchronous —
  untested. The "article not found" branch (IndexedDbArticleStore.kt:166-169) also silently
  no-ops after the server `PUT` already happened (relies on next sync to self-heal — fine by
  §4.3 LWW, but undocumented/untested).
- **Fix direction:** Add `markRead` / `deleteByFeedId` tests to both backend suites:
  `RoomArticleStoreTest` (`markRead_togglesReadStateAndBadge`, `deleteByFeedId_removesOnlyThatFeed`)
  and `IndexedDbArticleStoreTest` (same two). If the web `markRead` transaction hazard
  materializes, read in a `readonly` tx (or before opening the write tx), then `put` in a
  fresh tx that issues no request before any await. While here, add a >900-id `deleteByIds`
  case to pin the Room 900-chunk boundary (RoomArticleStore.kt chunking).
- **Validation:** `./gradlew :app:testDebugUnitTest` and `./gradlew :web:jsTest` — the new
  cases pass; the web case proves no `TransactionInactiveError` on the get-then-put path.
- **Resolution:** Added 3 new tests to `RoomArticleStoreTest` and 4 new tests to
  `IndexedDbArticleStoreTest`. The web `markRead` transaction hazard did not materialize —
  `awaitRequest`'s `cont.resume()` in the `onsuccess` handler dispatches the continuation
  inline, so the `store.put` fires in the same event-loop tick before auto-commit can occur.

### BUG-36: Android article-list `ORDER BY` defeats the `(published, seq)` index (P3)

- **Status:** FIXED
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/store/ArticleStoreDao.kt:42-75`
  (`observePageAll` / `observePageUnread` / `observePageByFeed`).
- **Symptom:** Each emission does a full scan + temp B-tree sort instead of an index walk.
  The `ORDER BY CASE WHEN published IS NULL THEN 1 ELSE 0 END, published DESC, seq DESC`
  leads with a **computed expression**, so SQLite cannot use `index_sync_articles_published_seq`
  (added in commit fa36355) to satisfy the sort — the index is effectively dead for the
  All/UnreadOnly ordering paths. At the design's stated ~20k-row scale this is a real
  per-emission cost, and emissions fire on every sync write.
- **Root cause:** NULLs-last is expressed with a `CASE` leading column rather than a stored
  sort key or index that matches the order.
- **Fix direction:** Make the order index-satisfiable — e.g. store `COALESCE(published, 0)`
  as a materialized sort column indexed with `seq`, or accept NULLs-first from the index and
  fix ordering in the mapper, or an expression index matching the `CASE`. Keep the
  `published DESC, seq DESC` user-visible order (E10).
- **Validation:** A query-plan test asserting `EXPLAIN QUERY PLAN` for `observePageAll` uses
  `index_sync_articles_published_seq` (no `USE TEMP B-TREE`). `./gradlew :app:testDebugUnitTest`.

### BUG-37: Article id width is inconsistent across the sync contract (`Int` vs `Long`) (P3)

- **Status:** OPEN
- **Module:** `shared/` + clients
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt:95-96`
  (`Article.id: Int`, `feed_id: Int`); `shared/.../sync/ArticleStore.kt:37,61`
  (`markRead(id: Int)` / `deleteByFeedId(feedId: Int)` vs `deleteByIds(List<Long>)`,
  `setCursor(Long)`); server `server/src/api/types.rs:473-474` serializes `id`/`feed_id` as `i64`.
- **Symptom:** `deleted_ids` was widened to `List<Long>` (commit bd65cf8) to match the
  server's `Vec<i64>`, but the article rows those tombstones reference still decode `id` into
  Kotlin `Int`. So tombstone ids are `Long` while article ids are `Int`, forcing `id.toInt()`
  bridging throughout, and an article rowid > `Int.MAX_VALUE` would throw at JSON decode.
- **Root cause:** Pre-existing `Article.id: Int` narrowing inherited by the sync work; the
  `deleted_ids` widening only half-completed the migration to a single id width.
- **Fix direction:** Widen `Article.id`/`feed_id` to `Long` and align the `ArticleStore`
  surface (`markRead`/`deleteByFeedId`) to `Long`, removing the `toInt()` bridges and the
  Room/IndexedDB key conversions. Larger change (touches both store backends' keys); scope as
  one follow-up. At ~20k live rowids it does not bite today.
- **Validation:** A `SyncResponseTest` decoding an article with `"id": 3000000000` succeeds;
  store round-trip tests with a >2^31 id. `./gradlew :shared:allTests` + both client suites.

### BUG-38: `GET /v1/sync` is undocumented and removed endpoints remain in the API docs (DOC)

- **Status:** FIXED
- **Module:** `server/` (docs)
- **Files:** `spec/API_DOCUMENTATION.md:565` (`GET /feeds/{feed_id}/articles`), `:612`
  (`GET /articles`), `:712` (`GET /articles/unread-count`) — all three removed routes still
  fully documented; no `/v1/sync` section exists anywhere.
- **Symptom:** The canonical REST reference (named in CLAUDE.md) is stale after the §3.5
  surface change: three deleted endpoints are still documented, and the new `GET /v1/sync`
  (its `since`/`limit` params, the Delta vs `{ "full_resync": true }` response shapes, the
  `has_more`/backfill loop, the 500 default / 2000 clamp) is entirely absent.
- **Root cause:** Docs not updated alongside #98.
- **Fix direction:** Remove the three `####` sections for the deleted routes; add a
  `#### GET /sync` section documenting both response variants, the pagination contract, and
  the `since=0` backfill loop.
- **Validation:** Doc-only (CLAUDE.md exception). Cross-check the rendered section against the
  actual handler in `server/src/api/handlers.rs`.

### BUG-39: T13 write-amplification "benchmark" is a local wall-clock smoke test, not a CI measure (P3)

- **Status:** OPEN
- **Module:** `server/`
- **Files:** `server/src/db_tests.rs` (`test_sync_bulk_insert_benchmark`: 1000 inserts,
  `assert!(elapsed.as_secs() < 30)`).
- **Symptom:** §3.2 / T13 ask for an insert-path **write-amplification** measurement run on
  CI, not locally (the seq trigger turns each insert into insert + counter-bump + write-back
  `UPDATE`). The present test is a 30-second wall-clock bound on a shared local box: it
  neither measures amplification (writes/insert) nor runs as a tracked CI metric — it only
  catches a catastrophic O(n²) regression. This conflicts with the project's "measure perf on
  CI, not local" rule.
- **Root cause:** Stand-in test labeled as satisfying T13.
- **Fix direction:** Either rename to `*_smoke` and downgrade the T13 claim, or add a CI job
  recording insert throughput / writes-per-insert as a tracked number.
- **Validation:** The renamed/added test runs in CI; if a tracked metric, it records a baseline.

### BUG-40: `SyncArticle` duplicates `Article` — a future column silently drops from `/v1/sync` (P3)

- **Status:** FIXED
- **Module:** `server/`
- **Files:** `server/src/api/types.rs` (`SyncArticle` struct + `From<Article>`, ~50 lines);
  `server/src/db.rs` (`Article.seq` is `#[serde(skip_serializing)]`).
- **Symptom:** A parallel ~12-field `SyncArticle` exists solely to re-expose `seq` (which
  `Article` hides from serialization). Every future article column must be added in both
  places or it silently disappears from the `/v1/sync` payload — a dead-code-prone trap.
- **Root cause:** `seq` was hidden on `Article` (so other responses don't leak it) and a
  whole duplicate type was introduced to re-add it for sync.
- **Fix:** Removed `#[serde(skip_serializing)]` from `Article.seq`. The search endpoint
  (`SearchResult`) also serializes `Article` via `#[serde(flatten)]`, so `seq` now appears
  in search results too — acceptable for a single-user app. Deleted `SyncArticle` struct
  and its `From<Article>` impl. `SyncResponse::Delta` now uses `Article` directly. Future
  columns only need to be added to `Article`.
- **Validation:** Existing `test_sync_response_includes_seq` still asserts `seq` present;
  `test_search_result_includes_seq` pins that `seq` appears in search results. All server
  tests pass.

### BUG-41: Android `SyncWiringIntegrationTest` never exercises the tombstone (`deleted_ids`) path (P3)

- **Status:** FIXED
- **Module:** `app/`
- **Files:** `app/src/test/java/eu/monniot/feed/integration/SyncWiringIntegrationTest.kt:134-162`.
- **Symptom:** The #103 acceptance "a server-side delete disappears locally after a sync" is
  meant to validate the **tombstone apply path** (`deleteByIds` from a `/v1/sync` response).
  As written the test deletes a whole **feed**, which is applied locally by
  `SharedFeedRepository.deleteFeed` → `store.deleteByFeedId` *before* the follow-up
  `refresh()` — so the rows are already gone and the test would pass even if tombstone
  handling via the sync response were completely broken.
- **Root cause:** The chosen delete (feed cascade, locally driven) bypasses the
  `deleted_ids`-through-`SyncEngine` path the test intends to cover. (The path is covered in
  shared `SyncEngineTest`, but not end-to-end against the real server here.)
- **Fix:** Rewrote the test to call `feedApi.deleteFeed()` (server-only delete) instead of
  `repository.deleteFeed()` (which also clears local data). The test now verifies articles
  still exist locally after the server delete, then confirms they disappear after
  `repository.refresh()` — exercising the full tombstone chain: server cascade delete →
  `articles_seq_ad` trigger → `deleted_articles` table → `/v1/sync` `deleted_ids` →
  `SyncEngine` → `store.deleteByIds`.
- **Validation:** `./gradlew :app:testDebugUnitTest` with the amended integration test green.

### BUG-42: Web IndexedDB store lacks quota / version-change handling; abort errors lose detail (P3)

- **Status:** OPEN
- **Module:** `web/`
- **Files:** `web/src/jsMain/kotlin/eu/monniot/feed/web/data/IndexedDbArticleStore.kt:112-120`
  (upsert), `:412-444` (`withTransaction`), `openDatabase` (~`:68-96`).
- **Symptom:** A 20k-row `since=0` backfill that hits the browser storage quota surfaces as a
  generic `RuntimeException("Transaction aborted")` with no detail and no recovery. The
  `withTransaction` `onerror`/`onabort` path drops the underlying `tx.error` (unlike
  `awaitRequest`/cursor paths which interpolate `req.error`), and the `IDBDatabase` has no
  `onversionchange`/`onclose` handler, so a second tab triggering an upgrade blocks silently.
- **Root cause:** Hardening not yet done (acceptable at launch — ~20k small rows is within
  typical quota), but there is no explicit handling or test.
- **Fix direction:** Surface `tx.error` in the `withTransaction` abort/error messages; add an
  `onversionchange` handler that closes the connection; detect `QuotaExceededError` and report
  it distinctly (so a future GC/backoff can react). Track as hardening, not a launch blocker.
- **Validation:** `./gradlew :web:jsTest` — a test forcing a transaction abort asserts the
  surfaced message includes the underlying error; manual check of the version-change path.

