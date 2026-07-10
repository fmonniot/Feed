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

### Group: FEATURES.md status reconciliation

#### #80 — Re-verify FEATURES.md scenarios and open follow-up tickets `[x]`

[spec/FEATURES.md](spec/FEATURES.md) used to carry a per-scenario `Status` column
(`✓` / `⚠` / `✗`). It was removed in the 2026-06-21 story-board accuracy audit
([spec/plans/storyboard-accuracy-audit-2026-06-21.md](spec/plans/storyboard-accuracy-audit-2026-06-21.md))
because it drifted badly out of date — most of the `✗`/`⚠` rows below were already
shipped (their implementing tickets are closed: **#40**, **#54**–**#62** are all `[x]`).
This ticket owns the one-time reconciliation: **verify the true current state of each
scenario that was *not* marked `✓`, then open a focused follow-up ticket for every
genuine gap** (one ticket per gap, or a small grouped ticket per screen). Do **not**
re-add a `Status` column to FEATURES.md — implementation status lives in the ticket
backlog from now on.

**Scenarios to verify** (with their last-recorded status before the column was dropped —
treat these as *suspect*, not authoritative; re-test each against the running clients):

| Scenario | Last-recorded status | Likely already done? |
|---|---|---|
| AUTH-1a (web Enter-submits) | ⚠ #26 | #26 is closed `[x]` — likely done |
| AUTH-1b (android IME Next/Go) | ⚠ #26 | #26 is closed `[x]` — likely done |
| AUTH-3 (web session persists across reload) | ⚠ #25 (web) | #25 closed `[x]` — likely done |
| AUTH-5 (debounced 401 → login) | ✗ #34 | #34 folded into #62 (`[x]`) — verify |
| FEED-1 / FEED-1a / FEED-2 (android list not empty) | ⚠ #27 (android) | #27 closed `[x]` — likely done |
| FEED-5 (stable per-feed hues) | ⚠ #36 | #36 still open (deferred) — likely real gap |
| FEED-6 (android pull-to-refresh) | ✗ #33 | #33 closed `[x]` — likely done |
| FEED-7 (web ↻ refresh) | ⚠ partial | re-test |
| FEED-8 (✓ mark-read on rows) | ✗ #40 | #40 closed `[x]` — done (seen in shots) |
| READ-5 (web ↗ Open / footer link) | ⚠ #29 (web) | #29 closed `[x]` — likely done |
| READ-7 (↩ Mark unread in reader) | ✗ #40 | #40 closed `[x]` — done |
| SUBS-4 (web rename + overflow above rows) | ⚠ #28 (web) | #28 closed `[x]` — likely done |
| SET-1 / SET-2 / SET-3 (web font-size persist + live) | ⚠ web (#30) | #30 closed `[x]` — likely done |
| SET-8 (Keep-articles retention) | ✗ #37 | #37 closed `[x]` — likely done |
| NAV-1 / NAV-2 (no Starred/Saved entry) | ⚠ pending #35 | #35 closed `[x]` (star removal) — likely done |
| ERR-1 (sync-failed; android snackbar) | ⚠ #33 (android), ✓ web | re-test android |
| ERR-3 (stale-cookie → modal) | ✗ #62 | #62 closed `[x]` — verify |
| ERR-4 (offline banner + footer) | ✗ #54 | #54 closed `[x]` — done |
| ERR-5 (server-unreachable mid-pane) | ✗ #55 | #55 closed `[x]` — done |
| ERR-6 (429 rate-limit banner + paused) | ✗ #56 | #56 closed `[x]` — done |
| ERR-7 (dead-feed 410 treatment) | ✗ #57 | #57 closed `[x]` — done |
| ERR-10 (first-run welcome mid-pane) | ✗ #60 | #60 closed `[x]` — done |
| ERR-11 (inbox-zero mid-pane) | ✗ #60 | #60 closed `[x]` — done (seen in shots) |
| ERR-12 / ERR-13 (add-feed form errors) | ✗ #61 | #61 closed `[x]` — done |
| ERR-14 (session-expired modal) | ✗ #62 | #62 closed `[x]` — done |

Settings-reference rows "Reader font size" (⚠ web #30) and "Keep articles" (✗ #37) map to
SET-1/2/3 and SET-8 above — verify once.

**Acceptance criteria**
- Each scenario above is exercised against the current web and Android clients (per its
  Platforms) and confirmed working, OR a follow-up ticket is filed describing the exact
  residual gap (platform, symptom, expected behaviour, suggested test).
- The follow-up tickets are added to TICKETS.md / BUGS.md and surfaced in NEXT.md.
- This ticket's body is updated with the verification outcome per row (done vs. ticketed),
  then closed.
- FEATURES.md is left **without** a status column; no per-scenario status is reintroduced.

**Verification outcome — 2026-06-22 (closed).** Each suspect row was re-verified
**against the current client/server source** (code-based verification, not a live-client
QA pass — UI-runtime confirmation is left to each scenario's own test suite). Result:
**every row is implemented except one genuine gap — READ-5 on Android — now filed as
BUG-32.** Per-row outcome:

| Scenario | Outcome | Evidence |
|---|---|---|
| AUTH-1a (web Enter-submits) | ✅ done | `wireLoginEnterSubmit` (LoginScreen.kt:475); tests `enterOn{Username,Password}FieldTriggersSubmit` |
| AUTH-1b (android IME Next/Go) | ✅ done | `ImeAction.Next`/`Go` (LoginScreen.kt:133-156); tests `usernameImeNextMovesFocus…`, `passwordImeGoSubmits…` |
| AUTH-3 (web session persists across reload) | ✅ done | SessionBootTest `startsLoggedInWhenFlagSet`, `loginPersistsFlagToStorage` |
| AUTH-5 (debounced 401 → login) | ✅ done | `onApiError` sets `_sessionExpiredUsername` once (FeedViewModel.kt:262); FeedViewModelUnauthorizedTest |
| FEED-1 / 1a / 2 (android list not empty) | ✅ done | MainTabShell / FeedScreen render live feeds + per-feed filter |
| FEED-5 (stable per-feed hues) | ✅ done | deterministic `feedHue(feedId)` (util/FeedHue.kt) shared across dot/thumb/avatar. Stability satisfied; cross-feed *collisions* remain tracked by #36 (deferred) — not a new gap |
| FEED-6 (android pull-to-refresh) | ✅ done | `PullToRefreshBox` (FeedScreen.kt:211). Gesture UI test stays device-only (@Ignore per CLAUDE.md) |
| FEED-7 (web ↻ refresh) | ✅ done | sidebar `↻` (SidebarFooter.kt:129) → `viewModel.refresh()`, which pulls **upstream** then re-reads (FeedViewModel.kt:307); FeedViewModelFetchNowTest |
| FEED-8 (✓ mark-read on rows) | ✅ done | web ArticleList.kt:380 / android ArticleRow.kt:165 |
| READ-5 (web ↗ Open / footer link) | ✅ done (web) | `window.open(article.url…)` + footer `<a>` (ReaderPane.kt:265,328) |
| READ-5 (android ↗ Open / footer link) | ⚠️ **GAP → BUG-32** | no `↗ Open` in reader top bar (only ↩/Aa/⎙, Share is a stub); footer URL is a non-clickable `Text` (ReaderScreen.kt:393) — no external-open path |
| READ-7 (↩ Mark unread in reader) | ✅ done | web ReaderPane.kt:267,353 / android ReaderScreen.kt:477 |
| SUBS-4 (web rename + overflow above rows) | ✅ done | rename prefill + overflow-escape; SubsOverflowMenuTest `renameDialogInputPrefilled…` |
| SET-1 / 2 / 3 (web font-size persist + live) | ✅ done | SettingsScreen segmented control bound to `prefs.fontSize`; FeedViewModelPrefsTest |
| SET-8 (Keep-articles retention) | ✅ done | server `/settings/retention` GET/PUT + sweep (db.rs:1521); both clients wire `onUpdateKeepArticles` + `loadRetention`; FeedViewModelRetentionTest |
| NAV-1 / NAV-2 (no Starred/Saved entry) | ✅ done | web 4 nav items (Sidebar.kt:212-215) / android 4 tabs (MainTabShell.kt:79-82); no star entry. `#35` star removal complete — only stale doc-comments remain (FeedViewModel.kt:132, Color.kt:33, empty "Starred Handlers" block in handlers.rs); cosmetic, not ticketed |
| ERR-1 (sync-failed; android snackbar) | ✅ done | android `Last sync failed · Retry` row (MainTabShell.kt:289) + FeedSnackbar; web footer Failed state |
| ERR-3 / ERR-14 (stale-cookie → session-expired modal) | ✅ done | web SessionExpiredModal.kt:20 (Main.kt:106); android MainActivity.kt:213 |
| ERR-4 (offline banner + footer) | ✅ done | web OFFLINE banner (ArticleList.kt:131); android offline snackbar path (FeedScreen.kt) |
| ERR-5 (server-unreachable) | ✅ done | web "Couldn't reach the server." mid-pane (BigMidPaneState.kt:279); android `serverUnreachable` snackbar (FeedScreen.kt:191) |
| ERR-6 (429 rate-limit banner + paused) | ✅ done | web RATE LIMIT banner (ArticleList.kt:137); shared `handleRateLimit` |
| ERR-7 (dead-feed 410) | ✅ done | feed-error contract on both clients — web + android Subscriptions accordion + tone badge |
| ERR-10 (first-run welcome) | ✅ done | web BigMidPaneState.kt:258 / android BigMidPaneState.kt:200 |
| ERR-11 (inbox-zero) | ✅ done | web BigMidPaneState.kt:242 / android BigMidPaneState.kt:180 |
| ERR-12 / ERR-13 (add-feed form errors) | ✅ done | `AddFeedError.ParseFail`/`Duplicate` wired web (SubscriptionsScreen.kt:1623) + android |

Net: 1 follow-up ticket filed (**BUG-32**, READ-5 android external-open). FEATURES.md left
without a status column. The remaining starring remnants are cosmetic comments only and
were judged not worth a ticket.

### Group: Cross-client server-backed prefs

Each adds a server endpoint plus a client read/write. Pick a session per ticket — server schema/endpoint changes don't want to compete for review attention.

#### #37 — "Keep articles" retention driven by the client setting `[x]`

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

#### #38 — Refresh interval (client-side auto-poll) `[x]`

The Settings → Refresh interval control (15m / 1h / 6h / manual) persists a value but no client polls. Wire a client-side timer. Scenario SET-9 in [spec/FEATURES.md](spec/FEATURES.md) is the acceptance shape.

**Acceptance criteria**
- Each client polls the article-list endpoints at the configured cadence (15m / 1h / 6h). `manual` disables the poll entirely.
- The poll is paused while the app/tab is backgrounded and resumed on foreground (web: `visibilitychange`; android: lifecycle `onStop` / `onStart`).
- Errors during a background poll surface via the ERR-1 path (sidebar footer on web; snackbar on android) — they do not interrupt the user's current screen.
- A test per platform covers both the cadence (use a virtual clock / `TestDispatcher` rather than real time) and the pause/resume.

### Group: Fetch-cadence UI follow-ups (from fetch-and-retention plan)

Server + shared layers for these landed with [spec/plans/fetch-and-retention-policy.md](spec/plans/fetch-and-retention-policy.md) (PRs #44–#51) but the final UI control was never wired — capability exists end-to-end *except* the widget. See that plan's §3.2 and §5.3.

#### #77 — Per-feed fetch-interval control in the UI `[x]`

The server accepts `fetch_interval_minutes` on `PUT /v1/feeds/{id}` (with a `min_interval_minutes` floor → `400`), and the shared `FeedViewModel.setFeedInterval(feedId, intervalMinutes)` is fully wired to it — but **nothing in either client calls it**. Its only caller is a test (`FeedViewModelFeedManagementTest`). An end user therefore has no way to change how often a feed is fetched upstream; the per-feed interval is effectively admin-only. (This is also why the global `default_fetch_interval_minutes` was left config-only — see the plan's §4.1 descope note.)

**Acceptance criteria**
- Both clients expose a per-feed fetch-interval control (e.g. in the subscription row's overflow menu next to Rename/Delete, or in a per-feed detail/edit sheet). A small preset list (e.g. 15m / 30m / 1h / 6h / 24h) is sufficient — no free-text needed.
- The control calls `FeedViewModel.setFeedInterval`; the displayed value reflects the feed's current `fetchIntervalMinutes`.
- A sub-floor selection is prevented client-side or surfaces the server's `400` via the existing error path (the ViewModel already maps it to `feedsError`).
- A UI test per platform asserts the control invokes `setFeedInterval` with the chosen value.

#### #78 — "Refresh this feed" per-feed action in the UI `[x]`

`POST /v1/feeds/{id}/refresh` (single-feed upstream pull, shares the global 60s rate limit) and the shared `FeedRepository.refreshFeedUpstream(feedId)` both exist and are tested, but there is **no `FeedViewModel` function exposing them and no UI affordance** — only the global refresh gesture (`refresh()` → `POST /v1/feeds/refresh`) is wired. The plan's §5.3 explicitly anticipated this as a deferrable follow-up: surface per-feed refresh as a **"Refresh this feed"** item in the subscription row's overflow menu (alongside Rename/Delete).

**Acceptance criteria**
- A `FeedViewModel` function (e.g. `refreshFeed(feedId)`) calls `repository.refreshFeedUpstream(feedId)`, then re-reads, and degrades gracefully on the shared 60s `429` rate-limit (silent fallback to a plain re-read, consistent with the global gesture in `refresh()`).
- Both clients add a "Refresh this feed" overflow-menu item that invokes it.
- A test per platform covers the happy path and the rate-limited fallback.

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

#### #43 — Android: add scroll indicator on the side when scrolling articles `[x]`

The article list does not display a scroll position indicator, making it unclear where the user is in a long list. Add a vertical scrollbar or scroll indicator on the right edge that appears when scrolling.

**Acceptance criteria**
- A scroll indicator (scrollbar or equivalent visual) is visible on the right edge of the article list when scrolling.
- The indicator position accurately reflects the current scroll position in the list.
- The indicator appears during active scrolling and fades out when idle (or remains visible based on design — match spec/VISUAL_SPEC.md once updated).
- No regression in existing article list functionality or layout.

---

#### #44 — Android: fix article entry padding and unread dot positioning `[x]`

The padding around article entries in the list is inconsistent, and the unread indicator dot is not properly aligned to the right edge of the entry (positioned at approximately 2/3 instead of the right edge).

**Acceptance criteria**
- Article entry padding is consistent on all sides (left, right, top, bottom).
- The unread indicator dot is positioned flush against the right edge of the entry, not inset by 2/3.
- Visual alignment matches spec/VISUAL_SPEC.md once updated with padding/spacing rules.
- All existing article row states (read, unread, with/without thumbnail) render correctly with the new padding.

**Resolution:** Fixed in `ArticleRow.kt`. Three changes: (1) moved `drawBehind` before `padding` so the 1px bottom border spans the full row width instead of being inset by horizontal padding; (2) restructured the meta line to use a fixed 52dp right-aligned cluster for the unread dot + mark-read button (per VISUAL_SPEC.md), replacing the broken `weight(1f)` on the time text that placed the dot at ~2/3; (3) changed vertical spacing between row children from 4dp to 8dp per spec. All 6 ArticleRow tests pass.

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

#### #67 — Android: reduce top bar and nav bar padding `[x]`

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

#### #68 — Android: remove all screen transitions `[x]`

Current screen transitions are distracting and inconsistent with the intended design. Remove them entirely for now; transitions can be added deliberately later.

**Acceptance criteria**
- Navigation between all screens (article list, reader, feeds, settings) has no animation.
- Manual verification.

**Resolution:** Set `enterTransition`, `exitTransition`, `popEnterTransition`, and `popExitTransition` to `None` on both NavHost instances (outer in `MainActivity.kt` and inner tab NavHost in `MainTabShell.kt`). All navigation now swaps instantly with no animation.

---

#### #69 — Android: move "Add feed" button to the app bar `[x]`

On the Feeds screen the "Add feed" button is at the end of the feed list, which is easy to miss and inconsistent with the web version's app-bar placement.

**Resolution:** Added an `actions` slot to `TabScreenHeader` and placed an Add icon button in the Feeds tab header. Removed the end-of-list button. Dialog state is managed in `MainTabShell` and passed down to `SubscriptionsScreenContent` via a `showAddFeedDialog` flag and `LaunchedEffect`. Two new tests in `TabScreenHeaderTest` verify the action button renders and invokes its callback.

**Acceptance criteria**
- An "Add feed" action (icon or text) is placed in the `FeedsScreen` top app bar.
- The FAB or end-of-list button is removed.
- The add-feed dialog behavior is unchanged.
- Manual verification.

#### #87 — Android: custom design for add-feed modal `[x]`

The add-feed modal uses Material Design styling rather than the app's custom design language. Replace it with a custom-designed modal that matches the visual spec and brand consistency.

**Resolution:** Rebuilt `AddFeedDialog` (`SubscriptionsScreen.kt`) on top of a raw Compose `Dialog` (`usePlatformDefaultWidth = false`) instead of Material's `AlertDialog`, reusing the exact shell shape already established by `ModalInterrupt.kt` — `bg` background, 1px `borderStrong` outline, 4dp corner radius, 32/32/28 padding, serif 24/500 title. The URL field is a custom `BasicTextField` styled like `LoginScreen`'s `LoginField` (uppercase sans label, no border/fill, bottom `HorizontalDivider` that switches to the tone border colour on error, placeholder text per the web add-feed-form spec). The Add/Cancel actions are hand-rolled `Text` + `.clickable()` pills matching `ModalInterrupt`'s primary (`ink` fill, `panel` text) / secondary (`border` outline, `panel` fill, `ink2` text) action-row shape. The existing `InlineFormError` primitive (from #48) is unchanged — it already rendered correctly inside the old Material dialog and continues to anchor ERR/WARN messages below the field. `RenameDialog`, `DeleteConfirmDialog`, and `FetchIntervalDialog` in the same file are unaffected — no other ticket asks for their redesign. Three new Robolectric tests in `SubscriptionsScreenTest` cover: Add disabled until a URL is typed then submits with the typed value, Cancel dismisses without submitting, and the ParseFail inline error renders next to the custom field. Full Android JVM suite: 336 passed, 0 failed, 2 skipped (baseline 333 + 3 new). No Android emulator was available in this environment, so the visual match against `spec/VISUAL_SPEC.md`'s "Modal interrupt" / "Inline form error" / Android login-field sections was verified by direct token/dimension comparison against the spec and against `ModalInterrupt.kt`'s already-shipped implementation, rather than a live screenshot.

Review follow-up: the Cancel pill had no loading guard — `.clickable(onClick = onDismiss)` fired regardless of `isLoading`, unlike the Add pill which was already gated by `addEnabled`, letting a user dismiss the dialog mid-submission. Gated Cancel with `enabled = !isLoading` (dimming its text to match the Add pill's disabled style) and closed the same gap on `onDismissRequest` (back-press / scrim-tap), which had always ignored `isLoading`. Added `addFeedDialog_loadingState_disablesFieldAndCancel` to `SubscriptionsScreenTest` — the only prior coverage of the loading-specific styling — asserting the field is `assertIsNotEnabled()` and that tapping Cancel while `addFeedLoading = true` neither submits nor dismisses. `./gradlew :app:testDebugUnitTest --tests SubscriptionsScreenTest`: 58 passed, 0 failed.

**Acceptance criteria**
- The add-feed modal (dialog/sheet) is redesigned to match the app's custom design tokens and typography (not Material defaults).
- All interactions (text input, error display, buttons) follow the established design language from #48-#73.
- The modal displays form validation errors using the standard inline form error primitive from #48.
- Visual consistency with the spec; manual verification with a screenshot comparison against `spec/VISUAL_SPEC.md`.
- No regression in form functionality (input validation, submission, error handling still work).

---

#### #110 — Android: justify reader pane text `[x]`

The reader pane displays article text with left alignment. Justified text alignment would improve the visual presentation and text readability consistency.

**Acceptance criteria**
- Article text in the reader pane is displayed with justified alignment (`textAlign = TextAlign.Justify` in Compose).
- Text breaks naturally at word boundaries; no hyphenation or unusual spacing introduced.
- Existing reader functionality (font sizing, line height, padding, mark-read, external links) remains unchanged.
- Manual verification: screenshot comparison of justified vs. left-aligned text in `ReaderScreen.kt`.

**Resolution:** Added `textAlign = TextAlign.Justify` to the body `TextStyle` in the article body `Text` composable in `ReaderScreen.kt` (the paragraph/heading/link/code copy produced by `htmlToAnnotatedString`). Font size, line height, padding, mark-read, and external-link behavior are untouched — only the `textAlign` field was added to the existing `TextStyle`. Compose's `TextAlign.Justify` wraps at word boundaries and stretches inter-word spacing only; no hyphenation is introduced. Covered by a new test, `ReaderScreenTest.bodyTextIsJustified`, which reads the actual rendered `TextLayoutResult` via `SemanticsActions.GetTextLayoutResult` (same pattern as `SettingsScreenTest`) and asserts `layoutInput.style.textAlign == TextAlign.Justify`, so justification is asserted directly rather than left to manual-only verification.

#### #112 — Android: pull-to-refresh should always query the server `[x]`

Pull-to-refresh is a deliberate user gesture to force a sync between clients. Currently, refresh may use cached data or skip a server query in some cases. Ensure pull-to-refresh always performs a fresh server query to guarantee the latest state is fetched.

**Acceptance criteria**
- Pull-to-refresh on the article list always triggers a server sync, bypassing any client-side cache optimizations.
- The refresh gesture works on all article-list views (Unread, per-feed, empty state).
- The existing refresh indicator and error handling (ERR-1 error snackbar on sync failure) are unchanged.
- A test covers the refresh path with a mock server to verify that a server query is made even when data is cached.

**Resolution (closed by #129):** fully subsumed by #129. The reflexive pull-to-refresh gesture now calls `FeedViewModel.syncFromServer()`, which unconditionally performs a fresh server query (`repository.refresh()` → `GET /v1/sync`) with no cache short-circuit, on every platform and every article-list view; the existing refresh indicator and ERR-1 error snackbar are preserved. Covered by `syncFromServerNeverTriggersUpstreamPull` in `FeedViewModelSyncStateTest` plus the retained #182 refresh-path tests. #129's owner-approved design deliberately routes the *upstream* fan-out to the explicit Settings "Force fetch from sources" action rather than the reflexive gesture — this is the intended shape, so #112 is closed rather than left open.

---

### Group: Web visual polish

> **Note:** Do #75 (screenshot audit) before this group. Same caveat as the Android
> polish group above.

#### #70 — Web: article list items too narrow `[x]`

The article list column is narrower than it could be; widening it would make better use of available space.

**#75 audit (2026-06-18):** the list rendered at exactly the spec width — **380 px** live (border at x=599 over a 219 px sidebar) vs **381 px** in `ref/desktop-editorial.png` — so it matched the reference and was *not* a drift. Initially closed `[-]` on that basis.

**Resolved (2026-06-18) — spec changed + implemented.** The owner then chose to **widen the design** anyway: the column went **380 px → 400 px** in `FeedScreen.kt`, and `VISUAL_SPEC.md` (layout diagram + § Web · Article list) was updated to match. The extra 20 px buys ~one more word per line, dropping many feed titles from three rendered lines to two. Verified in the regenerated unread shot: the list/reader border moved to x=619 (220 sidebar + 400 list) and titles like "EXT4 Reworks Fast Commit Handling & Faster Directory Hash Computation" now wrap to two lines. Web JS tests: 347 passed, 0 failed. (Companion to the #71 reader widening, 620 px → 900 px.) Evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md).

---

#### #71 — Web: article reader uses only half the available width `[x]`

The reader pane has excessive padding and renders content in roughly half the available column width.

**#75 audit (2026-06-18):** the old 620 px reader was spec-compliant but left >50 % of the pane as empty margin on wide windows (~40 % text fill at 1920 px). After reviewing the measurements the owner chose to **widen the design** rather than accept the gap.

**Resolved (2026-06-18) — spec changed + implemented.** Reader content `max-width` raised **620 px → 900 px** in `ReaderPane.kt` (padding unchanged at `52px 48px 80px` → ~804 px text line ≈ **100-char measure** at 18 px). `VISUAL_SPEC.md` § Container max-widths updated to match (the "most important number" rationale rewritten for the 100-char measure). Verified by rendered measurement: ~99 chars/line and ~61 % pane fill at 1920 px (up from ~40 %). The cap engages at viewport ≳ 1500 px; below that the column is pane-limited (≈91 chars at 1440, ≈72 at 1280). Evidence in [spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md).

---

#### #72 — Web: identity box in Settings / Subscriptions `[x]`

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

#### #73 — Login page redesign (web + Android) `[x]`

The login page has not been updated to match the current visual design. Both web and Android login screens still use the original placeholder styling.

**Web — done (2026-06-18).** `LoginScreen.kt` rebuilt to spec §Web · Login: ringed-"F" wordmark, SIGN IN eyebrow, serif 38px H1, italic subtitle, underlined username/password fields with a Show toggle, the styled AUTH-2 error box, secondary row, ink "Sign in" button with trailing arrow, OR divider, decorative Google / Magic-link ghost buttons, and the footer line. The Google / magic-link / forgot-password / create-account / keep-me-signed-in controls are decoration per FEATURES.md and are intentionally inert. #26 Enter-to-submit preserved (+ loading-disable, password Show/Hide). Verified against `build/.shots/ref/login-web.png`; the shot pipeline logs in through the new form to reach the authenticated screens, so the real auth path is exercised end-to-end. Web JS tests: 347 passed, 0 failed.

**Android — done (2026-06-20).** `ui/login/LoginScreen.kt` aligned to spec §Mobile (Android) · Login: auth error background corrected from `ToneErrBg` to `accentSoft` (matching web's `var(--feed-accentSoft)` per VISUAL_SPEC), password IME action changed from `Done` to `Go` (matching spec's `enterKeyHint="go"`). All existing design elements — `panel` background, wordmark at 18sp, SIGN IN eyebrow, serif 30sp H1, italic subtitle, underlined fields with Show/Hide toggle, compact AUTH-2 error box, ink-filled Sign-in button with trailing arrow — were already present. #26 IME ergonomics preserved (username→Next, password→Go submits). LoginScreenTest: 6 passed, 0 failed.

**Acceptance criteria**
- ~~Web login screen updated to match the design reference in `spec/`.~~ ✅
- ~~Android login screen updated to match the design reference in `spec/`.~~ ✅
- ~~Form ergonomics from #26 (Enter to submit, IME actions) are preserved.~~ ✅
- Manual verification with a screenshot comparison against the design reference — visual accuracy is a manual check; the layout matches the spec structurally.

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

### #90 — Remove share buttons in both Android and Web UIs `[x]`

Share functionality is not implemented and the buttons are not aligned with the product vision. Remove the share buttons from both the Android article reader and web UI.

**Acceptance criteria**
- Share button is removed from the Android reader screen
- Share button is removed from the web reader screen
- No broken references or UI layout issues remain after removal
- Verified with a screenshot of both clients with the buttons removed

**Resolution:** Removed the `⎙` share button from both reader top bars. Android: deleted the `onShare` stub call site, the `onShare` parameter, and the `TopBarButton(label = "⎙", ...)` usage in `ReaderTopBar` (`ReaderScreen.kt`) — the `↩` mark-unread and `Aa` font-size buttons are untouched. Web: removed `readerActionButton(id = "reader-share-btn", ...)` from `renderReaderActionGroup` and its clipboard-copy click handler from `wireReaderActions` in `ReaderPane.kt` — the `↗ Open` and `↩ Mark unread` buttons are untouched. Added `shareButtonIsAbsent` to `ReaderScreenTest.kt` (Android) and replaced the `readerOpenAndShareButtonsStillPresent` assertion with `readerOpenButtonStillPresent` + a new `readerShareButtonRemoved` in `MarkReadAffordanceTest.kt` (web). Verified visually: live web screenshot of the reader pane (`#article/1` on the seeded sample feed) shows only Open/Mark unread/Aa, no gap where Share was. No emulator/AVD was available in this environment to capture an equivalent Android screenshot (legacy `avdmanager` in the cached SDK fails under the installed JDK — `javax.xml.bind` was removed in JDK 9+); Android removal is instead verified by the new `shareButtonIsAbsent` Compose test plus the unchanged `markUnreadButtonIsPresent`/`tappingMarkUnreadButtonFiresCallback` tests confirming the sibling buttons still render and work. `./gradlew :app:testDebugUnitTest -PskipServerBuild` → 334 passed, 0 failed, 2 skipped. `./gradlew :web:jsTest` → 461 passed, 0 failed, 0 skipped.

---

### #113 — Web + Android: true infinite scroll, replacing the "Load more" button `[x]`

[#108](#108--badge-shows-full-unread-count-implement-pagination-for-frontends-) shipped a shared pagination primitive (`FeedViewModel.loadMore()` / `hasMore`, growing-window model over `observePage(filter, 0 until pageCount * DEFAULT_PAGE_SIZE)`) and a manual "Load more" button on both web (`ArticleList.kt`) and Android (`FeedScreen.kt`) that calls it. This ticket replaces that manual button with automatic loading: when the user scrolls near the bottom of the list, the next page loads and appends without a click. The shared `loadMore()`/`hasMore` contract stays as-is — only the trigger changes from a button click to a scroll-position observer, on both clients.

**Acceptance criteria**
- Web: the article list observes scroll position (e.g. an `IntersectionObserver` on a sentinel element near the last item, or a scroll-event threshold); when the user scrolls within a small margin of the last loaded article and `hasMore` is true, `viewModel.loadMore()` fires automatically. The manual button is removed.
- Android: the `LazyColumn` triggers `onLoadMore` automatically as the user scrolls near the end of the loaded items (e.g. via `LazyListState` index threshold), replacing the `TextButton` load-more row from #108.
- A loading indicator shows while a page fetch is in flight; it doesn't block scrolling of already-loaded content.
- Repeated scroll-triggered loads don't double-fire (e.g. a fetch-in-flight guard), and loading stops cleanly once `hasMore` is false.
- No regression in existing article list functionality (marking as read, filtering, article selection, feed switching resetting the window per #108's `_pageCount` reset).
- A test per platform covers: initial page render, scroll-triggered `loadMore()` invocation, appended content, and the stop condition when `hasMore` is false.

**Implementation notes**
- Do this after [BUG-46](BUGS.md) is resolved, so the auto-load path isn't built on top of an already-broken manual path.
- Performance: growing the window unboundedly (rather than replacing pages) means a user who scrolls through hundreds of articles keeps everything mounted/queried. Evaluate list virtualization (web) — Android's `LazyColumn` already virtualizes — before assuming eager full-window loading is fine at scale; note the finding in this ticket's resolution.

**Resolution:** Web (`ArticleList.kt`): replaced the `data-load-more` `<button>` + click-delegate with a `scroll` event listener on `container` — the same element `FeedScreen.kt` gives `overflow-y: auto` (`#feed-screen-article-list`), so it observes the real scroll position of the 400px article-list column. `maybeLoadMoreOnScroll` fires `viewModel.loadMore()` once `scrollHeight - scrollTop - clientHeight <= LOAD_MORE_SCROLL_MARGIN_PX` (200px), guarded by a module-level `loadMoreFetchInFlight` flag reset inside the existing `hasMore` collector (mirrors the BUG-46 fix's subscription-for-lifetime pattern). The button's `data-load-more` div became a non-interactive `data-load-more-indicator` "Loading more…" row. Android (`FeedScreen.kt`): replaced the `TextButton`/`onLoadMore` row with a `LaunchedEffect(listState, filteredItems.size, hasMore)` that `snapshotFlow`s `listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index` and fires `onLoadMore()` once the last *visible* item is within `LOAD_MORE_THRESHOLD_ITEMS` (5) of the end of the loaded window; a local `isLoadingMore` `mutableStateOf` guard (reset via a second `LaunchedEffect(hasMore, filteredItems.size)`) prevents double-firing while a fetch is in flight. The trailing list item became a `CircularProgressIndicator` (`testTag("load_more_indicator")`) instead of a button. Neither client changed the shared `FeedViewModel.loadMore()`/`hasMore` contract or the #108 `_pageCount` reset-on-filter-change behavior.
  - Gotcha hit during implementation: `Modifier.testTag()` chained after `lazyColumnScrollbar(listState)` on the `LazyColumn` got shadowed by that modifier's own internal `ScrollIndicatorTestTag` on the same semantics node (only one tag "wins" per node in Compose) — this broke the pre-existing `scrollbarModifierAppliedToArticleList` test. Fixed by not adding a competing tag to the `LazyColumn` at all; the new Android test locates it via `onNode(hasScrollAction())`, the same pattern already used by `SettingsScreenTest`.
  - **Virtualization finding:** Android's `LazyColumn` already virtualizes (only visible rows are composed/measured), so its growing window is cheap regardless of how many pages have been loaded. The web client has no virtualization — `updateArticleListRows` renders every item in the current filtered window into the DOM on each update. This ticket did not add web virtualization (out of scope per the ticket notes); a user who scrolls through many pages will accumulate DOM nodes for every loaded article. Given `DEFAULT_PAGE_SIZE` = 50 and typical single-user feed volumes, this is unlikely to be a practical problem short of thousands of loaded articles in one session, but if it becomes one, windowing the rendered rows (e.g. only keeping DOM nodes for a viewport-sized slice, or a library like `virtua`/manual `IntersectionObserver`-based recycling) is the fix — filed as a follow-up if/when it's observed in practice rather than spelled out as a new ticket now.
- Tests: added `FeedScreenInfiniteScrollTest` (Android, 4 tests: initial render with no indicator when `hasMore=false`, scroll-triggered `onLoadMore` firing, the fetch-in-flight guard across repeated scroll events, and the stop condition once `hasMore=false`) and rewrote `ArticleListLoadMoreTest` (web, 4 tests covering the same matrix via real `scroll` events dispatched on a fixed-height host, replacing the old click-driven assertions) rather than reimplementing the production entrypoints. `./gradlew :app:testDebugUnitTest -PskipServerBuild` → 361 passed, 0 failed, 2 skipped (baseline 356/0/2). `./gradlew :web:jsTest -PskipServerBuild` → 479 passed, 0 failed, 0 skipped (baseline 479/0/0 — a 1:1 test swap in `ArticleListLoadMoreTest`).

### #115 — Web: unread badge on sidebar source items `[x]`

Each source (feed) in the web sidebar task bar should display a badge showing the number of unread articles in that source. If there are no unread articles, the badge is hidden.

**Acceptance criteria**
- Each feed row in the web sidebar displays a badge (e.g. a small pill or number indicator) showing the unread article count for that feed.
- The badge is positioned consistently with other UI elements (typically right-aligned or inline with the feed name).
- When a feed has zero unread articles, the badge is hidden (not rendered as `0`).
- Clicking through to the feed and marking articles as read updates the badge count in real time.
- A test covers: badge renders when unread count > 0, badge is hidden when unread count = 0, badge updates when articles are marked read.

**Resolution:** The sidebar already had code to render `feed.unreadCount` in `feedRow`, but that value came from a one-time server API snapshot (`loadFeeds()`) and never updated when articles were marked read locally. Root cause: `FeedUiItem.unreadCount` was populated from `f.unread_count` (server response) and the `_feeds` StateFlow was never re-computed after local read-state changes.

Fix: Added `perFeedUnreadCounts: StateFlow<Map<Int, Int>>` to `FeedViewModel` using `_feeds.flatMapLatest { feeds -> combine(perFeedFlows) { ... } }` — each feed gets a `repository.observeUnreadCount(ArticleFilter.ByFeed(feedId))` flow, combined into a map. Added `data-part="unread-badge"` attribute to the unread span for testability. Updated `feedRow` to accept `liveUnreadCount: Int = feed.unreadCount` (default keeps all existing call-sites unchanged), `renderFeedListContent` to accept `unreadCounts: Map<Int, Int>` passed from `updateFeedList` via `viewModel.perFeedUnreadCounts.value`, and added a `perFeedUnreadCounts.collect` subscription in `renderSidebar` so the feed list re-renders whenever any feed's unread count changes. All changes are web-only except the new `perFeedUnreadCounts` field in the shared `FeedViewModel`. No `FeedRepository` interface changes were needed (existing `observeUnreadCount(ArticleFilter.ByFeed)` was sufficient).

Tests added in `SidebarUnreadBadgeTest.kt` (9 new tests): badge renders when `liveUnreadCount > 0`, badge hidden when `liveUnreadCount = 0`, default falls back to `feed.unreadCount`, `renderFeedListContent` uses live counts over server counts, and reactive integration test confirming the DOM badge disappears after marking all articles in a feed as read. `:web:jsTest` went from 479 to 488 passed, 0 failed; `:shared:allTests` 334 passed, 0 failed.

### #116 — Android: remove the search/paste-URL bar from the Feeds screen `[x]`

The Feeds screen currently has a persistent search/paste-URL bar at the top. Remove it — adding a feed by URL should happen through its own affordance (e.g. an "Add feed" action), not a bar that doubles as both a URL-paste field and (per [#117](#117)) the future search entry point.

**Acceptance criteria**
- The search/paste-URL bar is removed from the Feeds screen layout.
- Adding a feed by URL remains possible through another existing or minimal affordance (e.g. an "Add feed" button/dialog); this ticket should not regress the ability to add a feed.
- A test covers: the Feeds screen renders without the removed bar, and the add-feed flow still works.

**Resolution:** Removed the always-visible "Search or paste a URL…" `Box`/`BasicTextField` from `SubscriptionsScreenContent` in `SubscriptionsScreen.kt`. Adding a feed by URL was already handled by a separate, pre-existing "Add feed" affordance — the `+` icon action in the Feeds tab's top bar (`MainTabShell.kt`, `testTag("add_feed_action")`), which opens the custom `AddFeedDialog` via the existing `showAddFeedDialog`/`onAddFeedDialogShown`/`onAddFeed` wiring. That wiring was untouched, so no regression. Fixed together with [#117](#117) since removing the bar and adding the search icon are two halves of the same change (see #117 for the icon).

### #117 — Android: add a search icon to the Feeds screen menu `[x]`

Once the search/paste-URL bar is removed ([#116](#116)), the Feeds screen needs a way to search feeds/subscriptions. Add a search icon to the screen's menu/top bar that opens a search entry point.

**Acceptance criteria**
- A search icon appears in the Feeds screen's menu/top bar.
- Tapping it surfaces a way to search/filter feeds (exact UX — inline field vs. dedicated screen — is an implementation decision).
- A test covers: the search icon renders and tapping it triggers the search UI.

**Resolution:** Added an `IconButton` (`Icons.Default.Search`, `testTag("search_toggle")`, `contentDescription = "Search feeds"`) to `SubscriptionsScreenContent`'s own header area, right-aligned above the feed list — the screen's local top-bar row, where the removed bar used to sit. Tapping it toggles a new `searchExpanded` state; when true, an inline filter field (`testTag("search_field")`) appears with placeholder "Search feeds…", reusing the existing `searchQuery`/`filteredFeeds` substring-match logic unchanged. Collapsing the icon again clears `searchQuery` so the full feed list returns. `SubscriptionsScreen`/`SubscriptionsScreenContent` needed no new parameters — the toggle is self-contained state within the composable, so the existing test harness (which renders `SubscriptionsScreenContent` directly, no ViewModel/shell needed) could exercise it directly.

Tests added/updated in `SubscriptionsScreenTest.kt`: `oldSearchOrPasteUrlBarIsGone` (#116 — old placeholder text is gone), `searchIcon_rendersInScreenAndIsInitiallyCollapsed`, `searchIcon_tapRevealsSearchField`, `searchIcon_tapAgainHidesFieldAndClearsQuery` (#117 — icon renders, tapping reveals/hides the field, collapsing resets the query), and `addFeedFlow_stillWorksWithoutSearchBar` (#116 — add-feed-by-URL still works end-to-end through the dialog). `searchFiltersClientSide` and `searchIsCaseInsensitive` were updated to tap `search_toggle` before typing into `search_field`. The old `searchPlaceholderMatchesSpec` (BUG-27) test was replaced since the placeholder text and always-visible behavior it pinned are exactly what this ticket intentionally changes. `./gradlew :app:testDebugUnitTest -PskipServerBuild` → 411 passed, 0 new failures, 2 skipped (baseline 404/0/2; the only 3 failures are a pre-existing `FeedApiTest` network-timeout issue reproduced identically on unmodified `main`, unrelated to this change).

### #118 — Android: Feeds screen error summary bar takes too much space `[ ]`

The Feeds screen's error/summary top bar is currently fixed above the scrollable feed list, permanently consuming vertical space even when not critical. Either make it part of the scrollable content (so it scrolls away) or find a less intrusive way to surface it.

**Acceptance criteria**
- The error/summary bar no longer permanently occupies a large, fixed slice of the Feeds screen.
- The information it conveys (feed errors) remains discoverable to the user.
- A test covers the chosen presentation (e.g. bar scrolls with content, or is collapsed/compact by default).

### #119 — Android: show server URL in Settings → About `[x]`

The Settings screen's About section (which already shows the app/server version per [#39](#39--surface-server-version-on-settings--about-)) should also show the currently configured server URL, as read-only info. Switching servers still only happens from the login flow (per [#24](BUGS.md); not reintroduced here).

**Acceptance criteria**
- The About section displays the current server URL as an informational (non-editable) line, alongside the version info.
- No new way to change the server URL is introduced by this ticket.
- A test covers: the About section renders the configured server URL.

### #120 — Android: open article links in an in-app browser instead of an external app `[ ]`

The reader's "Open" action (added in [BUG-32](BUGS.md)) currently opens the article URL via `LocalUriHandler.openUri`, which launches a separate external browser app — a jarring context switch. Use Android's in-app browsing feature (Custom Tabs) instead, so the article opens in an embedded browser sheet without leaving the app.

**Acceptance criteria**
- Tapping "Open" (or the reader footer URL) opens the article URL via Custom Tabs (or equivalent in-app browsing), not a separate external browser app.
- Falls back gracefully if no Custom Tabs-capable browser is installed.
- A test covers: the open action launches the in-app browser intent with the correct URL.

### #121 — Mark all articles as read in a feed `[ ]`

When viewing articles in a given feed, offer a button or action to mark all unread articles in that feed as read at once. This reduces friction when catching up on a feed.

**Acceptance criteria**
- A "Mark all as read" action is available when viewing a feed with unread articles (e.g. in a toolbar, header, or overflow menu).
- Clicking the action marks all unread articles in the current feed as read.
- The action is disabled or hidden when the feed has no unread articles.
- The server's batch or individual write endpoints are used to update read status; changes sync to the server and persist across sessions.
- A test covers the action invocation and verifies that all articles transition to read state.

---

### #122 — Subscriptions redesign: shared category model + management actions `[ ]`

Underpins the redesigned Subscriptions / category manager (#123 web, #124 android). The server already models categories (create / rename / delete / reorder, nested-with-feeds responses, `ON DELETE SET NULL`) and per-feed `category_id` / `custom_title` / `url` / `fetch_interval_minutes` / paused via `PUT /v1/feeds/{id}`; the shared layer needs to expose all of it uniformly so both clients build on one model. Decomposes the client half of #4. Contract: [FEATURES.md](spec/FEATURES.md) §Categories & feed management, SUBS-1–5 / SUBS-10–16.

**Acceptance criteria**
- `FeedRepository` + `FeedViewModel` expose a **categories list** and actions: create, rename, delete-with-reassign (move orphaned feeds to a chosen target), and move-feed-to-category — mapping to the existing server endpoints.
- Per-feed actions are surfaced uniformly for both clients: refresh-now (`POST /v1/feeds/{id}/refresh`), rename (`custom_title`), set fetch interval (15m / 1h / 6h / Daily → `fetch_interval_minutes`), pause/resume, unsubscribe. Reuse what #3 already landed on Android rather than forking.
- "Uncategorized" is the permanent, locked, sorts-last bucket that absorbs feeds with no live category. **Ordering by drag is web-only** — the web feed-row drag handle drives re-filing (SUBS-10) and reordering; **Android has no drag, so reorder is out of scope there** and its list keeps a fixed order.
- Category + feed edits are reflected in the reading sidebar / Feeds-tab model without a full reload.
- Shared KMP tests cover the category CRUD + move + delete-reassign actions and the per-feed action set (`./gradlew :shared:allTests`).

### #123 — Subscriptions redesign: web two-pane category manager `[ ]`

Rebuild the web Subscriptions route as the two-pane category manager. Realizes [VISUAL_SPEC.md](spec/VISUAL_SPEC.md) §Web · Subscriptions and [FEATURES.md](spec/FEATURES.md) SUBS-1–5 / SUBS-10–16 on web. Depends on #122.

**Acceptance criteria**
- Three-column layout on the Subscriptions route: reading sidebar + 248px category **rail** (All feeds · categories · Uncategorized last · "+ New category" · per-category ⋯ rename/delete) + **feed pane** (category-name H1, count, "+ Add feed" into the selected category, pane search).
- Feed rows carry a drag handle, avatar (dimmed when paused), name + "Paused" badge, URL, `{N} new`/spinner, and a `⋯` overflow with the full per-feed action set (Refresh now / Move to category… / Rename… / Fetch interval… / Pause-Resume / Unsubscribe).
- Re-filing works two ways: **drag a row onto a rail category** and `⋯` → Move to category… (web-only drag). Delete-category opens the reassign modal; no feed is ever unsubscribed by a category delete.
- **Drag-to-reorder + persisted order must be built here** — the story board draws the drag handle but wires only re-filing (reorder is intentionally not prototyped, see [subscriptions.jsx](spec/story-board/prototypes/subscriptions.jsx) `SubHandle`). Implement drop-to-reorder and persistence from [FEATURES.md](spec/FEATURES.md) §Categories & feed management; the mock's silence is not "unsupported".
- The feed-error surface (broken row, inline accordion, summary banner) renders inside the feed pane (existing #79-cluster behaviour, new layout).
- Web tests (Karma) cover rail/pane rendering, category CRUD, move (drag + menu), reorder + persistence, delete-with-reassign, and the per-feed menu actions (`./gradlew :web:jsTest`).

### #124 — Subscriptions redesign: Android Feeds-tab category manager `[ ]`

Restructure the Android **Feeds** tab into the full category manager (bottom-sheet flows, no drag). Realizes [VISUAL_SPEC.md](spec/VISUAL_SPEC.md) §Mobile (Android) · Feeds and [FEATURES.md](spec/FEATURES.md) SUBS-1–5 / SUBS-10–16 on Android. Builds on the per-feed actions #3 already shipped. Depends on #122.

**Acceptance criteria**
- App-bar action cluster (search / add feed / overflow → "+ New category"); grouped-by-category list with uppercase headers, each non-locked header carrying a `⋯` for rename / delete.
- Per-feed `⋯` overflow opens the full action set; Move / Rename / Fetch interval / New category / Rename category / Delete category use **bottom sheets** (radio selection where applicable); Refresh / Pause / Unsubscribe act inline.
- Adding a feed lands it in Uncategorized with a "move it afterward" note; delete-category uses the same reassign model as web; "Uncategorized" is locked (no `⋯`).
- The feed-error surface (broken row, inline accordion, summary banner) renders in the grouped list.
- Android JVM/Robolectric tests cover the manager list, category CRUD, move-to-category, delete-with-reassign, and the per-feed sheet actions (`./gradlew :app:testDebugUnitTest`).

---

## P3 — Infra hygiene

---

### #24 — Contract tests between client models and server JSON `[x]`

Natural follow-up to #23. The shared client models ([`Models.kt`](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt)) and the server's serialized response shapes ([`server/src/db.rs`](server/src/db.rs), [`server/src/api/types.rs`](server/src/api/types.rs)) drift independently. The bug fixed in this commit: the client `Article` required a `read_at` field the server never emits → `MissingFieldException` swallowed → silently empty article list. With `ignoreUnknownKeys = true` only the "extra fields" direction is guarded; "missing fields" / "type changed" still blow up at runtime.

`ArticleModelTest` added alongside the fix covers the `Article` model. This ticket is about systematic coverage of the remaining endpoints.

**Acceptance criteria**
- For each REST endpoint the client calls (feed list, categories, stats, search, …), a test deserializes a representative server-shaped JSON into the client model without throwing.
- Fixtures or inline JSON strings live in `shared/src/commonTest/`.
- Ideally a server-side Rust test generates the same fixtures from real Rust structs so the two sides stay in sync; a simpler alternative is a test that calls a live test server and decodes one real response.

**Resolution:** Added inline-JSON contract tests in `shared/src/commonTest/kotlin/eu/monniot/feed/shared/api/` for every remaining client-decoded model, cross-checked field-by-field against the server structs in `server/src/db.rs` and `server/src/api/types.rs`: `FeedHealthFieldsModelTest` (the full `Feed` health-diagnostic field set — `feed_status`, `severity`, `last_error_kind`, `last_http_status`, `consecutive_failure_count`, `retries_paused`, `next_retry_at`, `first_410_at` — not covered by the existing BUG-5-focused `FeedModelTest`), `StatsModelTest`, `CategoryModelTest`, `OpmlImportModelTest`, `FeedParseErrorModelTest`, and `MiscResponseModelTest` (`HealthResponse`, `VersionResponse`, `LoginResponse`, `RetentionResponse`, `RefreshResponse`, `FeedAddResponse`, `UpdateResponse`, `UpdatedCountResponse`). Also extended `ArticleModelTest` with a case decoding the `SearchResult` shape (`Article` fields + `snippet`) since `GET /articles/search` is named in the ticket's example list even though no client method calls it yet. `Feed`, `SyncResponse`, and `Article` already had dedicated coverage from prior work and were left alone. Went with plain inline-JSON fixtures (matching the existing `ArticleModelTest`/`FeedModelTest`/`SyncResponseTest` precedent) rather than a fixture-generation pipeline — simpler and sufficient per the ticket's own escape hatch. Writing these tests surfaced one type-contract mismatch: `OpmlFeedResult.title` was declared as non-nullable `String` on the client, but the server's `OpmlImportResult`/`OpmlFeedResult` (`server/src/api/types.rs`) derives it as `Option<String>` (`outline.title.or(Some(outline.text))`). In practice this isn't reachable today — `Option::or(Some(x))` never yields `None`, and the opml crate defaults a missing `text` attribute to `""` rather than absent, so the server always sends `"title": ""` for an untitled outline, never `null`. The nullable type is still the correct alignment with the Rust contract and guards against future drift (e.g. if the server ever adds `skip_serializing_if` to `title` as it already has on the sibling `error`/`category` fields). Fixed the client model to `title: String? = null` and updated the two UI call sites that assumed non-null (`app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt`, `web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt`) to fall back to the feed URL when title is null or blank. `shared:allTests` went from 301 to 333 passed (32 new tests), 0 failed; `app:testDebugUnitTest` 394 passed/0 failed/2 skipped (unchanged skip count); `cargo test` unchanged at 286 passed/0 failed/0 ignored (no server code touched).

---

### #22 — Investigate the `#[ignore]`'d db tests `[x]`

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

**Resolution:** All 4 remaining `#[ignore]`'d tests fixed and un-ignored. `test_delete_old_articles` was previously resolved by splitting it into 6 specific test cases. Root causes: (1) `test_search_articles_not_logic` — test data was wrong (the "Python Tutorial" article didn't contain "programming" so it correctly wasn't matched by `programming NOT rust`); fixed test data. (2) `test_get_all_webhooks` — `ORDER BY created_at DESC` was nondeterministic when webhooks share the same second; added `id DESC` tiebreaker to `get_all_webhooks()`. (3-4) `test_get_article_count_since` and `test_get_daily_article_counts` — tests set `published` timestamps but the implementations query `fetched_at`, which `add_article()` always sets to `now()`; added `#[cfg(test)]` helper `add_article_with_fetched_at()` and rewrote tests to use it. `cargo test` now reports 262 passed, 0 failed, 0 ignored.

---

### #47 — Android: configure release signing `[x]`

The Android app currently uses debug signing keys for all builds, including what would be release builds. Before distribution (Play Store, F-Droid, or direct APK), the app needs a production signing key configured. Today [app/build.gradle.kts](app/build.gradle.kts) and the build flow have no release signing setup.

**Acceptance criteria**
- A production keystore is created (or template generated via `keytool`) and stored outside the repo (e.g. in a `~/.android/` or team secrets directory). Document the setup steps in [CONTRIBUTING.md](CONTRIBUTING.md) for maintainers.
- [app/build.gradle.kts](app/build.gradle.kts) is configured with a `signingConfigs { release { ... } }` block that reads the keystore path and password from environment variables or a local `keystore.properties` file (never committed).
- `build { release { signingConfig signingConfigs.release } }` wires the release variant to the signing config.
- `./gradlew assembleRelease` produces an APK signed with the production key (separate from `assembleDebug` which continues using the debug key).
- `.gitignore` blocks `*.keystore`, `keystore.properties`, and any team-secret files.
- A note in [CONTRIBUTING.md](CONTRIBUTING.md) and/or [server/README.md](server/README.md) explains the signing setup, which maintainers need to perform locally or in CI to build a release.

**Resolution:** Added a `signingConfigs { create("release") { ... } }` block to [app/build.gradle.kts](app/build.gradle.kts) that reads `storeFile`/`storePassword`/`keyAlias`/`keyPassword` from `app/keystore.properties` (loaded via `java.util.Properties` at configuration time). The signing config and the `release` build type's `signingConfig` assignment are both guarded by whether `app/keystore.properties` exists: when present, `release` uses `signingConfigs.getByName("release")`; when absent (fresh checkout, CI without secrets, plain `assembleDebug`/unit-test runs), `release` falls back to the debug signing config so the project always configures and builds without error. Added the committed template `app/keystore.properties.example` documenting the four required keys. Added `*.keystore`, `*.jks`, and `keystore.properties` to `.gitignore`. Added a "Release signing" subsection under "Android app" in [CONTRIBUTING.md](CONTRIBUTING.md) covering the `keytool -genkeypair` command, the `keystore.properties` format, where to place it, and that `assembleRelease` produces the signed APK once it's present. Validated: (a) `./gradlew :app:testDebugUnitTest -PskipServerBuild` — 344 passed, 0 failed, 2 skipped (matches baseline, no regressions); (b) `./gradlew :app:assembleRelease -PskipServerBuild` — configures and builds successfully with no `keystore.properties` present, producing a debug-signed `app-release.apk` via the fallback path (no configuration/evaluation error); also spot-checked that `:app:tasks` evaluates cleanly with a `keystore.properties` file present, confirming the signing-config registration path doesn't break configuration either.

---

### #20 — `data_extraction_rules.xml` TODO `[x]`

[app/src/main/res/xml/data_extraction_rules.xml:8](app/src/main/res/xml/data_extraction_rules.xml#L8) carries the scaffold TODO about `<include>`/`<exclude>`.

**Acceptance criteria**
- Decide what should and should not be in cloud/device backups (tokens? Room cache?) — likely: exclude the token DataStore and Tink keyset, allow everything else.
- File has explicit rules (no TODO), and a one-line comment explaining the choice.

**Resolution:** Replaced the scaffold TODO in `data_extraction_rules.xml` with an explicit `<exclude domain="file" path="datastore/ktor_session_cookies.preferences_pb"/>` under both `<cloud-backup>` and `<device-transfer>`, targeting the AndroidX DataStore preferences file (`shared/src/androidMain/.../DataStoreCookiesStorage.kt`) that holds the login session cookie — excluded so a restored/transferred backup can never carry a live or hijackable session onto another device. The Room article/feed cache (`feed_database`) is left in: it's a rebuildable-from-server cache, not a credential, and keeping it saves a resync after restore. Applied the equivalent `<exclude>` to the sibling `full-backup-content` file (`backup_rules.xml`, used for API < 31) so the two policies agree. There is no Tink/EncryptedSharedPreferences usage anywhere in the codebase (`grep -rniE 'Tink|MasterKey|EncryptedShared|keyset' app/src shared/src` — no hits) — the ticket's "Tink keyset" mention is stale; the cookie store is plain DataStore preferences. Added `BackupRulesTest.kt` (4 new JVM tests, no Android runtime needed) asserting neither file has a leftover TODO and both exclude the session-cookie path. `./gradlew :app:testDebugUnitTest -PskipServerBuild` → 463 passed, 0 failed, 2 skipped (459 baseline + 4 new).

---

### #74 — Reconsider the `/logs` endpoint for observability `[x]`

The server exposes `GET /v1/logs` and both clients surface it, but log-file tailing is a crude observability tool. Structured logging, metrics, or a better-integrated approach may serve the use case better for a self-hosted single-user deployment.

**Acceptance criteria** (when picked up)
- A short decision note: keep `/logs` as-is, improve it, or replace it with something lighter (e.g. `tracing`-based structured logs written to stderr, readable via `journalctl` or `docker logs`).
- If replaced: remove the endpoint and client surfaces; if kept: note why.


---

### #88 — Remove "end of article" line from reader pane footer `[x]`

The reader pane footer displays an "end of article" decorative line that serves no functional purpose and adds visual clutter. Removing it simplifies the UI.

**Acceptance criteria**
- The "end of article" footer line is removed from the reader pane.
- Manual verification: screenshot comparison of the reader pane before and after shows the footer line is gone with no layout regressions.
- No other reader footer content is affected (timestamp, etc. remain).

**Resolution:** Removed the `span { +"End of article" }` decorative line from `renderArticleFooter` in `ReaderPane.kt`. The footer now only renders the article URL anchor, right-aligned (`justify-content: flex-end` replacing `space-between`, since there is no longer a second item to space between). No dedicated CSS existed for the line — all footer styling was inline, so no stylesheet cleanup was needed. Added `footerDoesNotContainEndOfArticleText` to `ReaderPaneFooterTest.kt`, asserting the footer's text content no longer contains "End of article"; the three pre-existing anchor tests in that file continue to pass, confirming the URL link (the only remaining functional footer content) is unaffected. `./gradlew :web:jsTest` reports 461 passed, 0 failed, 0 skipped (460 baseline + 1 new test). The ticket's acceptance criteria refer to "the reader pane footer" generically, and the Android reader (`ReaderScreen.kt`) rendered the identical decorative `Text("End of article")` in its own footer `Row` — removed that too, right-aligning the remaining URL `Text` via `horizontalArrangement = Arrangement.End`. Added `footerDoesNotContainEndOfArticleText` to `ReaderScreenTest.kt`; `./gradlew :app:testDebugUnitTest --tests ReaderScreenTest` reports 18 passed, 0 failed.

---

### #89 — Clean up lingering doc-comments from starred feature removal `[ ]`

Starring removal (#35) is functionally complete, but three cosmetic artifacts remain: an obsolete doc-comment in `FeedViewModel`, a lingering comment in `Color.kt`, and an empty "Starred Handlers" code block. These should be removed to finish the cleanup.

**Acceptance criteria**
- Locate and remove the `FeedViewModel` doc-comment referencing starred functionality.
- Remove the lingering comment in `Color.kt` related to starring.
- Remove the empty "Starred Handlers" code block.
- All three removals are verified in a single test run: `./gradlew :shared:allTests :app:testDebugUnitTest` passes with no regressions.
- Commit message includes a reference to #35.

---

### #92 — Configurable JSON log output format for VictoriaLogs integration `[x]`

The server's JSON logging currently nests the message in `fields.message`. VictoriaLogs expects the message at the top level in a `_msg` field. We need environment variable-driven configuration to support both layouts while keeping the current format as the default.

**Acceptance criteria**
- A new environment variable (e.g., `LOG_FORMAT` or `VICTORIA_LOGS_COMPATIBLE`) controls the output format.
- When unset or `default`: message remains at `fields.message` (current behavior).
- When set to `victoria-logs`: message is placed at the top-level `_msg` field; other fields remain in `fields`.
- Both formats are tested: a unit test logs a message and verifies the JSON structure matches the expected format for each configuration.
- Documentation in [server/README.md](server/README.md) explains the environment variable and both output formats.

---

### #95 — Local-mirror article sync architecture (umbrella) `[x]`

Move both clients off the *view-cache* model (each refresh fetches a page and shows it) to a true **local-mirror** model: a persistent store on each platform synced incrementally via a monotonic `seq` cursor, with feed-selection becoming a pure local filter. This makes `badge == list` true by construction for every tab and feed.

**Design is locked.** The full proposal — change-log / sequence-based sync with server-side `seq` stamping and tombstone triggers — lives in [spec/plans/local-mirror-sync-95.md](spec/plans/local-mirror-sync-95.md). Read it before starting any child ticket; each child references the relevant section.

This umbrella has been **broken down into independently-executable child tickets #97–#105** (plus two deferred follow-ups, #106 and #107). Do not implement #95 directly — implement the children. #95 closes when all of #97–#105 land and the deferral sweep in the plan's §6 is done.

**Child tickets & dependency waves** (see NEXT.md cluster for the live order):

| Ticket | Scope | Module | Depends on |
|---|---|---|---|
| #97 | DB layer: migration v20, `seq`/tombstones/triggers, WAL | server | — |
| #98 | `GET /v1/sync` endpoint + remove orphaned routes | server | #97 |
| #99 | Sync contract: models, `ArticleStore` iface, `FeedApi.sync` | shared | — |
| #100 | `SyncEngine` loop | shared | #99 |
| #101 | Unify `FeedRepository` in `commonMain` + local badge/filter | shared | #99, #100 |
| #102 | Android Room `ArticleStore` impl | android | #99 |
| #103 | Android wiring + paging UI | android | #101, #102, #98 |
| #104 | Web backend decision + IndexedDB `ArticleStore` impl | web | #99 |
| #105 | Web wiring + range-query UI | web | #101, #104, #98 |
| #106 | FU-1: tombstone GC (deferred) | server | #97, #98 |
| #107 | FU-2: offline read-state mutation queue (deferred) | shared + clients | #101 |

**Acceptance criteria (umbrella — verify on close)**
- All of #97–#105 landed.
- The plan's §6 deferral sweep is done: #106 and #107 filed (they are, below), and the §3.4 tombstone-GC pointer is captured by #106.
- `badge == list` holds for the Unread tab, All tab, and per-feed view (covered by #101 T12 + #103/#105 integration).
- Deleted-on-server articles disappear locally after a sync (covered by #97 T2/T3 + #100 T11).
- Full suite green: `( cd server && cargo test ) && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest`.

---

#### #97 — Server: sync DB layer — migration v20, `seq` + tombstones + triggers + WAL `[x]`

Owns **all** changes to [server/src/db.rs](server/src/db.rs) and [server/src/db_tests.rs](server/src/db_tests.rs) for the sync rework. No other server ticket touches these files, so this is a clean single-owner seam. Plan: [§3.1](spec/plans/local-mirror-sync-95.md), [§3.2](spec/plans/local-mirror-sync-95.md), [§1.5](spec/plans/local-mirror-sync-95.md).

**Scope**
- Add **one** `if version < 20 { … }` block to the inline migration chain in `Database::new` containing, in this order (§3.1 step order matters):
  1. `CREATE TABLE sync_counter (id INTEGER PRIMARY KEY CHECK (id = 0), value INTEGER NOT NULL)` + `INSERT … VALUES (0, 0)`.
  2. `ALTER TABLE articles ADD COLUMN seq INTEGER NOT NULL DEFAULT 0` + `CREATE INDEX idx_articles_seq ON articles(seq)`.
  3. Backfill: `UPDATE articles SET seq = id;` then `UPDATE sync_counter SET value = (SELECT COALESCE(MAX(id), 0) FROM articles);` (O(n), **not** the O(n²) ranking variant — §3.1).
  4. `CREATE TABLE deleted_articles (seq INTEGER PRIMARY KEY, id INTEGER NOT NULL)` + `CREATE INDEX idx_deleted_articles_id ON deleted_articles(id)`.
  5. **Last:** create the three seq triggers (`articles_seq_ai` AFTER INSERT, `articles_seq_au` **AFTER UPDATE OF is_read**, `articles_seq_ad` AFTER DELETE → writes tombstone) exactly as in §3.2, and `DROP`+recreate the FTS `articles_au` trigger scoped to `AFTER UPDATE OF title, content` (§3.2).
- Tombstone PK is **`seq`, never `id`** (§3.1 / E13) — `id` is a plain column.
- `articles_seq_au` **excludes** `link_status`/`link_checked_at` (E4) — do not add them to `UPDATE OF`.
- Enable WAL hardening alongside the existing `PRAGMA foreign_keys = ON` setup (~db.rs:320): set `PRAGMA journal_mode = WAL` and a `busy_timeout` (§3.3 concurrency note). Keep it minimal.

**Acceptance criteria** (tests in [server/src/db_tests.rs](server/src/db_tests.rs); use existing `TestDatabase` from [server/src/test_utils.rs](server/src/test_utils.rs))
- **T1** — `seq` is unique and monotonically increasing across a mix of inserts, `is_read` updates, and deletes.
- **T2** — feed-delete **cascade** writes exactly one tombstone per cascaded article row (trigger fires per row; app code in `delete_feed` is untouched).
- **T3** — a retention purge `DELETE FROM articles WHERE …` writes tombstones the same way.
- **T4** — `recursive_triggers` is OFF and `articles_seq_au` does not re-fire itself (the `seq` write-back does not loop).
- **T5** — rowid reuse: delete the max-`id` article → re-insert (reuses the id) → delete again **succeeds** (no PK conflict on `deleted_articles`).
- **T6** — an `is_read` toggle / `seq` write produces **no** `articles_fts` mutation; a `title`/`content` change still reindexes.
- **T10** — over a seeded pre-#95 DB, the migration sets `seq = id` and the counter to `MAX(id)`; every post-migration stamped `seq` exceeds every backfilled one.
- **T13** — bulk feed-fetch insert benchmark stays within an acceptable bound with the triggers active (per-row write-back + counter bump). Per CLAUDE.md / project rule, treat the number as **CI-measured, not local**; assert a generous bound and note the CI measurement.
- `cd server && cargo test` → 0 failures, 0 ignored.

**Depends on:** nothing. **Module:** server.

---

#### #98 — Server: `GET /v1/sync` endpoint + remove orphaned article routes `[x]`

Reworks the HTTP article surface in [server/src/main.rs](server/src/main.rs) (routing) and [server/src/api/handlers.rs](server/src/api/handlers.rs) (handlers) — one atomic swap so there is no intermediate broken state. Plan: [§3.3](spec/plans/local-mirror-sync-95.md), [§3.4](spec/plans/local-mirror-sync-95.md), [§3.5](spec/plans/local-mirror-sync-95.md).

**Scope — add**
- `GET /v1/sync?since=<seq>&limit=<n>` (auth as today). `since` defaults to `0`; `limit` defaults to **500**, hard-clamped (not rejected) at **2000**.
- Response is the delta body `{ articles, deleted_ids, cursor, has_more }` (full `Article` rows with `seq > since` ascending by seq; tombstone ids with `seq > since`), **or** the single-field `{ "full_resync": true }` when `since > sync_counter.value` (§3.4).
- Contiguous pagination rule (§3.3): candidate seqs are every seq `> since` across **both** `articles` and `deleted_articles`; the cursor is the `limit`-th smallest such seq (use the `UNION ALL … LIMIT 1 OFFSET (:limit-1)` query in §3.3); range reads are bounded `seq > :since AND seq <= :cursor`. The cursor never advances past a not-fully-delivered seq.
- `since = 0` **omits tombstones** (§3.3 backfill).

**Scope — remove** (grep-verify no remaining consumer first; §3.5)
- `GET /v1/feeds/{id}/articles` (`get_feed_articles_handler`, ~main.rs:115).
- `GET /v1/articles` (`get_articles_handler`, ~main.rs:136).
- `GET /v1/articles/unread-count` (`get_unread_count_handler`, ~main.rs:140) — **keep the DB method `get_total_unread_count`**; the stats handler still uses it (~handlers.rs:1467).
- Drop the now-unused `unread_count` field from the `GET /v1/feeds` response.

**Acceptance criteria** (handler tests alongside the existing API tests)
- **T7** — with > `limit` candidates, `cursor` lands on a fully-delivered seq and `has_more=true`; the union of both streams is delivered exactly once (no seq split across a page boundary).
- **T8** — `limit` defaults to 500 and clamps at 2000; `since=0` omits tombstones; backfill paging drains to `has_more=false`.
- **T9** — `since > sync_counter.value` ⇒ `{ "full_resync": true }`.
- **T14** — a row delivered in an early page and deleted before a later page arrives as a tombstone later (net deleted); an insert during paging is picked up by its seq; the cursor never skips or double-counts a seq across the boundary.
- **T15** — with `/v1/articles/unread-count` removed, the stats handler still returns its unread count via `get_total_unread_count` (route removal didn't break the kept method).
- `cd server && cargo test` → 0 failures, 0 ignored.

**Depends on:** #97 (needs `seq`, `deleted_articles`, `sync_counter`). May build against the #97 schema contract on a branch. **Module:** server.

---

#### #99 — Shared: sync contract — models, `ArticleStore` interface, `FeedApi.sync` `[x]`

The foundation every client ticket builds on. Adds only `commonMain` types + the Ktor call + its test — small and conflict-free. Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§3.3](spec/plans/local-mirror-sync-95.md).

**Scope** (in `shared/src/commonMain/`)
- Add the `seq: Long` field to the `Article` model ([Models.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/api/Models.kt)).
- Add the sync response models: a delta variant `{ articles: List<Article>, deletedIds: List<Int>, cursor: Long, hasMore: Boolean }` and the `{ fullResync: true }` variant, decoded so the client treats `full_resync` as the signal regardless of other fields (§3.3). Add a `@Serializable` shape mirroring the server JSON.
- Add `FeedApi.sync(since: Long, limit: Int? = null): SyncResponse` over Ktor (hits `GET /v1/sync`).
- Define the `ArticleStore` interface and `ArticleFilter` (all / unread-only / `feedId`) **exactly** as in §4.0 — `upsert`, `deleteByIds`, `observePage(filter, window): Flow<List<Article>>`, `observeUnreadCount(filter): Flow<Int>`, `cursor()`, `setCursor(seq)`, `clear()`. Add the doc note that ordering is `published DESC, seq DESC` (§4.0 / E10) and the read side is windowed/aggregate, never whole-corpus.

**Acceptance criteria** (tests in `shared/src/commonTest/`)
- A serialization round-trip test decodes a representative server delta body and a `{ "full_resync": true }` body into the right model variant (extends the #24 contract-test pattern).
- `FeedApi.sync` test (mock engine) issues `GET /v1/sync` with `since`/`limit` query params and decodes the response.
- `./gradlew :shared:allTests` → 0 failures.

**Depends on:** nothing. **Module:** shared.

---

#### #100 — Shared: `SyncEngine` loop `[x]`  <!-- resolved: SyncEngine implemented with full T11 coverage -->

The platform-independent sync driver. Pure logic over the #99 interfaces, tested with a fake `ArticleStore` + mock `FeedApi` — no platform code. Plan: [§4.1](spec/plans/local-mirror-sync-95.md), [§4.3](spec/plans/local-mirror-sync-95.md).

**Scope** (new class in `shared/src/commonMain/`)
- `SyncEngine.sync()` implements the §4.1 loop: `do { r = api.sync(cursor, N); store.upsert(r.articles); store.deleteByIds(r.deletedIds); cursor = r.cursor; store.setCursor(cursor) } while (r.hasMore)`.
- Apply order is **upsert-then-delete** (order-independent within a page since seq is unique across both streams — §4.1).
- On a `full_resync` response: `store.clear()`, reset cursor to 0, and re-backfill from `since = 0` (§3.4 / §4.1).
- Cursor is read from / persisted to the store (`store.cursor()` / `setCursor`) so it survives process death (§4.2). Do **not** add a timer — `sync()` is invoked by the existing scheduled-fetch + pull-to-refresh triggers (§4.1).

**Acceptance criteria** (`shared/src/commonTest/`)
- **T11** — drives the loop with a fake store + mock api: asserts upsert-then-delete apply order, cursor advance + persistence across calls, `has_more` follow (multi-page drain), and that a `full_resync` response clears the store and re-backfills from 0.
- `./gradlew :shared:allTests` → 0 failures.

**Depends on:** #99. **Module:** shared.

---

#### #101 — Shared: unify `FeedRepository` in `commonMain` + local badge + local feed-filter `[x]`

Collapses the two duplicated platform `FeedRepository` impls into one shared, mirror-backed repository (§4.0). **Adds/changes only `shared/src/commonMain/` + `commonTest/`** — it does *not* touch `app/` or `web/` (the platform wiring tickets #103/#105 delete their old repos and adopt this). Keeps file ownership clean for parallelism. Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§4.4](spec/plans/local-mirror-sync-95.md), [§4.5](spec/plans/local-mirror-sync-95.md).

**Scope**
- Rework the `commonMain` `FeedRepository` surface (the one `FeedViewModel` consumes) to read from `ArticleStore` + drive `SyncEngine`:
  - list = `store.observePage(filter, window)` — **windowed**, never whole-corpus (§4.0).
  - badge = `store.observeUnreadCount(filter)` — local `COUNT`, replaces the server `unread_count` (§4.4); `badge == list` by construction.
  - feed-selection = a local `ArticleFilter(feedId)` — **no** per-feed network fetch; remove the `refreshForFeed`/`getFeedArticles` paths from the shared surface (§4.5). Feed *metadata* still comes wholesale from `GET /v1/feeds`.
  - the `Article → ArticleItem` mapping moves here (written once).
- Refresh/manual-pull routes through `SyncEngine.sync()`.

**Acceptance criteria** (`shared/src/commonTest/`)
- **T12** — `observeUnreadCount` equals the unread rows visible through the same filter as the windowed list, for all-tab and per-feed (badge == list by construction); list reads come from `observePage`, never a whole-corpus load.
- Existing `FeedViewModel` shared tests still pass against the reworked repository (state which by name if reused).
- `./gradlew :shared:allTests` → 0 failures.

**Depends on:** #99, #100. **Module:** shared.

**Resolution:** Landed in PR #81 (ticket/101-shared-feed-repository). `SharedFeedRepository` unifies the platform repos, consuming `ArticleStore.observePage` and `observeUnreadCount` so badge == list by construction. `SyncEngine.sync()` drives refresh. All shared tests pass.

---

#### #102 — Android: Room `ArticleStore` implementation `[x]`

Implements the §4.0 `ArticleStore` contract on Room. **Touches only `app/`.** Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§4.2](spec/plans/local-mirror-sync-95.md).

**Scope**
- A Room-backed `ArticleStore`: `upsert` = `@Insert(onConflict = REPLACE)` by id; `deleteByIds`; `observePage(filter, window)` backed by Room **`PagingSource`** (or a windowed range query) ordered `published DESC, seq DESC`; `observeUnreadCount(filter)` = `SELECT COUNT(*) … Flow<Int>` that never materializes rows; `clear()`.
- Cursor persistence: a one-row settings/`meta` table (or row alongside `rss_items`) backing `cursor()` / `setCursor()` (§4.2). Fresh install → `0`.
- Add the `seq` column to the Room article entity + a Room migration.

**Acceptance criteria** (`app/src/test/` Robolectric, in-memory Room)
- Tests cover: upsert-by-id replaces (only `is_read` changes in practice); `deleteByIds` removes rows; `observeUnreadCount` reflects unread rows for all-filter and per-feed; `observePage` returns the right window ordered `published DESC, seq DESC`; `cursor`/`setCursor` round-trip and survive reopen; `clear()` empties the store and resets the cursor.
- `./gradlew :app:testDebugUnitTest` → 0 failures (2 known `@Ignore`'d).

**Depends on:** #99 (interface only). **Module:** android.

---

#### #103 — Android: wire `SyncEngine` + paging UI; drop per-feed network path `[x]`

Adopts the shared mirror-backed repository (#101) + Room store (#102) in the Android app. **Touches only `app/`.** Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§4.1](spec/plans/local-mirror-sync-95.md), [§4.5](spec/plans/local-mirror-sync-95.md).

**Scope**
- Delete the app's own `FeedRepository` impl ([app/.../FeedRepository.kt](app/src/main/java/eu/monniot/feed/FeedRepository.kt)); inject the Room `ArticleStore` (#102) into the shared repository (#101) via `FeedApplication` / the ViewModel `Factory`.
- Feed-list UI consumes the windowed `observePage` (Paging 3) instead of a whole-list flow; badge from `observeUnreadCount`.
- Remove the per-feed network selection (`refreshForFeed`/`getFeedArticles` callers, the `/v1/feeds/{id}/articles` client path from PR 72); selection is now a local `ArticleFilter`.
- Scheduled-fetch + pull-to-refresh call `SyncEngine.sync()` (§4.1).

**Acceptance criteria**
- An integration test (ServerRule, spawns the real server built from #97/#98) seeds a feed with **> 50 unread** and asserts `badge == list` for Unread, All, and per-feed views, and that a server-side delete disappears locally after a sync.
- Existing app tests still pass (adapt the ones that assumed the old repo).
- `./gradlew :app:testDebugUnitTest` → 0 failures.

**Depends on:** #101, #102, #98 (server live for the integration test). **Module:** android.

**Resolution:** Most wiring landed in PR #81 (`FeedApplication` injects `RoomArticleStore` → `SyncEngine` → `SharedFeedRepository`; per-feed network paths removed). This PR completes the cleanup: drops the legacy `rss_items` table (Room migration v6→v7), removes `RssItemEntity`/`RssItemDao`/`toEntities`, and adds `SyncWiringIntegrationTest` covering badge == list for All/Unread/ByFeed with > 50 articles and server-side delete propagation.

---

#### #104 — Web: backend decision + IndexedDB `ArticleStore` implementation `[x]`

**Resolution:** IndexedDB chosen as the web storage backend (§6.B decision recorded in plan). `IndexedDbArticleStore` implemented with compound index on `[published, seq]` for ordering, `feed_id` index for per-feed filtering, and `meta` store for cursor persistence. Kotlin `Long` values stored as JS `Double` for IndexedDB compatibility. 24 tests covering full contract surface (upsert, delete, windowed paging, unread count, cursor persistence across reopens, clear, field round-trips).

Implements the §4.0 `ArticleStore` contract for web. **Touches only `web/`.** First acceptance item is the §6.B decision. Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§6.B](spec/plans/local-mirror-sync-95.md).

**Scope**
- **Decide the web storage backend (§6.B):** IndexedDB vs. a simpler persisted shape. The load-bearing constraint is **windowed range read** (`published DESC, seq DESC`) + **aggregate unread `COUNT` that never materializes rows** + upsert-by-id / delete-by-id / get-set cursor / clear, at 20k+ rows. A whole-set/single-JSON-blob backend **does not qualify**. Record the decision in a one-paragraph note (in the plan file's §6.B or a short follow-up note).
- Implement the chosen backend behind the `ArticleStore` interface, replacing the in-memory `MutableStateFlow` in [WebFeedRepository.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt) (the store, not the wiring — wiring is #105). Cursor persists in a `meta` record (§4.2).

**Acceptance criteria** (`web/src/jsTest/`, headless browser via Karma)
- Tests cover the same contract surface as #102 (upsert-by-id, deleteByIds, windowed `observePage` ordered `published DESC, seq DESC`, `observeUnreadCount` aggregate, cursor round-trip + persistence across a simulated reload, `clear()`).
- Articles **survive a page reload** (persistence assertion).
- `./gradlew :web:jsTest` → 0 failures.

**Depends on:** #99 (interface only). **Module:** web.

---

#### #105 — Web: wire `SyncEngine` + range-query UI; persistent store replaces in-memory `[x]`

Adopts the shared mirror-backed repository (#101) + IndexedDB store (#104) in the web client. **Touches only `web/`.** Plan: [§4.0](spec/plans/local-mirror-sync-95.md), [§4.1](spec/plans/local-mirror-sync-95.md), [§4.5](spec/plans/local-mirror-sync-95.md).

**Scope**
- Replace [WebFeedRepository.kt](web/src/jsMain/kotlin/eu/monniot/feed/web/data/WebFeedRepository.kt)'s logic with the shared repository (#101), injecting the IndexedDB `ArticleStore` (#104).
- List UI consumes the windowed range query (`observePage`); badge from `observeUnreadCount`.
- Remove the per-feed network selection (`refreshForFeed`/`getFeedArticles`, the `/v1/feeds/{id}/articles` client path); selection is a local `ArticleFilter`.
- The sidebar `↻` / scheduled poll call `SyncEngine.sync()` (§4.1).

**Acceptance criteria**
- A `:web:jsTest` asserts `badge == list` for Unread, All, and per-feed views over a seeded store with > 50 unread, and that a tombstoned id disappears from list + count after a sync.
- Articles survive a page reload (end-to-end through the wired repository, not just the store).
- `./gradlew :web:jsTest` → 0 failures.

**Depends on:** #101, #104, #98 (server live). **Module:** web.

**Resolution:** The wiring was completed as part of PR #81 (ticket/101-shared-feed-repository): `Main.kt` already builds `IndexedDbArticleStore` -> `SyncEngine` -> `SharedFeedRepository`, the old `WebFeedRepository.kt` was deleted, and all reads route through the windowed `observePage`/`observeUnreadCount` interface. Acceptance tests (`SyncWiringTest.kt`) verify badge == list for All, UnreadOnly, and ByFeed filters over > 50 unread articles, tombstone removal from both list and count after sync, article persistence across simulated page reload, and multi-page sync drain through the full stack.

---

#### #106 — FU-1: Tombstone GC for the sync log (deferred) `[ ]`

Filed from the plan's [§6.A FU-1](spec/plans/local-mirror-sync-95.md) / [§3.4](spec/plans/local-mirror-sync-95.md). **Not needed at #95 launch** — §3.4 keeps tombstones forever; this caps the one append-only table before any long-lived deployment.

**Scope**
- A scheduled job that prunes `deleted_articles` rows older than a bounded horizon (longest plausible client-offline window; propose **1 year**, configurable).
- `/v1/sync` returns `{ "full_resync": true }` when a client's `since` is below the oldest surviving tombstone seq (reintroduces the staleness handshake §3.4 deliberately eliminated at launch).

**Acceptance criteria**
- GC job prunes tombstones past the horizon; tombstones within the horizon are never pruned.
- A sync with a too-old cursor returns `full_resync`; a test seeds an old cursor and asserts the resync path, plus that the client clears + re-backfills.

**Depends on:** #97, #98 (tombstone table + `/v1/sync` exist). **Module:** server. **Tier:** Deferred.

---

#### #107 — FU-2: Offline read-state mutation queue (deferred) `[x]`

Filed from the plan's [§6.A FU-2](spec/plans/local-mirror-sync-95.md) / [§4.3](spec/plans/local-mirror-sync-95.md). Relevant only when robust offline use becomes a product goal — today read-state `PUT`s are synchronous (§4.3).

**Scope**
- Queue local `is_read` changes made while offline; flush to the server on reconnect.
- Guard the sync-apply path so an incoming pull does **not** overwrite an un-acked local change (per-id "pending mutation" set).

**Acceptance criteria**
- Marking read/unread offline persists locally and `PUT`s on reconnect.
- A sync pull arriving while a local change is un-acked does not revert it (test drives the pending-id guard).
- The queue survives process death.

**Depends on:** #101 (mirror + `SyncEngine` exist). **Module:** shared + clients. **Tier:** Deferred.

**Resolution:** Implemented the full offline mutation queue across all three platforms (shared/Android/Web). `ArticleStore` gains `enqueueMutation`/`dequeueMutation`/`pendingMutations` methods. `SharedFeedRepository.markAsRead/Unread` writes locally, enqueues, attempts the PUT immediately and dequeues on success; on failure, the queue is flushed at the start of the next `SyncEngine.sync()`. The sync guard filters out articles with pending mutations from server upserts to prevent stale echoes from reverting un-acked local state. Android uses a Room `pending_mutations` table (migration 8→9). Web uses an IndexedDB object store (DB version 1→2). `clear()` intentionally leaves the queue intact across `full_resync`. All three acceptance criteria validated by 58 new tests across shared, Android, and web targets.

---

### #122 — Remove client-orphaned bulk-read server endpoints `[x]`

The ticket #9 offline rework routes **all** bulk read operations through `POST /v1/articles/read` (client-side fan-out over locally-mirrored unread ids). Once that lands, `POST /v1/articles/read-all` (`mark_all_read_handler`) and `POST /v1/feeds/{id}/read` (`mark_feed_read_handler`) in [server/src/api/handlers.rs](server/src/api/handlers.rs) have no remaining consumer — the clients were the only callers.

**Acceptance criteria**
- Grep-verify no caller of `mark_all_read_handler` / `mark_feed_read_handler` remains (routes + shared `FeedApi.markAllRead`/`markFeedRead` already removed by #9).
- Remove the two routes, their handlers, and the now-unused `Database::mark_all_read` / `Database::mark_feed_read` methods.
- Server test suite still passes (`cd server && cargo test`, 0 failures); no test referenced the removed methods, or such tests are removed with them.

**Depends on:** #9 (client-side bulk-read offline rework landing). **Module:** server. **Tier:** Deferred.

**Resolution:** Confirmed via grep that the two routes were the only callers of `mark_all_read_handler`/`mark_feed_read_handler`, and that shared `FeedApi.markAllRead`/`markFeedRead` were already gone (clients fan out through `POST /v1/articles/read` per #9). Removed the `/feeds/{feed_id}/read` and `/articles/read-all` routes from `main.rs` (and the two names from the `handlers::{...}` import), the two handler fns from `handlers.rs`, the `Database::mark_feed_read`/`Database::mark_all_read` methods from `db.rs`, and their two dedicated tests (`test_mark_feed_read`, `test_mark_all_read`) from `db_tests.rs` — no other test exercised these routes/methods. Also dropped the two corresponding endpoint sections from `spec/API_DOCUMENTATION.md`. `cargo test`: 299 passed, 0 failed, 0 ignored (301 baseline − 2 removed tests).

---

### Group: Slow manual sync (`POST /v1/feeds/refresh`)

Pull-to-refresh shows the spinner for ~45 s. Diagnosed from a real logcat capture
(`logcat-sync-slow.log`): the spinner lifetime tracks a single blocking
`POST /v1/feeds/refresh`, which took ~45 s and ~43 s across two refreshes while the
sub-second `GET /v1/sync` and `GET /v1/feeds` that follow are negligible. During the
45 s the app is idle (only spinner frame draws logged). Root cause is in
[refresh_all_feeds_handler](server/src/api/handlers.rs#L1243-L1276): it fetches every
non-paused feed **sequentially and unconditionally**, so wall-clock is the *sum* of
each feed's upstream fetch, and one or two slow origins dominate. The client's
[FeedViewModel.refresh()](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L437)
awaits that call (`repository.refreshUpstream()`) before dropping the spinner.

**Observability (landed):** `refresh_all_feeds_handler` now times each feed fetch,
`warn!`s any single fetch ≥ 5 s (`SLOW_FEED_WARN_MS`), and logs a `"manual refresh
complete"` summary (total_ms, fetched/succeeded/failed/paused, top-3 slowest feeds)
via a testable `RefreshSummary`. Prod runs at the default `info` level with no
`RUST_LOG` override, so these lines will appear the next time a refresh is slow —
use them to confirm which origin(s) dominate before/while landing #126–#128.

#### #126 — Parallelize the manual refresh fetch loop `[x]`

Replace the sequential `for feed in feeds { process_feed(...).await }` in
`refresh_all_feeds_handler` with bounded-concurrency fetching (e.g.
`buffer_unordered(N)` / `for_each_concurrent(N, …)`), reusing the scheduler's
concurrency bound ([server/src/scheduler.rs:58](server/src/scheduler.rs#L58)) to stay
polite. Collapses wall-clock from the *sum* of fetch times to roughly the *slowest*
feed. Highest-leverage fix; should on its own resolve the reported symptom.

**Acceptance criteria**
- Refresh fetches feeds concurrently with a bounded limit (paused feeds still skipped).
- A test with multiple mock feeds (via `MockFeedServer`) asserts the handler fetches
  them concurrently rather than serially (e.g. total time ≈ slowest feed, not the sum),
  and returns the correct `feeds_fetched` count.
- `cd server && cargo test` passes, 0 failures.

#### #127 — Don't block the refresh spinner on the full upstream pull `[x]`

Even parallelized, one dead origin can stall the whole response. Make
`POST /v1/feeds/refresh` return promptly (kick the upstream fetch off in the background)
and have the client drop the spinner after the cheap `GET /v1/sync` re-read, surfacing
progress unobtrusively (e.g. a quiet "syncing…" indicator) instead of a blocking modal
spinner. Refresh latency should stop being bounded by the worst upstream server.

**Acceptance criteria**
- Manual refresh spinner clears within a small bound regardless of upstream feed latency
  (test drives a deliberately slow mock feed and asserts the spinner/`isRefreshing`
  clears without waiting for the slow fetch).
- New upstream articles still appear once fetched (subsequent `GET /v1/sync` picks them up).
- Shared/client test coverage for the non-blocking flow; `./gradlew :shared:allTests` passes.

#### #128 — Per-feed timeout in the refresh path `[x]`

Cheap robustness mitigation: wrap each `process_feed` call in the refresh handler with a
per-request timeout so a single hung origin can't hold the whole batch (and thus the
spinner) open indefinitely.

**Acceptance criteria**
- Each feed fetch in `refresh_all_feeds_handler` is bounded by a timeout; a timing-out
  feed is skipped/counted as failed without blocking the rest.
- A test with a mock feed that never responds asserts the handler still returns within
  the timeout bound.
- `cd server && cargo test` passes, 0 failures.

#### #129 — Split the refresh gesture: cheap server sync vs. explicit "force fetch from sources" `[x]`

**Builds on #182** (#126/#127/#128, in review) — does **not** invalidate it. #182 fixed
the *performance* of the upstream fan-out (parallelized, non-blocking response, per-feed
timeout) and added a client-side 5s safety timeout. #129 changes *which gesture triggers*
that fan-out, and is purely additive on top.

The reflexive refresh gesture (Android pull-to-refresh, web `↻`) calls
[FeedViewModel.refresh()](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L437),
which triggers the upstream fan-out (`repository.refreshUpstream()` →
`POST /v1/feeds/refresh`, fetches **all** non-paused feeds, bypassing the per-feed
interval gate) *then* a cheap DB re-read. After #182 this is fast and non-blocking, so
the **symptom** (45s spinner) is gone — but every reflexive pull *still* kicks a full
server-side fan-out of all origins, redundant with the scheduler that already polls them.
Meanwhile the cheap-only path (`repository.refresh()` — "reconcile the client with the
server's DB, no upstream") is wired only to the auto-poll timer and foreground-resume
([pollReadOnce](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L509)):
**no user gesture reaches it**, and in `manual` refresh-interval mode it never fires. There
is no first-class "ask the server for its latest state without hitting upstreams."

Owner-approved design (Option 1): the reflexive gesture becomes a **cheap server sync
only**; the upstream fan-out moves to a **new, explicit, warning-styled action in
Settings**. Post-#182 this is now a *semantics/origin-load* correction, not a latency fix —
so it is **no longer urgent** (the user-facing pain is resolved by #182). Reframes and
fully subsumes **#112** (which asked for "pull-to-refresh always queries the server" —
satisfied here by the cheap sync); #112 is closed by this ticket.

**Acceptance criteria**
- Android pull-to-refresh ([FeedScreen `PullToRefreshBox`](app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L304)) and the web refresh control (Sidebar `SyncStatus.Ok/Failed` onRefresh, `FeedScreen.kt` `viewModel.refresh()` sites) perform a **cheap server sync only** (`repository.refresh()`, no upstream call). Verified by a test asserting no `POST /v1/feeds/refresh` is issued on the gesture.
- A new Settings action (app `SettingsScreen.kt` + web `SettingsScreen.kt`), styled as a warning/destructive-tone action ("Force fetch from sources"), runs `repository.refreshUpstream()` then a re-read; it is rate-limit-aware (60s global `REFRESH_LIMITER`; surfaces the `429` "try again shortly"). Post-#182 the endpoint is **async** — `feeds_fetched` means "queued for background fetch," not "completed" — so surface it as "started fetching N sources," not a completion count.
- **Relocate, don't delete, #182's client-side upstream logic.** The `repository.refreshUpstream()` call, its `withTimeoutOrNull(REFRESH_UPSTREAM_TIMEOUT)` 5s wrapper, and the fall-through-to-plain-re-read (all added by #127 inside `FeedViewModel.refresh()`) move into the new `fetchFromSources()`/Settings path. The reflexive gesture must no longer call `refreshUpstream()` at all. Retarget `FeedViewModelRefreshUpstreamTimeoutTest` (added by #182) at the relocated method.
- The upstream action uses its **own** progress/loading state — it must not drive the sidebar `isRefreshing`/"Syncing…" indicator, so a slow fan-out never locks the article list.
- `FeedViewModel.refresh()` is split into two clearly-named methods (e.g. `syncFromServer()` cheap vs. `fetchFromSources()` upstream) to end the `refresh()`/`repository.refresh()`/`refreshUpstream()` ambiguity.
- Per-feed refresh (Subscriptions overflow "Refresh this feed" → `refreshFeed`) is unchanged (stays an explicit per-feed upstream pull).
- Open decisions resolved during implementation: (1) post-login refresh ([~FeedViewModel.kt:736](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt#L736)) — recommend downgrading to cheap sync since the server scheduler keeps the DB fresh; (2) error-retry paths (FeedScreen `onRetry`/snackbar retry) retry the cheap sync, not upstream; (3) whether the Settings upstream action needs a confirmation dialog (likely not, given the rate limit).
- Shared VM tests updated/extended (`FeedViewModelSyncStateTest`, `FeedViewModelFetchNowTest`, `FeedViewModelRateLimitTest`, `FeedViewModelRefreshFeedTest`, `FeedViewModelAutoPollTest`) to cover the new gesture→method mapping; `./gradlew :shared:allTests` and both client test suites pass.

**Resolution:** `FeedViewModel.refresh()` (the single method both the reflexive gesture and the "fetch now" upstream fan-out shared) was split into `syncFromServer()` (cheap `repository.refresh()` re-read only, no upstream call) and `fetchFromSources()` (the upstream fan-out, relocated here). A private `plainReRead()` helper holds the shared re-read + syncFailed/consecutiveFailures/isOffline/serverUnreachable/rate-limit bookkeeping used by both.

- **Reflexive gesture → cheap sync only.** Android `PullToRefreshBox` (via the app `FeedViewModel.syncFromServer()` wrapper, wired from `MainTabShell.kt`'s `onRefresh`/`onRetry` and `MainActivity.kt`'s parse-error `onRetry`) and the web refresh control (`Sidebar.kt`'s `SyncStatus.Ok`/`Failed` callbacks, `FeedScreen.kt`'s four `viewModel.refresh()` call sites) now all call `syncFromServer()`. New tests `syncFromServerNeverTriggersUpstreamPull(EvenOnFailure)` in `FeedViewModelSyncStateTest` assert `repo.refreshUpstreamCallCount == 0` after the gesture.
- **New Settings action.** Added a warning-styled "Force fetch from sources" row (danger-colored label, no confirmation dialog — the server's 60s `REFRESH_LIMITER` is the guardrail) to both `app/.../ui/settings/SettingsScreen.kt` (new "Advanced" section, `colors.danger` label matching the Logout row's style) and `web/.../ui/SettingsScreen.kt` (new "Advanced" section, danger-bordered button). Both call `fetchFromSources()`, show "Fetching…"/"Fetch now" based on `isFetchingFromSources`, and show the result message (`fetchFromSourcesResult`) as the row's hint. Success is phrased "Started fetching N source(s)." (matches `RefreshResult.Success.feedsFetched` = queued count, not completed); a 429 (`RefreshResult.RateLimited`) is phrased "Already fetching — try again shortly." with no error state.
- **Relocated #182 logic intact.** `fetchFromSources()` still wraps `repository.refreshUpstream()` in `withTimeoutOrNull(REFRESH_UPSTREAM_TIMEOUT)` and falls through silently (via `plainReRead()`) to the plain re-read on any non-401 failure/timeout/429 — same semantics as the old `refresh()`, just relocated. `FeedViewModelRefreshUpstreamTimeoutTest` was retargeted at `fetchFromSources()`/`isFetchingFromSources` (renamed from `isRefreshingClears…` to `isFetchingFromSourcesClears…`).
- **Own progress state.** `fetchFromSources()` uses `_isFetchingFromSources`/`isFetchingFromSources`, entirely separate from `_isRefreshing`/`isRefreshing` — verified by `fetchFromSourcesUsesOwnProgressStateNeverIsRefreshing` in `FeedViewModelFetchNowTest`. Deliberately does **not** touch the shared `rateLimitedUntil`/`rateLimitDuration` cooldown either (a 429 there is surfaced only via `fetchFromSourcesResult`), since the immediately-following `plainReRead()` on a typical successful re-read would just clear that cooldown again, and per this method's "own state" contract a 429 on the explicit action shouldn't pause the reflexive gesture's sidebar indicator.
- **Open decisions:** (a) post-login refresh downgraded to `syncFromServer()` only — `FeedViewModelLoginRefreshTest.loginTriggersImmediateRefresh` now asserts `refreshUpstreamCallCount == 0`. (b) error-retry paths (FeedScreen `onRetry`, parse-error retry, sync-error snackbar retry) all retry `syncFromServer()`. (c) no confirmation dialog on the Settings action — the 60s rate limiter already prevents runaway repeated taps.
- Per-feed refresh (`refreshFeed`) untouched.
- **Test plan:** `./gradlew :shared:allTests -PskipServerBuild` → 391 passed / 0 failed / 0 skipped (baseline 386). `./gradlew :web:jsTest -PskipServerBuild` → 539 passed / 0 failed / 0 skipped (baseline 537; +2 new tests for the web "Force fetch from sources" wiring). `./gradlew :app:testDebugUnitTest -PskipServerBuild` → 459 passed / 0 failed / 2 skipped (baseline 454; skips unchanged — the 2 `@Ignore`'d PullToRefresh gesture tests).

### #130 — Renumber the duplicate #122 (server bulk-read cleanup) + investigate the collision `[ ]`

TICKETS.md carries **two** entries both numbered `#122`: the "Subscriptions redesign: shared category model + management actions" ticket (P2 — Feature roadmap) and the "Remove client-orphaned bulk-read server endpoints" ticket (P3 — Infra hygiene). NEXT.md lists both too (Tier 3 and Deferred). The category-model #122 must keep the number because #123/#124 depend on and reference "#122"; the **server-cleanup entry** is the one to renumber. Rename it in place even if a concurrent work-cluster run has already marked it `[x] FIXED` — this is history cleanup.

**Root cause (already traced):** commit `e0a8eb5` (2026-07-06, "Plan the work to make mark as read offline-first") first assigned `#122` to the server-cleanup entry; two days later `5295883` (2026-07-08, "tickets") reused `#122` for the category-model entry when adding #122–#124, because ID assignment picked a "next" number without checking the existing max across the whole file.

**Acceptance criteria**
- Pick a fresh unused ticket ID (max existing + 1 at edit time) for the server-cleanup entry; update its TICKETS.md heading, its NEXT.md Deferred line, and any cross-references.
- `grep -n '#122' TICKETS.md NEXT.md` shows only the category-model ticket and its #123/#124 dependents — no server-cleanup collision remains.
- Add a one-line note on the root cause (ID assignment not checking existing max) and decide whether the add-task / ID-assignment flow needs a guard (e.g. scan for the true max, or fail on a collision). No test suite applies — this is docs/tooling hygiene; validate by grep.

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

### #21 — Investigate Metro DI (deferred) `[x]`

The top-level README has a note pondering whether to adopt [Metro](https://zacsweers.github.io/metro/latest/quickstart/) for DI. Currently DI is hand-rolled in `FeedApplication` + ViewModel `Factory`.

**Acceptance criteria** (when picked up)
- A short ADR-style note in the repo explains the decision (yes / no / not yet) and why.
- If yes: a single screen migrated as proof, with the rest of the migration tracked as a follow-up ticket.
- Low priority — revisit only once the Android side has noticeably more classes (post-#3/#4/#5).

**Resolution**: most likely never required.

---

### #64 — Out-of-band article link probe job `[x]`

Per-article HEAD probes currently run serially inside the main fetch loop (see [server/src/fetcher.rs](server/src/fetcher.rs) `probe_article_link`). F3 added a 5-second per-probe timeout and skips non-http(s) schemes as a mitigation, but a fresh feed with many new articles still blocks the scheduler tick proportionally. The right fix is a dedicated background job that probes links outside the fetch cycle.

Dev's note: we should weight that work against being good citizens and not making too many requests to feed providers.

**Acceptance criteria**
- A periodic background task (e.g., via `tokio-cron-scheduler`) probes `link_status IS NULL` article links in batches, independent of the feed-fetch scheduler.
- The per-probe timeout remains ≤ 5 s; concurrency within each batch is bounded (e.g. 10 concurrent HEAD requests) to avoid overwhelming the server's outbound connection pool.
- The fetch loop stops calling `probe_article_link` entirely; `link_status` is initially `NULL` and filled in by the background job.
- Existing tests for link-status probing are adapted to drive the new background job directly.

**Resolution:** Removed the inline HEAD-probe call from `FeedFetcher::process_feed` in [server/src/fetcher.rs](server/src/fetcher.rs) — new articles are now inserted with `link_status = NULL` and the fetch loop no longer touches it at all. Added a new `probe_pending_links(client, db, batch_size, concurrency)` function (same file) that queries `Database::get_articles_with_null_link_status` (new query in [server/src/db.rs](server/src/db.rs), `SELECT * FROM articles WHERE link_status IS NULL ORDER BY fetched_at ASC LIMIT ?`), then fans the batch out with a `tokio::task::JoinSet` gated by a `tokio::sync::Semaphore` (default concurrency 10) so at most N HEAD requests are in flight at once; each individual probe still reuses the existing `probe_article_link` helper and its 5-second timeout / non-http(s)-scheme skip unchanged. Wired a new cron job into [server/src/scheduler.rs](server/src/scheduler.rs)'s `setup_scheduler` (new `link_probe` constants module: 2-minute tick, 50-article batch, concurrency 10 — reusing `build_fetch_cron` for the cron expression, same pattern as the existing feed-fetch job) so probing runs on its own schedule, fully decoupled from the feed-fetch scheduler tick. Adapted the three existing link-probe tests in [server/src/fetcher_tests.rs](server/src/fetcher_tests.rs) to drive `probe_pending_links` directly instead of relying on `process_feed`, added a test asserting `process_feed` now leaves `link_status` `NULL`, and added two new tests: one for batch-size bounding/draining a backlog across multiple calls (`test_probe_pending_links_respects_batch_size`), and a DB-level test for the new query's filtering/limit behavior (`test_get_articles_with_null_link_status_filters_and_limits` in [server/src/db_tests.rs](server/src/db_tests.rs)). Validated: `cargo test` — 289 passed, 0 failed, 0 ignored (baseline was 286; +3 net new tests, 0 regressions). `cargo clippy --all-targets` — clean.

---

### #36 — Investigate feed-hue collisions `[x]`

SUBS-5 noted that two feeds with different names rendered the same avatar hue. The hue derivation is `abs(id.hashCode()) % 360` (Phase 1 implementation uses `ushr 1` to avoid `Int.MIN_VALUE` overflow), keyed off feed id, so identical hues across two ids are plausible at small N but worth checking — are we seeding from the right field, and is the modulo bucketing producing visible clashes on typical id ranges?

**Acceptance criteria**
- Audit `FeedHue` against real feed ids from a populated server; document whether observed collisions are at the expected rate.
- If the rate is unacceptable, switch to a better mixing function (e.g. xxhash of the feed's URL or title rather than the id's `hashCode()`), or shift to a curated palette of N hues distributed around the wheel.
- A unit test pins the chosen mapping so future changes are deliberate.

**Resolution:** Audited `feedHue` in [shared/src/commonMain/kotlin/eu/monniot/feed/shared/util/FeedHue.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/util/FeedHue.kt). The old derivation was `(feedId.hashCode() ushr 1) % 360`; since `Int.hashCode()` is the identity function in Kotlin (`this`), that reduced to `feedId / 2 % 360` for non-negative ids. Feed ids are auto-increment primary keys — small sequential integers — so this mapping guaranteed that **every consecutive pair of ids (2k, 2k+1) collided on the exact same hue**, for any id range, plus produced a visibly linear (not well-distributed) walk around the hue wheel. That's categorically worse than ordinary birthday-paradox collisions: a simulation over sequential ids confirmed ~50% of all feeds collided with their immediate neighbor regardless of N (e.g. 1..50 → 24 colliding entries out of 50, 1..200 → 99 out of 200 — essentially every pair), which matches exactly the SUBS-5 report of two differently-named feeds sharing a hue. For comparison, a well-mixed hash over 360 buckets would only be expected to produce ~42% probability of *any* collision at N=20 feeds and far fewer colliding entries at N=50/200 (birthday-bound, not a guaranteed clash). Given this is well above the acceptable/expected birthday-paradox rate for a realistic subscription count (20-200 feeds), the mapping needed fixing. Fix: `feedHue` now runs the id through a dependency-free splitmix64-style bit-mixer (`mix64`, the same finalizer used by `SplittableRandom`/Kotlin's `Random`) before reducing mod 360, instead of relying on the raw identity-hashCode. This spreads sequential ids uniformly across the wheel; a re-run of the same simulation post-fix shows the birthday-paradox-expected rate (1..200 → 46 colliding entries, matching the expected-distinct-count math for 200 uniform draws over 360 buckets: 360 × (1 − (359/360)²⁰⁰) ≈ 153.6 distinct hues, i.e. ≈46.4 colliding entries — and with **no guaranteed adjacent-id clash**; the actual adjacent-collision count is 0). Pinned the new mapping with explicit expected outputs for a fixed set of ids (0, 1, 2, 42, 100, -5, `Int.MAX_VALUE`, `Int.MIN_VALUE`) plus a regression test asserting the sequential-id (1..200) collision count stays in a tight band around the expected value (35-60, actual 46) and that adjacent-id collisions stay near the actual count of 0 (≤5 allowed), so a future low-entropy mixer would be caught. Validated via `./gradlew :shared:allTests` — 308 passed, 0 failed, 0 skipped (up from a 301-test baseline; net new: 2 new test methods plus expanded assertions in the existing pinned-value test) — and `./gradlew :web:jsTest`, which exercises `feedHue` dynamically in `SubsScreenRenderTest` and also passed.

### #96 — Reduce per-test resource churn in Android JVM integration tests `[x]`

The `FeedViewModel*` / `OpmlImportIntegrationTest` integration tests use a per-test (`@get:Rule`) `ServerRule` that spawns a fresh Rust server subprocess for **every test method**, plus a new CIO `HttpClient` and a full argon2id login in each `@Before`. Across ~30 methods running 2–4 per fork on CI, this churns dozens of server subprocesses + clients + leaked `viewModelScope` coroutines, oversubscribing the 4-core runner and causing flaky coroutine-scheduling timeouts. This has been proposed as the proper fix in **three** separate bug-fix sessions (most recently PR #73) and deferred each time as too large — worth a dedicated investigation rather than another round of mitigations.

Prior mitigations already landed (do not re-litigate): cheap test argon2id hash (`m=8`), a shared 30s hang-guard (`INTEGRATION_WAIT_MS`), and a dormant `TestDiag` instrumentation harness (`app/src/test/java/eu/monniot/feed/integration/TestDiagnostics.kt`, enable with `-PtestDiag=true`).

**Key finding from the PR #73 telemetry (the actual root signal):** there are *two* failure modes, and they pull in opposite directions on fork count:
1. **CPU-busy stall** — `sysCpu≈1.0`, high load average: coroutine-scheduling starvation under oversubscription. Helped by *fewer* forks / cheaper logins.
2. **CPU-idle stall** — `sysCpu≈0.03`, load `≈2.8` on 4 cores, **~100+ threads/fork**, a login continuation un-resumed for the full 30s timeout. This is a resource/thread-pool **deadlock from accumulation**, not contention — and it got *worse* with fewer forks (2-fork run: 10 failures), because longer-lived forks run more test classes and accumulate more leaked per-test resources before deadlocking. More, shorter-lived forks (4) emptied the queue before the deadlock triggered (slowest wait 119ms vs a 30s ceiling).

The accumulating resources are the per-test `HttpClient(CIO)` thread pools and the never-cancelled `viewModelScope` coroutines: the app-level `FeedViewModel` (`app/src/main/java/eu/monniot/feed/FeedViewModel.kt`) only cancels its scope via `onCleared()`, which the tests never trigger, so each test's post-login refresh + `stateIn` collectors leak (visible as `POST /v1/feeds/refresh -> EXC CancellationException` at every tearDown). Fork-count tuning only trades mode 1 against mode 2; **only removing the per-test churn fixes both.**

**Acceptance criteria**
- Quantify the per-test cost (server spawns, client/thread churn, peak thread count per fork) using the `TestDiag` harness; capture before/after numbers.
- Eliminate the leak/churn: e.g. per-class `@ClassRule` `ServerRule` (one spawn per class), a shared client/login per class, and/or cancelling the VM scope in `tearDown` — with a test-isolation review, since some tests assume a fresh/empty server (e.g. `loadFeeds with no feeds produces empty list`).
- Demonstrate neither failure mode (CPU-busy starvation nor CPU-idle accumulation deadlock) reproduces under the diagnostic harness, with peak threads/fork staying flat across a class instead of climbing to ~100.

---

### #125 — Android per-feed article browsing (FEED-2 gap) `[ ]`

The Subscriptions redesign (#124) makes the Android **Feeds** tab a category manager rather than a browse surface, so there is currently **no way to filter the article list to a single feed on mobile** — FEED-2 is now web-only ([FEATURES.md](spec/FEATURES.md) FEED-2). Design and implement a mobile entry point for per-feed browsing once there's an agreed shape; the local mirror already supports the filter (no server work).

**Acceptance criteria**
- A design for reaching one feed's article list on Android (e.g. tap-through from a manager row with `⋯` reserved for management, a category/feed picker on the Unread/All headers, or a dedicated route) — pick one and note the trade-off.
- Implement it against the existing local-filter path (Article sync contract); no per-feed server fetch.
- Update FEATURES.md FEED-2 back to `both` and cover the mobile path with a test.

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

### Group: Feed errors on Subscriptions (#79)

Surfaces *why* a feed is failing and how to fix it, consolidated onto the Subscriptions screen. Spec: [FEATURES.md §Feed errors](spec/FEATURES.md) and [VISUAL_SPEC.md §Subscriptions feed-error surface](spec/VISUAL_SPEC.md); story-board artboards under **Subscriptions · Feed errors** ([spec/story-board/prototypes/subscriptions-errors.jsx](spec/story-board/prototypes/subscriptions-errors.jsx)). #79 is the umbrella; #81–#86 are the implementation slices.

#### #79 — Feed errors on Subscriptions (umbrella) `[ ]`

When a feed's background sync fails, users need to understand *why* and have the tools to fix it. The end state is specced in [FEATURES.md §Feed errors](spec/FEATURES.md) (ERR-7, ERR-8, ERR-15–ERR-17, SUBS-6–SUBS-9) and [VISUAL_SPEC.md §Subscriptions feed-error surface](spec/VISUAL_SPEC.md).

**Design decision (2026-06-21): consolidate on Subscriptions.** Per-feed errors surface in exactly two places — the `!` badge (signal) on the sidebar / Feeds-tab row, and the Subscriptions screen (non-interactive summary banner above the search bar + per-row inline accordion carrying a mono diagnostic block, a one-sentence explanation, and context-dependent actions). A single broken feed **never** takes over the reading experience. This **supersedes** the shipped per-feed treatments from the #46 edge-case group:

- **#57** dead-feed (410) big mid-pane takeover — to be removed; opening a dead feed now just shows its cached articles.
- **#58** parse-error banner over the article list — to be removed. The **raw-response inspector** from #58 is **kept** and re-pointed to the accordion's `View raw ↗`.

Implementation is split across:

- **#81** — server: feed-health severity (warn vs error) + diagnostic fields in the feeds API.
- **#82** — server: edit a feed's source URL (`Fix URL…`).
- **#83** — shared: feed-error view-model + human-explanation / action-set / diagnostic mapping.
- **#84** — web: Subscriptions summary banner + broken-row + inline accordion + actions.
- **#85** — Android: Feeds-tab summary banner + broken-row + inline accordion + actions.
- **#86** — remove the superseded #57 big mid-pane + #58 list banner; re-point the inspector + tone the sidebar badge.

Closing all six closes #79. Also resolves the user-facing intent behind **BUG-23** (Android repetitive parse-error messages).

#### #81 — Server: feed-health severity + diagnostic fields in the feeds API `[x]`

Part of **#79**. The accordion needs richer, server-classified data than today's `{ok, error, parse_error, dead}` `feed_status`. The server already tracks `error_count`, `last_fetched`, `consecutive_410_count`, `first_410_at`, and the `feed_parse_errors` row; this ticket adds the **severity** dimension and the missing diagnostic fields.

**Acceptance criteria**
- Classify each failing condition into a **severity** the API exposes alongside `feed_status`: `error` for 410 / parse / HTTP 4xx (non-410), `warn` for HTTP 5xx and network-layer failures (DNS, connection refused, timeout). See the trigger table in [FEATURES.md §Feed errors](spec/FEATURES.md).
- Persist + expose, per feed, what the accordion's mono block renders: the **last HTTP status** (or network-error kind), the **last-attempt** timestamp (already `last_fetched`), the **consecutive-failure count** for the active condition, and the **next-retry** time (or a `retries_paused` flag for dead feeds).
- Surface all of it on the feeds-list and single-feed endpoints as additive fields (older clients ignore them).
- Migration follows the inline `if version < N` convention in [server/src/db.rs](server/src/db.rs); a test in [server/src/db_tests.rs](server/src/db_tests.rs) exercises the new columns, and a handler test asserts the API serializes severity + diagnostic fields for an `error`, a `warn`, and a `dead` feed.
- Update [spec/API_DOCUMENTATION.md](spec/API_DOCUMENTATION.md) — the feed object's `feed_status` / severity / diagnostic fields are currently undocumented.

**Resolution:** Migration v19 adds `last_error_kind` (TEXT) and `last_http_status` (INTEGER) columns to the `feeds` table. The fetcher classifies each error condition and writes the error kind + HTTP status on every failure; success paths clear both. `FeedWithUnread` derives `severity`, `consecutive_failure_count`, `retries_paused`, and `next_retry_at` from the stored state — no new columns for computed fields. Both the feeds-list (`GET /feeds`) and single-feed (`GET /feeds/{id}`) endpoints now return the diagnostic fields; the single-feed endpoint was upgraded from raw `Feed` to `FeedWithUnread`. API docs updated. 14 new tests cover migration columns, error/success lifecycle, severity derivation for each condition (410, dead, 5xx, network, parse, 4xx), healthy feeds, and JSON serialization.

#### #82 — Server: edit a feed's source URL (`Fix URL…`) `[x]`

**Resolution:** Extended `PUT /v1/feeds/{id}` with an optional `url` field. When provided and different from the current URL, the server revalidates by fetching + parsing (same as `POST /v1/feeds`). On success, `update_feed_url` atomically updates the URL, sets the new feed title, and clears error/dead state (`error_count`, `consecutive_410_count`, `first_410_at`, parse errors, cache headers). On failure, the request is rejected with the same error shape as add-feed. The feed's `id`, `category_id`, `custom_title`, and existing articles are preserved. Five new DB-level tests cover: successful URL change with error reset, parse error clearing, category/custom_title preservation, article survival, and nonexistent feed handling. API docs updated.

Part of **#79**. The accordion's `Fix URL…` action edits a feed's *source* URL — distinct from Rename, which only sets `custom_title`. Today no endpoint changes the URL a feed is fetched from.

**Acceptance criteria**
- Extend the feed-update path (`PUT /v1/feeds/{id}`) to accept a new source `url`. On change, the server **revalidates** the URL (fetch + parse) before committing, the same way `POST /v1/feeds` validates a new feed.
- A valid new URL clears the feed's error / dead state and resets the relevant failure counters; an invalid one is rejected with the same error shape add-feed uses (so the client can keep the accordion in its error state with an inline message).
- Editing the URL keeps the feed's id, category, `custom_title`, and existing articles intact.
- Tests cover: successful URL change (revalidates + clears error), rejected invalid URL (state unchanged), and that articles + id survive the change.
- Update [spec/API_DOCUMENTATION.md](spec/API_DOCUMENTATION.md).

#### #83 — Shared: feed-error view-model + explanation/action mapping `[x]`

Part of **#79**. A platform-agnostic mapping turns the server's status + severity + diagnostic fields into what the accordion renders, so web and Android stay identical and it's unit-testable on the JS target.

**Acceptance criteria**
- Extend `FeedUiItem` ([FeedViewModel.kt](shared/src/commonMain/kotlin/eu/monniot/feed/shared/FeedViewModel.kt)) with the new fields from #81 (severity, last HTTP status, consecutive-failure count, last attempt, next retry / paused).
- Add pure functions mapping `(feed_status, severity, http status, parse error)` → (a) the **badge label** (`410 GONE`, `PARSE FAIL`, `HTTP 500`, `HTTP 404`, …), (b) the **mono diagnostic lines**, (c) the **one-sentence human explanation**, and (d) the **action set** (`Retry now` / `Retry once` / `Fix URL…` / `View raw ↗` / `Unsubscribe`) per the FEATURES.md contract.
- Add a Subscriptions-level summary derivation: total failing, failing-vs-warning split, and whether the summary banner demotes to warn tone.
- `:shared:allTests` covers each condition (410/dead, parse, 4xx, 5xx, network) → expected badge / tone / actions, and the summary derivation (all-warn demotes; mixed stays error).

#### #84 — Web: Subscriptions feed-error UI `[x]`

Part of **#79**. Spec: [VISUAL_SPEC.md §Subscriptions feed-error surface](spec/VISUAL_SPEC.md), [FEATURES.md](spec/FEATURES.md) SUBS-6–SUBS-9.

**Acceptance criteria**
- Non-interactive summary banner above the search bar when ≥ 1 feed is failing (count chip + failing/warning sentence + last-checked; demotes to warn tone when all-warn; absent when none).
- Broken feed rows keep their folder position with a dimmed avatar (0.6), tone badge, time-since-failure, and chevron; tapping toggles an inline accordion (mono block + explanation + actions) with a 3px tone left-border. Healthy rows unchanged.
- Wire actions: `Retry now` / `Retry once` → `POST /v1/feeds/{id}/refresh` (Retry once does not un-pause); `Fix URL…` → inline source-URL editor (#82); `View raw ↗` → existing raw-response inspector; `Unsubscribe` → `DELETE /v1/feeds/{id}` with confirm. On success the row returns to healthy and the badge clears.
- Sidebar feed-row `!` badge takes the feed's tone (error / warn) and clears on next success.
- `:web:jsTest` covers: banner presence + tone demotion; broken-row badge + accordion toggle; each action firing the right call.

#### #85 — Android: Feeds-tab feed-error UI `[x]`

Part of **#79**. Spec as #84, mobile values from [VISUAL_SPEC.md §Subscriptions feed-error surface](spec/VISUAL_SPEC.md) and §Mobile · Feeds.

**Acceptance criteria**
- Summary banner above the search box; broken rows under their uppercase folder headers (dimmed avatar, tone badge, time-since, chevron); tap toggles the inline accordion.
- Same action wiring as #84; `View raw ↗` pushes the full-screen raw-response inspector (tab bar hidden).
- Feeds-tab `!` badge takes the feed's tone and clears on success.
- `:app:testDebugUnitTest` covers banner, broken-row + accordion toggle, and each action.

#### #86 — Remove superseded per-feed big mid-pane + parse banner; re-point inspector `[x]`

Part of **#79**. The consolidation decision (see #79) drops two shipped treatments and keeps a third.

**Acceptance criteria**
- Remove the **dead-feed (410) big mid-pane takeover** (#57) on web + Android: opening a dead feed shows its cached articles like any feed; the `!` badge + Subscriptions accordion are the only feed-gone surfaces.
- Remove the **parse-error banner over the article list** (#58) on web + Android: a parse-failing feed's list shows cached articles unchanged with no banner.
- Keep the **raw-response inspector** (#58) and re-point its sole entry to the accordion's `View raw ↗` (remove the old banner-link / snackbar-`Details` entry points).
- Remove the now-unused `line-through` + 0.55-opacity dead-feed styling in the sidebar.
- Tests that asserted the removed surfaces are deleted or repurposed; add/adjust tests asserting a dead / parse feed's list renders normally (no takeover, no banner) and the inspector still opens from the accordion.
- The story board's stale **Edge cases · Feed & article errors** artboards (feed-gone mid-pane, parse banner) are a design-side cleanup — note for the next design pass; spec already supersedes them.

#### #91 — Subscriptions error accordion: wire `Fix URL…` and `View raw ↗` actions `[x]`

Part of **#79** follow-up. Both [FEATURES.md §Feed errors](spec/FEATURES.md) (line 121–129) and the completed tickets #84–#85 specify that each error accordion should offer a context-dependent action set including `Fix URL…` (to change a feed's source URL) and `View raw ↗` (to inspect the raw response). These actions are currently missing from the Subscriptions UI on both web and Android.

**Acceptance criteria**

**Web + Android (both clients)**
- The error accordion's action set now includes `Fix URL…` and `View raw ↗` alongside `Retry now` / `Retry once` / `Unsubscribe`.
- `Fix URL…` opens an inline editor for the feed's source URL (distinct from the rename/custom-title action). On save, it calls `PUT /v1/feeds/{id}` with the new `url` field (see #82). Success clears the error state and closes the editor; a validation error from the server stays the editor open and shows the error inline.
- `View raw ↗` navigates to the raw-response inspector (from #86), passing the feed's parse error + last response body if available.
- Each action is only shown when appropriate per the spec: `Fix URL…` for parse errors and HTTP errors; `View raw ↗` primarily for parse failures.
- `:web:jsTest` and `:app:testDebugUnitTest` cover: both actions render when applicable, `Fix URL…` submits the correct PUT request with the new URL, `View raw ↗` navigates to the inspector view.

**Note:** #84 and #85 were closed as complete, but these specific actions were not implemented. This ticket closes the gap.

#### #93 — Web: show overflow menu on broken feed rows `[x]`

Part of **#79** follow-up. After #84 and #91, broken feed rows on the Subscriptions screen show an expandable error accordion but lost access to the regular overflow menu actions (rename, set folder, fetch interval, pause/resume). This ticket restores the overflow menu (⋯) alongside the chevron on broken rows, so management actions are available regardless of feed health.

**Acceptance criteria**
- Broken feed rows on the Subscriptions screen render both the error indicators (time-since + chevron) AND the overflow menu button (⋯).
- The overflow menu contains the same items as healthy rows: Refresh, Rename, Set folder, Fetch interval, Pause/Resume, Delete.
- Clicking ⋯ does NOT toggle the accordion (existing `stopPropagation` + `closest("button")` guard).
- `feedRowNoViewModel` (test renderer) also renders the overflow menu for broken rows.
- `:web:jsTest` covers: broken row has overflow button, broken row overflow menu contains all actions.

#### #94 — Android: show overflow menu on broken feed rows `[x]`

Part of **#79** follow-up. Android equivalent of #93. After #85, broken feed rows on the Feeds tab show the error accordion but lack the regular overflow/context menu actions (rename, set folder, fetch interval, pause/resume).

**Acceptance criteria**
- Broken feed rows on the Feeds tab render an overflow menu (or long-press context menu) alongside the error chevron, offering the same management actions as healthy rows.
- Tapping the overflow menu does not toggle the accordion.
- `:app:testDebugUnitTest` covers: broken row has overflow/context menu, menu contains all expected actions.

**Resolution:** `FeedRow` in `app/src/main/java/eu/monniot/feed/ui/subs/SubscriptionsScreen.kt` only rendered the overflow menu (`Box` + `IconButton` + `DropdownMenu`) in the healthy-feed branch. Extracted the menu into a shared private `FeedOverflowMenu` composable and render it in both branches — broken rows now show it alongside the time-since/chevron column. Because the menu's `IconButton` has its own `clickable`, tapping it is consumed before it reaches the row's outer `clickable(onClick = onToggleAccordion)`, so opening the menu never toggles the accordion (no extra guard needed). Added `overflow_menu_<id>` and per-item test tags (`menu_rename_<id>`, `menu_set_folder_<id>`, `menu_pause_resume_<id>`, `menu_delete_<id>`) reused by both row types. Covered by new tests in `SubscriptionsScreenTest.kt`: `brokenFeedRow_hasOverflowMenu`, `brokenFeedRow_overflowMenuContainsAllExpectedActions`, `brokenFeedRow_openingOverflowMenuDoesNotToggleAccordion`, `brokenFeedRow_overflowMenu_renameInvokesCallback`, `brokenFeedRow_overflowMenu_refreshInvokesCallback`, and a `healthyFeedRow_alsoHasOverflowMenu` regression guard.

---

### #108 — Badge shows full unread count; implement pagination for frontends `[x]`

Task #103 incorrectly assumed the badge count should be capped at 50 (the page window size). In reality, the badge must show the **full unread count** regardless of how many articles fit in one page. Additionally, frontends need **pagination logic** to load and display articles beyond the initial window when the unread count exceeds the page size.

**Acceptance criteria — server / API**
- Verify the server's `/v1/sync` endpoint already returns paginated data (cursor-based). No changes needed if the contract is already sound.
- Document the pagination contract clearly in [spec/API_DOCUMENTATION.md](spec/API_DOCUMENTATION.md) (window size, cursor semantics, how to fetch the next page).

**Acceptance criteria — shared**
- The shared `FeedViewModel` exposes a **loadMore()** function that fetches the next page of articles using the cursor.
- The unread badge is **always** sourced from `observeUnreadCount()` — a full aggregate count — never from the windowed list size.
- Tests verify that `observeUnreadCount()` returns the full count even when the page window is smaller (e.g., seed 100 unread, verify badge shows 100 not 50).

**Acceptance criteria — clients (web + Android)**
- The article list UI shows a **load-more affordance** (e.g., a button or auto-load-on-scroll) that calls `loadMore()` to fetch the next page.
- Once loaded, subsequent pages are appended to the displayed list (not replacing the first page).
- The unread badge in the Unread filter reflects the full unread count, not just the first page.
- An integration test per platform verifies: seed a feed with > 50 unread articles, assert the badge shows the full count, load more, assert the older articles appear.

**Depends on:** #101 (shared mirror-backed repository), #102 (Room store), #104 (IndexedDB store).
**Module:** server + shared + clients.

---

### #109 — Android: standardize button sizes across screens `[x]`

Android UI buttons currently vary in size across different screens and use cases. This ticket covers a systematic sweep to establish consistent button dimensions (height, padding, text styling) throughout the app.

**Acceptance criteria**
- Audit all button components in the codebase (primary buttons, secondary buttons, action buttons, etc.) and document current size variations.
- Define a standardized set of button styles (e.g., `ButtonSizeLarge`, `ButtonSizeMedium`, `ButtonSizeSmall` or similar) with explicit height, padding, and font size values.
- Apply the standardized sizes to all Compose button usages across [app/src/main/kotlin/eu/monniot/feed/android/](app/src/main/kotlin/eu/monniot/feed/android/) screens (login, feed list, reader, etc.).
- Visual verification: take screenshots before and after to confirm visual consistency and no layout regressions.
- Shared `ButtonStyle` definitions go in a reusable component (e.g., `Theme.kt` or a dedicated `Buttons.kt` file) to prevent future drift.
- Test coverage: Compose preview or screenshot test captures the standardized button set to block future regressions.

**Resolution:** Audited every button-shaped composable under `app/src/main/java/eu/monniot/feed/ui/` and found three de-facto size tiers hand-rolled with inconsistent padding — most notably the add-feed dialog's "Add" (18/10dp) and "Cancel" (12/6dp) buttons using *different* padding in the same row. Added [app/src/main/java/eu/monniot/feed/ui/theme/Buttons.kt](app/src/main/java/eu/monniot/feed/ui/theme/Buttons.kt) with a `ButtonSize` enum (`Large`/`Medium`/`Small`) exposing explicit `minHeight`/`contentPadding`/`fontSize` tokens via `ButtonSize.tokens()`, plus reusable `FeedButton`/`FeedTextButton` wrapper composables so new dialog actions inherit standardized sizing automatically. Applied the tokens to the login CTA (Large), all dialog confirm/cancel/OK actions and the add-feed row and "Load more" button (Medium), and the reader top-bar cluster + subscription error-accordion actions (Small) — colors, labels, and behavior untouched. `ButtonsTest.kt` pins the token values for all three tiers and renders `FeedButton`/`FeedTextButton` under Robolectric to confirm they render, are clickable, and meet their tier's minimum height.

**Depends on:** nothing.
**Module:** android.

---

### #111 — Fix gradle warnings `[x]`

The gradle build produces deprecation warnings in both web and Android modules, and the Android Gradle Plugin (AGP) should be upgraded to the latest stable version for security and feature improvements.

**Acceptance criteria**
- Run `./gradlew clean :web:build :app:build` and document or suppress all remaining gradle warnings with clear justification comments.
- Address deprecated gradle APIs and task configurations in both [web/build.gradle.kts](web/build.gradle.kts) and [app/build.gradle.kts](app/build.gradle.kts).
- Verify no regressions: `./gradlew :web:jsTest :app:testDebugUnitTest` passes with the same test counts as before.
- Update [CONTRIBUTING.md](CONTRIBUTING.md) if the AGP upgrade requires new setup steps or minimum gradle/JDK versions.

**Resolution:** AGP was already at 9.2.1 (Gradle 9.6.1) going into this ticket — the latest stable at the time — so no version bump was needed; a prior pass on this branch had already confirmed and dropped the "upgrade AGP" acceptance criterion. Work focused entirely on the deprecation warnings from `./gradlew clean :web:build :app:build --warning-mode all`:
- **Fixed — Kotlin DSL delegate syntax**: `val name by tasks.registering(Exec::class) { }` in [app/build.gradle.kts](app/build.gradle.kts) → `tasks.register<Exec>("name") { }`; `val name by getting { }` in [shared/build.gradle.kts](shared/build.gradle.kts) and [web/build.gradle.kts](web/build.gradle.kts) (two occurrences) → `getByName("name") { }`. Both syntaxes are removed in Gradle 10.
- **Fixed — deprecated `AndroidSourceDirectorySet.srcDir(Any)`**: the Room-schema asset wiring in [app/build.gradle.kts](app/build.gradle.kts) now mutates `.assets.directories` directly instead of calling the deprecated `srcDir(...)`.
- **Fixed — `androidLibrary { }` renamed to `android { }`**: discovered mid-ticket that AGP 9.2 itself renamed the KMP library extension block introduced in AGP 9.0 (see CLAUDE.md's "AGP 9.0 KMP library plugin" pitfall, now updated to "AGP 9.2"). Verified via AGP 9.2.1 sources (`KotlinMultiplatformAndroidPlugin.kt`: `DEPRECATED_ANDROID_EXTENSION_ON_KOTLIN_EXTENSION_NAME = "androidLibrary"` vs. `ANDROID_EXTENSION_ON_KOTLIN_EXTENSION_NAME = "android"`) that it's a pure rename of the same `KotlinMultiplatformAndroidLibraryExtension` type, still nested inside `kotlin { }` — not the classic top-level `com.android.library` `android { }` block. Renamed in [shared/build.gradle.kts](shared/build.gradle.kts).
- **Fixed — stale `gradle.properties` AGP-migration flags**: removed all 8 `android.*` overrides that an AGP 8→9.0 upgrade assistant had added automatically to pin pre-9.0 default behavior (flagged in a `TODO` comment, never revisited). 5 of them (`enableAppCompileTimeRClass`, `usesSdkInManifest.disallowed`, `r8.optimizedResourceShrinking`, `defaults.buildfeatures.resvalues`, `sdk.defaultTargetSdkToCompileSdkIfUnset`) were actively warning and slated for removal in AGP 10; the other 3 (`uniquePackageNames`, `dependency.useConstraints`, `r8.strictFullModeForKeepRules`) were silent no-ops already superseded by AGP 9 defaults. Verified safe to drop: no `resValue` usage anywhere in the repo, `targetSdk` is set explicitly, release build has `isMinifyEnabled = false` (so R8/resource-shrinking behavior changes don't currently apply), no custom ProGuard keep rules, no `<uses-sdk>` in the manifest, and the two modules already have distinct namespaces. Justification recorded as a comment in [gradle.properties](gradle.properties).
- **Justified and kept (not fixable from this repo)**: a "Using a Project object as a dependency notation has been deprecated" warning traces (via `--stacktrace`) into AGP's own internals (`VariantDependenciesBuilder.build` / `VariantManager.createTestComponents`), not into our `implementation(project(":shared"))` calls, which already use the recommended `DependencyHandler.project(String)` form. Documented with a comment in [app/build.gradle.kts](app/build.gradle.kts). Also left as-is: the `commonTest` exists but android host tests are not enabled" warning in [shared/build.gradle.kts](shared/build.gradle.kts) — `commonTest` there is pure-logic and already covered on the JS target via `:shared:allTests`; Android-specific behavior has its own dedicated JVM integration tests in `app/`, so a redundant Robolectric host-test run isn't warranted. Also noted but out of scope: a Node.js `DEP0169 url.parse()` warning from Kotlin/JS's bundled webpack/yarn tooling (third-party npm internals, pre-existing, unrelated to any Gradle/Kotlin file in this repo) and webpack's asset-size-limit advisories (bundle-size guidance, not a deprecation).
- Kotlin/Compose compiler warnings in application and test code (`createComposeRule` v1→v2 migration, `rememberSwipeToDismissBoxState`'s `confirmValueChange`, a non-AutoMirrored icon, a couple of always-true conditions and one unnecessary safe call) are pre-existing code-quality warnings, not gradle/build-config warnings, and were left untouched as out of scope for this ticket.
- Verified after all changes: `./gradlew clean :web:build :app:build` builds successfully with zero remaining fixable deprecation warnings (confirmed via `--warning-mode all`); `./scripts/test-counts.sh android` → 394 passed, 0 failed, 2 skipped (matches baseline exactly); `./scripts/test-counts.sh web` → 480 passed, 0 failed, 0 skipped (baseline was 479 passed, 0 failed, 0 skipped — no regressions, no new skips); `:shared:allTests` also passes cleanly. No CONTRIBUTING.md changes needed since AGP/Gradle/JDK minimums didn't change.

---

### #114 — Re-tune `maxParallelForks` for the Android JVM tests now that #96 removed the accumulation deadlock `[ ]`

Ticket #96 (PR #152) removed the per-test resource-churn that caused the CPU-idle accumulation deadlock by sharing one Rust server + one `HttpClient(CIO)` per test class. PR #73 had previously *raised* the fork count specifically to outrun that now-fixed deadlock (more, shorter-lived forks emptied the queue before it triggered). With the accumulation root cause gone, that rationale no longer applies, and the residual CI flakiness is CPU-busy starvation (mode 1): under `load1m≈8` on the 4-core runner the shared CIO client can starve — one of four `-PtestDiag=true` measurement runs on #152 hit a single ~95s ConnectTimeout tail stall in the reset path (masked by the `test-retry` plugin, build stayed green). Fewer forks should mean less CPU oversubscription and fewer mode-1 stalls. Lower priority than #96 since retry already keeps CI green.

**Acceptance criteria**
- Measure test wall-time and first-attempt flaky-failure rate across at least fork counts `maxParallelForks` = 2, 3, 4 on CI, using the existing `-PtestDiag=true` harness ([app/src/test/java/eu/monniot/feed/integration/TestDiagnostics.kt](app/src/test/java/eu/monniot/feed/integration/TestDiagnostics.kt)) and the `-PtestMaxForks=N` override. Capture the numbers.
- Pick the value that minimizes first-attempt flaky failures without an unacceptable wall-time regression, and set it (or its selection logic) at `it.maxParallelForks` in [app/build.gradle.kts](app/build.gradle.kts) (~line 127).
- Document the chosen value and the before/after flaky-rate + wall-time in the PR, and update the explanatory comment above the `maxParallelForks` assignment (which currently cites PR #73's raise-the-forks reasoning).
- Reference #96 / PR #152 / PR #73 for the history.

---

### #115 — Add partial index on `articles.link_status` for the probe-job queue scan `[ ]`

`get_articles_with_null_link_status` does a full-table scan every 2 minutes (no index on `link_status`). At single-user scale this is negligible, but a partial index `CREATE INDEX idx_articles_link_status_null ON articles (fetched_at) WHERE link_status IS NULL` would make the probe-job query O(pending) instead of O(total articles) with no schema-level risk. File once the article count is large enough to show up in profiling.

**Acceptance criteria**
- Add a migration in `server/src/db.rs` that creates `idx_articles_link_status_null ON articles (fetched_at) WHERE link_status IS NULL`.
- Add a test in `server/src/db_tests.rs` confirming the column and index exist after migration.
- Confirm `get_articles_with_null_link_status` query plan uses the index (EXPLAIN QUERY PLAN).

---

To be fleshed out at a later point

- server/config.example.toml isn't fully up to date (missing database group for example)
- ~~Write a set of scripts to analyze test results instead of having claude run find/exec things that require my approval.~~ Resolved — see [scripts/](scripts/) (`test-counts.sh`, `test-run.sh`, `test-failures.sh`, `server-build.sh`), documented in [CLAUDE.md](CLAUDE.md#helper-scripts) and allowlisted via [.claude/settings.local.json](.claude/settings.local.json) (`Bash(./scripts/*:*)` plus fixed prefix syntax for `cargo:*`, `grep:*`, etc.).
