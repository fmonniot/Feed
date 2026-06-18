# Plan: #8 — OPML import UI end-to-end verification

**Date:** 2026-06-17 20:38 PDT

---

## Current state

Both client surfaces exist and the basic plumbing is wired:

| Layer | Status |
|---|---|
| `POST /v1/feeds/import/opml` (server) | Fully implemented; `opml_tests.rs` has 4 passing tests |
| Android file picker → `importOpmlFromUri` → `importOpml(text)` | Wired; shows hint text on the Import OPML row |
| Web file input → `viewModel.importOpml(text)` | Wired; shows status in the Settings row span |
| Summary text | Only `"Imported N of M feeds."` — missing `already_exists`, `failed`, `categories_created` |
| Per-feed failure detail | Not shown on either client |
| End-to-end automated test (any client) | None exist |

The `OpmlImportResult` model already carries all the fields (`total_feeds`, `imported`, `already_exists`, `failed`, `categories_created`, `feeds: List<OpmlFeedResult>`). The ViewModel's `importOpml()` only reads `imported` and `total_feeds`.

## Gap analysis vs acceptance criteria

| Acceptance criterion | State |
|---|---|
| File picker for `.opml` / `.xml` | ✓ both clients |
| File body POSTed to server | ✓ both clients |
| Summary response rendered in a result dialog or screen | ✗ only a brief hint text; not a dialog; missing 3 of 5 fields |
| Per-feed failures scrollable | ✗ not implemented |
| End-to-end test | ✗ neither client |

## Out of scope for this session

**BUG-15** (`server/` side: dropped children, wrong "already exists", N² scans) is tracked separately in Tier 3 background. The client verification plan does not depend on fixing BUG-15 first — tests should use a correct small OPML fixture so the server behaves correctly.

---

## Implementation plan

### Step 1 — Richer summary in `FeedViewModel` (shared)

File: `shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt`

Change `importOpml()` around line 628 to:
1. Build a human-readable summary that includes all non-zero fields, e.g.:
   - "Imported 5 feeds." (simple case)
   - "Imported 3 feeds, 2 already existed." (partial)
   - "Imported 2 feeds, 1 failed." (with failure)
   - "Imported 2 feeds, 1 category created." (with categories)
   - Combine multiple clauses as needed.
2. Add a new `StateFlow<List<OpmlFeedResult>>` named `opmlImportFailures` that holds the failed entries from `result.feeds` (i.e. `result.feeds.filter { it.status == "failed" }`). Expose a `clearOpmlImportFailures()` helper alongside the existing `clearOpmlImportStatus()`.

No new public API surface needed beyond these two flows.

### Step 2 — Android: result dialog

File: `app/src/main/java/eu/monniot/feed/FeedViewModel.kt`

Expose `opmlImportFailures` (delegate to `shared.opmlImportFailures`).

File: `app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt`

After a successful import:
- If `opmlImportFailures` is non-empty, show an `AlertDialog` with:
  - Title: `"Import complete"`
  - Body: the summary string (from `opmlImportStatus`) followed by a scrollable `LazyColumn` listing each failed feed's `title` + `error` message.
  - Single "OK" button that calls `viewModel.clearOpmlImportStatus()` + `viewModel.clearOpmlImportFailures()`.
- If `opmlImportFailures` is empty and `opmlImportStatus` is set, keep the current hint-text approach (no dialog needed for the clean case — the hint is sufficient feedback).

Add `testTag = "opml_result_dialog"` to the dialog container for testability.

### Step 3 — Web: expanded failure list

File: `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt`

After the existing status span (around line 329), add a conditional block: when `viewModel.opmlImportFailures` is non-empty, render a `<ul>` inside the status area listing each failed feed's `title` and `error`. Style with the existing `--feed-err-*` token pair from `tokens.css`. Give the list element `id = SETTINGS_OPML_FAILURES_ID` for testability. Clear on the next import (existing `_opmlImportStatus.value = null` at the top of `importOpml()` already resets; add a parallel reset for the failures list).

No modal needed on web — an inline list below the hint row is consistent with the web's existing inline-feedback pattern.

### Step 4 — End-to-end integration tests

