# Feed — functional spec

This document is the source of truth for what each client of Feed is expected to do, end-to-end. It replaces the ad-hoc test catalog that lived in [spec/plans/new-design-rollout-implementation-plan.md](plans/new-design-rollout-implementation-plan.md) (kept around for historical context but no longer authoritative).

This spec describes the **target state of the product**, not the current state of the code. It no longer tracks a per-scenario implementation `Status`: that column drifted out of date faster than it was maintained (an accuracy audit found ~⅓ of the `⚠`/`✗` rows were actually shipped). The current implementation status of each scenario is verified out-of-band — see ticket #80, which owns re-verifying every scenario and opening follow-up tickets for the genuine gaps. When the design and the spec disagree, **the spec wins** — for example, dropping starring from the spec deletes the corresponding visual elements from the screens even though the design references still show them.

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
| Server URL setting on the web client | The web client is served by the Rust server itself; the URL is always same-origin (derived from `window.location.origin`). The setting is Android-only — there is no server URL control anywhere on web, including the login screen. See ticket #32 and BUG-29. |

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

## Article sync & local mirror

Each client keeps a **full local mirror** of the article corpus and syncs it incrementally. Rather than re-fetching article lists, a client pulls only what changed since its last sync through a single delta endpoint (`GET /v1/sync`), keyed by a server-side monotonic sequence cursor. The first sync on a fresh install backfills the whole corpus; every later sync is a true delta carrying:

- **new articles**,
- **read-state changes** (including those made on the *other* client), and
- **deletions** (retention purge + unsubscribe cascade).

Consequences the scenarios below depend on:

- **The unread badge and article lists are computed from the local mirror, never from a server count.** The Unread badge, the All count, and every per-feed count read the same local rows the list renders — so **badge == list by construction** on every tab. There is no separate server unread-count call.
- **Feed selection is a local filter.** Choosing a subscription filters the local mirror by feed; it does not issue a per-feed article fetch. Feed *metadata* (titles, folders, paused / error state) is still fetched wholesale via `GET /v1/feeds`.
- **Read-state converges across clients.** Marking an article read / unread writes through to the server immediately; the next sync on any client echoes that change into its mirror, so the two clients converge (last-write-wins, single user).
- **Ordering is stable.** Articles sort newest-first by `published`, with a stable secondary tie-break so the order is identical on both clients even when `published` is missing or non-monotonic.

Refresh gestures and the background poll (see the Settings reference) drive this sync; they do **not** re-download the full list.

---

## Settings reference

The settings surface is **not symmetric** across platforms. The table below is the contract. **Sizes on Android are in `sp`; on web they are in `px`.** A user-set "22" maps to 22sp on Android, 22px on web.

| Setting | Web | Android | Default | Notes |
|---|---|---|---|---|
| Reader font size (14–24 in fixed steps) | ✓ | ✓ | 18 web / 17 mobile | Applies to the reader body. Live-updates an open reader without reload. |
| Article-list density (compact / regular / comfy) | ✓ | ✓ | regular | Affects row padding, excerpt visibility (none in compact), and thumbnail rendering (comfy only). |
| Mark as read on open (always on) | ✓ | ✓ | — | Opening an article automatically fires `PUT /v1/articles/{id}/read`. On web, the article stays visible in the list (unread dot removed) until another article is selected; on Android the reader is full-screen so the list is not co-visible. No user toggle. |
| Keep articles (30d / 90d / 1y / forever) | ✓ | ✓ | 90d | Retention window. New ticket #37 wires this end-to-end. |
| Refresh interval (15m / 1h / 6h / manual) | ✓ | ✓ | 1h | **Client-side local polling only** — pulls an incremental sync delta (new articles, read-state changes, deletions) from our own server on this cadence and applies it to the local mirror; it does *not* re-download the full list. It does *not* control how often the server fetches feeds upstream (that's the server's per-feed fetch interval). `manual` disables the poll. The poll pauses while the client is backgrounded and resumes (with an immediate sync) on foreground; changing the interval takes effect live. |
| Fetch now (manual refresh) | ✓ | ✓ | — | The primary refresh gesture (web ↻ glyph / android pull-to-refresh) triggers an **upstream** pull via `POST /v1/feeds/refresh`, then pulls the resulting sync delta into the local mirror — the bridge that lets a user's refresh actually reach upstream. Globally rate-limited to once per 60s; silently falls back to a plain delta sync (no upstream pull) when rate-limited. Per-feed refresh (`POST /v1/feeds/{id}/refresh`) is a secondary overflow-menu action. |
| Server URL | — | ✓ | `http://10.0.2.2:3000/` | Android-only. Dev default targets the host machine from the emulator. See #32 for the web-side removal. |
| Account → Import OPML | ✓ | ✓ | — | Triggers `POST /v1/feeds/import/opml`. |
| Account → Logout | ✓ | ✓ | — | Clears the local session and returns to login. |
| Account → About / versions | ✓ | ✓ | — | Shows the client version and the server version on a single line. |

