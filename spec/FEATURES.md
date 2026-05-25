# Feed — functional spec

This document is the source of truth for what each client of Feed is expected to do, end-to-end. It replaces the ad-hoc test catalog that lived in [spec/plans/new-design-rollout-implementation-plan.md](plans/new-design-rollout-implementation-plan.md) (kept around for historical context but no longer authoritative).

This spec describes the **target state of the product**, not the current state of the code. When the two diverge, a row in the scenario table has a `Status` of `⚠` or `✗` linked to a TODO ticket. When the design and the spec disagree, **the spec wins** — for example, dropping starring from the spec deletes the corresponding visual elements from the screens even though the design references still show them.

The spec is written in scenario form so it can be:

- read as a manual QA checklist,
- ported directly to Playwright/Cypress (web) and Compose UI tests (Android), reusing the IDs in test method names so the spec ↔ test mapping stays greppable,
- amended whenever a feature is added, dropped, or changes shape.

For visual fidelity, see [VISUAL_SPEC.md](VISUAL_SPEC.md) and the story board at [story-board/index.html](story-board/index.html) — except where this spec calls for the removal of an element, in which case this spec takes precedence over the design.

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
| Article-list density (compact / regular / comfy) | ✓ | ✓ | regular | ✓ | Affects row padding, excerpt visibility (none in compact), and thumbnail rendering (comfy only). |
| Mark as read on open (always on) | ✓ | ✓ | — | ✓ | Opening an article automatically fires `PUT /v1/articles/{id}/read`. On web, the article stays visible in the list (unread dot removed) until another article is selected; on Android the reader is full-screen so the list is not co-visible. No user toggle. |
| Keep articles (30d / 90d / 1y / forever) | ✓ | ✓ | 90d | ✗ (#37) | Retention window. New ticket #37 wires this end-to-end. |
| Refresh interval (15m / 1h / 6h / manual) | ✓ | ✓ | 1h | ✗ (#38) | Client-side auto-poll cadence for the article list. |
| Server URL | — | ✓ | `http://10.0.2.2:3000/` | ✓ | Android-only. Dev default targets the host machine from the emulator. See #32 for the web-side removal. |
| Account → Import OPML | ✓ | ✓ | — | ✓ | Triggers `POST /v1/feeds/import/opml`. |
| Account → Logout | ✓ | ✓ | — | ✓ | Clears the local session and returns to login. |
| Account → About / versions | ✓ | ✓ | — | ✓ | Shows the client version and the server version on a single line. |

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
| FEED-8 | both | Populated server with at least one unread article | Click/tap the `✓` button next to the unread dot on an article row | A `✓` button appears next to the unread dot; both are visible only when the article is unread. Clicking the button fires `PUT /v1/articles/{id}/read` with `is_read=true`; the Unread badge decrements by one. In the All articles view the row stays but the dot and button disappear (article is now read). In the Unread view the row disappears immediately — the Unread list acts as a TODO list; marking an item done removes it on the spot. | ✗ #40 |
| FEED-9 | both | Unread article list | Click/tap any article row to open it in the reader | `PUT /v1/articles/{id}/read` fires automatically; the article's unread dot disappears. Web: the article row stays in the list (still selected, no dot) until another article is opened — then it drops out of the Unread filter. Android: the reader is full-screen; the article is removed from the list on return. | ✓ |
| FEED-10 | both | Density = `compact`; at least one article with non-blank excerpt | Open article list | Each row uses compact padding (10/18 web, 12/22 android); title is 15px/16sp; excerpt and thumbnail are absent. | ✓ |
| FEED-11 | both | Density = `comfy`; at least one article with non-blank excerpt | Open article list | Each row uses comfy padding (20/22); title is 17px/18sp; a 64×64 (web) / 56×56 (android) hue-colored thumbnail appears to the left of the excerpt, both in the same flex row. | ✓ |

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
| READ-7 | both | Reader open on any article | Tap/click the `↩ Mark unread` button in the reader action group (web: next to ↗ Open / ⎙ Share; android: compact `↩` next to ⎙ Share) | Because FEED-9 auto-marks articles as read on open, this button provides the undo direction only. Pressing it fires `PUT /v1/articles/{id}/read` with `is_read=false`; the Unread badge increments; on return to the list the row reappears in the Unread view with its dot restored. | ✗ #40 |

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
| SET-1 | both | Prefs set in storage: font=22, density=comfy | Open Settings | Each segmented control reflects the stored value as active. | ⚠ web (#30) |
| SET-2 | both | Default prefs | Change every control once, reload page / restart app | Each control reflects the new value; no value reset. | ⚠ web (#30) |
| SET-3 | both | Reader open at 18 | Change font size in Settings to 22 | Open reader body re-renders at 22 (px / sp) without reload. | ⚠ web (#30) |
| SET-4 | both | Article list at `regular` density | Change density to `compact`, then to `comfy` | `compact`: excerpts hidden, rows shorter. `comfy`: thumbnails visible. | ✓ |
| SET-5 | both | OPML file with 5 feeds | Account → Import OPML → choose file | 5 new feeds appear in the subscriptions list; success toast/dialog summarizes the response. | ✓ |
| SET-6 | android | Server URL = `http://10.0.2.2:3000/` | Change to `http://other:3000/`, save | URL persisted; next API call uses the new host; app re-prompts login if the new host's session is unknown. | ✓ |
| SET-7 | both | Logged in | Open Settings | Account section shows an Import OPML action, a Logout action, and an About row with `Client v<x> · Server v<y>`. Web shows no Server URL row (see #32). | ✓ |
| SET-8 | both | Default 90d retention | Change "Keep articles" to 30d, wait one window or trigger the retention sweep | The new value is persisted server-side; the server's retention sweep deletes articles older than 30d. "Forever" disables retention. | ✗ #37 |
| SET-9 | both | Refresh interval = `15m` | Open the article list, leave the app idle | Within ~15 minutes the list polls the server and any new articles appear without manual refresh. `Manual` disables the poll. | ✗ #38 |

### Navigation

These cover the primary-nav surfaces on each platform. Per-feed filtering is in FEED-2.

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| NAV-1 | web | Logged in | Click each sidebar primary-nav item in turn: Unread, All articles, Subscriptions, Settings | Each click navigates to the corresponding screen; the active item shows the `accentSoft` background + `accent` text. Unread is the post-login default. | ⚠ pending #35 (Starred entry still present until star removal lands) |
| NAV-2 | android | Logged in | Tap each of the four bottom-nav tabs in turn: Unread, All, Feeds, Settings | Each tab swap is < 200ms; active tab glyph + label in `accent`. Unread is the post-login default. | ⚠ pending #35 (Saved tab still present until star removal lands) |

### Error / edge

See [VISUAL_SPEC.md](VISUAL_SPEC.md) §States & feedback for the design treatment of each surface referenced below (banner, big mid-pane state, sidebar `!` badge, inline reader note, inline form error, modal interrupt, sidebar-footer state, snackbar).

| ID | Platforms | Setup | Steps | Expected | Status |
|---|---|---|---|---|---|
| ERR-1 | both | One sync attempt fails (kill server, then trigger refresh) | — | Web: sidebar footer changes to `Last sync failed · retry` (failed sync state). The previously-cached articles stay visible. Clicking `retry` fires a fresh sync; success returns the footer to `Synced … ago`. Android: pull-to-refresh spinner dismisses; a `Sync failed — retry` snackbar surfaces with a trailing `Retry` action. | ⚠ #33 (android), partial (web) |
| ERR-2 | both | Filter to a feed/subscription with 0 articles (or Subscriptions search with no matches) | Render | Centred serif italic 16 `ink3` "Nothing here yet." in the list / content area. No buttons — this is the no-verb empty state. When the empty state needs an action (no feeds at all, inbox zero), use the big mid-pane state instead — see ERR-10 / ERR-11. | ✓ |
| ERR-3 | both | Stale cookie (server restarted with new secret) | Trigger any API call | See AUTH-5; the visual treatment is the session-expired modal (ERR-14). | ✗ #34 |
| ERR-4 | both | Device loses network (airplane mode), or `fetch` rejects with a network-level error | Stay open while disconnecting, or open the app while offline | Web: warn **banner** above the content area (`OFFLINE · You're offline. Showing {N} cached articles from your last sync at {time}…`); sidebar footer switches to the `offline` state (`Offline · cache only`). Reading and marking-as-read still work against the cache and queue locally. Reconnecting clears the banner, replays the queued mutations, and returns the footer to `ok`. Android: same condition surfaced via snackbar + `offline` sidebar footer. | ✗ #54 |
| ERR-5 | both | Server is unreachable for ≥ 3 consecutive sync attempts (DNS failure, connection refused, 5xx with no retry budget left) | Wait, or click the sidebar `↻` | Web: **big mid-pane state** replaces list + reader. Eyebrow `ERR · {error-code}`, title `Couldn't reach the server.`, italic body explaining the cache is intact and what we're doing, mono detail block with the endpoint + retry budget, primary `Retry now`, secondary `Check service status ↗`. Sidebar footer is in the `failed` state. Android: same content surfaced via snackbar (`Couldn't reach the server — retry`) over the cached list; the big mid-pane only replaces the screen on a cold-boot with no cache. | ✗ #55 |
| ERR-6 | both | Server returns `429 Too Many Requests` on a sync | — | Web: warn **banner** above the content area (`RATE LIMIT · paused for {countdown}…`); sidebar footer switches to the `paused` state (`Paused · {duration}`). Background auto-poll is paused for the countdown; manual refresh, reading, and mark-as-read continue to work. When the countdown elapses, the banner clears and auto-poll resumes on the next interval. Android: snackbar with the same copy + `paused` sidebar footer. | ✗ #56 |
| ERR-7 | both | One feed returns `410 Gone` for ≥ 14 consecutive sync attempts | Navigate to that feed in the sidebar | The feed's sidebar row gets the `!` badge, its name renders with `line-through`, and its row drops to 0.55 opacity. Selecting the feed shows a **big mid-pane state**: eyebrow `ERR · HTTP 410 GONE`, title naming the feed (`"Cold Take" is gone.`), italic body explaining the consecutive failures and that cached articles are preserved, mono detail block with the URL + first-failure date + failure count, primary `Unsubscribe`, secondary `Keep watching`. | ✗ #57 |
| ERR-8 | both | One feed returns a non-parseable body (malformed XML / non-feed HTML / wrong Content-Type) on its most recent sync attempt | Navigate to that feed | Sidebar `!` badge on the feed (no strikethrough — the feed isn't dead yet). Error **banner** above the article list: `PARSE FAIL · We can't read the feed XML for {name}. Showing the last successful read from {when}…`. The list shows the cached articles unchanged; selecting one opens the reader normally. Clicking the banner's `View raw response ↗` link (web) or the snackbar's `Details` action (Android) opens a **raw-response inspector**: top bar with the feed name and Copy / Open-URL actions, a metadata strip (URL, fetched-at, response status + size + Content-Type, parser error location), a monospace source view with line numbers and the offending line highlighted in the error tone with a caret annotation below it, and a footer strip explaining the retry cadence. After 14 consecutive parse failures, the feed is escalated to the ERR-7 dead-feed treatment. | ✓ #58 |
| ERR-9 | both | Open an article whose `link` returned 4xx the last time we touched it (or whose body was retracted server-side) | Open the article in the reader | Above the body (between the action row and the first paragraph), a warn **inline reader note**: `WARN · The original page at {url} now returns {status}. You're reading the cached copy from {when}. Try Wayback ↗`. The rest of the reader is unchanged; `↗ Open` and the footer URL remain real anchors (the user may have a reason to click through even to a 404). | ✓ #59 |
| ERR-10 | both | Logged-in account with zero feeds (first run, post-unsubscribe-everything) | Open the app | Sidebar shows the four primary-nav items and no folder section; sidebar-footer text is `Nothing to sync yet`. Content area shows the **First run** big mid-pane state: eyebrow `WELCOME`, title `Start by adding a feed.`, italic body explaining we accept URLs or OPML, primary `Paste a URL…` (opens the same add-feed form as SUBS-2), secondary `Import OPML…` (same as SET-5), hint about no starter pack. | ✗ #41 |
| ERR-11 | both | Logged-in account with ≥ 1 feed and zero unread articles | Open the Unread view | Content area shows the **Inbox zero** big mid-pane state: eyebrow `INBOX ZERO`, title `You're caught up.`, italic body `No unread articles across {N} feeds.`, secondary `Browse all articles`. No stats, no hint copy — the design deliberately omits "today you read X" / "next sync in Y" because the client has no reliable source for those metrics yet. Sidebar Unread count is hidden (rendered as no count rather than `0`). Implementations may show ERR-11 in place of ERR-2 specifically for the Unread view — empty-but-completed is different from empty-but-filtered. | ✗ #41 |
| ERR-12 | both | Add-feed form open; paste a URL that doesn't return a valid RSS / Atom / JSON Feed body | Submit | The form stays open. The URL field's border switches to the error tone. An **inline form error** appears below the field: `ERR · This URL didn't return a valid feed. Paste the feed URL directly (e.g. example.com/rss/feed.xml), not the site's homepage.` No row is added; the Subscribe submit is consumed but no `POST /v1/feeds` fires. Focus stays in the URL field. The client does **not** attempt auto-discovery of common feed paths (`/feed`, `/rss`, `/atom.xml`, …) — only the URL as typed is fetched. | ✗ #41 |
| ERR-13 | both | Add-feed form open; paste a URL whose discovered feed URL exactly matches an existing subscription | Submit | The URL field's border switches to the warn tone. An **inline form error** appears below the field: `WARN · You're already subscribed to {name} — it's in the {folder} folder. Open it instead, or change the URL above.` The `{name}` is a real link that navigates to that feed's view. The submit is blocked; no `POST /v1/feeds` fires. | ✗ #41 |
| ERR-14 | both | Logged-in user; their session is invalidated mid-use (token rotated, cookie cleared, AUTH-5 path) | Trigger any API call | A warn **modal interrupt** covers the viewport: eyebrow `SESSION EXPIRED` (in warn fg), title `You've been signed out.`, italic body explaining the reason and that the cache is intact, an inner `panel` strip showing the signed-in identity (`Signed in as {username}` with the username in `ui-monospace`), primary `Sign in again` (routes through the login flow with the username prefilled), secondary `Forget this device` (clears local cache and routes to a clean login). The sidebar footer behind the scrim is in the `failed` state. The scrim blocks all interaction until the user picks an action. | ✗ #34 |
| ERR-15 *(reserved)* | — | — | — | New edge cases append here. | — |

**Catalogue invariant.** Every artboard in the story board's **Edge cases · …** design-canvas sections must map to an `ERR-*` row above; every `ERR-4`+ row must have a corresponding artboard. When you ship a new edge-case design, add the row in the same PR.

---

## Maintenance notes

- When a scenario starts passing, flip its `Status` to `✓` and drop the ticket reference.
- When a feature is added, append a new scenario in the appropriate group with a fresh ID (don't reuse retired IDs — gaps are fine) and set its `Status` (often `✗` linked to the implementation ticket).
- When a feature is dropped, move the scenario into the "Features explicitly NOT supported" table with one sentence on the reasoning.
- Settings are platform-asymmetric; keep the Settings reference table in sync with what each client actually renders.
- Visual fidelity questions go to [VISUAL_SPEC.md](VISUAL_SPEC.md); behavior questions live here — and behavior wins when the two disagree.
