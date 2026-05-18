# New-design rollout — orchestrator progress

**Date:** 2026-05-17 13:24 PDT

Tracking phase landings on the `new-design` branch (will merge to `main` at the end after the user's UI review).

| Phase | Title | Squash SHA | Tests after | Notes |
|---|---|---|---|---|
| 1 | Shared module — extend models & repository surface | `25f3f8b` | shared 39/platform · web 11 · android 50 · server 97 | `FeedHue` uses `ushr 1` (not `abs()`) to avoid `Int.MIN_VALUE` overflow. Custom `TestSettings` (no new test artifact dep). |
| 2 | Local user prefs (multiplatform-settings) | `12d9fad` | shared 75/platform · web 11 · android 50 · server 97 | Per-field `updateXxx()` setters (not a single polymorphic `updatePref`) — type-safe Kotlin idiom. Touched two existing app/ integration tests to satisfy the new VM constructor. |
| 3 | Web tokens, fonts, kotlinx.html scaffolding | `bde3fae` | web 11→16; others unchanged | Used `attributes["class"]` instead of `kotlinx.html`'s `classes` prop (latter is `Set<String>` and didn't compile cleanly here). Equivalent at DOM level. |
| 4 | Web Feed + Reader (three-column) | `fb6c134` | web 16→56; others unchanged | Added `FeedViewModel.articleItems: StateFlow<List<ArticleItem>>` to shared (outside phase scope but required — `viewModel.items` returns the older `RssItem` projection without `feedId`/`feedHue`/`excerpt`/...). HTML body sanitized via allowlist (p/a/strong/em/blockquote/ul/ol/li/img/h2/h3). |
| 5 | Web Subscriptions | `03e8b27` | web 56→78; others unchanged | Added `FeedViewModel.setFeedCategory` (was listed for Phase 1 but never landed there). `FeedUiItem` still lacks `categoryId` — folder-name column renders empty in production; tested via an injection seam. Fixed sidebar's "Subscriptions" link (was pointing at Settings). |
| 6 | Web Settings | `6fdc734` | web 78→88; others unchanged | Settings rewrite + new SegmentedControl helper. Wired OPML import end-to-end: `FeedApi.importOpml` → `FeedRepository.importOpml` → web + android impls → `FeedViewModel.importOpml` + `opmlImportStatus` flow. |
| 7 | Android Compose theme overhaul | `c73b73b` | android 50→73; others unchanged | `FeedDot` uses HSL approximation of OKLCH with a `TODO(phase-8)` note. Existing screens left untouched — `FeedTheme`'s `MaterialTheme` bridge maps Paper tokens to Material slots so legacy `MaterialTheme.colorScheme` reads transparently inherit Paper. |
| 8 | Android Feed screen + tab bar | `4473443` | android 73→81 (+8 tests, 1 `@Ignore`d); others unchanged | Tab-bar nav test `@Ignore`d (Robolectric NavHost without Activity isn't feasible at unit scope; covered later by instrumented). Independently added same `articleItems` field as Phase 4 — auto-merge took both copies, manually dropped the duplicate. `HomeScreen` retained with TODO; Phase 9/10 finalize cleanup. |
| 9 | Android Reader screen | `46fd886` | android 81→87 (+6 tests, 1 still `@Ignore`d); others unchanged | Worktree isolation didn't take — agent committed directly on `new-design`. Content is clean. WebView article view removed; reader is a top-level route outside the tab shell (bar hides while reading). Uses `LinkAnnotation.Url`. Body font size pulled from `UserPrefs.fontSize`. |
| 10 | Android Subscriptions + Settings | `990a837` | android 87→103 (+16 tests; still 1 `@Ignore`d from Phase 8); others unchanged | Worktree isolation didn't take — agent committed directly on `new-design` (same as Phase 9). Added `FeedUiItem.categoryId` to shared (closing the gap Phase 5 had flagged); subscriptions group by category with an "Uncategorized" group for nulls. Settings uses Modal bottom-sheet pickers. Removed `HomeScreen` and the old inline `FeedsScreen`/`SettingsScreen`. |

## Final test totals (post Phase 10, on `new-design` HEAD `6fdc734`)

| Target | Passed | Failed | Skipped/Ignored |
|---|---|---|---|
| server (`cargo test`) | 97 | 0 | 6 ignored |
| android (`:app:testDebugUnitTest`) | 103 | 0 | 1 `@Ignore`d (Phase 8: `tabBarPersistsAcrossTabSwitch` — Robolectric NavHost without Activity isn't feasible at unit scope; covered later by instrumented testing under `app/src/androidTest/`) |
| shared-js | 75 | 0 | 0 |
| shared-wasmjs | 75 | 0 | 0 |
| web (`:web:jsTest`) | 88 | 0 | 0 |

## Known deferred items

These are not bugs in the implementation; they are explicit deferrals to be addressed in a follow-up pass once the user validates the UI:

- `FeedDot` uses an HSL approximation of `oklch(0.65 0.12 hue)` on Android (Phase 7 → 10). Visually close but not pixel-exact. Web uses real `oklch()` since browsers support it.
- The "tag" column shown next to feed names in the web Subscriptions screen renders the **folder name** (per the plan's design-decision #2 dropping `tag`). The README's mock shows distinct tag strings (e.g. "Design", "Tech"); reintroducing a separate `tag` field would need a server schema change.
- Phase 8's one `@Ignore`d test (tab-bar persistence across NavHost switches) needs an instrumented test, not a Robolectric unit test.
- Phase 9's `⎙` (share) top-bar button is a stub — wiring it to `Intent.ACTION_SEND` was not in the plan's Phase 9 scope.

## Worktree cleanup

Eight locked worktrees remain under `.claude/worktrees/`. They are not blocking anything and can be removed at the user's convenience with `git worktree remove --force <path> && git branch -D <branch>`. Phases 9 and 10 committed directly to `new-design` (their worktrees were never created), so only the other eight have associated branches.

## Parallelism note

Phases 1 and 2 are sequential foundation. From Phase 3 onward, the web track (3→6) and the android track (7→10) run in parallel as two concurrent worktrees branched off `new-design`. Each track is internally sequential.
