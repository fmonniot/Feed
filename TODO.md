# Feed — TODO

Backlog of tickets, organized by **execution priority** (P0 → P4). Reference tickets by their numeric ID (e.g. "work on #3"). Numeric IDs are stable; gaps from closed/superseded tickets are intentional.

Status legend: `[ ]` open · `[~]` in progress · `[x]` done · `[-]` closed without action · `[?]` needs verification

Within each priority phase, tickets are grouped so a single grouping fits naturally into one Sonnet 4.6 session.

---

## P0 — Unblockers

Land these before any P1 work; everything downstream benefits.

### #27 — Android: article list is empty after login `[x]`

After a successful login on Android, the Feed screen renders no rows even though the server has articles. This blocks every FEED-*, READ-*, MOB-*, SET-3, SET-4 and ERR-1 manual test on Android.

**Acceptance criteria**
- Logging into Android with a populated server shows the same article list the web client shows for the same account.
- Pulling/refresh works (see #33).
- A new JVM test (Robolectric + `ServerRule`) exercises the login → list-populated path and asserts non-zero rows. Likely related to the network/JSON drift class of bugs (#23 / #24); the fix should land before re-running the catalog on Android.

---

## P1 — Spec gap fixes

These close the `⚠` / `✗` rows in [spec/FEATURES.md](spec/FEATURES.md). Groups below are sized to fit one session each.

### Group: Web Settings batch

Same screen, shared prefs plumbing — do all three in one pass.

#### #30 — Web: Settings missing reader font-size control `[x]`

The web Settings screen does not expose a default reader font size, even though `UserPrefs.fontSize` is wired and the reader honors it. READ-5 and SET-1 both fail because of this.

**Acceptance criteria**
- The web Settings → Reading section includes a segmented control for reader font size (range 14–24px, matching the design's discrete steps; align with the Android picker options once #29's Android spec is settled).
- Changing the value persists via `UserPrefs` and the open reader pane re-renders at the new size without reload.
- A `:web:jsTest` asserts the control reflects and writes back the stored value.

#### #31 — Web: Settings missing density control `[x]`

The web Settings page omits the "Density" segmented control (compact/regular/comfy). The article-list rows currently render at a fixed density. SET-4 fails on web for this reason.

**Acceptance criteria**
- Web Settings → Reading exposes Density (compact/regular/comfy).
- The article list reads `UserPrefs.density` and applies the row-padding/excerpt-visibility/thumbnail rules from [spec/VISUAL_SPEC.md](spec/VISUAL_SPEC.md).
- A `:web:jsTest` covers the rendering of at least one row in each density.

#### #32 — Web: drop Server URL setting `[x]`

The web client's Settings includes a "Server URL" row, but it has no production value — in deployment the client is served by the same origin, and in development we can hardcode `http://localhost:3000/` (or whatever the dev URL is). SET-6 reports the row as broken on web; the resolution is to remove it rather than fix it.

**Acceptance criteria**
- The Server URL row is removed from the web Settings screen.
- The web client uses a fixed base URL (same-origin in production, dev-time default in development). No setting, no `ServerUrlStore` read path on web.
- Android keeps its Server URL setting unchanged — this is web-only.
- The Account section on web still shows "Signed in as: …" and logout; just no URL row.

---

### Group: Web UI bugs

#### #28 — Web: subscription overflow menu clipped + rename field empty `[x]`

Two issues on the Subscriptions screen's per-row `⋯` menu:

1. The dropdown is constrained to the `subs-feed-list` container and gets clipped instead of overflowing on top. It should render in a layer that is not bound by the list's overflow context (portal/absolute positioning relative to the viewport, or an `overflow: visible` parent).
2. The rename dialog's text input starts empty. It should be prepopulated with the feed's current `custom_title ?: title` and the input selected so the user can either edit incrementally or overwrite.

**Acceptance criteria**
- The `⋯` menu renders above adjacent rows and is not clipped, regardless of where in the list the row sits.
- Opening "Rename" pre-fills the input with the current name and selects the text.
- A `:web:jsTest` asserts the rename input's initial value.

#### #29 — Reader: article URL should be a hyperlink `[x]`

On the web reader pane, the feed/article URL displayed in the footer (or the `↗ Open` action target) shows as plain text in some surfaces — it should be a real `<a target="_blank" rel="noopener noreferrer">` so the user can click through. (Already covered by the design's "Open externally" action; the regression is that the URL text itself is not anchored.)

**Acceptance criteria**
- Wherever a feed/article URL is rendered in the web reader, it is a clickable link that opens in a new tab.
- A `:web:jsTest` asserts the DOM contains an anchor with the expected href.

#### #42 — Web: article list scroll position lost when opening article `[x]`

On the web app, after scrolling the article list and selecting an article to open in the reader pane, the article list jumps back to the top instead of maintaining the scroll position. Opening an article should not refresh or reset the list's scroll state.

**Acceptance criteria**
- Clicking/tapping an article to open it in the reader does not change the article list's scroll position.
- If the list is scrolled to row N, and the user opens an article, the list remains scrolled to approximately row N when the reader closes or the article is deselected.
- A `:web:jsTest` asserts that the list's scroll position is preserved before and after opening an article (e.g. by measuring `scrollTop` or via a virtual scroller's item offset).

---

### Group: Read-state surfaces

Both hit the same `PUT /v1/articles/{id}/read` endpoint and share the badge/dot optimistic-update plumbing.

#### #40 — Mark-read affordance on article rows and in the reader `[x]`

[spec/FEATURES.md](spec/FEATURES.md)'s FEED-8 and READ-7 both depend on a single read-toggle surface that hits `PUT /v1/articles/{id}/read` with the inverted flag. The row-level button sits next to the unread dot; the reader-level button lives in the reader's action group (web: next to `↗ Open` / `⎙ Share`; Android: next to `⎙ Share`). Both surfaces share the same source of truth, optimistically update the unread dot and badge, and on the Unread route the row stays in place until the next refresh.

**Acceptance criteria**
- Clicking/tapping the row-level affordance fires the PUT and decrements the Unread badge by one; the unread dot disappears.
- The reader-level button reflects the article's current read state (label "Mark unread" when read, "Mark read" when unread) and inverts on press.
- The Unread view does not optimistically drop the article; it stays put until the next list refresh.
- Tests cover both surfaces on both clients (web `:web:jsTest`, Android JVM test through [ServerRule](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt)).

#### #41 — Mark as read on open `[x]`

Replaced the never-implemented "mark as read on scroll" dwell-time preference with a simpler always-on behavior: opening an article automatically fires `PUT /v1/articles/{id}/read`. The `markAsReadOnScroll` preference, its Settings UI toggle, and all associated tests were removed.

**Web specifics:** `WebFeedRepository.markAsRead` now updates `isRead` in-place (instead of filtering the item out), and `updateArticleListRows` keeps the selected article in the display list even after it is marked read — it disappears from the Unread filter only when another article is selected. This avoids the jarring three-pane UX where the article vanishes from the left pane while still open in the reader.

**Android:** `markAsRead` is called in `MainTabShell.onArticleClick` before navigating to the full-screen reader. The existing Room delete-on-read behavior is correct for Android's non-co-visible layout.

See [FEATURES.md](spec/FEATURES.md) FEED-9 for the scenario.

---

### Group: Android refresh

#### #33 — Android: pull-to-refresh on article lists `[x]`

Resolved. `FeedScreen` already had `PullToRefreshBox` wired to `isRefreshing` and `onRefresh = { viewModel.refresh() }` in `MainTabShell`. Added the missing error banner: when `uiState is UiState.Error`, the header footer shows "Last sync failed · Retry" with a clickable Retry that re-triggers the refresh. `FeedScreenContent` gained an `uiState: UiState = UiState.Idle` parameter. Covered by two new Robolectric tests (`errorBannerShownWhenRefreshFails`, `retryClickInvokesOnRefresh`) in `FeedScreenTest`; the swipe-gesture test lives in `FeedScreenInstrumentedTest` (instrumented, requires a device) — `PullToRefreshBox` gesture dispatch does not fire under Robolectric. Android test counts: 104 passed, 0 failed, 2 skipped.

---

### Group: Cross-client server-backed prefs

Each adds a server endpoint plus a client read/write. Pick a session per ticket — server schema/endpoint changes don't want to compete for review attention.

#### #37 — "Keep articles" retention driven by the client setting `[ ]`

The Settings → Keep articles control (30d / 90d / 1y / forever) is shown in both clients but nothing reads it today. Wire it as a **client → server** preference: the value the user sets on either client persists to the server and replaces the server's current fixed-config retention sweep. Single-user product → single global value. Scenario SET-8 in [spec/FEATURES.md](spec/FEATURES.md) is the acceptance shape.

**Acceptance criteria — server**
- A new endpoint, e.g. `GET /v1/settings/retention` and `PUT /v1/settings/retention`, returns/accepts `{ "days": <int> | null }` where `null` ≡ "forever".
- The value is persisted (new `settings` table or a key/value row in an existing settings store — pick the smaller change).
- The server's article-cleanup sweep reads this value at each tick. If the value is missing (fresh DB), it falls back to whatever the config file currently specifies.
- A server-side test covers the endpoint + the sweep honoring the persisted value (including the `forever` case which performs no deletions).

**Acceptance criteria — clients**
- Both clients query the endpoint on Settings screen mount; the displayed value reflects the server's truth.
- Changing the control on either client writes the new value before navigating away (optimistic UI is fine; rollback on PUT failure).
- A client-side test per platform covers the read/write round-trip.

#### #38 — Refresh interval (client-side auto-poll) `[ ]`

The Settings → Refresh interval control (15m / 1h / 6h / manual) persists a value but no client polls. Wire a client-side timer. Scenario SET-9 in [spec/FEATURES.md](spec/FEATURES.md) is the acceptance shape.

**Acceptance criteria**
- Each client polls the article-list endpoints at the configured cadence (15m / 1h / 6h). `manual` disables the poll entirely.
- The poll is paused while the app/tab is backgrounded and resumed on foreground (web: `visibilitychange`; android: lifecycle `onStop` / `onStart`).
- Errors during a background poll surface via the ERR-1 path (sidebar footer on web; snackbar on android) — they do not interrupt the user's current screen.
- A test per platform covers both the cadence (use a virtual clock / `TestDispatcher` rather than real time) and the pause/resume.

#### #39 — Surface server version on Settings → About `[x]`

[spec/FEATURES.md](spec/FEATURES.md)'s Settings reference and SET-7 specify an About row on both clients showing `Client v<x> · Server v<y>`. Today the row is missing on web and Android doesn't surface the server version.

**Acceptance criteria — server**
- A new lightweight endpoint exposes the server version — e.g. `GET /v1/version` returning `{ "version": "<x.y.z>" }`, pulled from `env!("CARGO_PKG_VERSION")` at compile time. (Or extend the existing health endpoint with a version field — pick whichever is smaller.)
- The endpoint requires no authentication (so the About row works even on a stale session — fits the AUTH-5 spirit).
- A server-side test asserts the response shape.

**Acceptance criteria — clients**
- Both Settings screens render an About row reading `Client v<x> · Server v<y>` (`x` = client version baked at build time, `y` = response from `/v1/version`).
- On failure to reach the server, the row reads `Client v<x> · Server unreachable` in `ink3`.
- A unit test per platform covers both the success rendering and the unreachable fallback.

---

### #46 — Audit and spec non-happy-path styles from Claude Design `[ ]`

Claude Design contains visual treatments for error states, empty states, loading states, and other non-happy paths. These styles are not yet reflected in [spec/VISUAL_SPEC.md](spec/VISUAL_SPEC.md) or [spec/FEATURES.md](spec/FEATURES.md). As a result, implementations (e.g. the ERR-1 "Last sync failed" banner added in #33) use ad-hoc styles rather than spec-governed ones.

**Acceptance criteria**
- Review Claude Design for all non-happy-path treatments: error banners, empty states (no articles, no feeds, no search results), loading skeletons, inline field errors, toast/snackbar patterns.
- Document each treatment in `spec/VISUAL_SPEC.md` under a new "States" or "Feedback" section with color, typography, and layout rules.
- Update `spec/FEATURES.md` ERR-* rows if any error surfaces are missing or mis-described.
- No code changes required by this ticket — it is a spec-only update. Code fixes that flow from the audit belong in follow-up tickets.

---

## P2 — Feature roadmap

Server endpoints exist; client surface is missing. Tackle after P1 so the existing surfaces are spec-clean first.

### Group: Android visual polish

#### #43 — Android: add scroll indicator on the side when scrolling articles `[ ]`

The article list does not display a scroll position indicator, making it unclear where the user is in a long list. Add a vertical scrollbar or scroll indicator on the right edge that appears when scrolling.

**Acceptance criteria**
- A scroll indicator (scrollbar or equivalent visual) is visible on the right edge of the article list when scrolling.
- The indicator position accurately reflects the current scroll position in the list.
- The indicator appears during active scrolling and fades out when idle (or remains visible based on design — match spec/VISUAL_SPEC.md once updated).
- No regression in existing article list functionality or layout.

---

#### #44 — Android: fix article entry padding and unread dot positioning `[ ]`

The padding around article entries in the list is inconsistent, and the unread indicator dot is not properly aligned to the right edge of the entry (positioned at approximately 2/3 instead of the right edge).

**Acceptance criteria**
- Article entry padding is consistent on all sides (left, right, top, bottom).
- The unread indicator dot is positioned flush against the right edge of the entry, not inset by 2/3.
- Visual alignment matches spec/VISUAL_SPEC.md once updated with padding/spacing rules.
- All existing article row states (read, unread, with/without thumbnail) render correctly with the new padding.

---

### #4 — Categories UI and filtering `[ ]`

Server supports categories with reorder and nested-with-feeds responses. Client has none of it.

**Acceptance criteria**
- Categories can be created, renamed, deleted, reordered from a "Categories" screen.
- Feeds can be assigned to a category (from #3's feed detail/edit flow).
- The home article list can be filtered to: All / Uncategorized / a specific category. Filter persists across launches.
- Deleting a category does not delete its feeds (server already handles `ON DELETE SET NULL`); confirm UX matches.

---

### #5 — Full-text search UI `[ ]`

`GET /v1/articles/search` (FTS5) is implemented server-side and unused.

**Acceptance criteria**
- A search entry point (top app bar icon or pull-down) on the article list.
- Submitting a query hits `/articles/search` with debouncing (≥250ms) and shows results.
- Result rows show the snippet returned by the server (with the `<b>` highlights rendered or stripped — pick one and be consistent).
- Optional: a feed filter on the search screen (the endpoint accepts `feed_id`).

---

### #7 — Stats / health dashboard `[ ]`

`GET /v1/stats` and `GET /v1/feeds/health` exist and are unused.

**Acceptance criteria**
- A "Dashboard" or "Stats" screen shows totals (feeds, articles, unread, starred) and trends (24h/7d/30d, plus daily counts).
- A feed-health section flags feeds with errors, paused feeds, and never-fetched feeds — with a tap-through to the feed's detail/edit screen (#3).
- The screen pulls fresh data on each navigation; no caching needed.

Note: the "starred" total can be dropped from the acceptance criteria now that #35 removed the feature.

---

### #9 — Batch read operations `[ ]`

Server supports `mark-all-read`, `mark-feed-read`, and batch `articles/read`. Client only marks one at a time via swipe.

**Acceptance criteria**
- "Mark all as read" action on the home screen (with confirmation if unread count > some threshold, e.g. 50).
- "Mark feed as read" from the feed detail screen (#3).
- Selection mode on the article list allows multi-select → batch mark-read via `/articles/read`.
- Local Room cache is updated to match server state (or evicted, matching current single-row pattern).

---

## P3 — Infra hygiene

### #15 — Add LICENSE file `[ ]`

[server/README.md:418](server/README.md#L418) references "MIT License - see the LICENSE file" but no such file exists. (~15 minutes.)

**Acceptance criteria**
- A `LICENSE` file exists at repo root with chosen license text.
- Top-level README references it.
- Server README's existing reference resolves to a real file.

---

### #10 — First-run DB bootstrap `[x]`

[server/src/main.rs:74](server/src/main.rs#L74) carries a TODO: the server doesn't have a clean path when no SQLite DB exists at the resolved path. SQLx's connection options need `create_if_missing` or the directory needs to exist.

**Acceptance criteria**
- Running the binary on a clean system with no DB file creates it (and any missing parent directory) at the path returned by `Config::database_url()`.
- A clear log line confirms first-run setup (so the user knows what happened).
- Existing-DB behaviour is unchanged.
- An integration test covers the cold-start case.

---

### #17 — CI on GitHub Actions `[x]`

No CI exists. Easy to regress. The local test environment is now trustworthy (see [.claude/plans/test-environment-hardening.md](.claude/plans/test-environment-hardening.md)) — `cargo test` and `./gradlew testDebugUnitTest` both run clean, and the gradle task auto-builds the server binary the Android integration tests need.

**Acceptance criteria**
- A workflow runs `cargo fmt --check`, `cargo clippy -D warnings`, and `cargo test` on every push/PR.
- A workflow runs `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` for the Android module. The existing `:app:buildServerBinary` task means no extra orchestration is needed — gradle will build the server before the Android tests run.
- Workflow files are committed to `.github/workflows/`.

---

### #16 — Real Dockerfile + image build `[x]`

The server README shows an example Dockerfile but nothing is wired up.

**Acceptance criteria**
- A `Dockerfile` (or `server/Dockerfile`) at the repo produces a working server image.
- Multi-stage build keeps the runtime image slim (Debian slim or distroless).
- The image runs as non-root.
- Config and DB paths are volume-mountable; `FEED_JWT_SECRET` env var override works.
- A README section documents `docker run` with a real example.
- Optional: a `docker-compose.yml` for single-command bring-up.

---

### #24 — Contract tests between client models and server JSON `[ ]`

Natural follow-up to #23. The shared client models ([`Models.kt`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt)) and the server's serialized response shapes ([`server/src/db.rs`](server/src/db.rs), [`server/src/api/types.rs`](server/src/api/types.rs)) drift independently. The bug fixed in this commit: the client `Article` required a `read_at` field the server never emits → `MissingFieldException` swallowed → silently empty article list. With `ignoreUnknownKeys = true` only the "extra fields" direction is guarded; "missing fields" / "type changed" still blow up at runtime.

`ArticleModelTest` added alongside the fix covers the `Article` model. This ticket is about systematic coverage of the remaining endpoints.

**Acceptance criteria**
- For each REST endpoint the client calls (feed list, categories, stats, search, …), a test deserializes a representative server-shaped JSON into the client model without throwing.
- Fixtures or inline JSON strings live in `shared/src/commonTest/`.
- Ideally a server-side Rust test generates the same fixtures from real Rust structs so the two sides stay in sync; a simpler alternative is a test that calls a live test server and decodes one real response.

---

### #22 — Investigate the `#[ignore]`'d db tests `[ ]`

Several tests in [server/src/db_tests.rs](server/src/db_tests.rs) were marked `#[ignore]` during the test-hardening pass because their assertions don't match current behavior. Some may be real bugs in the server, others stale test expectations. Untriaged. (Post-#35 the count is 5 ignored, down from 6; refresh the inventory when picking this up.)

The remaining suspects:
- `test_search_articles_not_logic` — FTS5 NOT operator returns more rows than expected.
- `test_get_all_webhooks` — filtering returns more rows than expected.
- `test_get_article_count_since` — count off by one or boundary handling.
- `test_get_daily_article_counts` — daily bucket count mismatch.
- `test_delete_old_articles` — retention cleanup doesn't delete what the test expected.

(`test_get_starred_articles` was retired with the rest of the starring code in #35. `test_cleanup_expired_refresh_tokens` was deleted as part of the Phase 0 cookie-auth migration that dropped the `refresh_tokens` table.)

**Acceptance criteria**
- For each test: determine whether the test is wrong or the implementation is wrong, fix the appropriate side, remove the `#[ignore]`.
- `cargo test` reports `0 ignored` (or higher passing count if new tests are added in the process).
- Any genuine bugs found in server code are noted in the commit message.

---

### #47 — Android: configure release signing `[ ]`

The Android app currently uses debug signing keys for all builds, including what would be release builds. Before distribution (Play Store, F-Droid, or direct APK), the app needs a production signing key configured. Today [app/build.gradle.kts](app/build.gradle.kts) and the build flow have no release signing setup.

**Acceptance criteria**
- A production keystore is created (or template generated via `keytool`) and stored outside the repo (e.g. in a `~/.android/` or team secrets directory). Document the setup steps in [CONTRIBUTING.md](CONTRIBUTING.md) for maintainers.
- [app/build.gradle.kts](app/build.gradle.kts) is configured with a `signingConfigs { release { ... } }` block that reads the keystore path and password from environment variables or a local `keystore.properties` file (never committed).
- `build { release { signingConfig signingConfigs.release } }` wires the release variant to the signing config.
- `./gradlew assembleRelease` produces an APK signed with the production key (separate from `assembleDebug` which continues using the debug key).
- `.gitignore` blocks `*.keystore`, `keystore.properties`, and any team-secret files.
- A note in [CONTRIBUTING.md](CONTRIBUTING.md) and/or [server/README.md](server/README.md) explains the signing setup, which maintainers need to perform locally or in CI to build a release.

---

### #20 — `data_extraction_rules.xml` TODO `[ ]`

[app/src/main/res/xml/data_extraction_rules.xml:8](app/src/main/res/xml/data_extraction_rules.xml#L8) carries the scaffold TODO about `<include>`/`<exclude>`.

**Acceptance criteria**
- Decide what should and should not be in cloud/device backups (tokens? Room cache?) — likely: exclude the token DataStore and Tink keyset, allow everything else.
- File has explicit rules (no TODO), and a one-line comment explaining the choice.

---

## P4 — Deferred investigations

Low priority; pick up only when context warrants (touching nearby code, scaling pain, etc.).

### #14 — Migration framework (deferred) `[ ]`

[server/src/db.rs:128-482](server/src/db.rs#L128-L482) chains ten inline `if version < N { ... }` blocks. Works today, gets awkward as it grows.

**Acceptance criteria**
- Migrations live in their own files (e.g. `migrations/0001_initial.sql`) and are applied either via `sqlx::migrate!` or a small bespoke runner.
- Existing databases at any current `schema_version` (1-10) upgrade cleanly without data loss.
- `Database::new` becomes substantially shorter.
- Low priority — defer until adding migration #11 actually hurts.

---

### #21 — Investigate Metro DI (deferred) `[ ]`

The top-level README has a note pondering whether to adopt [Metro](https://zacsweers.github.io/metro/latest/quickstart/) for DI. Currently DI is hand-rolled in `FeedApplication` + ViewModel `Factory`.

**Acceptance criteria** (when picked up)
- A short ADR-style note in the repo explains the decision (yes / no / not yet) and why.
- If yes: a single screen migrated as proof, with the rest of the migration tracked as a follow-up ticket.
- Low priority — revisit only once the Android side has noticeably more classes (post-#3/#4/#5).

---

### #36 — Investigate feed-hue collisions `[ ]`

SUBS-5 noted that two feeds with different names rendered the same avatar hue. The hue derivation is `abs(id.hashCode()) % 360` (Phase 1 implementation uses `ushr 1` to avoid `Int.MIN_VALUE` overflow), keyed off feed id, so identical hues across two ids are plausible at small N but worth checking — are we seeding from the right field, and is the modulo bucketing producing visible clashes on typical id ranges?

**Acceptance criteria**
- Audit `FeedHue` against real feed ids from a populated server; document whether observed collisions are at the expected rate.
- If the rate is unacceptable, switch to a better mixing function (e.g. xxhash of the feed's URL or title rather than the id's `hashCode()`), or shift to a curated palette of N hues distributed around the wheel.
- A unit test pins the chosen mapping so future changes are deliberate.

---

## Needs verification

### #8 — OPML import UI `[?]`

`POST /v1/feeds/import/opml` is implemented server-side. The ticket was filed when no client entry point existed, but [spec/FEATURES.md](spec/FEATURES.md) SET-5 currently lists OPML import as ✓ on both clients and the Settings reference table marks "Account → Import OPML" as ✓. **Action:** confirm both client surfaces exist and the file-picker → POST → summary-dialog flow works end-to-end, then flip to `[x]`. If a client surface is actually missing, drop the `[?]` back to `[ ]` and keep the original acceptance criteria below.

**Acceptance criteria (original)**
- A "Import OPML" action in Settings (or the Feeds screen from #3) opens a file picker for `.opml` / `.xml` files.
- The file body is POSTed as-is to the server.
- The summary response (`imported` / `already_exists` / `failed` / `categories_created`) is rendered in a result dialog or screen.
- Failures per feed (from the response's `feeds` list) are scrollable so the user can inspect what didn't import.

---

## Closed without action

### #6 — Starring / favorites UI `[-]`

Superseded by #35. Star toggle, `is_starred` / `starred_at` columns, and the "Starred" filter are no longer part of the product — see [spec/FEATURES.md](spec/FEATURES.md) under "Features explicitly NOT supported". Do not reintroduce.

---

## Done

### #1 — Configurable server URL `[x]`

Resolved. The URL now lives in a sibling DataStore ([ServerUrlStore.kt](app/src/main/java/eu/monniot/feed/api/ServerUrlStore.kt)) and a new [BaseUrlInterceptor](app/src/main/java/eu/monniot/feed/api/NetworkModule.kt) rewrites each request's scheme/host/port from the provider on every call, so URL changes take effect on the next request without rebuilding any API clients. A dedicated `ServerConfigScreen` is reachable both from `Settings → Server URL` and a "Server: …" button on the login screen. `FeedViewModel.login()` now distinguishes `IOException` (unreachable → "Cannot reach server at …") from `HttpException` (401 → "Invalid username or password"). Default remains `http://10.0.2.2:3000/`. Normalization handles missing scheme, missing trailing slash, whitespace, and rejects non-http URLs — covered by `ServerUrlStoreTest` (9 cases) and `BaseUrlInterceptorTest` (3 cases).

---

### #2 — Show real feed title on article list `[x]`

Resolved. `FeedRepository.refresh()` now makes one `getFeeds()` call alongside `getArticles()`, builds a `feed_id → custom_title ?: title` map, and joins client-side via a new pure `toEntities(articles, feedTitlesById)` helper. `RssItemEntity` gained a nullable `feedTitle` column (Room v2→v3 migration: `ALTER TABLE rss_items ADD COLUMN feedTitle TEXT`, leaves existing rows NULL). `RssItem.feedTitle` is displayed in the article row instead of the hardcoded "Feed"; the ViewModel maps NULL to "Unknown" so legacy offline rows render gracefully until the next refresh fills them in. Covered by `ToEntitiesTest` (5 cases) plus the existing `FeedRepositoryTest` which now exercises the full join + insert path against the real server.

---

### #3 — Feed management UI `[x]`

Resolved. A `FeedsScreen` is reachable from the home screen's TopAppBar (RssFeed icon). `FeedRepository` gained four new methods (`getFeeds`, `addFeed`, `updateFeed`, `deleteFeed`); `updateFeed` always sends all three mutable fields to avoid serde-default clobbering on the server's PUT endpoint. `FeedViewModel` exposes five new `StateFlow`s and eight action methods (`loadFeeds`, `addFeed`, `renameFeed`, `setFeedInterval`, `toggleFeedPaused`, `deleteFeed`, plus two error-clear methods). The screen handles the empty state (CTA to add first feed), shows a FAB/icon to open an add-feed dialog (inline verbatim server errors on 400), and per-feed actions via a MoreVert dropdown: rename (pre-filled `AlertDialog`), set interval (numeric input, client-side ≥5 guard), pause/resume (label flips), and delete (error-colored confirm dialog). Covered by `FeedRepositoryFeedsTest` (11 cases) and `FeedViewModelFeedsTest` (15 cases), using a new `MockRssServer` helper (OkHttp MockWebServer) so the Rust subprocess can fetch a local RSS fixture for success-path tests.

---

### #11 — Test file housekeeping `[x]`

Resolved in the test-environment-hardening pass (see [.claude/plans/test-environment-hardening.md](.claude/plans/test-environment-hardening.md)). `db_tests.rs` is re-enabled with 86 passing tests and 7 `#[ignore]`'d ones tracked in #22. `fetcher_tests_simple.rs` was promoted to canonical `fetcher_tests.rs`; a fresh `scheduler_tests.rs` was written against the real function signatures. All `_simple`/`_working` suffixed files are gone. `cargo test` reports `93 passed; 0 failed; 7 ignored`.

---

### #12 — Remove `server/test.db` from the repo `[x]`

Resolved in the test-environment-hardening pass. `server/test.db` and `server/config.toml` turned out to never have been tracked in git history — they were only present in the working tree. `.gitignore` now has `server/*.db` and `server/config.toml` to prevent future accidents.

---

### #13 — `config.toml` should not be in the repo `[x]`

Resolved alongside #12 — `server/config.toml` was never tracked in git history. `.gitignore` now covers it. No credential rotation needed since the file never reached the remote. `config.example.toml` remains as the template.

---

### #18 — Update top-level README `[x]`

Resolved alongside the cross-platform support work (branch `crossplatform-support`). [README.md](README.md) now covers the three-module architecture, quick-start for all three clients, feature list, and API link. A new [CONTRIBUTING.md](CONTRIBUTING.md) covers prerequisites, build commands, test commands, and module-by-module guidance for contributors. [CLAUDE.md](CLAUDE.md) updated to reflect new module layout and test baselines.

---

### #19 — `androidTest` scaffold cleanup `[x]`

Resolved in the test-environment-hardening pass. `ExampleInstrumentedTest.kt` and `ExampleUnitTest.kt` deleted. `app/src/androidTest/` kept with a `.gitkeep` so the source set is preserved for future instrumented tests.

---

### #23 — Surface refresh / API errors in dev `[x]`

Resolved. Added a shared `Logger` (`shared/src/commonMain/kotlin/eu/monniot/feed/shared/util/Logger.kt`) with platform actuals — Android delegates to `android.util.Log.e`, JS to `console.error`, wasmJs to `console.error` via `@JsFun`. Every `catch (_: Exception)` block in [`FeedViewModel`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt) now binds the exception and calls `Logger.e(TAG, "<action> failed", e)` before mapping to the existing user-facing message — user-facing strings are unchanged. The repository layers had no `catch` blocks to update. `Logger.sink` is a `var` so tests can capture log invocations. Covered by `FeedViewModelErrorLoggingTest` (6 cases) verifying refresh, markAsRead, loadFeeds, addFeed (non-HTTP path), loadCategories, and importOpml all route the throwable through `Logger` before producing their error state.

---

### #25 + #34 — Web session persistence & 401 → login redirect `[x]`

Resolved together. Plan: [`spec/plans/work-on-ticket-25-hashed-squirrel.md`](spec/plans/work-on-ticket-25-hashed-squirrel.md).

**#25 (web):** `SessionManager` now accepts an optional `Settings?` parameter. On construction it reads `session_active` from the settings; `setLoggedIn()` writes it back. `Main.kt` passes the existing `StorageSettings` instance so the web app reads the persisted flag on every page load — no network call on boot, no flash of the login screen.

**#34 (both clients):** Added `internal fun onApiError(e: Exception): Boolean` to the shared `FeedViewModel`. It checks `e is ClientRequestException && e.response.status.value == 401`, calls `sessionManager.setLoggedIn(false)` when true, and returns whether a redirect was triggered. All non-login action catch blocks call this helper; `login()`'s own `ClientRequestException` catch is deliberately excluded to avoid an infinite redirect loop on wrong-credentials 401. Android navigation (`MainActivity`) and web routing (`Main.kt`) already react to `isLoggedIn` state changes, so the redirect is automatic on both platforms.

New tests: `SessionManagerTest` +3 persistence tests; `SessionBootTest.kt` (:web:jsTest, 4 tests); `FeedViewModelUnauthorizedTest.kt` (:shared:allTests, 4 tests). Test counts: shared-js 86, web 103, android 107, server 95 — all green.

---

#### #26 — Auth form keyboard ergonomics `[x]`

The login form is keyboard-hostile on both clients.

- **Web:** pressing Enter from inside the password field does not submit the form. The form should submit on Enter from either field.
- **Android:** the username field's IME action should advance focus to the password field (currently inserts a newline), and the password field's IME action should submit. The on-screen keyboard should expose a primary action ("Login"/"Done") that performs the submission.

**Acceptance criteria**
- Web: Enter from username or password submits the login form.
- Android: username IME action = Next (advances to password); password IME action = Done/Go (submits). Newlines are no longer inserted.
- A unit/UI test per platform asserts the keyboard-driven submission path.

---

### #45 — Settings UI refresh: match prototype on web and Android `[x]`

Aligned both Settings screens with the visual prototype in `spec/prototype/prototypes/editorial.jsx` (web) and `editorial-mobile.jsx` (Android). Plan: [`spec/plans/settings-ui-refresh-look-radiant-quiche.md`](spec/plans/settings-ui-refresh-look-radiant-quiche.md).

**Web (`web/src/jsMain/.../SettingsScreen.kt`):**
- Reading section reordered to font size → density → mark as read on scroll; removed "Reader theme" and "Default sort" rows; added hint text to all rows.
- Sync section: added hint text to Refresh interval and Keep articles rows.
- Account section: removed "Signed in as" row; added About row (hint: `Client v1.0.0 · Server v0.7.2`, right: `—`); added hint text to Import OPML and Logout; Logout button now styled with `--feed-danger` (red border + text).
- Added `--feed-danger: #a05050` to `tokens.css`.
- Expanded `settingsGroup` max-width from 640 px → 700 px so the six-option font-size segmented control is not clipped.

**Android (`app/src/main/.../SettingsScreen.kt`):**
- Replaced the tap-row → ModalBottomSheet picker pattern with two new composables: `SettingsSegmentedControl<T>` (inline pill buttons matching prototype styling) and `SettingsSegmentedRow<T>` (label + optional hint on left, control on right).
- Reading section: font size → density → mark as read on scroll (all inline segmented, with hints); removed "Reader theme" and "Default sort" rows.
- Sync section: refresh interval → keep articles (segmented, with hints) + Server URL row moved here from Account.
- Account section: Import OPML and About rows retain the navigation chevron pattern; About carries the version hint; Logout chevron rendered in accent color.
- Removed `SettingsPicker` enum, `PickerOption` composable, and all `ModalBottomSheet` / `activePicker` code.
- `SettingsScreenTest` updated to tap segmented buttons directly via test tags (e.g. `font_size_seg_22`); all 7 tests pass. Test counts unchanged (109 android, 99 web, all green).

Note: About row version strings are hardcoded (`Client v1.0.0 · Server v0.7.2`); dynamic server-version fetch is tracked in #39.

---

### #35 — Remove starring / favorites end-to-end `[x]`

Resolved in commit `787897c`. Server dropped `is_starred` / `starred_at` columns plus the `PUT /v1/articles/{id}/star` and `GET /v1/articles/starred` routes via a schema-version migration. The shared KMP layer dropped `toggleStarred` / `getStarred` from `FeedRepository` and `FeedApi`. Android removed the ★ button from `ReaderScreen` / `ArticleRow`, the `SavedTabPlaceholder`, and the "Saved" bottom-nav tab; the "Today" label flipped to "Unread". Web removed `Route.Starred`, the sidebar "Starred" entry, the `reader-star-btn`, `starredItems` subscription, and `WebFeedRepository.toggleStarred` / `getStarred` / `isStarred`. All starring-related tests deleted. Post-change test counts: server 95 passed (5 ignored — see #22), android 102 passed, shared-js / shared-wasmjs 73 each, web 88 passed. Closes #6 by supersession; FEATURES.md's "Features explicitly NOT supported" enshrines the decision.

---

To be fleshed out at a later point

- server/config.example.toml isn't fully up to date (missing database group for example)
- ~~Write a set of scripts to analyze test results instead of having claude run find/exec things that require my approval.~~ Resolved — see [scripts/](scripts/) (`test-counts.sh`, `test-run.sh`, `test-failures.sh`, `server-build.sh`), documented in [CLAUDE.md](CLAUDE.md#helper-scripts) and allowlisted via [.claude/settings.local.json](.claude/settings.local.json) (`Bash(./scripts/*:*)` plus fixed prefix syntax for `cargo:*`, `grep:*`, etc.).
