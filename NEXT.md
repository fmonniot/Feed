# Feed — Next

> **Session order lives here.** [TICKETS.md](TICKETS.md) and [BUGS.md](BUGS.md) are flat, ID-ordered backlogs with no priority encoded — this file is the sole source of what to work on next.

**Last updated:** 2026-07-13

---

## Tier 1 — Blocking

*Fix before the app is usable day-to-day. Pick from the top.*

*(empty — BUG-57 fixed on `bug/57-purge-resurrects-read-articles`)*

---

## Tier 2 — Degraded

*App works but something visible is wrong or a promised feature does nothing.*

**Web article rendering**

- **BUG-54** — Article doesn't render correctly (feed.ashelia.xyz #346/feed/2) · web

---

## Tier 3 — Background

*Real bugs and work, not in the daily critical path.*

**Server edge cases** _(batch into one session)_

**Feed errors on Subscriptions** _(#79 umbrella; #81–#86, #93, #94 done)_

**Fetch-cadence UI follow-ups** _(server + shared landed in PRs #44–#51; only the widget is missing)_

**Feature roadmap**
- **#63** — Server-side rate limiting · server
- **#4** — Categories UI + filtering _(decomposed into #122–#124 for the redesign)_ · clients
- **#5** — Full-text search UI · clients
- **#7** — Stats / health dashboard · clients
- **#9** — Batch read operations · clients

**Subscriptions / category-management redesign** _(spec: FEATURES.md §Categories & feed management + SUBS-10–16; #122–#124, #133 done)_

**Android UX follow-ups (issue #161)**
- **#118** — Feeds screen error summary bar takes too much space · android
- **#120** — Open article links in an in-app browser instead of an external app · android
- **#134** — Feeds search field takes fixed real estate, never collapses on scroll _(needs design exploration first)_ · android

---

## Deferred

_Pick up only when adjacent code is being touched or a specific pain point appears._

- **#14** — Migration framework: inline migration chain gets awkward past ~15 · server
- **#114** — Re-tune `maxParallelForks` now #96 killed the accumulation deadlock _(CI tuning follow-up; retry keeps builds green)_ · android + tooling
- **BUG-37** — Article id width inconsistent across the sync contract (`Article.id: Int` vs `deleted_ids: List<Long>`) _(latent; doesn't bite at ~20k rowids — fix when touching the store keys)_ · shared + clients
- **#132** — Partial index on `articles.link_status` for the probe-job queue scan _(pick up if profiling ever shows full-table scan cost; negligible at single-user scale)_ · server
- **#106** — FU-1: tombstone GC for the sync log _(file once #95/#97/#98 land; caps the one unbounded table)_ · server
- **#125** — Android per-feed article browsing (FEED-2 gap) _(needs a mobile design first; blocked on #124 landing)_ · android

---

## How to use this file

- **Starting a session:** pick the top unblocked item in Tier 1. If it is the wrong size or wrong module for the session, skip it with a one-line note and take the next.
- **Adding new work:** bugs → [BUGS.md](BUGS.md); features/UX → [TICKETS.md](TICKETS.md); then add a line to the right tier here.
- **When done:** remove the line. No need to archive here — BUGS.md and TICKETS.md carry the done history.
- **TICKETS.md / BUGS.md** are flat lists ordered by ID only, with no priority encoded there. This file is the sole source of session order.

---

## Entry format

### Tiers

Three tiers plus a deferred section:

```
## Tier 1 — Blocking
## Tier 2 — Degraded
## Tier 3 — Background
## Deferred
```

Each tier opens with a one-line italic description of what belongs there.

### Clusters

Within a tier, items are grouped by theme. The cluster name is a **bold paragraph** on its own line, optionally followed by an italic parenthetical note on the same line:

```
**Cluster name** _(optional note about the cluster)_
```

Cluster names are free-form labels — pick whatever groups the items meaningfully (e.g. `**Auth & session**`, `**Server edge cases**`, `**Visual polish**`).

### List items

Each item is a bullet under its cluster:

```
- **{ID}** — {short description} · {module(s)}
```

- **ID** — ticket number (`#N`) or bug ID (`BUG-N`), bold.
- **Description** — one short phrase: symptom or title. No trailing period.
- **Module(s)** — one or more of `server` · `shared` · `android` · `web` · `clients` · `all` · `tooling`, separated by ` + ` when more than one.
- **Trailing note** — optional, italic, in parentheses at the end of the line for caveats or pairing hints: `_(side-effect of BUG-7; fix together)_`.

Full example:

```
**Auth & session**
- **BUG-7** — Android: session not persisted → forced login on every cold start · android + shared
- **BUG-18** — Android: login screen flashes on every launch _(side-effect of BUG-7; fix together)_ · android
```

### Order within a tier

- **Tier 1:** top-to-bottom is the intended fix order. Clusters exist for readability only — overall position is what matters.
- **Tier 2 and 3:** order within a cluster is a suggestion; order between clusters is a rough guide, not a strict sequence.
- **Deferred:** no ordering implied.