---

## Feed errors

When the server's background sync fails for a feed, that feed is **never** allowed to take over the reading experience. Feed-level errors surface in exactly two places, and nowhere else:

1. **The `!` badge** on the feed's row — in the web sidebar folder list and on the Android **Feeds** tab. This is the *signal*: it lights up the moment a feed enters an error / warning state and clears automatically on the next successful sync. It carries the tone (error vs. warning) but no detail.
2. **The Subscriptions screen** — the *explanation and remediation* surface. A non-interactive summary banner sits above the search bar, and each broken feed carries an inline accordion with the full diagnostic and the actions to fix it.

This **consolidation is deliberate**: a single broken feed is a management concern, not a reading interruption. Opening a broken (or even dead) feed's article list shows its cached articles exactly as a healthy feed would — there is **no** big mid-pane takeover and **no** banner pinned over the list for a single-feed failure. (App-wide failure — every feed failing because the server itself is down — still escalates to the big mid-pane state; see ERR-5.) This supersedes the earlier per-feed treatments: the dead-feed big mid-pane and the parse-error banner-over-the-list are dropped. The raw-response inspector is kept and is now reached from the accordion's `View raw ↗` action.

### Trigger conditions and tone

Every failing sync maps to one of two tones. **ERR** (red) is a problem the user probably has to act on; **WARN** (amber) is a transient problem that usually clears itself. The server classifies each condition and exposes the tone; clients never re-derive it from the raw status.

| Condition | Tone | Retry behaviour |
|---|---|---|
| HTTP 410 Gone (≥ 14 consecutive → `dead`) | ERR | Retries paused — permanent signal |
| Parse failure (HTML / non-feed body, wrong Content-Type, malformed XML) | ERR | Retries continue on schedule; escalates to `dead` after 14 consecutive |
| HTTP 4xx (non-410) | ERR | Retries continue on schedule |
| HTTP 5xx | WARN | Retries continue on schedule (honours `Retry-After`, FETCH-2) |
| DNS failure / connection refused / network timeout | WARN | Retries continue on schedule |

The summary banner reflects the worst tone present: it renders in ERR tone whenever ≥ 1 feed is failing, and **demotes to WARN tone only when every issue is a warning**.

### The accordion

Each broken feed stays in its folder at its normal position, with its icon dimmed (0.6 opacity) and an error / warn badge after its name. Tapping the row toggles an inline accordion below it containing, top to bottom:

- a **monospace diagnostic block** — the HTTP status (or parser error) and the URL tried, the consecutive-failure count, the last-attempt time, and the next retry time (or `retries paused`);
- a **one-sentence plain-English explanation** of the likely cause and what is preserved (cached articles always survive);
- a **context-dependent action set**, drawn from: `Retry now` / `Retry once` / `Fix URL…` / `View raw ↗` / `Unsubscribe`.

Action semantics:

- **Retry now** — for an actively-retrying feed; triggers an immediate upstream fetch (`POST /v1/feeds/{id}/refresh`).
- **Retry once** — for a paused / dead feed; a single manual fetch attempt that does **not** un-pause the schedule.
- **Fix URL…** — opens an inline editor for the feed's *source* URL (distinct from Rename, which only sets `custom_title`); saving revalidates the URL server-side.
- **View raw ↗** — opens the raw-response inspector (web: same-shell view with the sidebar visible; Android: full-screen pushed view). Primarily shown for parse failures.
- **Unsubscribe** — the destructive action; confirms, then deletes the feed (`DELETE /v1/feeds/{id}`).

---

## Scenarios

