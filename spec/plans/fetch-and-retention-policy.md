# Fetch cadence & retention policy — design + implementation plan

**Date:** 2026-06-20 13:05 PDT

> **Status (2026-06-21): all steps landed (PRs #44–#51).** This document is the
> frozen design rationale; the §7 step checkboxes below reflect the plan as drafted,
> not current state. Closed BUG-12, #37, #38. **One deliberate descope:** the
> `/v1/settings/fetch-interval` endpoint (§4.1, §6) was **not** built — the
> `default_fetch_interval_minutes` setting is config-only. **Two UI gaps were left
> behind** — the per-feed fetch-interval control (#77) and the per-feed "Refresh this
> feed" action (#78): both have working server + shared layers but no wired widget. See
> the notes in §4.1/§5.3/§6.

Supersedes the loose, partially-decorative behavior behind BUG-12, #37, #38. Defines
end-to-end how often clients refresh, how often the server fetches upstream, how long
articles are kept, and how the server stays a good citizen to upstream sources.

---

## 1. The three knobs, kept separate

The current design conflates "fetch from server" with "fetch from source". They are
three independent concerns and must be modeled as such:

| Knob | Owner | Cost | What it controls |
|---|---|---|---|
| **Client refresh interval** | client (per device) | cheap — hits our own server | How often a client re-reads the article list for UI freshness. #38. |
| **Server fetch interval** | server (global default + per-feed) | expensive — hits upstream | How often the server pulls each feed from its source. Good-citizen surface. |
| **Retention window** | server (global) | n/a | How long the server keeps articles before purging. #37. |

Conflation is the root of BUG-12: the client "Refresh interval" control implies it
controls upstream fetching, but it can only ever control local polling. The spec must
say this explicitly, and we add a **manual "fetch now"** bridge (§5) so the user's
refresh gesture *can* reach upstream when they want it to.

---

## 2. Server-persisted settings store (foundation for #37)

No settings/KV table existed when this plan was drafted. **PR #44 now delivers this store**
(see §7) — the description below documents the target shape; treat it as "verify #44 matches,
then extend" rather than build-from-scratch. It is the prerequisite for everything
server-side that a client needs to configure.

- **Migration v16** in [server/src/db.rs](server/src/db.rs): a small key/value table
  (`settings(key TEXT PRIMARY KEY, value TEXT)`) rather than a wide typed table — fewer
  future migrations as knobs are added. Test in `db_tests.rs` per the migration rule.
- Typed accessor layer in Rust (parse/serialize per key) so handlers and the scheduler
  read typed values, not raw strings.
- **Fallback chain:** persisted value → config file value → built-in default. A fresh DB
  with a populated config behaves exactly as today.

Keys introduced by this plan:

| Key | Type | Default | Used by |
|---|---|---|---|
| `retention_days` | int \| null (null = forever) | config `[retention].days`, else 90 | retention sweep (§4) |
| `retention_purge_read_only` | bool | true | retention sweep (§4) |
| `default_fetch_interval_minutes` | int | config `[fetch].default_interval_minutes`, else 60 | new feeds (§3) |

---

## 3. Server fetch cadence + good-citizen behavior

### 3.1 Finer tick, gated by per-feed interval
Replace the single 30-min cron with a **5-minute tick** that still defers to
[`should_skip_feed`](server/src/scheduler.rs#L25). The gating already exists; the coarse
tick is the only reason a 15-min feed interval can't be honored. A 5-min tick lets us
honor 15m/30m/1h intervals without ever fetching a feed more often than its own interval.

Update the [scheduler.rs:28-29](server/src/scheduler.rs#L28) comment that documents the
30-min floor.

### 3.2 Global default + per-feed override
Per-feed `fetch_interval_minutes` stays the source of truth. New feeds inherit
`default_fetch_interval_minutes` (§2) instead of the hardcoded `DEFAULT 30` column
default — change [`add_feed`](server/src/db.rs#L705) to read the setting. Enforce a
**`min_fetch_interval_minutes` floor** (config, default 15) so neither a client nor a
config typo can make us hammer a source.

### 3.3 Politeness (new good-citizen controls)
Already have: conditional GET, exponential backoff, 30s timeout, custom UA. Add:

1. **Honor `Retry-After`.** Today HTTP 429/503 fall into the generic error path →
   exponential backoff. Make 429/503 a first-class `FetchContent` outcome in
   [fetcher.rs](server/src/fetcher.rs) that reads `Retry-After` (delta-seconds or HTTP
   date) and sets the feed's next-eligible time to at least that value. This is the single
   most important anti-ban behavior.
2. **Per-host spacing + jitter.** Within a tick, don't fire all feeds of the same host at
   once and don't fire every feed exactly at `:00`. Add small per-host serialization /
   delay and a random jitter to each feed's due-time so we never thunder-herd a host or
   our own egress. (Single-user scale: a simple per-host "last hit" map + min-gap is
   enough; no need for a full work queue.)
3. **Richer User-Agent with contact URL.** Bump
   [fetcher.rs:54](server/src/fetcher.rs#L54) from `RSSAggregator/1.0` to
   `Feed/<version> (+<contact_url>)` so site operators can identify and reach the operator
   — standard RSS etiquette and the cheapest way to avoid being blocked.
   **The version is NOT in config** — it's baked in at build time via
   `option_env!("FEED_VERSION")` (same source as [handlers.rs:165](server/src/api/handlers.rs#L165),
   fallback `0.0.0-dev`). The server assembles the UA at runtime from that build-time
   version plus a config-supplied `[fetch].contact_url`. Config holds only the contact
   URL, not the full string. (If a full override is ever wanted, accept a `[fetch].user_agent`
   template containing a literal `{version}` placeholder the server interpolates — but
   default to the assembled form.)
4. **Adaptive interval (optional, phase 3).** Feeds that return many consecutive 304s get
   their *effective* interval lengthened (bounded by a max); feeds that change every fetch
   get it shortened (bounded by the min floor). Purely an optimization — gate behind a
   config flag, ship after the above land.

### 3.4 New config section
```toml
[fetch]
scheduler_tick_minutes = 5          # how often the loop wakes; per-feed interval still gates
default_interval_minutes = 60       # inherited by new feeds
min_interval_minutes = 15           # hard floor; protects upstreams
contact_url = "https://github.com/fmonniot/Feed"  # used in UA; version is build-time, not here
respect_retry_after = true
```

---

## 4. Retention (#37)

### 4.1 Wire the client setting through
- Endpoint: **per-knob, as already shipped in PR #44** — `GET`/`PUT /v1/settings/retention`
  taking `{ "days": <int> | null }` (null = forever). Earlier drafts proposed a
  consolidated `PUT /v1/settings` with partial-update (PATCH-style) semantics, but #44
  landed the per-knob shape first and it's the better fit here: with one endpoint per knob
  there is no "field absent vs. field present and null" ambiguity to disambiguate in the
  deserializer, so we avoid the `Option<Option<T>>`/`Patch<T>` complexity entirely. The
  cost — one more endpoint per future knob — is negligible at this app's scale. **Adopt
  per-knob; the fetch-interval setting (§3.2) gets its own `…/settings/fetch-interval`
  endpoint the same way.** *(Descoped — not built. The global default
  `default_fetch_interval_minutes` is config-only; the setting key still resolves through
  the fallback chain — its persisted tier simply has no production writer. Note the original
  rationale ("clients already set per-feed intervals via `PUT /v1/feeds/{id}`") only holds at
  the API/ViewModel layer: the endpoint and `FeedViewModel.setFeedInterval` exist, but **no
  UI control invokes them**, so fetch cadence is admin/config-only for end users today. See
  ticket #77 for the missing per-feed UI control.)*
- The 3 AM sweep reads `retention_days` each tick via the fallback chain (#44 does this
  already; note its "forever" is stored as the string `"forever"`, not `null`, in the KV
  value column — a cosmetic choice, left as-is). See [scheduler.rs:156](server/src/scheduler.rs#L156).

### 4.2 Never purge unread (design improvement)
The user framed retention as "how long it keeps **read** articles." Current
[`delete_old_articles`](server/src/db.rs#L1148) deletes by age regardless of read state —
it can silently drop something the user never saw. Change the default sweep to delete
`published/fetched_at` older than N days **AND `is_read = true`**, controlled by
`retention_purge_read_only` (default true). This makes the Unread list a durable TODO
list and matches user intent. Keep an escape hatch (`false`) for users who want a hard
age cap regardless of read state.

**"Forever" = truly never delete** *(resolved)* — `retention_days = null` performs zero
deletions, no hidden safety cap.

### 4.3 Tests
Server: sweep honors persisted value (incl. `forever` = no-op) and the read-only filter
(unread survives a sub-retention age). Per-client: Settings read/write round-trip.

---

## 5. Client refresh + the "fetch now" bridge (#38 + new)

### 5.1 Client auto-poll (#38, unchanged scope)
Each client polls the article-list endpoints at 15m/1h/6h; `manual` disables. Pause on
background (`visibilitychange` / lifecycle), resume on foreground. Errors route to ERR-1.
Test with a virtual clock, not wall time.

### 5.2 New: on-demand upstream fetch endpoint
Today refresh only re-reads the DB, so a user can refresh forever and see nothing new
until the 30-min tick. Add two endpoints that trigger an immediate upstream fetch,
**rate-limited** (reuse [rate_limit.rs](server/src/rate_limit.rs); e.g. once per 60s
globally) and respecting the same conditional-GET / Retry-After path so they can't be
used to hammer sources:

- **`POST /v1/feeds/refresh`** — pull all feeds. The primary gesture.
- **`POST /v1/feeds/{id}/refresh`** — pull a single feed. The secondary, per-feed gesture.

**Scaling note:** the v1 implementation processes feeds sequentially (awaiting
each `process_feed` serially). Response time grows linearly with feed count
(~*N × avg-fetch-latency*). Fine for a small install with the 60s rate limit,
but if the feed list grows, consider either:
1. `futures::stream::iter(feeds).for_each_concurrent(limit, |f| ...)` — concurrent with a cap.
2. Spawn the work into a background task (`tokio::spawn`) and return immediately; the client
   re-reads the article list afterward anyway.

### 5.3 How "fetch now" is exposed (resolved)
Two distinct actions exist — **(A)** re-read the list from our DB (cheap), and **(B)**
trigger an upstream pull then re-read (expensive, rate-limited). **Do not show two refresh
buttons.**

- **Primary refresh = B.** The web ↻ glyph (FEED-7) and android pull-to-refresh
  (FEED-6, #33) call `POST /v1/feeds/refresh`, then re-read. This matches what users
  expect "refresh" to mean in an RSS reader: *get me the newest articles*, not *re-render
  what's already cached*. When rate-limited, the gesture **silently falls back to a plain
  re-read (A)** and updates the "Synced … ago" line — no error, no second button.
- **A is never a user-facing button.** It is what the auto-poll timer (#38) does silently
  and what runs after B completes.
- **Per-feed refresh** (`POST /v1/feeds/{id}/refresh`) is exposed only as a secondary
  affordance: a **"Refresh this feed"** item in the subscription row's overflow menu (next
  to Rename/Delete, SUBS-4/5). Not a primary button. If step 5 runs long, per-feed can
  ship a follow-up — the endpoint lands with the global one; only the menu wiring is
  deferrable. *(Outcome: the menu wiring **was** deferred. `POST /v1/feeds/{id}/refresh`
  and `FeedRepository.refreshFeedUpstream` shipped, but no `FeedViewModel` function or
  overflow-menu item invokes them yet — tracked as #78.)*

---

## 6. Spec changes

- **spec/FEATURES.md settings table:** split the "Refresh interval" note to state it is
  *client-side local polling only*, and add a one-line "Fetch now" / manual-refresh row
  describing the on-demand upstream trigger.
- **Update SET-8** (retention) to specify the unread-preservation behavior.
- **Update SET-9** (refresh) to clarify it polls *our server*, not upstream.
- **New scenarios:** FETCH-1 (server honors per-feed interval at the finer tick), FETCH-2
  (429/Retry-After defers the feed), SET-10 (manual "fetch now" pulls upstream, is
  rate-limited).
- **server/README.md + config.example.toml:** document the new `[fetch]` / `[retention]`
  sections and the settings precedence (persisted → config → default).
- **spec/API_DOCUMENTATION.md:** document the per-knob settings endpoints
  (`/v1/settings/retention` — already in #44 — and `/v1/settings/fetch-interval`) and the
  refresh endpoint(s). *(`/v1/settings/fetch-interval` was descoped — see §4.1; only
  `/v1/settings/retention` and the refresh endpoints are documented.)*

---

## 7. Sequencing & how PR #44 fits

**PR #44 (`ticket/37-keep-articles-retention`, open) already lands most of steps 1–2.**
It adds a generic KV `settings` table (migration v16) with `get_setting`/`set_setting`,
wires retention end-to-end on both clients (`KeepArticles.toDays/fromDays`, Settings-mount
load + sync-on-change), and makes the 3 AM sweep read the persisted value with a "forever"
skip. Fully tested (12 server + 8 shared new tests, suites green).

**Reuse from #44 as-is:**
- The KV `settings` table + migration v16 + `get_setting`/`set_setting` — this *is* the
  foundation store from old step 1, and it's generic, so `default_fetch_interval_minutes`
  and the other §2 keys slot straight in.
- Client retention wiring (enum↔wire conversion, Settings load/sync) — done.
- Scheduler reading the persisted retention value + "forever" short-circuit — done.

**Deltas to apply on top of #44 (do NOT redo what it did):**
- **Unread-preservation is missing.** #44's sweep still calls `delete_old_articles(days)`
  by age regardless of read state. Add `retention_purge_read_only` (default true) and the
  `is_read = true` filter (§4.2). This is the one functional gap.
- **Endpoint shape diverges from §4.1.** #44 shipped per-knob `GET/PUT /v1/settings/retention`
  (`{ days: int|null }`); "forever" is the magic string `"forever"` in the value column,
  not `null`. **Recommendation: accept #44's per-knob pattern and drop the consolidated
  `/v1/settings` idea** — see the revised §4.1. Merge #44 on its own terms.
- **`[fetch]`/`[retention]` config sections + fallback chain** (persisted → config →
  default) are not in #44. Still to add.

**Revised step order:**

1. **Merge PR #44** (retention end-to-end + KV store). — *server + clients* ✅ mostly done
2. **Retention follow-up:** unread-preservation (`retention_purge_read_only` + `is_read`
   filter) + test that unread survives a sub-retention age. — *server + clients*
3. **`[fetch]`/`[retention]` config sections + typed accessors over the KV store**
   (`default_fetch_interval_minutes`, fallback chain). — *server*
4. **Finer tick + min-floor + default-interval inheritance** (§3.1–3.2). — *server*
5. **Politeness: Retry-After + UA + per-host jitter** (§3.3.1–3.3.3). — *server*
6. **On-demand fetch endpoint + wire into client refresh** (§5.2–5.3; pairs with #33). — *server + clients*
7. **Client auto-poll (#38).** — *clients*
8. *(optional)* **Adaptive interval** (§3.3.4). — *server*

Ties into existing backlog: closes BUG-12, #37 (via #44), #38; complements #63 (rate
limiting) and #33 (android pull-to-refresh); reuses #7's health surface for "last/next
fetch" if we want to expose cadence to clients later.
