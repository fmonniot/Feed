# Feed — Tickets

Backlog of tickets, organized by **classification** (P0 → P4). Reference tickets by their numeric ID (e.g. "work on #3"). Numeric IDs are stable; gaps from closed/superseded tickets are intentional.

Status legend: `[ ]` open · `[~]` in progress · `[x]` done · `[-]` closed without action · `[?]` needs verification

Session order is in [NEXT.md](NEXT.md) — P-levels here describe classification, not necessarily the order to tackle them.

---

## P0 — Unblockers

*Nothing currently blocking.*

---

## P1 — Spec gap fixes

These close the `⚠` / `✗` rows in [spec/FEATURES.md](spec/FEATURES.md). Groups below are sized to fit one session each.

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

### Group: Edge-case visuals (from #46)

Implementation follow-ups for the spec landed by [#46](#46--audit-and-spec-non-happy-path-styles-from-claude-design-). Cluster A (#48–#53) ships the reusable primitives; Cluster B (#54–#62) wires them to real data sources. Most `ERR-*` rows in [spec/FEATURES.md](spec/FEATURES.md) are currently flagged `✗ #41` as a placeholder — each Cluster-B ticket replaces its row's flag with its own ID on landing. One ticket per Sonnet 4.6 session.

#### #48 — Edge-case visual tokens & small primitives `[x]`

Ships the foundational toolkit used by every other ticket in this group: the three semantic tones (`info`/`warn`/`err`), the monospace tone pill, and the two smallest text-only feedback surfaces (inline form error, inline reader note). Spec: [VISUAL_SPEC.md §States & feedback](spec/VISUAL_SPEC.md).

**Acceptance criteria**

- Web (`web/src/jsMain/...`): three pairs of `--warn-*` / `--err-*` / `--info-*` CSS custom properties added; computed once at theme load. `TonePill({tone, label})` reproduces the pill spec (`ui-monospace` 9.5–10.5 / 0.14em uppercase, tone border, 45%-white fill, 2px radius, 2/6 padding). `InlineFormError({tone, message})` and `InlineReaderNote({tone, message})` match the spec exactly.
- Android (`app/src/main/.../ui/theme/`): three `Color` triplets added to the palette, plus a Compose `TonePill` / `InlineFormError` / `InlineReaderNote` with the same surface area.
- `:web:jsTest` + `:app:testDebugUnitTest` cover one render per tone per component (9 web + 9 Android assertions).
- No consumers wired yet — this ticket only ships the toolkit.

#### #49 — Banner shell (web) and snackbar shell (Android) `[x]`

Counterpart surfaces, paired in one session since they share copy and tone. Spec: [VISUAL_SPEC.md §Banner](spec/VISUAL_SPEC.md) and [§Toasts / snackbars](spec/VISUAL_SPEC.md).

**Acceptance criteria**

- Web: a `Banner({tone, pill, message, action?})` component renders the full-width row with the spec's padding, border, leading pill, body typography, and optional right-aligned action link. Banners do not auto-dismiss.
- Android: a `Snackbar({tone, message, action?, persistent?})` Compose component. 56dp single-line / 80dp two-line, above the bottom tab bar. Replaces any previous snackbar; one at a time.
- Both components are pure presentational — consumers in Cluster B wire them in.
- Tests per platform exercising each tone, with/without action.

#### #50 — Big mid-pane state component `[x]`

Spec: [VISUAL_SPEC.md §Big mid-pane state](spec/VISUAL_SPEC.md). Used by ERR-5, ERR-7, ERR-10, ERR-11, and by the existing happy-path empty states.

**Acceptance criteria**

- Web and Android both expose `BigMidPaneState({eyebrow, title, body, mono?, primary?, secondary?, hint?})`. Optional slots collapse cleanly; primary + secondary buttons follow existing button shapes (no new styles).
- 460px text-column max width on web; mono detail block hidden on Android per spec.
- A test per platform asserts: (a) all four mandatory slots render, (b) every optional slot can be omitted without layout break, (c) the four happy-path variants (*Select an article*, *Nothing here yet*, *Caught up*, *First run*) produce the expected DOM/composition shape.

#### #51 — Modal interrupt component `[x]`

Spec: [VISUAL_SPEC.md §Modal interrupt](spec/VISUAL_SPEC.md). Consumed only by ERR-14, but shipped as a primitive so the modal logic is decoupled from the auth path.

**Acceptance criteria**

- Web: `ModalInterrupt({tone, eyebrow, title, body, panelStrip?, primary, secondary?})` rendered into a viewport-level portal. Scrim (`rgba(20,25,40,0.32)` + 2px backdrop blur) blocks click-through; the 420px-wide dialog matches the spec's typography and shadow.
- Android: same surface area as a Compose `Dialog` consumer; sized proportionally to the device width.
- Tests assert: scrim consumes pointer events, primary action callback fires, optional panel strip slot renders content verbatim.

#### #52 — Sidebar footer state machine (web) `[x]`

Spec: [VISUAL_SPEC.md §Sidebar footer · sync states](spec/VISUAL_SPEC.md). Five states: `ok` / `syncing` / `failed` / `offline` / `paused`.

**Acceptance criteria**

- A single `SidebarFooter({status})` web component renders the right text + glyph + tone per state, with the `retry` callback wired for `failed`.
- `SyncStatus` (or equivalent) becomes the single source of truth — every consumer (refresh, offline detector, 429 handler) writes the same model.
- The ad-hoc `Last sync failed · retry` rendering shipped by #33 is replaced with the state-machine version. ERR-1 web's status flag updates from `partial (web)` to `✓ (web)`.
- A `:web:jsTest` asserts the five states render their expected DOM and that `retry`'s click handler is invoked.
- Android out of scope (mobile uses snackbars per spec); no changes to `:app:`.

#### #53 — Sidebar per-feed `!` badge and dead-feed row treatment `[x]`

Spec: [VISUAL_SPEC.md §Sidebar per-feed badge](spec/VISUAL_SPEC.md). Web sidebar + Android Feeds tab.

**Acceptance criteria**

- A `feedStatus` field is plumbed through `Feed` / `FeedRow` (`ok` / `error` / `dead`). If the server doesn't yet expose it, the ticket adds the column read from the feeds table and surfaces it on the feeds list endpoint.
- Web: feed rows in the sidebar render the `!` chip when `error` or `dead`; on `dead`, the name gets `line-through` and the row drops to opacity 0.55, with the unread count hidden.
- Android: the Feeds tab applies the same chip + dead treatment.
- Tests per platform cover all three states.

#### #54 — ERR-4: Offline detection + banner + offline footer state `[x]`

Spec: [FEATURES.md ERR-4](spec/FEATURES.md). Consumes #49 + #52.

**Acceptance criteria**

- Web: subscribes to `navigator.onLine` + `online`/`offline` events. When offline, renders the spec's `OFFLINE · …` warn banner above the content area and switches the sidebar footer to `offline`. Reading and mark-as-read continue against the cache; mutations queue locally. Reconnecting flushes the queue and returns the footer to `ok`.
- Android: same condition surfaced via snackbar + `offline` footer state (or a top app-bar indicator if no sidebar surface is in play).
- A test per platform simulates offline → online and asserts the banner / snackbar / footer behaviour.
- Updates ERR-4's status from `✗ #41` to `✗ #54` in [spec/FEATURES.md](spec/FEATURES.md).

#### #55 — ERR-5: Server-unreachable big mid-pane after retry exhaustion `[x]`

Spec: [FEATURES.md ERR-5](spec/FEATURES.md). Consumes #50 + #52.

**Acceptance criteria**

- A shared retry-budget counter tracks consecutive sync failures (DNS, connection refused, 5xx). After ≥ 3, the web client replaces list + reader with the big mid-pane state using the spec's `ERR · {code}` eyebrow, `Couldn't reach the server.` title, and `Retry now` / `Check service status ↗` actions. Sidebar footer goes to `failed`.
- Android: snackbar copy `Couldn't reach the server — retry`. Big mid-pane only replaces the screen on a cold boot with no cache.
- Tests per platform simulate the 3-fail threshold and assert the right surface appears.
- Updates ERR-5's status from `✗ #41` to `✗ #55`.

#### #56 — ERR-6: 429 rate-limit banner + paused footer state `[x]`

Spec: [FEATURES.md ERR-6](spec/FEATURES.md). Consumes #49 + #52.

**Acceptance criteria**

- The networking layer recognises `429 Too Many Requests` and honours `Retry-After`. Background auto-poll pauses for the countdown; manual refresh / reading / mark-as-read continue to work.
- Web: warn banner with countdown copy; footer switches to `paused`. Both clear when the countdown elapses.
- Android: snackbar + paused footer state.
- Tests per platform with a `TestDispatcher` / virtual clock cover the countdown drain and resumption.
- Updates ERR-6's status from `✗ #41` to `✗ #56`.

#### #57 — ERR-7: Dead-feed (HTTP 410) tracking and surface `[x]`

Spec: [FEATURES.md ERR-7](spec/FEATURES.md). Server changes + consumes #50 + #53.

**Acceptance criteria — server**

- Per-feed `consecutive_410_count` + `first_410_at` columns on the feeds table; migration added per the inline-migration convention in [server/src/db.rs](server/src/db.rs).
- The sync worker bumps the counter on `410 Gone`, resets on any non-410 response. After ≥ 14 consecutive, the feed is marked `dead`; surfaced on the feeds list endpoint as `feedStatus: "dead"`.
- A new test in [server/src/db_tests.rs](server/src/db_tests.rs) covers the counter increments and the dead-feed transition.

**Acceptance criteria — clients**

- The sidebar badge from #53 already covers the visual; this ticket adds the big mid-pane state shown when the user navigates into the dead feed.
- Mid-pane content matches the spec: `ERR · HTTP 410 GONE` eyebrow, the feed's name in the title, mono detail block with URL + first-failure date + failure count, primary `Unsubscribe` (wires to existing `DELETE /v1/feeds/{id}`), secondary `Keep watching`.
- Tests per platform cover the big mid-pane render + the Unsubscribe action.
- Updates ERR-7's status from `✗ #41` to `✗ #57`.

#### #58 — ERR-8: Parse-fail banner and raw-response inspector `[x]`

Spec: [FEATURES.md ERR-8](spec/FEATURES.md) and [VISUAL_SPEC.md §Raw-response inspector](spec/VISUAL_SPEC.md). The largest ticket in the group — confirm scope fits one session before kicking off; consider splitting into "server + banner" and "inspector" if it grows.

**Acceptance criteria — server**

- On parse failure, persist the last raw response (body + headers + parser error with line/col) per feed in a new `feed_parse_errors` table. Replace the row on each new failure; clear when the next sync parses successfully.
- A new endpoint `GET /v1/feeds/{id}/parse-error` returns the persisted row or 404.
- A migration + server-side test covers the persist + clear path.

**Acceptance criteria — clients**

- A parse-fail banner (#49) appears above the article list when `feedStatus === 'parse_error'`. The cached articles list is unchanged.
- A new `RawResponseInspector` view renders the four-region layout (top bar, metadata strip, source view with line numbers + caret, footer detail strip). Web keeps the sidebar visible; Android pushes a full-screen view with the tab bar hidden.
- Tests cover: banner appearance on parse_error feed; inspector renders all four regions; the error line is highlighted with caret annotation.
- Updates ERR-8's status from `✗ #41` to `✗ #58`.

#### #59 — ERR-9: Article link-rot inline reader note `[x]`

Spec: [FEATURES.md ERR-9](spec/FEATURES.md). Server changes + consumes #48.

**Acceptance criteria — server**

- Per-article `link_status` (nullable int) + `link_checked_at` columns. The sync worker probes the article's `link` URL with a HEAD (or small-range GET if HEAD is unreliable) and records the status; cheap because it runs at most once per article.
- Surfaced via the existing article endpoint.

**Acceptance criteria — clients**

- When `link_status` is 4xx, the reader renders the inline reader note primitive above the body with the spec's copy. The Wayback link is a real anchor to `https://web.archive.org/web/*/{url}`.
- Tests per platform cover render with link_status null (no note), 404 (note appears), 200 (no note).
- Updates ERR-9's status from `✗ #41` to `✗ #59`.

#### #60 — ERR-10 + ERR-11: First-run welcome and inbox zero mid-panes `[x]`

Spec: [FEATURES.md ERR-10/11](spec/FEATURES.md). Paired because both are pure UI variants of the big mid-pane state (#50) with no new data plumbing.

**Acceptance criteria**

- When the logged-in account has zero feeds, the content area shows the *First run* mid-pane (`WELCOME` eyebrow, `Start by adding a feed.` title, `Paste a URL…` and `Import OPML…` actions wiring to the SUBS-2 / SET-5 flows). Sidebar footer reads `Nothing to sync yet`.
- When the Unread view has zero unread, the content area shows the *Inbox zero* mid-pane. The sidebar Unread count is hidden (not rendered as `0`). ERR-11 may replace ERR-2 only on the Unread view; per-feed empty filters keep ERR-2.
- Tests per platform cover both states.
- Updates ERR-10 and ERR-11 statuses from `✗ #41` to `✗ #60`.

#### #61 — ERR-12 + ERR-13: Add Feed form errors (bad URL + duplicate) `[x]`

Spec: [FEATURES.md ERR-12/13](spec/FEATURES.md). Paired because both attach to the same Add Feed form and consume the inline form error primitive (#48).

**Acceptance criteria**

- ERR-12: on submit, the client fetches the URL as typed (no auto-discovery of `/feed`, `/rss`, …). On non-feed bodies, the form stays open, the URL field's border switches to the error tone, and the spec's `ERR · This URL didn't return a valid feed…` inline form error appears. Focus stays on the URL field. No `POST /v1/feeds` is sent.
- ERR-13: when the typed URL exactly matches an existing subscription's feed URL, the warn-toned inline form error shows the spec's copy, with `{name}` as a real link to that feed's view. Submit is blocked.
- Tests per platform cover both error paths and the happy path (no false positives).
- Updates ERR-12 and ERR-13 statuses from `✗ #41` to `✗ #61`.

#### #62 — ERR-14: Session-expired modal over 401 path `[x]`

Spec: [FEATURES.md ERR-14](spec/FEATURES.md). [#34](#25--34--web-session-persistence--401--login-redirect-) already shipped the basic 401 → login redirect; this ticket layers the modal interrupt (#51) in front of that redirect.

**Acceptance criteria**

- When any API call returns 401 (or the session is otherwise invalidated mid-use), the warn modal interrupt (#51) covers the viewport with the spec's `SESSION EXPIRED` eyebrow, `You've been signed out.` title, identity panel strip, primary `Sign in again` (routes through login with username prefilled), and secondary `Forget this device` (clears local cache + routes to clean login).
- The sidebar footer behind the scrim reflects the `failed` state (#52).
- The scrim blocks all interaction until the user picks an action.
- Tests per platform cover both action paths.
- Updates ERR-14's status from `✗ #34` to `✗ #62`, and ERR-3's status from `✗ #34` to `✗ #62` (same scenario seen from the auth angle).

---

## P2 — Feature roadmap

Server endpoints exist; client surface is missing. Tackle after P1 so the existing surfaces are spec-clean first.

### #63 — Server-side rate limiting `[ ]`

The client already handles `429 Too Many Requests` (see #56), but the server never actually emits one. Add proper rate limiting to the server so the client-side handling is exercised in real deployments.

**Acceptance criteria — server**
- A configurable rate-limit middleware (requests per window per IP or per authenticated user) is applied to the sync-triggering and write endpoints (e.g. `POST /v1/feeds`, `PUT /v1/articles/{id}/read`, manual-refresh trigger if one exists).
- The response includes a `Retry-After` header (seconds until the window resets) so the client countdown is accurate.
- The rate-limit window size and request budget are configurable via `config.toml` (with sensible single-user defaults — the product is self-hosted, so the bar should be generous, e.g. 60 requests/minute).
- A server-side test covers: request within budget succeeds with 200; request over budget returns 429 with `Retry-After`; after the window resets, requests succeed again.

**Acceptance criteria — integration**
- The Android JVM integration tests that exercise refresh (`ServerRule`-based) still pass — the default config must not rate-limit the test harness.
- A dedicated integration test issues requests at a rate that exceeds the configured limit and asserts the 429 + `Retry-After` shape.

---

### #75 — Screenshot access for Claude + design-accuracy audit `[ ]`

Two-part prerequisite for the visual polish groups below. **Gate resolved 2026-06-18 (see NEXT.md):** Part 1 is a hard prerequisite and screenshot-vs-reference comparison is now the definition-of-done for every visual item. #67/#70/#71/#72 were built straight from VISUAL_SPEC.md and still drifted off-spec, so target precision was never the gap — the missing verification loop was. Run Part 2 as a lightweight current-vs-reference sweep, not a from-scratch rewrite of acceptance criteria.

**Part 1 — Tooling:** Establish a repeatable way to get screenshots of the running app into a Claude session alongside the design reference in `spec/story-board/prototypes/`. Candidates: save emulator/browser screenshots to a known path readable via the IDE's image support; `adb exec-out screencap -p` for Android; a headless browser screenshot script for web.

**Part 2 — Audit:** With screenshots in hand, run a comparison session between each client and the design reference. The exact targets already live in VISUAL_SPEC.md — the audit's job is to diff the *rendered* result against the prototype/spec and confirm or file discrepancies (spacing, typography, color, component shape), starting with re-verifying #67/#70/#71/#72.

**Acceptance criteria**
- A documented, repeatable screenshot workflow exists (a script or a note in CONTRIBUTING.md).
- A comparison session has run for both clients against `spec/story-board/prototypes/`.
- Resulting discrepancies are filed as tickets in TICKETS.md.

---

### #76 — Instrumented Android screenshot capture (deferred) `[ ]`

The #75 tooling captures Android screenshots via `scripts/shot-android.sh`
(`adb exec-out screencap`), which requires **manual** navigation to each screen
on a running device with the server up and data seeded. This works but is not
repeatable/automatable. This ticket is the investigation + option write-up for
replacing manual navigation with an instrumented test, **deferred** — manual is
acceptable for now (decided 2026-06-18). Captured here so we don't re-derive it.

**Findings (the infrastructure largely already exists):**
- `app/build.gradle.kts` already wires Compose `ui-test-junit4`, espresso, the
  `AndroidJUnitRunner`, and a working `:app:connectedDebugAndroidTest` task.
  [FeedScreenInstrumentedTest.kt](app/src/androidTest/java/eu/monniot/feed/ui/feed/FeedScreenInstrumentedTest.kt)
  is a live example using `createComposeRule().setContent { … }`.
- **Every screen has a stateless `*Content` seam** (`FeedScreenContent`,
  `SettingsScreenContent`, `SubscriptionsScreenContent`, `ReaderScreen`) **plus
  `@Preview` fixtures** — so screens can be rendered with synthetic data **without
  login, a server, or seeding** (the manual path needs all three).
- Capture is one line: `composeTestRule.onRoot().captureToImage().asAndroidBitmap()`.

**Two implementation tiers:**

| Tier | Effort | Fidelity | Server/login |
|---|---|---|---|
| 1 — isolated `*Content` shots (reuse preview fixtures) | ~½ day | Screen **body only** — no tab bar / scaffold / system chrome | None |
| 2 — full-app via `createAndroidComposeRule<MainActivity>()` + fake `FeedRepository` (injected through `FeedApplication` / `FeedViewModel.Factory`), driving real navigation | ~1–2 days | Full frame | None (fake repo), needs auth bypass |

**Gotchas:**
- The one genuinely fiddly part is getting PNGs **off-device**: either
  `androidx.test.services` test storage + `additionalTestOutputDir` (gradle
  auto-pulls connected-test output) or write to `getExternalFilesDir()` + an
  `adb pull` step.
- **Tier 1 cannot validate chrome-dependent tickets** — the bottom tab bar lives
  in `MainTabShell`, outside the screen content — so it can't cover **#67**
  (nav-bar padding) or **#69** (add-feed in app bar). Those need tier 2.
- Either tier still requires a connected device/emulator (no win over manual
  there); the win is determinism + no server/seed + cleaner renders + CI-ability.

**When to pick this up:** if visual checks become frequent or we want
screenshot-based visual-regression in CI. Tier 1 is the high-value/low-cost
slice; tier 2 is justified mainly by the CI goal. Until then, manual
`scripts/shot-android.sh` per [scripts/shots/SCENARIOS.md](scripts/shots/SCENARIOS.md).

---

### Group: Android visual polish

> **Note:** Do #75 (screenshot audit) before this group. The tickets below are based on
> rough descriptions; the audit will sharpen acceptance criteria and may add items.

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

#### #65 — Android: remove article list filter chips `[x]`

The filter chips ("Today", "Long reads", "Short reads") on the Android article list are broken (see BUG-8) and add cognitive noise without delivering value. Remove them rather than fixing the underlying data-plumbing.

**Note:** Resolving this ticket makes BUG-8 moot. If the chips are instead kept and fixed, close this ticket and work BUG-8.

**Acceptance criteria**
- Filter chips are removed from the article list UI.
- The article list displays all articles (the pre-filter behavior).
- No regression in article list scrolling or row rendering.
- Manual verification (UI change).

---

#### #66 — Android: pull-to-refresh on the inbox-zero screen `[x]`

When the article list is empty (inbox zero state), the pull-to-refresh gesture is not available, so there is no way to trigger a sync from that screen.

**Acceptance criteria**
- The inbox-zero / first-run mid-pane supports pull-to-refresh.
- Pulling triggers the same `refresh()` path as the populated list.
- Manual verification; existing pull-to-refresh tests (#33) still pass.

**Fix:** Added `verticalScroll(rememberScrollState())` to `BigMidPaneState`'s outer `Box` in `BigMidPaneState.kt`. The `PullToRefreshBox` already wrapped all empty-state branches; the `BigMidPaneCaughtUp` and `BigMidPaneFirstRun` composables were missing a scrollable container, so the nested-scroll mechanism never fired the pull gesture. The swipe gesture itself requires a real device for full verification (Robolectric limitation, per existing `@Ignore` annotations).

---

#### #67 — Android: reduce top bar and nav bar padding `[ ]`

The top app bar has excessive top padding, and the article list disappears roughly 10 dp above the bottom navigation bar — articles are hidden behind the nav bar.

**#75 audit (2026-06-18, evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md)):** confirmed top-bar drift. The status-bar→large-title gap renders at ~2× the reference artboard (live ~78–90 dp vs reference 48 dp). Two compounding causes:
1. **Doubled status-bar inset.** Edge-to-edge is on; both the outer `MainTabShell` Scaffold and each per-tab Scaffold (e.g. `FeedScreen`, `SettingsScreen`) consume `WindowInsets.systemBars`, so the status-bar inset is applied twice (~26 dp extra above every tab header).
2. **Header padding too large.** The screen header uses `padding(horizontal = 22.dp, vertical = 22.dp)`; spec §Mobile header wants top = inset + **14 dp**, bottom = **18 dp** (horizontal 22 dp is correct).

The "articles hidden ~10 dp behind the nav bar" symptom did **not** reproduce in the current shot — the outer Scaffold already insets the list by the nav-bar height. Keep it as a device-only scroll check.

**Acceptance criteria**
- The status-bar inset is applied exactly once: set `contentWindowInsets = WindowInsets(0)` on the nested per-tab Scaffolds (or drop the nested Scaffold for inset purposes).
- Screen header padding is `top = 14.dp, bottom = 18.dp` (keep horizontal 22 dp), so total top padding = status-bar inset + 14 dp per spec.
- The article list extends to within the correct inset of the bottom nav bar; no articles hidden behind it (verify by scrolling to the last row).
- Manual verification on a device or emulator with both gesture-navigation and 3-button nav.

---

#### #68 — Android: remove all screen transitions `[ ]`

Current screen transitions are distracting and inconsistent with the intended design. Remove them entirely for now; transitions can be added deliberately later.

**Acceptance criteria**
- Navigation between all screens (article list, reader, feeds, settings) has no animation.
- Manual verification.

---

#### #69 — Android: move "Add feed" button to the app bar `[ ]`

On the Feeds screen the "Add feed" button is at the end of the feed list, which is easy to miss and inconsistent with the web version's app-bar placement.

**Acceptance criteria**
- An "Add feed" action (icon or text) is placed in the `FeedsScreen` top app bar.
- The FAB or end-of-list button is removed.
- The add-feed dialog behavior is unchanged.
- Manual verification.

---

### Group: Web visual polish

> **Note:** Do #75 (screenshot audit) before this group. Same caveat as the Android
> polish group above.

#### #70 — Web: article list items too narrow `[-]`

The article list column is narrower than it could be; widening it would make better use of available space.

**#75 audit (2026-06-18) — closed without action; matches reference.** Measured the live shot against the reference artboard: the list renders at **380 px** (live border at x=599 over a 219 px sidebar) vs **381 px** in `ref/desktop-editorial.png` (border at x=601 over a 220 px sidebar) — within 1–2 px of both the reference and the spec's fixed `width: 380px`. The spec deliberately pins the list at 380 px so the reader stays the protagonist ("when you have a choice, pick the quieter one"). The "could be wider" is a preference that contradicts the design, not a drift. Evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md).

---

#### #71 — Web: article reader uses only half the available width `[-]`

The reader pane has excessive padding and renders content in roughly half the available column width.

**#75 audit (2026-06-18) — closed without action; matches reference.** `ReaderPane.kt` renders `max-width: 620px; padding: 52px 48px 80px`, exactly per spec. Measured text block: **520 px wide in the live desktop shot (1280)** vs **475 px in the reference** (`ref/desktop-editorial.png`, 1180) — the live column is actually *wider*. The "half width" impression is the intentional centering whitespace that appears only when the viewport pushes the reader pane past the 620 px cap (680 px pane at 1280); at narrower viewports (e.g. 900) the pane is below 620 and fills edge-to-edge. This is the spec's explicit behaviour: *"the column itself fills the remaining width without stretching the reading measure… Don't widen it. If the user complains the page feels narrow, increase the font size, not the column width."* Evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md).

---

#### #72 — Web: identity box in Settings / Subscriptions `[ ]`

There is an inconsistent visual element (a box) around identity/account language in the web Settings or Subscriptions screen. Needs investigation with a screenshot to confirm exact location.

**#75 audit (2026-06-18, evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md)):** the "box" is a **card wrapper** that contradicts the spec's flat, no-card / no-tonal-surface aesthetic. It is systematic, not just around account language (it's most conspicuous around the Account section, which is what the reporter saw):
- **Settings** — every section is wrapped by `settingsGroup` (`SettingsScreen.kt`): `background: var(--feed-panel); border: 1px solid var(--feed-border); border-radius: 4px; max-width: 700px`. Spec §Web · Settings wants flat rows on `bg`, no panel fill / border / radius, content **max-width 640 px**.
- **Subscriptions** — the feed-row list is wrapped by a `border: 1px; border-radius: 4px; overflow: hidden` card (`SubscriptionsScreen.kt`). Spec wants a flat stack with a 1px bottom border between rows, no surrounding card. (The search bar's own border/radius/panel is spec-correct and stays.)

**Acceptance criteria**
- Remove the `settingsGroup` card chrome (panel fill, border, radius); render Settings sections as flat rows on `bg` separated by 1px hairline dividers, with the section eyebrow above each group.
- Settings content max-width changed from 700 px to **640 px** per spec.
- Remove the feed-list card box on Subscriptions; keep the 1px hairline divider between rows (none on the last). Leave the search bar styling unchanged.
- Manual verification with a screenshot comparison against the spec.

---

#### #73 — Login page redesign (web + Android) `[ ]`

The login page has not been updated to match the current visual design. Both web and Android login screens still use the original placeholder styling.

**Acceptance criteria**
- Web and Android login screens are updated to match the design reference in `spec/`.
- Form ergonomics from #26 (Enter to submit, IME actions) are preserved.
- Manual verification with a screenshot comparison against the design reference.

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
- A "Dashboard" or "Stats" screen shows totals (feeds, articles, unread) and trends (24h/7d/30d, plus daily counts).
- A feed-health section flags feeds with errors, paused feeds, and never-fetched feeds — with a tap-through to the feed's detail/edit screen (#3).
- The screen pulls fresh data on each navigation; no caching needed.

---

### #9 — Batch read operations `[ ]`

Server supports `mark-all-read`, `mark-feed-read`, and batch `articles/read`. Client only marks one at a time.

**Acceptance criteria**
- "Mark all as read" action on the home screen (with confirmation if unread count > some threshold, e.g. 50).
- "Mark feed as read" from the feed detail screen (#3).
- Selection mode on the article list allows multi-select → batch mark-read via `/articles/read`.
- Local Room cache is updated to match server state (or evicted, matching current single-row pattern).

---

## P3 — Infra hygiene

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

### #74 — Reconsider the `/logs` endpoint for observability `[ ]`

The server exposes `GET /v1/logs` and both clients surface it, but log-file tailing is a crude observability tool. Structured logging, metrics, or a better-integrated approach may serve the use case better for a self-hosted single-user deployment.

**Acceptance criteria** (when picked up)
- A short decision note: keep `/logs` as-is, improve it, or replace it with something lighter (e.g. `tracing`-based structured logs written to stderr, readable via `journalctl` or `docker logs`).
- If replaced: remove the endpoint and client surfaces; if kept: note why.

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

### #64 — Out-of-band article link probe job `[ ]`

Per-article HEAD probes currently run serially inside the main fetch loop (see [server/src/fetcher.rs](server/src/fetcher.rs) `probe_article_link`). F3 added a 5-second per-probe timeout and skips non-http(s) schemes as a mitigation, but a fresh feed with many new articles still blocks the scheduler tick proportionally. The right fix is a dedicated background job that probes links outside the fetch cycle.

Dev's note: we should weight that work against being good citizens and not making too many requests to feed providers.

**Acceptance criteria**
- A periodic background task (e.g., via `tokio-cron-scheduler`) probes `link_status IS NULL` article links in batches, independent of the feed-fetch scheduler.
- The per-probe timeout remains ≤ 5 s; concurrency within each batch is bounded (e.g. 10 concurrent HEAD requests) to avoid overwhelming the server's outbound connection pool.
- The fetch loop stops calling `probe_article_link` entirely; `link_status` is initially `NULL` and filled in by the background job.
- Existing tests for link-status probing are adapted to drive the new background job directly.

---

### #36 — Investigate feed-hue collisions `[ ]`

SUBS-5 noted that two feeds with different names rendered the same avatar hue. The hue derivation is `abs(id.hashCode()) % 360` (Phase 1 implementation uses `ushr 1` to avoid `Int.MIN_VALUE` overflow), keyed off feed id, so identical hues across two ids are plausible at small N but worth checking — are we seeding from the right field, and is the modulo bucketing producing visible clashes on typical id ranges?

**Acceptance criteria**
- Audit `FeedHue` against real feed ids from a populated server; document whether observed collisions are at the expected rate.
- If the rate is unacceptable, switch to a better mixing function (e.g. xxhash of the feed's URL or title rather than the id's `hashCode()`), or shift to a curated palette of N hues distributed around the wheel.
- A unit test pins the chosen mapping so future changes are deliberate.

---

## Needs verification

### #8 — OPML import UI `[x]`

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

### #15 — Add LICENSE file `[x]`

Resolved. A `LICENSE` file with MIT license text was created at the repo root. Top-level README gained a License section referencing it. The server README's existing reference to the LICENSE file now resolves correctly.

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

### #26 — Auth form keyboard ergonomics `[x]`

The login form is keyboard-hostile on both clients.

- **Web:** pressing Enter from inside the password field does not submit the form. The form should submit on Enter from either field.
- **Android:** the username field's IME action should advance focus to the password field (currently inserts a newline), and the password field's IME action should submit. The on-screen keyboard should expose a primary action ("Login"/"Done") that performs the submission.

**Acceptance criteria**
- Web: Enter from username or password submits the login form.
- Android: username IME action = Next (advances to password); password IME action = Done/Go (submits). Newlines are no longer inserted.
- A unit/UI test per platform asserts the keyboard-driven submission path.

---

### #45 — Settings UI refresh: match prototype on web and Android `[x]`

Aligned both Settings screens with the visual prototype in `spec/story-board/prototypes/editorial.jsx` (web) and `editorial-mobile.jsx` (Android). Plan: [`spec/plans/settings-ui-refresh-look-radiant-quiche.md`](spec/plans/settings-ui-refresh-look-radiant-quiche.md).

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

### #10 — First-run DB bootstrap `[x]`

[server/src/main.rs:74](server/src/main.rs#L74) carries a TODO: the server doesn't have a clean path when no SQLite DB exists at the resolved path. SQLx's connection options need `create_if_missing` or the directory needs to exist.

**Acceptance criteria**
- Running the binary on a clean system with no DB file creates it (and any missing parent directory) at the path returned by `Config::database_url()`.
- A clear log line confirms first-run setup (so the user knows what happened).
- Existing-DB behaviour is unchanged.
- An integration test covers the cold-start case.

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

### #17 — CI on GitHub Actions `[x]`

No CI exists. Easy to regress. The local test environment is now trustworthy (see [.claude/plans/test-environment-hardening.md](.claude/plans/test-environment-hardening.md)) — `cargo test` and `./gradlew testDebugUnitTest` both run clean, and the gradle task auto-builds the server binary the Android integration tests need.

**Acceptance criteria**
- A workflow runs `cargo fmt --check`, `cargo clippy -D warnings`, and `cargo test` on every push/PR.
- A workflow runs `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` for the Android module. The existing `:app:buildServerBinary` task means no extra orchestration is needed — gradle will build the server before the Android tests run.
- Workflow files are committed to `.github/workflows/`.

---

### #27 — Android: article list is empty after login `[x]`

After a successful login on Android, the Feed screen renders no rows even though the server has articles. This blocks every FEED-*, READ-*, MOB-*, SET-3, SET-4 and ERR-1 manual test on Android.

**Acceptance criteria**
- Logging into Android with a populated server shows the same article list the web client shows for the same account.
- Pulling/refresh works (see #33).
- A new JVM test (Robolectric + `ServerRule`) exercises the login → list-populated path and asserts non-zero rows. Likely related to the network/JSON drift class of bugs (#23 / #24); the fix should land before re-running the catalog on Android.

---

### #28 — Web: subscription overflow menu clipped + rename field empty `[x]`

Two issues on the Subscriptions screen's per-row `⋯` menu:

1. The dropdown is constrained to the `subs-feed-list` container and gets clipped instead of overflowing on top. It should render in a layer that is not bound by the list's overflow context (portal/absolute positioning relative to the viewport, or an `overflow: visible` parent).
2. The rename dialog's text input starts empty. It should be prepopulated with the feed's current `custom_title ?: title` and the input selected so the user can either edit incrementally or overwrite.

**Acceptance criteria**
- The `⋯` menu renders above adjacent rows and is not clipped, regardless of where in the list the row sits.
- Opening "Rename" pre-fills the input with the current name and selects the text.
- A `:web:jsTest` asserts the rename input's initial value.

---

### #29 — Reader: article URL should be a hyperlink `[x]`

On the web reader pane, the feed/article URL displayed in the footer (or the `↗ Open` action target) shows as plain text in some surfaces — it should be a real `<a target="_blank" rel="noopener noreferrer">` so the user can click through. (Already covered by the design's "Open externally" action; the regression is that the URL text itself is not anchored.)

**Acceptance criteria**
- Wherever a feed/article URL is rendered in the web reader, it is a clickable link that opens in a new tab.
- A `:web:jsTest` asserts the DOM contains an anchor with the expected href.

---

### #30 — Web: Settings missing reader font-size control `[x]`

The web Settings screen does not expose a default reader font size, even though `UserPrefs.fontSize` is wired and the reader honors it. READ-5 and SET-1 both fail because of this.

**Acceptance criteria**
- The web Settings → Reading section includes a segmented control for reader font size (range 14–24px, matching the design's discrete steps; align with the Android picker options once #29's Android spec is settled).
- Changing the value persists via `UserPrefs` and the open reader pane re-renders at the new size without reload.
- A `:web:jsTest` asserts the control reflects and writes back the stored value.

---

### #31 — Web: Settings missing density control `[x]`

The web Settings page omits the "Density" segmented control (compact/regular/comfy). The article-list rows currently render at a fixed density. SET-4 fails on web for this reason.

**Acceptance criteria**
- Web Settings → Reading exposes Density (compact/regular/comfy).
- The article list reads `UserPrefs.density` and applies the row-padding/excerpt-visibility/thumbnail rules from [spec/VISUAL_SPEC.md](spec/VISUAL_SPEC.md).
- A `:web:jsTest` covers the rendering of at least one row in each density.

---

### #32 — Web: drop Server URL setting `[x]`

The web client's Settings includes a "Server URL" row, but it has no production value — in deployment the client is served by the same origin, and in development we can hardcode `http://localhost:3000/` (or whatever the dev URL is). SET-6 reports the row as broken on web; the resolution is to remove it rather than fix it.

**Acceptance criteria**
- The Server URL row is removed from the web Settings screen.
- The web client uses a fixed base URL (same-origin in production, dev-time default in development). No setting, no `ServerUrlStore` read path on web.
- Android keeps its Server URL setting unchanged — this is web-only.
- The Account section on web still shows "Signed in as: …" and logout; just no URL row.

---

### #33 — Android: pull-to-refresh on article lists `[x]`

Resolved. `FeedScreen` already had `PullToRefreshBox` wired to `isRefreshing` and `onRefresh = { viewModel.refresh() }` in `MainTabShell`. Added the missing error banner: when `uiState is UiState.Error`, the header footer shows "Last sync failed · Retry" with a clickable Retry that re-triggers the refresh. `FeedScreenContent` gained an `uiState: UiState = UiState.Idle` parameter. Covered by two new Robolectric tests (`errorBannerShownWhenRefreshFails`, `retryClickInvokesOnRefresh`) in `FeedScreenTest`; the swipe-gesture test lives in `FeedScreenInstrumentedTest` (instrumented, requires a device) — `PullToRefreshBox` gesture dispatch does not fire under Robolectric. Android test counts: 104 passed, 0 failed, 2 skipped.

---

### #39 — Surface server version on Settings → About `[x]`

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

### #40 — Mark-read affordance on article rows and in the reader `[x]`

[spec/FEATURES.md](spec/FEATURES.md)'s FEED-8 and READ-7 both depend on a single read-toggle surface that hits `PUT /v1/articles/{id}/read` with the inverted flag. The row-level button sits next to the unread dot; the reader-level button lives in the reader's action group (web: next to `↗ Open` / `⎙ Share`; Android: next to `⎙ Share`). Both surfaces share the same source of truth, optimistically update the unread dot and badge, and on the Unread route the row stays in place until the next refresh.

**Acceptance criteria**
- Clicking/tapping the row-level affordance fires the PUT and decrements the Unread badge by one; the unread dot disappears.
- The reader-level button reflects the article's current read state (label "Mark unread" when read, "Mark read" when unread) and inverts on press.
- The Unread view does not optimistically drop the article; it stays put until the next list refresh.
- Tests cover both surfaces on both clients (web `:web:jsTest`, Android JVM test through [ServerRule](app/src/test/java/eu/monniot/feed/integration/ServerRule.kt)).

---

### #41 — Mark as read on open `[x]`

Replaced the never-implemented "mark as read on scroll" dwell-time preference with a simpler always-on behavior: opening an article automatically fires `PUT /v1/articles/{id}/read`. The `markAsReadOnScroll` preference, its Settings UI toggle, and all associated tests were removed.

**Web specifics:** `WebFeedRepository.markAsRead` now updates `isRead` in-place (instead of filtering the item out), and `updateArticleListRows` keeps the selected article in the display list even after it is marked read — it disappears from the Unread filter only when another article is selected. This avoids the jarring three-pane UX where the article vanishes from the left pane while still open in the reader.

**Android:** `markAsRead` is called in `MainTabShell.onArticleClick` before navigating to the full-screen reader. The existing Room delete-on-read behavior is correct for Android's non-co-visible layout.

See [FEATURES.md](spec/FEATURES.md) FEED-9 for the scenario.

---

### #42 — Web: article list scroll position lost when opening article `[x]`

On the web app, after scrolling the article list and selecting an article to open in the reader pane, the article list jumps back to the top instead of maintaining the scroll position. Opening an article should not refresh or reset the list's scroll state.

**Acceptance criteria**
- Clicking/tapping an article to open it in the reader does not change the article list's scroll position.
- If the list is scrolled to row N, and the user opens an article, the list remains scrolled to approximately row N when the reader closes or the article is deselected.
- A `:web:jsTest` asserts that the list's scroll position is preserved before and after opening an article (e.g. by measuring `scrollTop` or via a virtual scroller's item offset).

---

### #46 — Audit and spec non-happy-path styles from Claude Design `[x]`

Resolved in commit `0667d02`. [spec/VISUAL_SPEC.md](spec/VISUAL_SPEC.md) gained a full §States & feedback chapter (tones, banner, big mid-pane state, modal interrupt, raw-response inspector, inline reader note, sidebar footer state machine, snackbar). [spec/FEATURES.md](spec/FEATURES.md) gained rows ERR-4..ERR-14, each mapped to an artboard in [spec/story-board/](spec/story-board/). Spec-only ticket — implementation follow-ups are tracked as the **Group: Edge-case visuals (from #46)** under P1 (#48–#62).

---

To be fleshed out at a later point

- server/config.example.toml isn't fully up to date (missing database group for example)
- ~~Write a set of scripts to analyze test results instead of having claude run find/exec things that require my approval.~~ Resolved — see [scripts/](scripts/) (`test-counts.sh`, `test-run.sh`, `test-failures.sh`, `server-build.sh`), documented in [CLAUDE.md](CLAUDE.md#helper-scripts) and allowlisted via [.claude/settings.local.json](.claude/settings.local.json) (`Bash(./scripts/*:*)` plus fixed prefix syntax for `cargo:*`, `grep:*`, etc.).