Every scenario lists **ID · Platforms · Setup · Steps · Expected**. Platforms is one of `web`, `android`, `both`. These describe the target behaviour; whether a given scenario is implemented today is tracked by ticket #80, not in this table.

**Fixtures.** The Setup column describes the *kind* of state the server is in (e.g. "populated server", "feed with no unread articles") rather than fixed counts. When a count appears in the Expected column it is an invariant against whatever the fixture actually contains (e.g. "the Unread badge matches the number of unread articles in the local mirror"), not a magic number that must be matched by every concrete test.

### Authentication & session

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| AUTH-1 | both | Fresh client, valid creds in config | Open app, type username + password, submit by clicking the primary button | Lands on Feed (Unread) screen within 2s. |
| AUTH-1a | web | Fresh client, valid creds | Type username, Tab to password, type password, press **Enter** | Form submits (Enter from either field). |
| AUTH-1b | android | Fresh client, valid creds | Type username, press the **Next** IME action, type password, press the **Done/Go** IME action | Focus moves to the password field on Next; pressing Done submits. |
| AUTH-2 | both | Fresh client, invalid password | Submit login form | Error message shown ("Invalid username or password"); focus stays on the password field. |
| AUTH-3 | both | Already logged in | Reload page (web) / kill + reopen app (android) | No login prompt; lands on Unread. |
| AUTH-4 | both | Logged in | Settings → Logout | Returns to login screen; session cleared. |
| AUTH-5 | both | Logged in, server's JWT secret has been rotated (or cookie otherwise invalidated) | Trigger any API call | A single, debounced redirect to the login screen. No infinite-loop. Local session signal cleared. |

### Feed list & navigation

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| FEED-1 | both | Populated server (multiple feeds, some unread articles) | Open the **Unread** view (default after login) | List shows only unread items, sorted by `published` DESC. Unread nav badge equals the number of unread articles in the local mirror (badge == list — see the Article sync contract; after a sync the mirror matches the server). |
| FEED-1a | both | Same fixture | Switch to **All articles** | List shows all articles (read + unread), newest first. |
| FEED-2 | both | Populated server with at least one feed | Click/tap a feed (subscription) in the sidebar folder list (web) or `Feeds` tab → row (android) | List filters to that subscription's items — a **local filter against the mirror**, no per-feed server fetch (Article sync contract). Web URL = `/feed/:subscriptionId` (the server's feed id). |
| FEED-3 | web | Article list with at least two items | Click any item that isn't currently selected | The clicked row gets `panel` background + 2px inset `accent` bar on its left edge; the previously selected row loses that styling; the reader pane fills with the clicked article. |
| FEED-4 | web | Article list and reader both have overflow content | Scroll the list, then scroll the reader independently | Each column scrolls without affecting the other. |
| FEED-5 | both | At least two distinct feeds | Render the article list | Per-feed dot colors are stable across reloads and identical for the same feed across sidebar / list / reader meta. Collisions across distinct feeds are tracked separately — see #36. |
| FEED-6 | android | Article list with content | Pull down on any article list (Unread / All / per-feed) | Triggers a refresh; spinner shown; list refreshes; error path lands in ERR-1. |
| FEED-7 | web | Article list with content; server reachable | Click the `↻` refresh glyph in the sidebar footer (next to the "Synced … ago" line) | Triggers a refresh; the footer updates the "Synced … ago" timestamp on success; error path lands in ERR-1. |
| FEED-8 | both | Populated server with at least one unread article | Click/tap the `✓` button next to the unread dot on an article row | A `✓` button appears next to the unread dot; both are visible only when the article is unread. Clicking the button fires `PUT /v1/articles/{id}/read` with `is_read=true`; the Unread badge decrements by one. In the All articles view the row stays but the dot and button disappear (article is now read). In the Unread view the row disappears immediately — the Unread list acts as a TODO list; marking an item done removes it on the spot. |
| FEED-9 | both | Unread article list | Click/tap any article row to open it in the reader | `PUT /v1/articles/{id}/read` fires automatically; the article's unread dot disappears. Web: the article row stays in the list (still selected, no dot) until another article is opened — then it drops out of the Unread filter. Android: the reader is full-screen; the article is removed from the list on return. |
| FEED-10 | both | Density = `compact`; at least one article with non-blank excerpt | Open article list | Each row uses compact padding (10/18 web, 12/22 android); title is 15px/16sp; excerpt and thumbnail are absent. |
| FEED-11 | both | Density = `comfy`; at least one article with non-blank excerpt | Open article list | Each row uses comfy padding (20/22); title is 17px/18sp; a 64×64 (web) / 56×56 (android) hue-colored thumbnail appears to the left of the excerpt, both in the same flex row. |
| FEED-12 | both | Same article unread on two logged-in clients (web + android) | Mark it read on client A; let client B reach its next sync (poll, foreground, or manual refresh) | On client B the article drops out of the Unread view and the Unread badge decrements without a full re-download — the read-state change arrives on B's next sync delta. Marking it unread again on either client converges the same way (Article sync contract). |

