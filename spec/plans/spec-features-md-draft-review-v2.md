# Plan — `spec/FEATURES.md` for review (v2)

**Date:** 2026-05-17 13:24 PDT

## Context

After the manual walkthrough of the post-Phase-10 build against the e2e catalog in [new-design-rollout-implementation-plan.md](new-design-rollout-implementation-plan.md), we agreed to:

1. File tickets for the bugs surfaced during the walkthrough — **done** in the prior turn (TODO.md #25–#36).
2. Promote a more rigorous, behavior-oriented spec to its own file at `spec/FEATURES.md` so future work has a stable target.

This plan presents the proposed `spec/FEATURES.md` content **verbatim** so the user can comment on it line-by-line before I commit to it as the spec. **v2** integrates the user's review feedback on v1.

### Decisions taken so far

| Question | Choice |
|---|---|
| Scope of the Settings model | Keep only what we implement (drop reader theme + default sort); **keep refresh interval as something to implement.** |
| Mobile filter chips | **Remove entirely.** All/Unread filtering moves to the navigation layer. (Design will be updated.) |
| Density + font-size platform coverage | Both controls on both clients. |
| Parity model | Per-scenario platform matrix (one canonical scenario per ID with a `Platforms` column). |
| Spec scope (target vs. current) | **Target.** Add a `Status` column showing desired-vs-actual diff. |
| Visual vs. behavior precedence | Behavior wins when it removes a visual element (e.g. dropping starred drops the star glyph). |
| Mobile bottom nav contents | **Unread · All · Feeds · Settings.** (Replaces Today/Saved/Feeds/Settings.) |
| READ-1 web route shape | `/article/:articleId` confirmed. |
| Server URL on web | Stays NOT supported. Not reconsidering. |
| About / version row | Add to web (in addition to Android). Surface server version on the same line. |
| Star-removal scope | Extend #35 to also remove the server-side star concept (DB column + endpoints). |
| "Keep articles" retention | Real feature, not yet wired — new ticket. **Client setting drives the server's retention sweep** (i.e. the server's existing fixed-config retention is replaced by reading the value the client wrote). |
| Web sidebar primary nav | `Unread` (default) · `All articles` · `Subscriptions` · `Settings`. Folder list below. |
| `sp` vs. `px` note placement | Single note at the top of the Settings reference table — not repeated in every scenario. |

### Files this plan touches

