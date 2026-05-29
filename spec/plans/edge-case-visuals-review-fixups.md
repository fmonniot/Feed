**Date:** 2026-05-25 20:42 PDT

# Edge-case visuals (#48–#62) — review fix-ups

## Context

The branch `implement-the-design-improvements` lands TODO tickets #48–#62 (the
edge-case visuals group from #46). All 15 tickets are marked `[x]` in
[TODO.md](../../TODO.md), all four test suites are green (server 119, shared 115,
web 294, android 198), and `spec/FEATURES.md` has been brought in line with each
ticket's status-flag promise.

Four independent module reviews surfaced no merge blockers but a punch list of
behavioural bugs, perf concerns, a11y gaps, and architectural drift items. This
plan turns those findings into independently shippable tasks so multiple Sonnet
sessions can work in parallel without colliding.

Each task below names the files it touches; tasks in different "Touch:" buckets
are safe to run concurrently. The "Depends on:" line flags the few cases where
order matters.

Per [CLAUDE.md](../../CLAUDE.md): **every code change must be validated by a
test** — each task's acceptance criteria spell out which test must be added or
which existing test must continue to pass.

## Task index

| ID | Priority | Scope | Touch | Module | Depends on |
|---|---|---|---|---|---|
| F1 | P0 | sm | shared `FeedViewModel.kt` + tests | shared | — |
| F2 | P0 | sm | android `MainActivity.kt` + `SubscriptionsScreen.kt` + test | android | — |
| F3 | P0 | md | server `fetcher.rs` + tests | server | — |
| F4 | P1 | sm | android `FeedScreen.kt` (snackbar wiring) | android | — |
| F5 | P1 | md | web `ModalInterrupt.kt` + `SessionExpiredModal.kt` + tests | web | — |
| F6 | P1 | sm | web `Banner.kt` + `FeedbackComponents.kt` + tests | web | — |
| F7 | P2 | md | web `RawResponseInspector.kt` **+** android `RawResponseInspectorScreen.kt` + tests | both | — |
| F8 | P2 | sm | web `Sidebar.kt` + `SidebarFooter.kt` | web | — |
| F9 | P2 | sm | shared `FeedViewModel.kt` (refresh concurrency) | shared | F1 lands first |
| F10 | P3 | md | server nits roll-up | server | F3 lands first |
| F11 | P3 | sm | shared nits roll-up | shared | F1+F9 land first |
| F12 | P3 | sm | web nits roll-up | web | F5+F6+F8 land first |
| F13 | P3 | sm | android nits roll-up | android | F2+F4+F7 land first |

P0 = user-visible correctness bug; P1 = architectural drift / a11y; P2 =
perf/quality; P3 = nits roll-up.

---

## F1 — Cancel the rate-limit timer on successful refresh `[x]`

**Priority:** P0  **Scope:** sm  **Touch:** shared only

After the client sees a 429 with a 60-minute Retry-After and the next
`refresh()` happens to succeed, the "rate-limited" banner persists for the full
hour because the success path never cancels the active `rateLimitJob` or clears
`_rateLimitedUntil` / `_rateLimitDuration`. `handleRateLimit` cancels the prior
job when a *new* 429 comes in, but the success branch does not.

**Files**

- [shared/.../FeedViewModel.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt) — success branch around lines 245-278
- [shared/.../FeedViewModelRateLimitTest.kt](../../shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelRateLimitTest.kt) — add coverage

**Acceptance criteria**

- In the success branch of `refresh()`, cancel `rateLimitJob` if active and clear
  both `_rateLimitedUntil` and `_rateLimitDuration` to null.
- New test in `FeedViewModelRateLimitTest.kt`: trigger 429 → assert
  `rateLimitedUntil` is set → trigger a second `refresh()` that succeeds → assert
  `rateLimitedUntil` is null and `rateLimitDuration` is null **before** the
  Retry-After elapses (use `TestDispatcher.advanceTimeBy` for determinism).
- `./gradlew :shared:allTests` stays green (was 115 passed; new test should make
  it 116).

---

## F2 — Two small Android dialog fixes `[x]`

**Priority:** P0  **Scope:** sm  **Touch:** android only

Two independent one-liners that together harden the dialog story:

1. **Dead-feed mid-pane is clamped to a phone-sized card.** `BigMidPaneDeadFeed`
   is rendered inside a `Dialog` without `DialogProperties(usePlatformDefaultWidth = false)`,
   so Compose clamps it to platform-default width (small alert-dialog) and the
   "big" mid-pane truncates on phone widths. Compare to
   [ModalInterrupt.kt:65](../../app/src/main/java/eu/monniot/feed/ui/theme/ModalInterrupt.kt#L65)
   which already sets the flag correctly.
2. **`SessionExpiredDialog` can stack over the login screen.** It's hoisted
   above the `NavHost`, so a 401 fired while the user is on the `"login"`
   destination (e.g. server-config-after-logout flow) would render
   "you've been signed out" over the sign-in form.

**Files**

- [app/.../ui/subs/SubscriptionsScreen.kt:365-385](../../app/src/main/java/eu/monniot/feed/ui/subs/SubscriptionsScreen.kt#L365-L385)
- [app/.../MainActivity.kt:85-91](../../app/src/main/java/eu/monniot/feed/MainActivity.kt#L85-L91)

**Acceptance criteria**

- The dead-feed `Dialog` uses `DialogProperties(usePlatformDefaultWidth = false)`
  and sizes via the same `fillMaxWidth(0.95f)` (or similar) pattern as
  `ModalInterrupt`.
- `SessionExpiredDialog` is gated so it only renders when the user is on a
  logged-in destination (current route ≠ `"login"`, or equivalent).
- Extend [SessionExpiredDialogTest.kt](../../app/src/test/java/eu/monniot/feed/ui/SessionExpiredDialogTest.kt)
  with a case: navigation is on login → session-expired username is set →
  dialog is **not** shown. (Hint: drive the dialog with a state object instead
  of asserting through the real NavController.)
- `./gradlew :app:testDebugUnitTest` stays green; new test bumps count from 198
  to 199.

---

## F3 — Server fetcher.rs hardening `[ ]`

**Priority:** P0  **Scope:** md  **Touch:** server only

Three correctness/safety issues in
[server/src/fetcher.rs](../../server/src/fetcher.rs):

1. **UTF-8 panic** ([:207-210](../../server/src/fetcher.rs#L207-L210)) —
   `&s[..MAX_RAW_BODY_BYTES]` slices a `&str` on a raw byte boundary. If the
   256 KB cutoff lands inside a multi-byte codepoint, the runtime panics. Use
   `s.char_indices()` to find the nearest preceding boundary, or
   `floor_char_boundary` on a recent enough toolchain.
2. **Silent error swallowing** ([:277-279](../../server/src/fetcher.rs#L277-L279)) —
   the per-article HEAD probe drops both network failures (`Err(_) =&gt; {}`)
   and the result of `db.update_...` (`let _ = ...`). A timeout looks identical
   to "never probed." Log via `tracing::warn!` at minimum; consider writing a
   sentinel `link_status` (e.g. `-1`) so the client can distinguish "unreachable"
   from "never checked."
3. **Per-article HEAD probe runs serially inside the fetch loop**
   ([:265-280](../../server/src/fetcher.rs#L265-L280)) — every new article adds
   one synchronous HEAD on top of the GET. A fresh feed with 50 new items now
   blocks for 50 sequential HEADs before returning. **Mitigation for this PR:**
   bound the work to keep the scheduler tick fast — skip non-`http(s)` schemes
   and add a short per-probe timeout (e.g. 5s). A proper out-of-band probe job
   is out of scope; capture it as a follow-up ticket in [TODO.md](../../TODO.md).

**Files**

- [server/src/fetcher.rs](../../server/src/fetcher.rs)
- [server/src/fetcher_tests.rs](../../server/src/fetcher_tests.rs)

**Acceptance criteria**

- New test in `fetcher_tests.rs`: feed body of length exactly
  `MAX_RAW_BODY_BYTES - 1` followed by a 4-byte UTF-8 codepoint does not panic
  on truncation (i.e. raw_body is stored cleanly at the previous char boundary).
- Probe errors emit `tracing::warn!` with the article URL and the reqwest error;
  unit test asserts the warn was emitted (use `tracing-test` or
  `tracing_subscriber::fmt::TestWriter`).
- Probe path skips `mailto:`, `magnet:`, schemeless URLs without crashing.
- A new follow-up TODO row is added at the bottom of
  [TODO.md](../../TODO.md) describing the out-of-band probe job (do not
  implement it here — just file it).
- `cd server && cargo test` reports `120+ passed; 0 failed; 5 ignored`.

---

## F4 — Wire `FeedSnackbar` into Android's `SnackbarHost` `[ ]`

**Priority:** P1  **Scope:** sm  **Touch:** android only

Ticket #49 shipped
[FeedSnackbar.kt](../../app/src/main/java/eu/monniot/feed/ui/theme/FeedSnackbar.kt)
and 5 unit tests for it, but production code still uses the Material `Snackbar`
with `colors.ink`/`colors.panel` overrides at
[app/.../FeedScreen.kt:242-247](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L242-L247).
That defeats #49's stated goal of decoupling the visual treatment from Material
defaults.

**Files**

- [app/.../ui/feed/FeedScreen.kt](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt) — replace the inline Material `Snackbar` with `FeedSnackbar`
- Audit any other call sites that hand-build a snackbar (grep `SnackbarHost {`
  across `app/src/main/`)

**Acceptance criteria**

- `SnackbarHost(snackbarHostState) { data -&gt; FeedSnackbar(data, tone = …) }` is
  the only snackbar rendering path in production code. Hand-built `Snackbar(…)`
  call sites are gone.
- Tone is derived from the snackbar's action label or a wrapper data class —
  the four call sites (offline, parse-fail, rate-limit, sync-failed) should map
  to the right tone (info / err / warn / err).
- Add an integration-style test in
  [FeedScreenTest.kt](../../app/src/test/java/eu/monniot/feed/ui/feed/FeedScreenTest.kt)
  (or similar) that asserts the `FeedSnackbar` semantic tag appears for at
  least one error scenario.
- `./gradlew :app:testDebugUnitTest` green.

---

## F5 — Web `ModalInterrupt` accessibility `[ ]`

**Priority:** P1  **Scope:** md  **Touch:** web only

[ModalInterrupt.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/ModalInterrupt.kt)
and the consumer
[SessionExpiredModal.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SessionExpiredModal.kt)
lack the bare-minimum a11y surface for a portal-based modal: no
`role="dialog"`, no `aria-modal="true"`, no `aria-labelledby` linking to the
title, no ESC-to-dismiss (for the cancellable case), no focus trap, no focus
restoration on close.

**Files**

- [web/.../ui/components/ModalInterrupt.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/ModalInterrupt.kt)
- [web/.../ui/SessionExpiredModal.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SessionExpiredModal.kt) — verify it inherits the new attrs
- [web/.../ModalInterruptTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/components/ModalInterruptTest.kt) and [SessionExpiredModalTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/SessionExpiredModalTest.kt) — assert the new attrs

**Acceptance criteria**

- The root container has `role="dialog"` and `aria-modal="true"`. The title
  gets an `id` and the container references it via `aria-labelledby`.
- Initial focus lands on the primary action button when the modal opens.
- Focus is restored to the previously-focused element on close. Use a captured
  `document.activeElement` snapshot at open.
- Focus trap: Tab / Shift+Tab cycle within the modal (find first/last
  focusable, wrap around).
- ESC closes the modal **only if** a `secondary` action is provided (the
  session-expired case has both Sign-in-again and Forget-this-device, so ESC
  invokes the secondary). Document this contract at the function signature.
- Tests cover: dialog/aria attrs present, primary button focused on mount, ESC
  invokes secondary callback, focus restoration after unmount.
- `./gradlew :web:jsTest` green; count rises from 294 to 294 + new tests.

---

## F6 — Web banner / inline-feedback `aria-live` `[ ]`

**Priority:** P1  **Scope:** sm  **Touch:** web only

Offline / rate-limit / parse-fail banners and inline reader notes are exactly
the surfaces screen-reader users need to hear announced. None currently carry
`role="status"` / `role="alert"` / `aria-live`.

**Files**

- [web/.../ui/components/Banner.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/Banner.kt)
- [web/.../ui/components/FeedbackComponents.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/FeedbackComponents.kt) — `InlineReaderNote`, `InlineFormError`
- [web/.../BannerTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/components/BannerTest.kt) and [FeedbackComponentsTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/components/FeedbackComponentsTest.kt)

**Acceptance criteria**

- `Banner` with tone=err uses `role="alert"` + `aria-live="assertive"`; warn/info
  use `role="status"` + `aria-live="polite"`. (`alert` implies polite=assertive,
  so the redundant `aria-live` is for defensive support across older AT.)
- `InlineFormError` (err tone) carries `role="alert"`; `InlineReaderNote` is
  `role="note"`.
- Tests assert the attrs per tone.
- No regressions in `./gradlew :web:jsTest`.

---

## F7 — Raw-response inspector perf, both platforms `[ ]`

**Priority:** P2  **Scope:** md  **Touch:** web **and** android (coordinated)

Same root issue on both platforms: the inspector renders one DOM node /
Compose row per line of `raw_body` eagerly. On a 5 MB malformed body that's
tens of thousands of nodes injected in one frame. Also, both copies cap the
caret at `minOf(col, 8)` for no documented reason, and the Android version has
no `BackHandler` and a never-invoked `onRetry` param.

**Files**

- [web/.../ui/components/RawResponseInspector.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/RawResponseInspector.kt) (line gutter + caret cap + perf)
- [app/.../ui/inspector/RawResponseInspectorScreen.kt](../../app/src/main/java/eu/monniot/feed/ui/inspector/RawResponseInspectorScreen.kt) (same)
- Their test files

**Acceptance criteria**

- **Web:** either virtualize the line list (only render the visible window via
  `IntersectionObserver`-driven mounting) or clamp displayed lines to a
  ±200-line window centred on `error_line`, with an "Expand" affordance to
  show more. The clamp is the smaller scope; pick it unless you're confident
  about virtualization in plain DOM.
- **Android:** convert the source-view `Column` into a `LazyColumn`. Add a
  `BackHandler` that pops the navigation entry. Either wire `onRetry` to a
  visible action in the meta strip or delete the param.
- Both platforms: caret count is exactly 1 (a single `^` pointing to
  `error_col`), not `"^".repeat(min(col, 8))`. If the original intent was a
  run-of-carets underline, document it; otherwise remove the cap.
- Both platforms: new test exercises the large-body path (e.g. 2,000-line raw
  response) and asserts the render completes and the error line is in the
  scroll target.
- All four suites green.

---

## F8 — Web sidebar perf `[ ]`

**Priority:** P2  **Scope:** sm  **Touch:** web only

[Sidebar.kt:146-167](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt#L146-L167)
combines six flows and rebuilds the footer DOM on every change. Two issues:

1. No `distinctUntilChanged` on the derived `SyncStatus`, so every upstream
   flicker re-runs the footer's `replace()` pipeline.
2. `@Suppress("UNCHECKED_CAST")` on positional `values[i] as Boolean` casts is
   fragile; adding a future flow would compile but crash at runtime.

**Files**

- [web/.../ui/feed/Sidebar.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt)

**Acceptance criteria**

- The derived `SyncStatus` flow is `.distinctUntilChanged()`.
- The six-way `combine` uses the typed N-arg overload (or a small data class
  carrier), removing the cast suppression.
- Behaviour-preserving — no test changes required if existing
  [SidebarFooterTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/components/SidebarFooterTest.kt)
  still passes. Add one test that asserts a no-op upstream change does not
  trigger a re-render (e.g. via a `data-render-count` attr or an injected
  spy).

---

## F9 — Shared `_consecutiveFailures` race `[ ]`

**Priority:** P2  **Scope:** sm  **Touch:** shared only  **Depends on:** F1

[FeedViewModel.kt:254, 269](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L254)
mutates `_consecutiveFailures.value++` — a non-atomic read-modify-write. On JVM
(Android), two concurrent `refresh()` calls (e.g. two rapid pull-to-refresh
gestures) can under-count failures and skip the `&gt;= 3` threshold that drives
ERR-5.

**Files**

- [shared/.../FeedViewModel.kt](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt)
- [shared/.../FeedViewModelSyncStateTest.kt](../../shared/src/commonTest/kotlin/eu/monniot/feed/shared/FeedViewModelSyncStateTest.kt)

**Acceptance criteria**

- Either: (a) gate `refresh()` on `_isRefreshing.value` — short-circuit if a
  refresh is in flight; or (b) use a `compareAndSet` loop on `MutableStateFlow`.
  Option (a) is preferred — concurrent refresh is not a user-meaningful
  operation.
- New test: launch two parallel `refresh()` coroutines that both fail; assert
  `_consecutiveFailures.value == 1` (not 2, because the second call should
  short-circuit), and assert only one network call was made on the fake repo.
- Coordinate with F1 — both touch the success path of `refresh()`. F9 should
  rebase on F1, not the other way around.

---

## F10 — Server nits roll-up `[ ]`

**Priority:** P3  **Scope:** md  **Touch:** server only  **Depends on:** F3

Bundle the non-blocking server findings into one session:

- **Clear active `parse_error` on a 410 transition.** In
  [server/src/db.rs:817-836](../../server/src/db.rs#L817-L836) `increment_feed_410`,
  also `DELETE FROM feed_parse_errors WHERE feed_id = ?`. Add a unit test.
- **N+1 in `FeedWithUnread::new`.**
  [server/src/db.rs:104-117](../../server/src/db.rs#L104-L117) does
  `get_parse_error` per feed. Replace with a `LEFT JOIN` against
  `feed_parse_errors` in `get_feeds_with_unread` and
  `get_uncategorized_feeds_with_unread` (db.rs:1258, 1383). Performance-only;
  existing tests should still pass; add one assertion on the row count of SQL
  statements executed (or just verify behaviour).
- **Add a route test for `get_feed_parse_error_handler`** at
  [server/src/api/handlers.rs:296-307](../../server/src/api/handlers.rs#L296-L307).
  Currently the DB layer is covered but not the handler's `ApiError::NotFound`
  mapping.
- **Tighten `extract_line_col`** at
  [server/src/fetcher.rs:395-409](../../server/src/fetcher.rs#L395-L409) — match
  `\bline\s+(\d+)` rather than the first occurrence of `"line "`. Add a unit
  test with an error string containing "outline" or "underline" to confirm the
  regex isn't fooled.
- **Document the SQLite ≥ 3.35 requirement** for the migration test in
  [server/src/db_tests.rs:1258-1274](../../server/src/db_tests.rs#L1258-L1274) —
  add a one-line comment + a note in
  [CONTRIBUTING.md](../../CONTRIBUTING.md).

**Acceptance criteria**

- Server test count grows by at least 3 (one per fix that adds a test); all
  pass.
- One row added to [CONTRIBUTING.md](../../CONTRIBUTING.md) under prerequisites
  (or wherever SQLite is mentioned).

---

## F11 — Shared nits roll-up `[ ]`

**Priority:** P3  **Scope:** sm  **Touch:** shared only  **Depends on:** F1 + F9

- Tighten the `serverFeedStatus` doc-comment at
  [FeedViewModel.kt:71](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L71)
  and [Models.kt:42-43](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt#L42-L43)
  to include `"parse_error"`.
- Add a one-line note in
  [FeedRepository.kt:25](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedRepository.kt#L25)
  that `linkStatus` is populated by per-platform repository impls, not in
  shared.
- Treat empty-string username as null in
  [FeedViewModel.kt:207-211](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L207-L211)
  so the session-expired modal never renders with a blank identity panel.
- Decide on duplicate-detection normalization at
  [FeedViewModel.kt:415](../../shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L415) —
  trailing slash + http/https are the obvious near-misses. **Default
  recommendation:** leave the client check as-is (exact string match) and
  rely on the server's uniqueness constraint to catch normalized duplicates
  with a friendlier error, unless this is causing user-visible duplicates.
  Either way, add a comment explaining the decision.
- **Test fixture dedup.** Extract `FakeFeedRepository` and `InMemorySettings`
  into a shared `commonTest` helper file consumed by all four new
  `FeedViewModel*Test.kt` files. ~300 lines of dupe removed.

**Acceptance criteria**

- Tests still pass; refactor should not change behaviour.
- New helper file lives at
  `shared/src/commonTest/kotlin/eu/monniot/feed/shared/test/Fakes.kt` (or
  similar) and is used by all `FeedViewModel*Test.kt`.

---

## F12 — Web nits roll-up `[ ]`

**Priority:** P3  **Scope:** sm  **Touch:** web only  **Depends on:** F5 + F6 + F8

- `formatBytes` integer truncation at
  [RawResponseInspector.kt:444-447](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/RawResponseInspector.kt#L444-L447) —
  use proper rounding so 1.99 MB doesn't display as `1.0 MB`.
- Add `aria-label="parse error"` to the `!` badge at
  [Sidebar.kt:325-341](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/Sidebar.kt#L325-L341)
  so screen readers don't announce just "exclamation."
- Replace `GlobalScope.launch` collectors in
  [FeedScreen.kt:158-180, 221-243](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt#L158-L180)
  with a screen-scoped `Job` that is cancelled on unmount.
- Clean up the awkward `addEventListener("click", { it.preventDefault(); status.onRetry() })`
  shape at [SidebarFooter.kt:172](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/SidebarFooter.kt#L172) —
  use an explicit block.
- Drop dead `// ↻` / `// ○` glyph-comment lines at
  [SidebarFooter.kt:129, 135](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/SidebarFooter.kt#L129).
- Dead-feed mid-pane: `Pair&lt;String, String&gt;` with empty href + selector
  wiring is brittle ([BigMidPaneState.kt:280-281](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/BigMidPaneState.kt#L280-L281)).
  Switch to a nullable `onClick` slot or a sealed action type.

**Acceptance criteria**

- `./gradlew :web:jsTest` green.
- One test asserting the `!` badge has `aria-label`.

---

## F13 — Android nits roll-up `[ ]`

**Priority:** P3  **Scope:** sm  **Touch:** android only  **Depends on:** F2 + F4 + F7

- Add a `danger` slot to `FeedColors` at
  [Color.kt:36](../../app/src/main/java/eu/monniot/feed/ui/theme/Color.kt#L36)
  and route the existing `MaterialTheme.colorScheme.error` fallbacks
  ([MainActivity.kt:199](../../app/src/main/java/eu/monniot/feed/MainActivity.kt#L199),
  [SubscriptionsScreen.kt:523](../../app/src/main/java/eu/monniot/feed/ui/subs/SubscriptionsScreen.kt#L523))
  through it for consistency with the Paper palette.
- Dedupe the two `InlineReaderNote` overloads at
  [FeedbackComponents.kt:104-123](../../app/src/main/java/eu/monniot/feed/ui/theme/FeedbackComponents.kt#L104-L123) —
  extract a private body composable taking `AnnotatedString`, let the String
  overload wrap.
- Wrap the `parseErrorFeedId` derivation at
  [FeedScreen.kt:135-137](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L135-L137)
  in `remember(feeds)` for consistency with the surrounding code.
- Don't dim the `!` badge inside the dead-feed row at
  [SubscriptionsScreen.kt:430](../../app/src/main/java/eu/monniot/feed/ui/subs/SubscriptionsScreen.kt#L430) —
  apply the 0.55 alpha to text only.
- Either honour or default the `tone` param in
  [FeedSnackbar.kt:35](../../app/src/main/java/eu/monniot/feed/ui/theme/FeedSnackbar.kt#L35)
  (current behaviour: "required but ignored" is the worst of both worlds). If
  F4 has wired tones through, this may already be resolved.
- Add a Room migration 4→5 test at
  [FeedRepository.kt:115](../../app/src/main/java/eu/monniot/feed/FeedRepository.kt#L115)
  using `MigrationTestHelper` to verify the `linkStatus` column landing.

**Acceptance criteria**

- `./gradlew :app:testDebugUnitTest` green.
- One new migration test passing.

---

## Sequencing for parallel sessions

These groups can run in parallel safely:

- **Wave 1 (no deps):** F1, F2, F3, F4, F5, F6, F7, F8 — 8 sessions
- **Wave 2 (after Wave 1):** F9, F10, F11, F12, F13 — 5 sessions

If only one Opus session, F1 → F2 → F3 → F5 → F4 → F7 in priority order is a
reasonable serial path; the nits roll-ups can wait for a follow-up branch.

## Out of scope

- The "out-of-band link probe job" filed in F3 — separate ticket.
- Any pixel-perfect spec audit against
  [story-board/prototypes/edge-cases.jsx](../story-board/prototypes/edge-cases.jsx) —
  this branch ships the behaviour; visual review happens during manual QA.
- Re-doing the `[VISUAL_SPEC.md §Snackbar priority]` (offline > parse-fail
  ordering) — current behaviour is "highest-priority signal wins indefinitely"
  which is defensible.