#### Android JVM test

New file: `app/src/test/java/eu/monniot/feed/integration/OpmlImportIntegrationTest.kt`

Use `ServerRule` (real Rust server subprocess) + `FeedAndroidViewModel`. Test cases:

1. **`importOpml_newFeedsAreImportedAndStatusReflectsCount`** — craft a small 3-feed OPML string, call `viewModel.importOpml(opml)`, advance coroutines, assert:
   - `opmlImportStatus.value` contains "Imported 3"
   - `opmlImportFailures.value` is empty
   - Feed list (from `loadFeeds()`) grows by 3
2. **`importOpml_duplicatesReportedInStatus`** — import the same OPML twice, assert second call's status says "already existed" and no new feeds are added
3. **`importOpml_failuresPopulateFailureList`** — import an OPML with a feed whose URL is not reachable (use a syntactically valid but unreachable URL; the server records it as failed if the URL is structurally bad); assert `opmlImportFailures.value` is non-empty

Note: the server's import handler records structural failures (bad URL format) synchronously. Network-unreachable URLs may be treated as "imported" (the feed is created but pending its first fetch). Use a URL like `not-a-valid-url` to force a structural failure if the server validates URLs at import time, or consult `opml_tests.rs` for the failure path.

#### Web jsTest

New file: `web/src/jsTest/kotlin/eu/monniot/feed/web/ui/SettingsOpmlImportTest.kt`

Use a fake `FeedRepository` (pattern: `Fakes.kt` in `shared/src/commonTest/`). Test cases:

1. **`opmlImportShowsSuccessSummaryHint`** — fake returns `OpmlImportResult(total=2, imported=2, already_exists=0, failed=0, categories_created=0, feeds=…)`, render SettingsScreen, trigger import, assert the status span text contains "Imported 2"
2. **`opmlImportShowsFailureListWhenFailed`** — fake returns result with `failed=1` and one `OpmlFeedResult` with `status="failed"` and `error="…"`, assert the `SETTINGS_OPML_FAILURES_ID` list is rendered and contains the feed title
3. **`opmlImportFailureListClearsOnNextImport`** — render, trigger import with failure, then trigger again; assert the failure list is gone before the second result is received

### Step 5 — Shared unit test update

File: `shared/src/commonTest/kotlin/eu/monniot/feed/shared/OpmlImportSummaryTest.kt` (new)

Unit tests for the summary string logic:
- All-imported: `"Imported 5 feeds."`
- With already_exists: `"Imported 3 feeds, 2 already existed."`
- With failed: `"Imported 2 feeds, 1 failed."`
- With categories_created: `"Imported 4 feeds, 1 category created."`
- Multi-clause: `"Imported 2 feeds, 1 already existed, 1 failed."`
- Zero imported: `"0 feeds imported — 2 already existed."`

These are pure string assertions against the summary function extracted from `FeedViewModel.importOpml()` — extract the formatting logic to a package-private function or companion object for easier testing.

---

## File change summary

| File | Change |
|---|---|
| `shared/.../FeedViewModel.kt` | Richer summary text; add `opmlImportFailures` StateFlow |
| `app/.../FeedViewModel.kt` | Expose `opmlImportFailures`; delegate to shared |
| `app/.../ui/settings/SettingsScreen.kt` | Show failure `AlertDialog` when `opmlImportFailures` is non-empty |
| `web/.../ui/SettingsScreen.kt` | Show inline failure `<ul>` when failures present |
| `shared/.../commonTest/.../OpmlImportSummaryTest.kt` | New — summary text unit tests |
| `app/.../test/.../integration/OpmlImportIntegrationTest.kt` | New — Android JVM e2e tests (3 cases) |
| `web/.../jsTest/.../SettingsOpmlImportTest.kt` | New — web jsTests (3 cases) |

---

## Acceptance check

When all tests pass and the dialog/list renders correctly:
- Flip `#8` status from `[?]` to `[x]` in `TICKETS.md`
- Remove the `#8` line from `NEXT.md` Tier 2

BUG-15 (server-side OPML correctness) remains in Tier 3 as-is; it does not block closing #8.
