# Feed — Next

> **Session order lives here.** P-levels in [TICKETS.md](TICKETS.md) and [BUGS.md](BUGS.md) describe severity/classification only — this file decides what to work on next.

**Last updated:** 2026-06-28

---

## Tier 1 — Blocking

*Fix before the app is usable day-to-day. Pick from the top.*

_(empty — all Tier 1 items resolved)_

---

## Tier 2 — Degraded

*App works but something visible is wrong or a promised feature does nothing.*

---

## Tier 3 — Background

*Real bugs and work, not in the daily critical path.*

**Server edge cases** _(batch into one session)_

**Feed errors on Subscriptions** _(#79 umbrella; #81–#86 done)_
- **#94** — Android: show overflow menu on broken feed rows · android

**Visual polish**
- **BUG-31** — Android: Feeds header misaligned vertically with other headers · android
- **#87** — Android: custom design for add-feed modal · android
- **#90** — Remove share buttons in both Android and Web UIs · clients
- **BUG-32** — Android reader can't open the original article URL externally (READ-5 gap: no `↗ Open`, footer URL not clickable) · android


**Fetch-cadence UI follow-ups** _(server + shared landed in PRs #44–#51; only the widget is missing)_

**Feature roadmap**
- **#63** — Server-side rate limiting · server
- **#4** — Categories UI + filtering · clients
- **#5** — Full-text search UI · clients
- **#7** — Stats / health dashboard · clients
- **#9** — Batch read operations · clients

**Infra hygiene**
- **#24** — Contract tests between client models and server JSON · shared + server
- **#47** — Android release signing · android
- **#20** — `data_extraction_rules.xml` TODO · android
- **#74** — Reconsider `/logs` endpoint for observability · server
- **#81** — Fix gradle warnings on web and app modules · web + android
- **#89** — Clean up lingering doc-comments from starred feature removal · android + shared

---

## Deferred

_Pick up only when adjacent code is being touched or a specific pain point appears._

- **#14** — Migration framework: inline migration chain gets awkward past ~15 · server
- **#21** — Metro DI investigation · android
- **#64** — Out-of-band article link probe job · server
- **#36** — Feed-hue collision investigation · shared
- **#96** — Reduce per-test resource churn in JVM integration tests _(recurring flaky-timeout root cause; deferred 3×)_ · android + tooling
- **BUG-37** — Article id width inconsistent across the sync contract (`Article.id: Int` vs `deleted_ids: List<Long>`) _(latent; doesn't bite at ~20k rowids — fix when touching the store keys)_ · shared + clients
- **BUG-42** — Web IndexedDB store: no quota / `onversionchange` handling; abort errors drop detail _(hardening, not a launch blocker)_ · web
- **#106** — FU-1: tombstone GC for the sync log _(file once #95/#97/#98 land; caps the one unbounded table)_ · server
- **#107** — FU-2: offline read-state mutation queue _(only when robust offline use is a goal)_ · shared + clients

---

## How to use this file

- **Starting a session:** pick the top unblocked item in Tier 1. If it is the wrong size or wrong module for the session, skip it with a one-line note and take the next.
- **Adding new work:** bugs → [BUGS.md](BUGS.md); features/UX → [TICKETS.md](TICKETS.md); then add a line to the right tier here.
- **When done:** remove the line. No need to archive here — BUGS.md and TICKETS.md carry the done history.
- **P-levels in TICKETS.md / BUGS.md** describe severity, not order. This file overrides them.

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
