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

Session order is in [TASKS.md](../TASKS.md) — P-levels here describe severity only.

---

## P1 — Security / broken core behavior

### BUG-1: XSS bypass in web HTML sanitizer (`javascript:` check defeated by whitespace)

- **Status:** OPEN
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

- **Status:** OPEN
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

- **Status:** OPEN
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
- **Fix direction:** In `FeedApi.getParseError`, catch `ClientRequestException`,
  return `null` when status is 404, rethrow otherwise. In
  `FeedViewModel.loadParseError`, set `_parseError.value = null` before the fetch
  (or in the catch) so failures don't leave stale state.
- **Validation:** Shared commonTest (`./gradlew :shared:allTests`, 107 passing
  before): fake repository returning null → `parseError` becomes null; repository
  throwing → `parseError` cleared, not stale. Ideally also a Ktor `MockEngine` test
  for the 404 → null mapping in `FeedApi`.

---

## P2 — Wrong results / data integrity / major UX

### BUG-4: `/v1/logs` returns wrong/old lines when the active log file is short

- **Status:** OPEN
- **Module:** `server/`
- **Files:** `server/src/api/handlers.rs:723-759` (`get_logs_handler`).
- **Symptom:** Right after log rotation (current file has fewer lines than
  requested), the endpoint drops the newest entries entirely and returns the tail
  of an older file instead.
- **Root cause:** Files are iterated newest-first and lines appended in that order,
  so `all_lines` = newest file's lines followed by older files' lines. The final
  `.rev().take(n).rev()` takes the tail of the vector — the *oldest* content.