### Reader

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| READ-1 | web | Feed screen, article A selected | Click article B in the same list | Reader pane swaps to article B in place; URL updates to `/article/:articleId` so the reader's content is deep-linkable. |
| READ-1b | android | Article list visible | Tap an article row | A full-screen Reader screen is pushed on top of the tab shell; the bottom-nav tab bar is hidden; the OS status bar inset is respected. |
| READ-1c | android | Reader open, list was scrolled to some position before opening | Tap the back chevron in the reader top bar | Returns to the list at the same scroll position. |
| READ-2 | both | Article whose HTML body includes `<script>`, `<iframe>`, `<img>`, `<p>`, `<a>`, `<em>`, `<strong>` | Open in reader | The body is sanitized through an allowlist: `<script>` and `<iframe>` are stripped; `<p>`, `<a>`, `<em>`, `<strong>`, `<img>`, `<blockquote>`, `<ul>`, `<ol>`, `<li>`, `<h2>`, `<h3>` are preserved. Anything outside the allowlist is dropped (tag) but its text content is kept. |
| READ-3 | both | Reader open | Tap/click the `Aa` button | Opens a font-size picker bound to `UserPrefs.fontSize`. The active value is the user's current setting; selecting a new value persists immediately and updates the reader body in place. |
| READ-4 | both | Font size set to 22 in Settings | Open reader | Body text computed size = 22 (px on web, sp on android). |
| READ-5 | both | Article with `link = "https://example.com"` | Tap/click ↗ Open (or the URL in the reader footer) | Opens the article URL in a new browser tab (web) / external browser intent (android). The footer URL itself is a clickable anchor. |
| READ-6 | web | Fresh login, no article selected | Open Unread / All | Reader pane shows em-dash + "Select an article to begin reading." |
| READ-7 | both | Reader open on any article | Tap/click the `↩ Mark unread` button in the reader action group (web: next to ↗ Open / ⎙ Share; android: compact `↩` next to ⎙ Share) | Because FEED-9 auto-marks articles as read on open, this button provides the undo direction only. Pressing it fires `PUT /v1/articles/{id}/read` with `is_read=false`; the Unread badge increments; on return to the list the row reappears in the Unread view with its dot restored. |

