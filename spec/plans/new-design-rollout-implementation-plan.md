# Implementation plan — New design rollout

## Context

[`.claude/plans/new-design/README.md`](.claude/plans/new-design/README.md) is a high-fidelity design handoff covering all four primary screens (Feed, Reader, Subscriptions, Settings) on desktop **web** and **mobile** (Android). It locks the palette ("Paper"), typography (Source Serif 4 + IBM Plex Sans), spacing, radii, copy, and interaction model.

Current state:

- **Web** ([web/src/jsMain/](web/src/jsMain/)) is a minimal hash-routed Kotlin/JS SPA with raw-`innerHTML` rendering, inline style strings, and four bare screens (Login / List / Article / Settings). No CSS file, no design tokens. Article reader is an `<iframe>`.
- **Android** ([app/src/main/](app/src/main/)) is Jetpack Compose using a default Material3 purple theme ([app/src/main/java/eu/monniot/feed/ui/theme/](app/src/main/java/eu/monniot/feed/ui/theme/)). Screens exist (Login, Home/RssList, Settings, Feeds, Article-WebView) but UI is functional-only, no custom typography.
- **Shared** ([shared/src/commonMain/](shared/src/commonMain/)) hosts `FeedViewModel`, `FeedRepository`, `FeedApi`, `AuthApi`. Article model has `is_starred`, `author`, `content`. Feed model has `category_id`, `custom_title`, `is_paused` — **no `hue`, no `tag`**.
- **Server** ([server/](server/)) is feature-rich (FTS5 search, categories, starred endpoints, OPML import, webhooks) — most of the API is unused by current clients. **Missing**: per-feed `hue`/`color`, per-feed `tag`, any user-prefs table.

Out of scope: **iOS** (no iOS module exists; the README mentions iOS as a design target but the project has only web + Android).

Design decisions baked into this plan (override if you disagree):

1. **`hue` is computed client-side** from a stable hash of `feed.id`. No server schema change. The README explicitly says "default deterministic from feed-id hash"; user-editable hue can come later.
2. **`tag` is dropped from the visible UI.** It's redundant with the folder/category name we already have. The Subscriptions row will show the folder name where the design says "tag".
3. **Folder = existing `categories` table.** The design's "folder" maps 1:1 to the server's existing `category_id` mechanism.
4. **User prefs** (font size, density, view mode, refresh interval, keep-articles, mark-as-read-on-scroll, default sort, theme) are **stored locally** via `multiplatform-settings` on each platform. No server changes.
5. **Excerpt and min-read** are **derived client-side** from `Article.content` (strip HTML, take first N words; min-read = word count / 220).
6. **Web HTML composition** switches from raw `innerHTML` strings to `kotlinx.html` DSL during phase 3 to keep subsequent phases tractable.
7. **Android keeps its existing top-level nav** (Login, ServerConfig) outside the tab bar. The tab bar (Today / Saved / Feeds / Settings) wraps only the post-login flows.

## Phases

Each phase is sized for a single Sonnet 4.6 session, with explicit prerequisites and a verifiable acceptance bar. Web track (3–6) and Android track (7–10) can run in parallel after phases 1–2.

Dependency graph:

```
1 ─► 2 ─┬─► 3 ─► 4 ─► 5 ─► 6      (web)
        └─► 7 ─► 8 ─► 9 ─► 10     (android)
```

---

## Orchestration — instructions to the driver session

> **You** are the Opus orchestrator session that will execute this plan. Treat this section as your runbook. The human user will not review intermediate phases — they will inspect the final UI at the end, after phase 10 is on `main`. Your job is to land every phase on `main` with its automated tests green, then hand off.

### Per-phase loop

For each phase 1 → 10, in order, do:

1. **Pre-flight on `main`.** `git status` clean, `git pull` (if a remote is set), and run the relevant test suite for the previous phase to confirm `main` is green. If red, stop and surface the failure to the user — do not start a new phase on top of broken `main`.

