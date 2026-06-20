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

- **Status:** OPEN
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

- **Status:** OPEN
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

- **Status:** OPEN
- **Module:** `shared/` (androidMain)
- **Files:** `shared/src/androidMain/kotlin/eu/monniot/feed/shared/api/DataStoreCookiesStorage.kt`
  (serialize/deserialize lines ~76-105 omit `maxAge`; `init` block lines ~27-32 uses
  `runBlocking` over DataStore).
- **Symptom:** The server's session cookie uses `Max-Age` only (no `Expires`), so
  persisted cookies never expire client-side; the client can't distinguish
  expired-from-invalid (server JWT validation masks this). Separately, the
  `runBlocking` DataStore read runs on whatever thread constructs the storage
  (app startup) — first-frame jank/ANR risk.
- **Fix direction:** Convert `maxAge` to an absolute `expires` at `addCookie` time
  (receipt time + maxAge) before persisting; or persist maxAge + receipt timestamp.
  Replace the `runBlocking` init with lazy suspend loading (load on first
  `get`/`addCookie` under the existing mutex).
- **Validation:** Android JVM unit test for round-tripping a Max-Age-only cookie
  (expiry honored after restart); existing cookie tests still pass.
  `./gradlew :app:testDebugUnitTest`.

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

- **Status:** OPEN
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
- **Fix direction:** Mirror the BUG-13 fix: introduce a nullable list or a
  `articlesLoaded: Boolean` flag in `FeedViewModel`; show a loading/skeleton state
  (or nothing) while the initial DB query is in flight; only show the empty-state
  screen once loading has completed and the list is confirmed empty.
- **Validation:** Shared `FeedViewModel` test asserting articles are `null`/unloaded
  before the first DB emission and non-null after (`./gradlew :shared:allTests`).
  Robolectric test that the empty-state composable is not rendered before the first
  article-list emission (`./gradlew :app:testDebugUnitTest`). Pairs well with BUG-18
  follow-up work.

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