- **Edited:** `spec/FEATURES.md` (overwritten with the content in the **Proposed `spec/FEATURES.md`** section below).
- **Edited:** `TODO.md` (amendments described in **TODO.md amendments** below — update #35 scope, add two new tickets).

### Verification

- Spec is Markdown — no test target. Structural checks:
  - Every ticket reference in FEATURES.md resolves to a real ticket in [TODO.md](../../TODO.md).
  - Every scenario ID is unique within its group; the `Status` column is one of `✓` / `⚠` / `✗`.
  - Settings reference table matches what each client renders today (with planned-not-wired rows marked `✗`).
  - Internal links resolve.

---

## TODO.md amendments

These ride alongside the new spec. The plan file does not contain the full new ticket bodies (the file is too long); the bullets below define what I'd write.

1. **Edit #35 — Remove starring / favorites from the clients** → also remove from the **server**.

   New acceptance criteria appended:
   - `is_starred` column and `starred_at` column dropped via a new schema migration in [server/src/db.rs](../../server/src/db.rs).
   - `PUT /v1/articles/{id}/star` route + handler removed.
   - `GET /v1/articles/starred` route + handler removed.
   - Tests covering star endpoints / column behavior in [server/src/db_tests.rs](../../server/src/db_tests.rs) and the API integration suite are removed.
   - `cargo test` returns `<new total> passed; 0 failed`; document the new test count.
   - Existing databases migrate cleanly (the migration drops the columns and any star-only indices).
   - Update the title to drop "/ favorites" if you want; or leave it. (Cosmetic.)
   - Existing acceptance criteria about client-side removal stay as-is.

2. **New #37 — "Keep articles" retention driven by the client setting**

   The Settings → Keep articles control is shown in both clients but nothing reads it today. Wire it as a **client → server** preference: the value the user sets on either client persists to the server and replaces the server's current fixed-config retention sweep.

   Acceptance criteria:
   - Server gains an endpoint to GET/PUT the active retention window (e.g. `GET/PUT /v1/settings/retention` returning `{ "days": <int> | null }` where `null` ≡ "forever"). Single-user product → single global value.
   - Server's article-cleanup sweep reads this value at each tick instead of (or in addition to) the config file. Existing config-based retention falls back if the persisted value is missing on first start.
   - Both clients write the value when the Settings control changes and read it on Settings screen mount so the displayed value reflects the server's truth.
   - "Forever" disables retention.
   - Tests: a server-side test exercises the new endpoint + the sweep honoring it; a client-side test per platform covers the read/write round-trip.

3. **New #38 — Refresh interval (client-side auto-poll)**

   The Settings → Refresh interval control persists a value but no client polls. Wire a client-side timer.

   Acceptance criteria:
   - Each client polls the article-list endpoints at the configured cadence (15m / 1h / 6h). "Manual" disables the poll.
   - The poll is paused while the app/tab is backgrounded and resumed on foreground.
   - Errors during a background poll surface via the ERR-1 path; they do not interrupt the user's current screen.
   - A test per platform covers the cadence + the pause/resume.

4. **New #39 — Surface server version on the Settings → About row, on both clients**

   The Settings → About row is added to web (was Android-only in v1 of the spec). The row shows both the client version and the server version.

   Acceptance criteria:
   - Server exposes `GET /v1/version` (or extends `/v1/health` with a version field). Pulled from `CARGO_PKG_VERSION` at compile time.
   - Both clients query the endpoint on Settings screen mount, render `Client v<x> · Server v<y>`. Failure to reach the server renders `Client v<x> · Server unreachable` in `ink3`.
   - A unit test per platform covers the rendering and the error fallback.

---

## Proposed `spec/FEATURES.md`

Everything from this point to the end of the file is the verbatim proposed content of `spec/FEATURES.md`. Annotate inline; I'll fold it into the final version.

---

# Feed — functional spec

This document is the source of truth for what each client of Feed is expected to do, end-to-end. It replaces the ad-hoc test catalog that lived in [spec/plans/new-design-rollout-implementation-plan.md](plans/new-design-rollout-implementation-plan.md) (kept around for historical context but no longer authoritative).

This spec describes the **target state of the product**, not the current state of the code. When the two diverge, a row in the scenario table has a `Status` of `⚠` or `✗` linked to a TODO ticket. When the design and the spec disagree, **the spec wins** — for example, dropping starring from the spec deletes the corresponding visual elements from the screens even though the design references still show them.

The spec is written in scenario form so it can be:

- read as a manual QA checklist,
- ported directly to Playwright/Cypress (web) and Compose UI tests (Android), reusing the IDs in test method names so the spec ↔ test mapping stays greppable,
- amended whenever a feature is added, dropped, or changes shape.

For visual fidelity, see [spec/plans/new-design/README.md](plans/new-design/README.md) and [spec/plans/new-design/design-files/index.html](plans/new-design/design-files/index.html) — except where this spec calls for the removal of an element, in which case this spec takes precedence over the design.

---

## Audience and platforms

Feed is a **self-hosted RSS reader**.

| Platform | Status | Notes |
|---|---|---|
| Web (Kotlin/JS SPA in [web/](../web/)) | Supported | Served by the Rust server on the same origin in production |
| Android (Jetpack Compose in [app/](../app/)) | Supported | Talks to a configurable server URL |
| iOS | Out of scope | Mentioned in the design as an aspirational third client; no iOS module exists |

There is **no multi-user mode**. There is no public-facing UI. The server enforces a single account, configured at first run.

---

## Features explicitly NOT supported

These are called out because they appeared in the original design, in template scaffolding, or in conversations with previous iterations, and decisions have been made against them. Do not reintroduce them without a discussion.

| Feature | Why dropped |
|---|---|
| Starring / favorites (★) | Came from the generic design template. This project doesn't benefit from a saved-for-later flag; "unread" is the only state we care about. See ticket #35 (covers both client and server removal). |
| Multi-user / account management | Single-user by design. The server has a fixed credential set; clients only authenticate, they do not manage accounts. |
| Reader theme picker (Paper / Soft / Dim) | The design locks "Paper" as the only palette. The Soft/Dim variants in the design files are historical declinations only. |
| Default sort (Newest / Priority) | The server has no priority concept and no plans for one. Article lists are always sorted newest-first by `published`. |
| Mobile filter chips | Initial design included `Long reads / Short reads / Today / Unread / All` chips above the article list. These are dropped entirely — `Unread` and `All` move to the navigation layer (bottom-nav tabs on mobile, sidebar entries on web); the others were not validated as useful. |
| Per-feed `tag` column | Redundant with the folder (category) name we already have; the design's "Design" / "Tech" tag strings are not user-editable in this product. |
| Server URL setting on the web client | The web client is served by the Rust server itself; the URL is always same-origin. The setting is Android-only. See ticket #32. |

---

## Navigation contract

### Web — sidebar primary nav

Top-to-bottom, four items with right-aligned counts where applicable:

1. **Unread** — count = unread articles across all feeds. Default landing item after login.
2. **All articles** — count = total articles.
3. **Subscriptions** — count = number of feeds.
4. **Settings** — no count.

Below the primary nav, the folder list (server categories) lets the user filter the article list to a single subscription, as today.

### Android — bottom tab bar

Four tabs, left-to-right:

1. **Unread** — default landing tab after login.
2. **All** — every article.
3. **Feeds** — the Subscriptions list.
4. **Settings**.

The bottom bar uses the design's pill / `accent` styling on the active tab. The Reader screen pushes on top of the tab shell and hides the bar.

---

## Settings reference

The settings surface is **not symmetric** across platforms. The table below is the contract. **Sizes on Android are in `sp`; on web they are in `px`.** A user-set "22" maps to 22sp on Android, 22px on web.

| Setting | Web | Android | Default | Status | Notes |
|---|---|---|---|---|---|
| Reader font size (14–24 in fixed steps) | ✓ | ✓ | 18 web / 17 mobile | ⚠ web (#30) | Applies to the reader body. Live-updates an open reader without reload. |
| Article-list density (compact / regular / comfy) | ✓ | ✓ | regular | ⚠ web (#31) | Affects row padding, excerpt visibility (none in compact), and thumbnail rendering (comfy only). |
| Mark as read on scroll (off / on) | ✓ | ✓ | on | ✓ | When on, an article row marked as visible for ≥1s in the list flips to read. |
| Keep articles (30d / 90d / 1y / forever) | ✓ | ✓ | 90d | ✗ (#37) | Retention window. New ticket #37 wires this end-to-end. |
| Refresh interval (15m / 1h / 6h / manual) | ✓ | ✓ | 1h | ✗ (#38) | Client-side auto-poll cadence for the article list. |
| Server URL | — | ✓ | `http://10.0.2.2:3000/` | ✓ | Android-only. Dev default targets the host machine from the emulator. See #32 for the web-side removal. |
| Account → Signed in as | ✓ | ✓ | — | ✓ | Display-only. |
| Account → Import OPML | ✓ | ✓ | — | ✓ | Triggers `POST /v1/feeds/import/opml`. |
| Account → Logout | ✓ | ✓ | — | ✓ | Clears the local session and returns to login. |
| Account → About / versions | ✓ | ✓ | — | ✗ (#39) | Shows the client version and the server version on a single line. See #39. |

---

## Scenarios

Every scenario lists **ID · Platforms · Setup · Steps · Expected · Status**. Platforms is one of `web`, `android`, `both`. Status is `✓` (works today), `⚠` (partial / known bug, ticket linked), or `✗` (not implemented, ticket linked).

**Fixtures.** The Setup column describes the *kind* of state the server is in (e.g. "populated server", "feed with no unread articles") rather than fixed counts. When a count appears in the Expected column it is an invariant against whatever the fixture actually contains (e.g. "the Unread badge matches the number of unread articles returned by the server"), not a magic number that must be matched by every concrete test.

### Authentication & session

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| AUTH-1 | both | Fresh client, valid creds in config | Open app, type username + password, submit by clicking the primary button | Lands on Feed (Unread) screen within 2s. | ✓ |
| AUTH-1a | web | Fresh client, valid creds | Type username, Tab to password, type password, press **Enter** | Form submits (Enter from either field). | ⚠ #26 |
| AUTH-1b | android | Fresh client, valid creds | Type username, press the **Next** IME action, type password, press the **Done/Go** IME action | Focus moves to the password field on Next; pressing Done submits. | ⚠ #26 |
| AUTH-2 | both | Fresh client, invalid password | Submit login form | Error message shown ("Invalid username or password"); focus stays on the password field. | ✓ |
| AUTH-3 | both | Already logged in | Reload page (web) / kill + reopen app (android) | No login prompt; lands on Unread. | ⚠ #25 (web) |
| AUTH-4 | both | Logged in | Settings → Logout | Returns to login screen; session cleared. | ✓ |
| AUTH-5 | both | Logged in, server's JWT secret has been rotated (or cookie otherwise invalidated) | Trigger any API call | A single, debounced redirect to the login screen. No infinite-loop. Local session signal cleared. | ✗ #34 |

### Feed list & navigation

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| FEED-1 | both | Populated server (multiple feeds, some unread articles) | Open the **Unread** view (default after login) | List shows only unread items, sorted by `published` DESC. Unread nav badge equals the number of unread articles returned by the server. | ⚠ #27 (android empty) |
| FEED-1a | both | Same fixture | Switch to **All articles** | List shows all articles (read + unread), newest first. | ⚠ #27 (android empty) |
| FEED-2 | both | Populated server with at least one feed | Click/tap a feed (subscription) in the sidebar folder list (web) or `Feeds` tab → row (android) | List filters to that subscription's items. Web URL = `/feed/:subscriptionId` (the server's feed id). | ⚠ #27 (android empty) |
| FEED-3 | web | Article list with at least two items | Click any item that isn't currently selected | The clicked row gets `panel` background + 2px inset `accent` bar on its left edge; the previously selected row loses that styling; the reader pane fills with the clicked article. | ✓ |
| FEED-4 | web | Article list and reader both have overflow content | Scroll the list, then scroll the reader independently | Each column scrolls without affecting the other. | ✓ |
| FEED-5 | both | At least two distinct feeds | Render the article list | Per-feed dot colors are stable across reloads and identical for the same feed across sidebar / list / reader meta. Collisions across distinct feeds are tracked separately — see #36. | ⚠ #36 |
| FEED-6 | android | Article list with content | Pull down on any article list (Unread / All / per-feed) | Triggers a refresh; spinner shown; list refreshes; error path lands in ERR-1. | ✗ #33 |
| FEED-7 | web | Article list with content; server reachable | Click the `↻` refresh glyph in the sidebar footer (next to the "Synced … ago" line) | Triggers a refresh; the footer updates the "Synced … ago" timestamp on success; error path lands in ERR-1. | ⚠ partial |

### Reader

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| READ-1 | web | Feed screen, article A selected | Click article B in the same list | Reader pane swaps to article B in place; URL updates to `/article/:articleId` so the reader's content is deep-linkable. | ✓ |
| READ-1b | android | Article list visible | Tap an article row | A full-screen Reader screen is pushed on top of the tab shell; the bottom-nav tab bar is hidden; the OS status bar inset is respected. | ✓ |
| READ-1c | android | Reader open, list was scrolled to some position before opening | Tap the back chevron in the reader top bar | Returns to the list at the same scroll position. | ✓ |
| READ-2 | both | Article whose HTML body includes `<script>`, `<iframe>`, `<img>`, `<p>`, `<a>`, `<em>`, `<strong>` | Open in reader | The body is sanitized through an allowlist: `<script>` and `<iframe>` are stripped; `<p>`, `<a>`, `<em>`, `<strong>`, `<img>`, `<blockquote>`, `<ul>`, `<ol>`, `<li>`, `<h2>`, `<h3>` are preserved. Anything outside the allowlist is dropped (tag) but its text content is kept. | ✓ |
| READ-3 | both | Reader open | Tap/click the `Aa` button | Opens a font-size picker bound to `UserPrefs.fontSize`. The active value is the user's current setting; selecting a new value persists immediately and updates the reader body in place. | ✓ |
| READ-4 | both | Font size set to 22 in Settings | Open reader | Body text computed size = 22 (px on web, sp on android). | ✓ |
| READ-5 | both | Article with `link = "https://example.com"` | Tap/click ↗ Open (or the URL in the reader footer) | Opens the article URL in a new browser tab (web) / external browser intent (android). The footer URL itself is a clickable anchor. | ⚠ #29 (web) |
| READ-6 | web | Fresh login, no article selected | Open Unread / All | Reader pane shows em-dash + "Select an article to begin reading." | ✓ |

### Subscriptions

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| SUBS-1 | both | Server has feeds spread across multiple folders (categories) | Open Subscriptions (web sidebar link / Android `Feeds` tab) | Every feed the server returns is rendered as a row. Android groups rows by folder with uppercase headers; web lists rows flat with the folder name in the right column. | ✓ |
| SUBS-2 | both | Empty subscriptions; valid RSS URL on hand | Click "+ Add feed", paste URL, submit | New row appears; `POST /v1/feeds` returns 201; nav count increments. | ✓ |
| SUBS-3 | both | Multiple feeds, at least one whose name contains the substring being typed | Type that substring in the search box | Only matching rows visible; filtering is client-side, no server call. | ✓ |
| SUBS-4 | both | Existing feed | Open the overflow menu → Rename → enter new name → save | Row updates inline; `PUT /v1/feeds/{id}` with `custom_title = "<new>"`. **Web requirements:** the overflow dropdown must render above adjacent rows (not be clipped by the list container), and the rename input must be prepopulated with the current name and pre-selected. | ⚠ #28 (web) |
| SUBS-5 | both | Existing feed with at least one article | Overflow → Delete → confirm | Feed and its articles vanish from the article list and the subscriptions list. | ✓ |

### Settings (and prefs persistence)

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| SET-1 | both | Prefs set in storage: font=22, density=comfy | Open Settings | Each segmented control reflects the stored value as active. | ⚠ web (#30, #31) |
| SET-2 | both | Default prefs | Change every control once, reload page / restart app | Each control reflects the new value; no value reset. | ⚠ web (#30, #31) |
| SET-3 | both | Reader open at 18 | Change font size in Settings to 22 | Open reader body re-renders at 22 (px / sp) without reload. | ⚠ web (#30) |
| SET-4 | both | Article list at `regular` density | Change density to `compact`, then to `comfy` | `compact`: excerpts hidden, rows shorter. `comfy`: thumbnails visible. | ⚠ web (#31) |
| SET-5 | both | OPML file with 5 feeds | Account → Import OPML → choose file | 5 new feeds appear in the subscriptions list; success toast/dialog summarizes the response. | ✓ |
| SET-6 | android | Server URL = `http://10.0.2.2:3000/` | Change to `http://other:3000/`, save | URL persisted; next API call uses the new host; app re-prompts login if the new host's session is unknown. | ✓ |
| SET-7 | both | Logged in | Open Settings | Account section shows "Signed in as: …", an Import OPML action, a Logout action, and an About row with `Client v<x> · Server v<y>`. Web shows no Server URL row (see #32). | ✗ #39 |
| SET-8 | both | Default 90d retention | Change "Keep articles" to 30d, wait one window or trigger the retention sweep | The new value is persisted server-side; the server's retention sweep deletes articles older than 30d. "Forever" disables retention. | ✗ #37 |
| SET-9 | both | Refresh interval = `15m` | Open the article list, leave the app idle | Within ~15 minutes the list polls the server and any new articles appear without manual refresh. `Manual` disables the poll. | ✗ #38 |

### Navigation

These cover the primary-nav surfaces on each platform. Per-feed filtering is in FEED-2.

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| NAV-1 | web | Logged in | Click each sidebar primary-nav item in turn: Unread, All articles, Subscriptions, Settings | Each click navigates to the corresponding screen; the active item shows the `accentSoft` background + `accent` text. Unread is the post-login default. | ⚠ pending #35 (Starred entry still present until star removal lands) |
| NAV-2 | android | Logged in | Tap each of the four bottom-nav tabs in turn: Unread, All, Feeds, Settings | Each tab swap is < 200ms; active tab glyph + label in `accent`. Unread is the post-login default. | ⚠ pending #35 (Saved tab still present until star removal lands) |

### Error / edge

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| ERR-1 | both | Refresh fails (kill server, then trigger refresh) | — | Web: sidebar footer changes to "Last sync failed · retry" in `ink2` with the `retry` link in `accent`. Android: pull-to-refresh spinner dismisses and a snackbar / inline error surfaces. | ⚠ #33 (android), partial (web) |
| ERR-2 | both | Filter to a feed/subscription with 0 articles | Render | "Nothing here yet." centered serif italic 16 `ink3` in the list column / list area. | ✓ |
| ERR-3 | both | Stale cookie (server restarted with new secret) | Trigger any API call | See AUTH-5. | ✗ #34 |

---

## Maintenance notes

- When a scenario starts passing, flip its `Status` to `✓` and drop the ticket reference.
- When a feature is added, append a new scenario in the appropriate group with a fresh ID (don't reuse retired IDs — gaps are fine) and set its `Status` (often `✗` linked to the implementation ticket).
- When a feature is dropped, move the scenario into the "Features explicitly NOT supported" table with one sentence on the reasoning.
- Settings are platform-asymmetric; keep the Settings reference table in sync with what each client actually renders.
- Visual fidelity questions go to [spec/plans/new-design/README.md](plans/new-design/README.md); behavior questions live here — and behavior wins when the two disagree.