- **Fix direction:** Collect per-file line vectors, then assemble oldest→newest
  before taking the last N (or prepend older files' lines). Preserve the per-file
  1 MB tail cap.
- **Validation:** Extend `test_get_logs_handler_tail` in `server/src/main.rs` with a
  two-file case: small current file + large rotated file → result must end with the
  current file's last line and preserve order across the boundary.
  `cd server && cargo test`.

### BUG-5: Client `Feed.title` non-nullable vs server `Option<String>` → feed list can permanently fail to load

- **Status:** OPEN
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

- **Status:** OPEN
- **Module:** `server/`
- **Files:** `server/src/db.rs:1146-1154` (`delete_old_articles`);
  `server/src/scheduler.rs:141` (hardcoded `RETENTION_DAYS: i64 = 90`).
- **Symptom:** (a) Unread articles older than 90 days are silently deleted.
  (b) Articles whose feed provides no publish date (`published IS NULL`) are never
  deleted and accumulate forever.
- **Root cause:** Deletion filters only on `published < cutoff`.
- **Fix direction:** Use `COALESCE(published, fetched_at) < cutoff`. Decide whether
  unread articles should be exempt (`AND is_read = 1`) — for a single-user reader,
  exempting unread is the safer default; note the decision in the code. (The
  client-side "Keep articles" preference is a separate gap — see BUG-12.)
- **Validation:** New tests in `server/src/db_tests.rs`: undated-but-old article
  deleted; recent article kept; unread-old behavior per the chosen policy.
  `cd server && cargo test`.

### BUG-7: Android: transient network failure at startup forces login screen; session state not persisted

- **Status:** OPEN
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

- **Status:** OPEN
- **Module:** `app/` (+ `shared/` model field)
- **Files:** `app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt:89-106`
  (`ArticleFilter.matches`);
  `app/src/main/java/eu/monniot/feed/FeedRepository.kt:33-52` (`toEntities`) and
  the `items` mapping (~149-163);
  `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt`
  (`ArticleItem`).
- **Symptom:** On Android, the "Today" chip always shows an empty list and
  "Long reads" is always empty / "Short reads" matches everything.
- **Root cause:** `Today` parses `article.pubDate` with `toLongOrNull()`, but
  `pubDate` is a formatted string ("EEE, d MMM yyyy" on Android, relative time on
  web) — never an epoch. `LongReads`/`ShortReads` use `minutesToRead`, which the
  Android repository never computes (always the default `1`); same for `excerpt`.
- **Fix direction:** Add an epoch field (e.g. `publishedEpochSeconds: Long?`) to
  `ArticleItem` and populate it in both repositories; filter `Today` on it. In the
  Android repository, compute `minutesToRead`/`excerpt` from content via the shared
  `eu.monniot.feed.shared.util` helpers (as `WebFeedRepository` does) — requires
  persisting content length or minutes in `RssItemEntity` (Room migration to v6).
- **Validation:** `FeedScreenTest.kt` (Robolectric) cases per chip with controlled
  epochs/read-times; repository mapping test for the new fields; Room migration
  test in the existing `RoomMigrationTest` style. `./gradlew :app:testDebugUnitTest`.

---

## P3 — Robustness / leaks / polish

### BUG-9: ParseFailed response doesn't reset the consecutive-410 counter

- **Status:** OPEN
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

- **Status:** OPEN
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

- **Status:** OPEN
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

- **Status:** OPEN
- **Module:** `server/`
- **Files:** `server/src/api/handlers.rs:770-918` (`import_opml_handler`).
- **Symptom / root cause:** (a) An outline with both `xmlUrl` and children is treated
  as a feed and its children silently dropped. (b) "Already exists" is inferred from
  `title.is_some()`, so a feed from a previously-failed import reports
  `already_exists` instead of being repaired. (c) `get_all_feeds()` is re-fetched
  inside the per-feed loop — N² on large OPML files. Also `update_feed_metadata`
  errors are discarded (`let _ =`), which is one of the NULL-title sources feeding
  BUG-5.
- **Fix direction:** Recurse into children even when `xml_url` is present; determine
  existence from the `get_or_create_feed` result (e.g. return created-vs-found, or
  check existence with one indexed SELECT) instead of title sniffing; hoist the feed
  list/lookup out of the loop; log or propagate metadata-update failures.
- **Validation:** New cases in the server tests covering: folder-with-xmlUrl OPML,
  re-import after failure, and a count assertion. `cd server && cargo test`.

### BUG-16: `ServerConfigScreen` shows "Saved" before any save

- **Status:** OPEN
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

- **Status:** OPEN
- **Module:** `shared/`
- **Files:** `shared/src/commonMain/kotlin/eu/monniot/feed/shared/util/RelativeTime.kt`.
- **Symptom:** "1 minutes ago" (no singular forms); future-dated articles (feeds
  sometimes post-date entries) display as "… ago" because of `abs()`. Also, the web
  sidebar footer shows "Synced just now ago" — `RelativeTime.format` returns `"just now"`
  and the caller appends `" ago"` unconditionally, producing a redundant suffix.
- **Fix direction:** Singular/plural per unit; for future instants return something
  honest ("in 2 hours" or "just now" for small skew). Either return a self-contained
  string ("5 minutes ago", "just now") so callers don't append "ago", or document the
  contract so callers suppress the suffix for the `"just now"` case.
- **Validation:** Extend the existing `RelativeTime` tests in shared commonTest.
  `./gradlew :shared:allTests`.

---

### BUG-18: Android login screen flashes briefly on every app launch

- **Status:** OPEN
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

### BUG-19: Android Settings → Import OPML → Choose does nothing

- **Status:** OPEN
- **Module:** `app/`
- **Files:** `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt`
  (Import OPML row button handler).
- **Symptom:** Tapping the file chooser in Settings → Import OPML opens no file
  picker. Nothing happens.
- **Root cause:** Likely the `ActivityResultLauncher` for `GetContent` is not
  registered or `launch()` is not called on tap.
- **Fix direction:** Verify `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())`
  is registered and that `launch("*/*")` (or `"text/xml"`) is called from the button's
  `onClick`. Ensure the result callback passes the selected URI to the OPML import flow.
- **Validation:** Robolectric test tapping the button and asserting the correct intent
  is fired. Integration test that selects a `.opml` fixture and confirms it is POSTed
  to the server. `./gradlew :app:testDebugUnitTest`.