### Subscriptions

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| SUBS-1 | both | Server has feeds spread across multiple folders (categories) | Open Subscriptions (web sidebar link / Android `Feeds` tab) | Every feed the server returns is rendered as a row. Android groups rows by folder with uppercase headers; web lists rows flat with the folder name in the right column. |
| SUBS-2 | both | Empty subscriptions; valid RSS URL on hand | Click "+ Add feed", paste URL, submit | New row appears; `POST /v1/feeds` returns 201; nav count increments. |
| SUBS-3 | both | Multiple feeds, at least one whose name contains the substring being typed | Type that substring in the search box | Only matching rows visible; filtering is client-side, no server call. |
| SUBS-4 | both | Existing feed | Open the overflow menu → Rename → enter new name → save | Row updates inline; `PUT /v1/feeds/{id}` with `custom_title = "<new>"`. **Web requirements:** the overflow dropdown must render above adjacent rows (not be clipped by the list container), and the rename input must be prepopulated with the current name and pre-selected. |
| SUBS-5 | both | Existing feed with at least one article | Overflow → Delete → confirm | Feed and its articles vanish from the article list and the subscriptions list. |
| SUBS-6 | both | Subscriptions screen with ≥ 1 failing feed | Open Subscriptions | A non-interactive summary banner is pinned above the search bar showing a count chip (`{N} errors`), a human sentence splitting failing vs. warning (`2 failing · 1 warning — last checked {time}`), and **no** expand / collapse control. It renders in error tone when ≥ 1 feed is failing and demotes to warn tone when every issue is a warning. It is absent when no feed is failing. See the Feed errors contract. |
| SUBS-7 | both | Subscriptions screen with a mix of healthy and broken feeds | Render the list | Broken feeds stay in their folder at their normal position (web: flat list with the folder in the right column; Android: grouped under uppercase folder headers — SUBS-1). A broken row has its avatar dimmed to 0.6 opacity, an error / warn badge after the name, a time-since-failure on the right in the tone foreground, and a ▼ chevron. Healthy rows are unchanged (unread count + `⋯` overflow). |
| SUBS-8 | both | Subscriptions screen with a broken feed row | Tap the broken row | The row toggles an inline accordion open below it (▼ → ▲); tapping again closes it. The accordion has a 3px left border in the row's tone and contains the mono diagnostic block, the one-sentence explanation, and the action buttons (Feed errors contract). Tapping a *healthy* feed row does not expand anything (web: navigates to the feed's articles; Android: per SUBS-1). |
| SUBS-9 | both | Broken feed accordion open | Trigger each action | `Retry now` / `Retry once` fires `POST /v1/feeds/{id}/refresh` (Retry once does not un-pause the schedule) and shows an in-progress state; on success the row returns to healthy and the badge clears. `Fix URL…` opens an inline editor for the feed's source URL; saving revalidates server-side (a still-invalid URL keeps the error, a valid one clears it). `View raw ↗` opens the raw-response inspector (ERR-8). `Unsubscribe` confirms then fires `DELETE /v1/feeds/{id}`, removing the feed and its articles. |