2. **Spawn an implementation agent in an isolated worktree:**

   ```
   Agent(
     subagent_type: "general-purpose",
     isolation: "worktree",
     description: "Phase N: <short title>",
     prompt: <see template below>
   )
   ```

   Prompt template (substitute the phase's content):

   > You are implementing **Phase N — \<title>** of the redesign described in `.claude/plans/new-design-rollout-implementation-plan.md`. Read that plan file in full, then implement **only** Phase N. The full design spec is at `.claude/plans/new-design/README.md` — consult it for visual details.
   >
   > **Scope (verbatim from the plan):** <paste the phase's bullet list of files-to-touch>
   >
   > **Automated acceptance bar (verbatim from the plan):** <paste the phase's "Verify (automated)" bullet list>
   >
   > **Rules:**
   > - Touch only files within the phase's scope. If you need to change something outside, stop and report it instead of doing it.
   > - The CLAUDE.md testing requirement applies: every code change must be validated by an automated test before you declare done. UI visual fidelity is **not** your responsibility — the orchestrator's human reviewer handles that at the end.
   > - Before reporting done: run the acceptance commands and paste the full output.
   > - Commit your work on the worktree's branch with descriptive messages. Do not push.
   > - Report back: branch name, worktree path, summary of changes, test output, and any deviations from the plan with justification.

3. **Receive the agent's result.** Read the summary and the pasted test output. Then **independently verify** from outside the worktree:

   ```sh
   cd <worktree path>
   <the phase's acceptance commands>
   ```

   Do not trust the agent's self-report — re-run.

4. **Decide merge / iterate / escalate:**

   - **All green + scope respected:** merge the branch into `main` (squash-merge with a message like `phase-N: <title>`), delete the worktree (`git worktree remove <path>`), continue to the next phase.
   - **Tests failed or partial work:** `SendMessage` the same agent with a precise diff of what's missing (cite file paths, test names, error excerpts). Allow up to **2 follow-ups** to this same agent. If still failing, stop and ask the user with `AskUserQuestion`.
   - **Agent went out of scope** (touched files not listed in the phase): pull just the in-scope changes via `git checkout <branch> -- <files>` onto a fresh branch, drop the rest, then continue. Note the deviation in your phase report.

5. **Record progress.** Maintain a small `progress.md` in `.claude/plans/` (or append to the bottom of this plan file under a "Status" heading you create) noting: phase number, branch name (post-merge it's the squash commit SHA), test counts, anything notable. This is your durable scratchpad in case you get compacted between phases.

### Parallelism

- Phases 1 and 2 are strictly sequential and must land on `main` before anything else.
- After phase 2 is on `main`, **run web track (3→4→5→6) and android track (7→8→9→10) in parallel**: spawn one Agent per track simultaneously in two different worktrees. Each track is internally sequential.
- Do not start phase N+1 in a track until phase N in that track is merged to `main` (the next agent branches from updated `main`).

### Failure-mode policy

- **Build / test infrastructure breakage** (e.g. gradle config error that wasn't in the plan): stop and ask the user. Do not paper over with workarounds.
- **A pre-existing test failure on `main`**: stop and ask the user; do not silently ignore.
- **Design ambiguity** (the agent reports a spec it can't pin down): consult `.claude/plans/new-design/README.md` and `design-files/index.html`. If still ambiguous, pause that track and ask the user with `AskUserQuestion`.
- **Worktree leftover** after a failed run: clean it up before the next attempt (`git worktree remove --force`).

### Final hand-off

When phase 10 is merged to `main` and the full test suite is green from repo root:

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Post a single message to the user that:

1. Lists the squash-merge SHAs for phases 1–10.
2. Confirms test totals (server `97 passed`, shared ≥22 per platform, web ≥15, android ≥50 — exact numbers from your post-merge run).
3. Includes the commands to launch each client for visual review:
   - `./gradlew :web:jsBrowserDevelopmentRun`
   - `./gradlew :app:installDebug`
4. Points them at the **End-to-end test catalog** section of this plan as the manual checklist to walk through.
5. Notes any deviations from the plan and any rows in the catalog that you could not automate.

Then **stop** — do not start fixing UI nits autonomously. The user will direct any visual rework.

---

### Phase 1 — Shared module: extend models & repository surface

**Goal:** Surface the data the new UI needs from the existing server endpoints. No server changes.

Touch:

- [shared/src/commonMain/kotlin/eu/monniot/feed/Models.kt](shared/src/commonMain/kotlin/eu/monniot/feed/Models.kt) — confirm `Article.is_starred`, `Article.author`, `Article.content` are wired; add `Feed.category_id` mapping helpers.
- [shared/src/commonMain/kotlin/eu/monniot/feed/data/FeedRepository.kt](shared/src/commonMain/kotlin/eu/monniot/feed/data/FeedRepository.kt) — add:
  - `toggleStarred(articleId: Int)`
  - `getStarred(): Flow<List<ArticleItem>>`
  - `getCategories(): List<Category>`
  - `setFeedCategory(feedId, categoryId)`
  - Extend `ArticleItem` with `feedId`, `feedHue` (computed, not stored), `isStarred`, `isRead`, `author`, `minutesToRead`, `excerpt`.
- [shared/src/commonMain/kotlin/eu/monniot/feed/api/FeedApi.kt](shared/src/commonMain/kotlin/eu/monniot/feed/api/FeedApi.kt) — add wrappers for `PUT /v1/articles/{id}/star`, `GET /v1/articles/starred`, `GET /v1/categories`, `POST /v1/categories`, `PUT /v1/feeds/{id}/category`.
- New: `shared/src/commonMain/kotlin/eu/monniot/feed/util/FeedHue.kt` — deterministic hue from feed id (`abs(id.hashCode()) % 360`).
- New: `shared/src/commonMain/kotlin/eu/monniot/feed/util/Excerpt.kt` — strip HTML, return first ~180 chars + `minutesToRead(content)`.
- [shared/src/commonMain/kotlin/eu/monniot/feed/FeedViewModel.kt](shared/src/commonMain/kotlin/eu/monniot/feed/FeedViewModel.kt) — expose new state: `starredItems`, `categories`, `selectedFeedId`, `selectedArticleId`; new actions: `selectFeed`, `selectArticle`, `toggleStarred`, `loadCategories`.

**Verify (automated):**

- `./gradlew :shared:allTests` passes; new `FeedHueTest`, `ExcerptTest`, `FeedViewModelStarredTest` added. Target: 16 → ~22 tests per platform.

**Manual scenarios** (catalog refs): none — this phase is data-layer only.

---

### Phase 2 — Local user prefs in `multiplatform-settings`

**Goal:** Persist the design's user-controlled settings (font size, density, view mode, refresh interval, theme, mark-as-read-on-scroll, default sort, keep articles).

Touch:

- New: `shared/src/commonMain/kotlin/eu/monniot/feed/data/UserPrefs.kt` — `UserPrefs` class wrapping `Settings` (the existing `multiplatform-settings` instance already used by `ServerUrlStore`). Keys + defaults match the README's Settings rows exactly.
- [shared/src/commonMain/kotlin/eu/monniot/feed/FeedViewModel.kt](shared/src/commonMain/kotlin/eu/monniot/feed/FeedViewModel.kt) — inject `UserPrefs`, expose `prefs: StateFlow<UserPrefs.Snapshot>` and `updatePref(key, value)`.
- Wire instantiation in [app/src/main/java/eu/monniot/feed/FeedApplication.kt](app/src/main/java/eu/monniot/feed/FeedApplication.kt) and [web/src/jsMain/kotlin/eu/monniot/feed/web/Main.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/Main.kt) (or wherever the JS `Settings` is built today).

**Verify (automated):**

- `UserPrefsTest` in `shared/src/commonTest` covers defaults + set/get round-trip with an `InMemorySettings`.
- `./gradlew :shared:allTests` passes.

**Manual scenarios:** none — proven indirectly when phases 4, 6, 8, 9, 10 read these prefs.

---

### Phase 3 — Web: design tokens, fonts, kotlinx.html scaffolding

**Goal:** Lay the foundation that subsequent web phases will paint into. No screen redesign yet — apply the new background color and font stack to the existing screens just enough to prove the tokens work.

Touch:

- New: `web/src/jsMain/resources/css/tokens.css` — define every `--feed-*` CSS variable from the README's palette and type tokens, plus utility classes for each type scale row (`.type-h1`, `.type-list-title`, `.type-meta`, etc.).
- New: `web/src/jsMain/resources/css/reset.css` — minimal CSS reset (box-sizing, body margin, font smoothing).
- [web/src/jsMain/resources/index.html](web/src/jsMain/resources/index.html) — add Google Fonts `<link>` (Source Serif 4 opsz 8..60 weights 400/500/600 + IBM Plex Sans weights 400/500/600), link the two CSS files, set `<body>` bg to `var(--feed-bg)`.
- [web/build.gradle.kts](web/build.gradle.kts) — add `kotlinx-html-js` dependency.
- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/dom/Render.kt` — small helpers `render(parent, block: TagConsumer.() -> Unit)` and `replace(id, block)` so screens compose with the `kotlinx.html` DSL instead of `innerHTML` strings.
- Migrate existing screens ([ListScreen.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/ListScreen.kt), [LoginScreen.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/LoginScreen.kt), [SettingsScreen.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt), [ArticleScreen.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/ArticleScreen.kt)) to the DSL. Behavior unchanged; visuals only get the new bg + font stack.
- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/BrandMark.kt` — the 22×22 circle + "Feed" wordmark; reused by sidebar and mobile-future.

**Verify (automated):**

- `./gradlew :web:jsTest` — existing 11 router tests still pass (the migration to `kotlinx.html` is renderer-only).
- New `BrandMarkTest.kt` asserts the DOM shape of the brand mark via a JSDOM helper.

**Manual scenarios:** AUTH-1, AUTH-2, AUTH-4 (regression check that the existing login screen still works under the new fonts/palette).

---

### Phase 4 — Web: Feed + Reader (three-column layout)

**Goal:** The biggest visual change. Replace `ListScreen` and `ArticleScreen` with the design's three-column desktop layout.

Touch:

- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt` — 220px panel: brand mark, primary nav (All / Starred / Subscriptions / Settings with counts from `FeedViewModel`), folder list (driven by `categories` + `feeds`), sync footer.
- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt` — 380px column: sticky header (title + subtitle), rows with meta line (feed dot via `FeedHue`, name, time-ago via `RelativeTime`, star/unread indicator), serif title, sans excerpt, min-read footer. 2px-wide `accent` left bar on selected row via `box-shadow: inset 2px 0 0 var(--feed-accent)`.
- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt` — fills remaining width. Empty state with em-dash, article view with meta row, serif H1, italic dek, action row (Star/Open/Share/Aa), body. Body rendered from `Article.content` (sanitize HTML — use a tiny allowlist; do **not** keep the `<iframe>` approach). Font-size pulled from `UserPrefs.fontSize`.
- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt` — composes the three columns; owns selection state by reading `FeedViewModel.selectedFeedId` / `selectedArticleId`.
- [web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt) — add `/feed/:feedId` and `/article/:articleId` routes (deep-linkable per README). Update parser + serializer + router tests.
- Delete the old `ListScreen.kt` and `ArticleScreen.kt`.

**Verify (automated):**

- `./gradlew :web:jsTest` — extend `RouterTest` to round-trip the new `/feed/:feedId` and `/article/:articleId` routes; target 11 → ~15 tests.
- New `ArticleListSelectionTest.kt` — pure logic test: given a fake `FeedViewModel` state, list rendering picks the correct selected row and emits the right click events.

**Manual scenarios:** FEED-1, FEED-2, FEED-3, FEED-4, FEED-5, FEED-6, READ-1, READ-2, READ-3, READ-6, READ-7, ERR-2.

---

### Phase 5 — Web: Subscriptions screen

**Goal:** New screen at `/subscriptions`. Sidebar (Phase 4) + single content area (max-width 720px).

Touch:

- New: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/subs/SubscriptionsScreen.kt` — H1 + "+ Add feed" button (opens a small inline form), search bar (filters client-side), feed rows (36×36 letter avatar tinted by `FeedHue`, name, URL sub-line, folder name right-aligned, unread count, overflow `⋯`).
- Wire overflow menu → rename / set folder / pause / delete (existing `FeedViewModel` actions).
- Add-feed form uses existing `addFeed()`.
- [web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt) — wire `Subscriptions` route to the new screen (already declared, currently a stub).

**Verify (automated):**

- `./gradlew :web:jsTest` — `RouterTest` round-trips `/subscriptions`.
- New `SubsSearchFilterTest.kt` — pure filter logic over a fixture feed list.

**Manual scenarios:** SUBS-1, SUBS-2, SUBS-3, SUBS-4, SUBS-5, SUBS-6.

---

### Phase 6 — Web: Settings screen

**Goal:** Replace the bare server-URL settings page with the sectioned design.

Touch:

- Rewrite [web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt) — H1 "Settings", three sections (Reading / Sync / Account) with the exact rows from the README. Segmented controls bound to `UserPrefs` from Phase 2.
- Account section: "Signed in as: local · this device", `Import OPML` button (calls `POST /v1/feeds/import/opml` — needs adding to `FeedApi` if not present), server URL row (keep current behavior), logout.
- New segmented-control DOM helper in `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/Segmented.kt`.

**Verify (automated):**

- `./gradlew :web:jsTest` — still passes.
- New `SegmentedControlTest.kt` asserts active/inactive segment ARIA + click-binding behavior.

**Manual scenarios:** SET-1, SET-2, SET-3, SET-4, SET-5, SET-6.

---

### Phase 7 — Android: Compose theme overhaul

**Goal:** Compose-side equivalent of Phase 3.

Touch:

- [app/src/main/java/eu/monniot/feed/ui/theme/Color.kt](app/src/main/java/eu/monniot/feed/ui/theme/Color.kt) — replace the Material purple palette with the 11 Paper tokens as `Color` constants. Build a custom `FeedColors` data class (not Material's `ColorScheme`) so the names match the design.
- [app/src/main/java/eu/monniot/feed/ui/theme/Type.kt](app/src/main/java/eu/monniot/feed/ui/theme/Type.kt) — load Source Serif 4 and IBM Plex Sans via Google Fonts (`androidx.compose.ui.text.googlefonts.GoogleFont`). Define a `FeedTypography` with named styles for every row in the README's type scale (`h1Page`, `h1Article`, `listTitle`, `listExcerpt`, `metaLabel`, `navItem`, …).
- [app/src/main/java/eu/monniot/feed/ui/theme/Theme.kt](app/src/main/java/eu/monniot/feed/ui/theme/Theme.kt) — `FeedTheme` provides `FeedColors` and `FeedTypography` via `CompositionLocal`. **Disable** Material's dynamic color (the design is locked).
- Add `androidx.compose.ui:ui-text-google-fonts` to [app/build.gradle.kts](app/build.gradle.kts).
- New: `app/src/main/java/eu/monniot/feed/ui/components/FeedDot.kt` — 6×6 circle using `FeedHue`.
- Touch existing screens *minimally*: swap `MaterialTheme.colorScheme.background` → `FeedColors.bg` so nothing crashes; visuals stay otherwise unchanged.

**Verify (automated):**

- `./gradlew :app:testDebugUnitTest` — existing 50 tests still pass.
- New `FeedThemeTest.kt` (Robolectric + Compose-test) snapshot-asserts that a `Text` styled with `FeedTypography.h1Page` resolves to Source Serif 4 / 28sp / w500.

**Manual scenarios:** AUTH-1, AUTH-2, AUTH-4 (regression on Android login under the new theme).

---

### Phase 8 — Android: Feed screen + bottom tab bar

**Goal:** Replace `HomeScreen` and the post-login Jetpack nav with a tabbed shell + redesigned Feed screen.

Touch:

- New: `app/src/main/java/eu/monniot/feed/ui/shell/MainTabShell.kt` — `Scaffold` with bottom `NavigationBar` (Today / Saved / Feeds / Settings) using the 4px-radius, `accent`-on-active styling. The shell wraps a nested `NavHost` for the four tabs.
- [app/src/main/java/eu/monniot/feed/MainActivity.kt](app/src/main/java/eu/monniot/feed/MainActivity.kt) — keep `login` and `server-config` as top-level routes; replace `home` with `main` that hosts `MainTabShell`. The Reader screen pushes on top of the tab shell and hides the bar.
- New: `app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt` — large title row, subtitle, horizontal filter chips (All / Unread / Long reads / Short reads / Today — pure client-side filters over `viewModel.items`), `LazyColumn` of article rows.
- New: `app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt` — meta line + serif title + sans excerpt + min-read, density driven by `UserPrefs`.
- Delete `HomeScreen` and the existing `RssList`/`RssItemRow` once `FeedScreen` reaches parity.

**Verify (automated):**

- `./gradlew :app:testDebugUnitTest` — Compose Robolectric tests:
  - `FeedScreenTest.tabBarPersistsAcrossTabSwitch()`
  - `FeedScreenTest.filterChipFiltersList()` — given 4 fixture articles with `minutesToRead` 2/9/9/14, `LongReads` shows only the 14-min one.
  - `FeedScreenTest.tappingRowNavigatesToReader()` via Compose `Navigator` test rule.
- Existing `ServerRule`-driven integration tests still pass (`./gradlew :app:testDebugUnitTest`).

**Manual scenarios:** MOB-1, MOB-4, FEED-1, FEED-2, FEED-6.

---

### Phase 9 — Android: Reader screen

**Goal:** Replace the WebView article view with a native Compose reader matching the mobile-reader spec.

Touch:

- New: `app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt` — sticky top bar (back chevron `← {feedName}` + Aa/★/⎙ cluster), serif H1, italic dek (derived from excerpt), body rendered from `Article.content` using a small HTML→AnnotatedString converter (existing libraries: `coil-html` is overkill; use Jsoup + a manual span builder, or `androidx.compose.ui.text.AnnotatedString`).
- Wire `★` to `viewModel.toggleStarred(articleId)`.
- Reader font size pulled from `UserPrefs.fontSize`.
- Delete the old `ArticleScreen` (WebView).

**Verify (automated):**

- `./gradlew :app:testDebugUnitTest` — new `ReaderScreenTest.kt`:
  - `bodyRendersAtConfiguredFontSize()` — feeds `UserPrefs.fontSize = 22`, asserts the body `Text` resolves to 22sp.
  - `starButtonTogglesIsStarred()` — uses an `InMemoryFeedRepository` to verify the action is dispatched.
  - `backButtonPopsToList()` via the test `NavController`.

**Manual scenarios:** MOB-2, MOB-3, READ-1, READ-2, READ-3, READ-4, READ-5.

---

### Phase 10 — Android: Subscriptions + Settings screens

**Goal:** Bring the remaining two tabs to design fidelity.

Touch:

- Rewrite [app/src/main/java/eu/monniot/feed/MainActivity.kt](app/src/main/java/eu/monniot/feed/MainActivity.kt)'s `FeedsScreen` (replace) → new `app/src/main/java/eu/monniot/feed/ui/subs/SubscriptionsScreen.kt`. Folder groups (uppercase header), 34×34 letter avatars, name + URL, unread count.
- Rewrite the existing Settings into `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt` with the three sections from the README. Each row is a tappable `ListItem` opening a native bottom-sheet picker; values bound to `UserPrefs`.

**Verify (automated):**

- `./gradlew :app:testDebugUnitTest`:
  - `SettingsScreenTest.changingFontSizePersistsToUserPrefs()` — taps the 22sp picker option, asserts `UserPrefs.fontSize == 22`.
  - `SubscriptionsScreenTest.feedsGroupByFolder()` — feeds two folders with 2 feeds each, asserts the LazyColumn structure.
  - `SubscriptionsScreenTest.searchFiltersClientSide()`.

**Manual scenarios:** SUBS-1, SUBS-2, SUBS-3, SUBS-4, SET-1, SET-2, SET-3, SET-4.

---

## End-to-end test catalog

Scenarios below are written so they can be ported directly to:

- **Web:** Playwright/Cypress against `./gradlew :web:jsBrowserDevelopmentRun` with a fresh `TestDatabase` seeded via the server's existing [server/src/test_utils.rs](server/src/test_utils.rs).
- **Android:** Compose UI tests under [app/src/androidTest/](app/src/androidTest/) using [ServerRule.kt](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt) (already spawns a real Rust server subprocess) + [MockRssServer.kt](app/src/test/java/eu/monniot/feed/integration/MockRssServer.kt) for upstream RSS fixtures.

Each scenario lists ID · setup · steps · expected. Re-use the IDs (`FEED-2`, `SET-3`, etc.) in test method names so the spec ↔ test mapping is greppable.

### Authentication & session

| ID | Setup | Steps | Expected |
|---|---|---|---|
| AUTH-1 | Fresh client, valid creds in config | Open app, enter username + password, submit | Lands on Feed screen, sidebar populated within 2s |
| AUTH-2 | Fresh client, invalid password | Submit login form | Error message shown, focus stays on login |
| AUTH-3 | Already logged in | Reload (web) / kill+reopen app (android) | No login prompt, lands on Feed |
| AUTH-4 | Logged in | Open Settings → Logout | Redirected to login, cookie cleared |

### Feed list & navigation

| ID | Setup | Steps | Expected |
|---|---|---|---|
| FEED-1 | 7 feeds, 24 unread articles seeded | Open Feed screen | List shows 24 items sorted by `published` DESC; sidebar "All articles" count = 24 |
| FEED-2 | Same fixture | Click "Field Notes" in sidebar | List filters to only Field Notes items; URL = `/feed/fieldnotes` (web) |
| FEED-3 | Same fixture, 3 starred items | Click "Starred" in sidebar | List shows exactly 3 items; URL = `/saved` (web) |
| FEED-4 | Article list with 5 items | Click 3rd item | 3rd row has `panel` bg + 2px inset accent bar on left edge; reader pane fills with that article |
| FEED-5 | Long list + long article | Scroll list, then scroll reader | Each column scrolls independently |
| FEED-6 | Two feeds with ids `fieldnotes` and `theloop` | Render | Per-feed dot colors are stable across reloads; identical for same feed across sidebar / list / reader meta |

### Reader

| ID | Setup | Steps | Expected |
|---|---|---|---|
| READ-1 | Feed screen, article A selected | Click article B in the same list | Reader pane swaps to article B *without route change* on web (mobile pushes a screen — see MOB-2) |
| READ-2 | Article with HTML body including `<script>`, `<iframe>`, `<img>` | Open in reader | `<script>` and `<iframe>` stripped; `<img>` and `<p>`/`<a>`/`<em>`/`<strong>` preserved |
| READ-3 | Unstarred article | Click ★ | Star fills with `accent`; `PUT /v1/articles/{id}/star` sent; refresh keeps the star |
| READ-4 | Reader open | Click `Aa` button | Font size cycles 14→18→22 (or opens picker on mobile) |
| READ-5 | Font size set to 22 in Settings | Open reader | Body text computed style = 22px (web) / 22sp (android) |
| READ-6 | Article with `link = "https://example.com"` | Click ↗ | Opens external tab/Browser intent to that URL |
| READ-7 | Web only, fresh login, no article selected | Open Feed screen | Reader pane shows em-dash + "Select an article to begin reading." |

### Subscriptions

| ID | Setup | Steps | Expected |
|---|---|---|---|
| SUBS-1 | 7 feeds in 3 folders | Open Subscriptions | 7 rows; on mobile grouped by folder with uppercase headers |
| SUBS-2 | Empty subscriptions; valid RSS URL on hand | Click "+ Add feed", paste URL, submit | New row appears; `POST /v1/feeds` returned 201; sidebar count increments |
| SUBS-3 | 7 feeds | Type "field" in search box | Only "Field Notes" row visible; no server call |
| SUBS-4 | Existing feed | Click ⋯ → Rename → "Foo" → save | Row updates inline; `PUT /v1/feeds/{id}` with `custom_title = "Foo"` |
| SUBS-5 | Feed with `hue = 22` | Render row | Avatar bg = `oklch(0.85 0.05 22)`, fg = `oklch(0.35 0.08 22)`; computed via `FeedHue` |
| SUBS-6 | Feed with 3 articles | Delete it via ⋯ menu | Confirmation; on accept, feed and its articles vanish from Feed list and sidebar |

### Settings (and prefs persistence)

| ID | Setup | Steps | Expected |
|---|---|---|---|
| SET-1 | Prefs set in storage: font=22, density=comfy | Open Settings | Each segmented control reflects the stored value as active |
| SET-2 | Default prefs | Change every control once, reload page / restart app | Each control reflects the new value; no value reset |
| SET-3 | Reader open at 18px | Change font size in Settings to 22 | Reader body re-renders at 22px without reload |
| SET-4 | Article list at `regular` density | Change density to `compact` | Excerpts hidden, rows shorter |
| SET-5 | OPML file with 5 feeds | Account → Import OPML → choose file | 5 new feeds appear in sidebar; success toast |
| SET-6 | Server URL = `http://localhost:3000/` | Change to `http://other:3000/`, save | URL persisted; app re-prompts login against new host |

### Mobile-specific

| ID | Setup | Steps | Expected |
|---|---|---|---|
| MOB-1 | App on Today tab | Tap Saved, Feeds, Settings | Each tab swap is < 200ms; active tab glyph + label in `accent` |
| MOB-2 | Feed screen, article B in list | Tap article B | Full-screen Reader pushed; tab bar hidden; status bar inset respected |
| MOB-3 | Reader open, list was scrolled to 50% | Tap back chevron | Returns to list at 50% scroll |
| MOB-4 | List on All tab | Tap "Long reads" chip | List filtered to items with `minutesToRead >= 10` |

### Error / edge

| ID | Setup | Steps | Expected |
|---|---|---|---|
| ERR-1 | Refresh fails (kill server) | Click refresh | Sidebar footer changes to "Last sync failed · retry" in `ink2` with retry link in `accent` |
| ERR-2 | Filter to a feed with 0 unread | Render | "Nothing here yet." centered serif italic 16px `ink3` in the list column |
| ERR-3 | Stale cookie (server restarted with new secret) | Trigger any API call | Single redirect to login; no infinite-loop |

---

## End-to-end verification (post phase 10)

From repo root:

```sh
( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
```

Expected: `97 passed; 0 failed; 6 ignored` (server), shared ≥22 per platform, web ≥15, android ≥50.

Manual smoke (run the full catalog above):

1. `./gradlew :web:jsBrowserDevelopmentRun` — execute every web-applicable row (AUTH-*, FEED-*, READ-1..7 minus MOB-*, SUBS-*, SET-*, ERR-*).
2. `./gradlew :app:installDebug` — execute every Android-applicable row (AUTH-*, FEED-1..6, READ-*, SUBS-*, SET-*, MOB-*, ERR-*).
3. Open the design HTML at [.claude/plans/new-design/design-files/index.html](.claude/plans/new-design/design-files/index.html) side-by-side and spot-check pixel fidelity per the README's "match them pixel-faithfully" instruction.

The catalog should be checked into a follow-up doc (e.g. `docs/test-scenarios.md`) once phase 10 lands, and used as the source of truth when Playwright (web) and androidTest Compose suites get built out.
