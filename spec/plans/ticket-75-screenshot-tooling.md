# #75 — Screenshot tooling + design-accuracy sweep

**Date:** 2026-06-18 07:16 PDT

## Why

#67/#70/#71/#72 were built directly from the exact pixel targets in
[VISUAL_SPEC.md](../VISUAL_SPEC.md) and still drifted off-spec. The bottleneck is
not target precision — it is the missing **verification loop**: whoever writes
the UI never sees the rendered result next to the reference, so there is no way
to measure error and correct it. This ticket builds that loop.

Gate decision (recorded in [NEXT.md](../../NEXT.md) and TICKETS.md #75):
Part 1 (tooling) is a hard prerequisite for the Visual polish cluster, and a
screenshot-vs-reference comparison becomes the definition-of-done for every
visual item.

## Goal

A repeatable, documented way to put **(a)** a screenshot of the running app and
**(b)** the corresponding design reference into a Claude session side-by-side —
for both clients (web + Android) — with seeded content so screens are non-empty.

## The two reference surfaces

- **Live app** — web dev bundle on `:8080` (proxies API to the Rust server on
  `:3000`); Android debug build on an emulator.
- **Design reference** — the JSX prototypes in
  [spec/story-board/prototypes/](../story-board/prototypes/) (`editorial.jsx`,
  `editorial-mobile.jsx`, `login.jsx`, `edge-cases*.jsx`), served by
  `spec/story-board/run.sh` (python http server on `:6789`). These render in a
  browser, so the same web screenshot tool can capture them too.

## Plan

### Part 1a — Web screenshot script  ·  `scripts/shot-web.sh` + helper

- A small **Playwright** (Node) script: `scripts/shots/web-shots.mjs`.
  - Pinned viewport presets: desktop `1280×900` (the `≥1100px` layout) and a
    narrow `900×900` to catch the breakpoint.
  - Navigates the hash routes from [Router.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/Router.kt):
    `#login`, `#list` (Unread), `#all`, `#feed/<id>`, `#article/<id>`,
    `#subscriptions`, `#settings`, plus the reader font-size popover state.
  - Writes PNGs to `build/.shots/web/<route>.png` (gitignored).
- `scripts/shot-web.sh` orchestrates: ensure server + seed (Part 1c) + web dev
  bundle are up, then run the Playwright script. Fail with a clear message if a
  prerequisite port is down rather than producing blank shots.
- Playwright is a dev-only dependency installed under `scripts/shots/` (its own
  `package.json`); it is **not** added to the `web/` Gradle/npm graph. Document
  the one-time `npm install` + `npx playwright install chromium`.

### Part 1b — Android screenshot script  ·  `scripts/shot-android.sh`

- Thin wrapper over `adb exec-out screencap -p > build/.shots/android/<name>.png`
  using the SDK adb at `~/Library/Android/sdk/platform-tools/adb` (honor
  `$ANDROID_HOME`/`$ANDROID_SDK_ROOT` if set).
- Preconditions checked with clear errors: a device/emulator is connected
  (`adb devices`), and the app is installed.
- Navigation: the screens (Unread, All, Reader, Feeds, Settings, Login) are
  driven **manually** for the first pass — the script captures on demand
  (`shot-android.sh <name>`). A follow-up option (noted, not built here) is an
  instrumented `androidTest` that drives navigation and captures, if manual
  proves too slow.

### Part 1c — Seed helper  ·  `scripts/shots/seed.sh`

- Screens are meaningless empty. Seed the running dev server via its REST API
  (login → add a handful of feeds → trigger a refresh), reusing the sample
  feeds that [server/src/test_utils.rs](../../server/src/test_utils.rs)
  (`with_sample_data`) already defines so live and test data match.
- Point feeds at a local fixture rather than the network for determinism
  (reuse a `MockFeedServer`-style static file, or commit a small static RSS
  fixture served by python). Keep it minimal — enough rows for list/reader/feeds
  screens to look real.

### Part 1d — Reference capture + docs

- `scripts/shot-ref.sh`: start `spec/story-board/run.sh` and use the same
  Playwright script to capture the prototype screens to `build/.shots/ref/`, so
  app and reference shots sit in matching folders for side-by-side review.
- Document the whole workflow in [CONTRIBUTING.md](../../CONTRIBUTING.md) under
  a new "Screenshots / design verification" subsection.
- Add `build/.shots/` to `.gitignore`; allowlist the new scripts in
  [.claude/settings.local.json](../../.claude/settings.local.json).

### Part 2 — Lightweight design-accuracy sweep (the useful core of #75 Part 2)

- Run the tooling against the current build; capture app + reference shots.
- Compare each screen to its prototype/VISUAL_SPEC target. **Re-verify
  #67/#70/#71/#72 first** — confirm the specific drift with before/after shots.
- Confirm or file discrepancies as tickets. Do **not** rewrite acceptance
  criteria wholesale — VISUAL_SPEC.md already holds the targets; the sweep
  records *rendered vs. target* deltas.

## Validation (per the project testing requirement)

The deliverables are scripts/tooling, not product logic. Validation:

1. **Scripts produce non-blank PNGs** at the expected paths for at least the web
   client — demonstrable and re-runnable (`scripts/shot-web.sh` then list the
   output dir). This is the automated check for the web path.
2. **Android path is manual-verification** (requires a running emulator):
   `shot-android.sh list` yields a real screenshot. Stated explicitly as manual.
3. Seed helper validated by the web shots being non-empty (articles visible).
4. No changes to product code, so existing test suites are unaffected; run
   `./scripts/test-counts.sh` only if any shared/web file is touched (not
   expected).

## Out of scope / follow-ups

- Automated pixel-diff / visual-regression assertions in CI (this ticket is
  about *seeing*, not gating CI). Note as a possible #75 follow-up.
- Instrumented-test-driven Android navigation capture (fallback if manual is
  painful).

## Scope note

Tooling + docs only on the `ticket-75-screenshot-tooling` branch. The actual
visual fixes (#67/#43/#44/#67-73, BUG-20) are separate items scheduled *after*
this lands, each verified with this tooling.