### Settings (and prefs persistence)

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| SET-1 | both | Prefs set in storage: font=22, density=comfy | Open Settings | Each segmented control reflects the stored value as active. |
| SET-2 | both | Default prefs | Change every control once, reload page / restart app | Each control reflects the new value; no value reset. |
| SET-3 | both | Reader open at 18 | Change font size in Settings to 22 | Open reader body re-renders at 22 (px / sp) without reload. |
| SET-4 | both | Article list at `regular` density | Change density to `compact`, then to `comfy` | `compact`: excerpts hidden, rows shorter. `comfy`: thumbnails visible. |
| SET-5 | both | OPML file with 5 feeds | Account → Import OPML → choose file | 5 new feeds appear in the subscriptions list; success toast/dialog summarizes the response. |
| SET-6 | android | Server URL = `http://10.0.2.2:3000/` | Change to `http://other:3000/`, save | URL persisted; next API call uses the new host; app re-prompts login if the new host's session is unknown. |
| SET-7 | both | Logged in | Open Settings | Account section shows an Import OPML action, a Logout action, and an About row with `Client v<x> · Server v<y>`. Web shows no Server URL row (see #32). |
| SET-8 | both | Default 90d retention | Change "Keep articles" to 30d, wait one window or trigger the retention sweep | The new value is persisted server-side; the server's retention sweep deletes articles older than 30d; those deletions propagate to each client's mirror on its next sync (Article sync contract). "Forever" disables retention. |
| SET-9 | both | Refresh interval = `15m` | Open the article list, leave the app idle | Within ~15 minutes the client pulls an incremental sync delta from **our own server** (not upstream) and any new articles already fetched appear without manual refresh. `Manual` disables the poll; changing the interval re-takes effect live without a restart. The poll **pauses while the client is backgrounded** (web tab hidden / android Activity stopped) and on return to the foreground does an immediate sync before resuming the cadence. |
| SET-10 | both | Article list open; ≥1 subscribed feed that has published new articles upstream | Trigger the primary refresh gesture (web ↻ glyph / android pull-to-refresh) | The server is asked to pull the feeds **upstream** (`POST /v1/feeds/refresh`), then the client pulls the resulting sync delta, the newly-fetched articles appear in the local mirror, and the "Synced … ago" line updates. The gesture is globally rate-limited to once per 60s; when rate-limited it **silently** falls back to a plain delta sync (no upstream pull) — no error, no second button. |

### Server fetch cadence & good-citizen behavior

These cover the server's upstream-fetch politeness controls. They are server-side
behaviors (not a UI surface); "Platforms" marks where the *effect* is observable.

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| FETCH-1 | both | A feed with `fetch_interval_minutes = 15`; scheduler tick = 5 min | Wait across several scheduler ticks | The feed is fetched once per ~15 minutes (not every 5-min tick and not stuck on a coarse 30-min floor); shorter-than-tick intervals are honored at tick granularity. |
| FETCH-2 | both | A feed whose upstream returns `429 Too Many Requests` (or `503`) with a `Retry-After` header; `respect_retry_after = true` | The scheduler fetches the feed | The feed is deferred until at least the `Retry-After` time (delta-seconds or HTTP-date), the deferral does **not** count as an error (no exponential backoff / dead-feed escalation), and the outgoing request carries the `Feed/<version> (+<contact_url>)` User-Agent. The deferral clears on the next successful fetch. With `respect_retry_after = false`, 429/503 fall back to the generic backoff error path. |
| FETCH-3 | both | `adaptive_interval = true`; a feed with `fetch_interval_minutes = 60`; the feed returns 304 Not Modified on 8 consecutive fetches | Wait across several scheduler ticks | The feed's effective interval is lengthened to ~180 min (base 60 * multiplier 3), bounded by `max_interval_minutes` (default 1440). On the next fetch that returns new content, the counter resets and the effective interval returns to the configured 60 min. The user's per-feed `fetch_interval_minutes` is never mutated. With `adaptive_interval = false` (default), the feed is fetched at exactly its configured interval regardless of 304s. *Gated/optional optimization.* |

### Navigation

These cover the primary-nav surfaces on each platform. Per-feed filtering is in FEED-2.

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| NAV-1 | web | Logged in | Click each sidebar primary-nav item in turn: Unread, All articles, Subscriptions, Settings | Each click navigates to the corresponding screen; the active item shows the `accentSoft` background + `accent` text. Unread is the post-login default. |
| NAV-2 | android | Logged in | Tap each of the four bottom-nav tabs in turn: Unread, All, Feeds, Settings | Each tab swap is < 200ms; active tab glyph + label in `accent`. Unread is the post-login default. |

### Error / edge

See [VISUAL_SPEC.md](VISUAL_SPEC.md) §States & feedback for the design treatment of each surface referenced below (banner, big mid-pane state, sidebar `!` badge, Subscriptions feed-error surface — summary banner + inline accordion, raw-response inspector, inline reader note, inline form error, modal interrupt, sidebar-footer state, snackbar).

| ID | Platforms | Setup | Steps | Expected |
|---|---|---|---|---|
| ERR-1 | both | One sync attempt fails (kill server, then trigger refresh) | — | Web: sidebar footer changes to `Last sync failed · retry` (failed sync state). The previously-cached articles stay visible. Clicking `retry` fires a fresh sync; success returns the footer to `Synced … ago`. Android: pull-to-refresh spinner dismisses; a `Sync failed — retry` snackbar surfaces with a trailing `Retry` action. |
| ERR-2 | both | Filter to a feed/subscription with 0 articles (or Subscriptions search with no matches) | Render | Centred serif italic 16 `ink3` "Nothing here yet." in the list / content area. No buttons — this is the no-verb empty state. When the empty state needs an action (no feeds at all, inbox zero), use the big mid-pane state instead — see ERR-10 / ERR-11. |
| ERR-3 | both | Stale cookie (server restarted with new secret) | Trigger any API call | See AUTH-5; the visual treatment is the session-expired modal (ERR-14). |
| ERR-4 | both | Device loses network (airplane mode), or `fetch` rejects with a network-level error | Stay open while disconnecting, or open the app while offline | Web: warn **banner** above the content area (`OFFLINE · You're offline. Showing {N} cached articles from your last sync at {time}…`); sidebar footer switches to the `offline` state (`Offline · cache only`). Reading and marking-as-read still work against the cache and queue locally. Reconnecting clears the banner, replays the queued mutations, and returns the footer to `ok`. Android: same condition surfaced via snackbar + `offline` sidebar footer. |
| ERR-5 | both | Server is unreachable for ≥ 3 consecutive sync attempts (DNS failure, connection refused, 5xx with no retry budget left) | Wait, or click the sidebar `↻` | Web: **big mid-pane state** replaces list + reader. Eyebrow `ERR · {error-code}`, title `Couldn't reach the server.`, italic body explaining the cache is intact and what we're doing, mono detail block with the endpoint + retry budget, primary `Retry now`, secondary `Check service status ↗`. Sidebar footer is in the `failed` state. Android: same content surfaced via snackbar (`Couldn't reach the server — retry`) over the cached list; the big mid-pane only replaces the screen on a cold-boot with no cache. |
| ERR-6 | both | Server returns `429 Too Many Requests` on a sync | — | Web: warn **banner** above the content area (`RATE LIMIT · paused for {countdown}…`); sidebar footer switches to the `paused` state (`Paused · {duration}`). Background auto-poll is paused for the countdown; manual refresh, reading, and mark-as-read continue to work. When the countdown elapses, the banner clears and auto-poll resumes on the next interval. Android: snackbar with the same copy + `paused` sidebar footer. |
| ERR-7 | both | One feed returns `410 Gone` for ≥ 14 consecutive sync attempts | Navigate to that feed, then open Subscriptions | The feed's sidebar / Feeds-tab row gets the `!` badge in **error** tone; the row is **not** struck through and selecting the feed shows its cached articles normally (no big mid-pane takeover). On Subscriptions the feed stays in its folder with its icon dimmed (0.6) and a `410 GONE` error badge after the name; expanding its accordion (SUBS-8) shows the mono diagnostic (HTTP 410 + URL, consecutive-failure count, first-failure date, `next retry: none`), the explanation that the publisher signals the feed is permanently gone and cached articles are preserved, and the actions `Retry once` / `Fix URL…` / `View raw ↗` / `Unsubscribe`. |
| ERR-8 | both | One feed returns a non-parseable body (malformed XML / non-feed HTML / wrong Content-Type) on its most recent sync attempt | Navigate to that feed, then open Subscriptions | The feed's sidebar / Feeds-tab row gets the `!` badge in **error** tone. There is **no** banner over the article list — the cached articles render unchanged and open in the reader normally. On Subscriptions the feed shows a `PARSE FAIL` error badge; expanding its accordion shows the mono diagnostic (response status + Content-Type, e.g. `200 OK · text/html (expected application/rss+xml)`, URL + size, the parser error with line/col, consecutive-failure count + next retry), the explanation that HTML was returned instead of a feed and stale articles are still shown, and the actions `Retry now` / `View raw ↗` / `Unsubscribe`. `View raw ↗` opens the **raw-response inspector** (unchanged from its existing spec — top bar with the feed name and Copy / Open-URL actions, metadata strip, line-numbered source view with the offending line highlighted in the error tone and a caret annotation below it, footer strip). After 14 consecutive parse failures the feed escalates to the ERR-7 dead-feed treatment. |
| ERR-9 | both | Open an article whose `link` returned 4xx the last time we touched it (or whose body was retracted server-side) | Open the article in the reader | Above the body (between the action row and the first paragraph), a warn **inline reader note**: `WARN · The original page at {url} now returns {status}. You're reading the cached copy from {when}. Try Wayback ↗`. The rest of the reader is unchanged; `↗ Open` and the footer URL remain real anchors (the user may have a reason to click through even to a 404). |
| ERR-10 | both | Logged-in account with zero feeds (first run, post-unsubscribe-everything) | Open the app | Sidebar shows the four primary-nav items and no folder section; sidebar-footer text is `Nothing to sync yet`. Content area shows the **First run** big mid-pane state: eyebrow `WELCOME`, title `Start by adding a feed.`, italic body explaining we accept URLs or OPML, primary `Paste a URL…` (opens the same add-feed form as SUBS-2), secondary `Import OPML…` (same as SET-5), hint about no starter pack. |
| ERR-11 | both | Logged-in account with ≥ 1 feed and zero unread articles | Open the Unread view | Content area shows the **Inbox zero** big mid-pane state: eyebrow `INBOX ZERO`, title `You're caught up.`, italic body `No unread articles across {N} feeds.`, secondary `Browse all articles`. No stats, no hint copy — the design deliberately omits "today you read X" / "next sync in Y" because the client has no reliable source for those metrics yet. Sidebar Unread count is hidden (rendered as no count rather than `0`). Implementations may show ERR-11 in place of ERR-2 specifically for the Unread view — empty-but-completed is different from empty-but-filtered. |
| ERR-12 | both | Add-feed form open; paste a URL that doesn't return a valid RSS / Atom / JSON Feed body | Submit | The form stays open. The URL field's border switches to the error tone. An **inline form error** appears below the field: `ERR · This URL didn't return a valid feed. Paste the feed URL directly (e.g. example.com/rss/feed.xml), not the site's homepage.` No row is added; the Subscribe submit is consumed but no `POST /v1/feeds` fires. Focus stays in the URL field. The client does **not** attempt auto-discovery of common feed paths (`/feed`, `/rss`, `/atom.xml`, …) — only the URL as typed is fetched. |
| ERR-13 | both | Add-feed form open; paste a URL whose discovered feed URL exactly matches an existing subscription | Submit | The URL field's border switches to the warn tone. An **inline form error** appears below the field: `WARN · You're already subscribed to {name} — it's in the {folder} folder. Open it instead, or change the URL above.` The `{name}` is a real link that navigates to that feed's view. The submit is blocked; no `POST /v1/feeds` fires. |
| ERR-14 | both | Logged-in user; their session is invalidated mid-use (token rotated, cookie cleared, AUTH-5 path) | Trigger any API call | A warn **modal interrupt** covers the viewport: eyebrow `SESSION EXPIRED` (in warn fg), title `You've been signed out.`, italic body explaining the reason and that the cache is intact, an inner `panel` strip showing the signed-in identity (`Signed in as {username}` with the username in `ui-monospace`), primary `Sign in again` (routes through the login flow with the username prefilled), secondary `Forget this device` (clears local cache and routes to a clean login). The sidebar footer behind the scrim is in the `failed` state. The scrim blocks all interaction until the user picks an action. |
| ERR-15 | both | One feed returns an HTTP 4xx other than 410 (e.g. 403 / 404) on its most recent sync | Open Subscriptions | The feed shows the `!` badge and an **error**-tone badge carrying the status (e.g. `HTTP 404`); its accordion mono block names the status + URL, consecutive-failure count, last attempt, and next retry. Retries continue on schedule. Actions: `Retry now` / `Fix URL…` / `View raw ↗` / `Unsubscribe`. |
| ERR-16 | both | One feed returns an HTTP 5xx on its most recent sync (not yet escalated) | Open Subscriptions | The feed shows the `!` badge and a **warn**-tone badge (e.g. `HTTP 500`); its accordion renders entirely in warn tone (warnFg / warnBg / warnBd), the explanation notes the server is erroring and this usually clears on its own, and the mono block shows `next retry in ~{interval}`. Retries continue on schedule and honour any `Retry-After` (FETCH-2). Actions: `Retry now` / `Unsubscribe`. |
| ERR-17 | both | One feed's fetch fails at the network layer (DNS failure, connection refused, timeout) | Open Subscriptions | The feed shows the `!` badge and a **warn**-tone badge; the accordion explains the feed couldn't be reached and retries continue. If *every* feed is failing this way the condition is the server being unreachable — escalate to **ERR-5** (big mid-pane) instead of showing a per-feed warning for each. |
| ERR-18 *(reserved)* | — | — | — | New edge cases append here. |

**Catalogue invariant.** Every artboard in the story board's **Edge cases · …** and **Subscriptions · Feed errors** design-canvas sections must map to a scenario above; every `ERR-4`+ row must have a corresponding artboard. The **Subscriptions · Feed errors** artboards map to the feed-error scenarios (ERR-7, ERR-8, ERR-15–ERR-17 for the conditions; SUBS-6–SUBS-9 for the surface mechanics). When you ship a new edge-case design, add the row in the same PR.

---

## Maintenance notes

- This document does **not** track per-scenario implementation status. Don't re-add a `Status` column; implementation status is verified out-of-band under ticket #80, which spawns follow-up tickets for real gaps. Keep these scenarios describing the *target* behaviour only.
- When a feature is added, append a new scenario in the appropriate group with a fresh ID (don't reuse retired IDs — gaps are fine).
- When a feature is dropped, move the scenario into the "Features explicitly NOT supported" table with one sentence on the reasoning.
- Settings are platform-asymmetric; keep the Settings reference table in sync with what each client actually renders.
- Visual fidelity questions go to [VISUAL_SPEC.md](VISUAL_SPEC.md); behavior questions live here — and behavior wins when the two disagree.
