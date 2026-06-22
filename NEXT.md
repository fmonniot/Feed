# Feed — Next

> **Session order lives here.** P-levels in [TICKETS.md](TICKETS.md) and [BUGS.md](BUGS.md) describe severity/classification only — this file decides what to work on next.

**Last updated:** 2026-06-21

---

## Tier 1 — Blocking

*Fix before the app is usable day-to-day. Pick from the top.*

---

## Tier 2 — Degraded

*App works but something visible is wrong or a promised feature does nothing.*

**Article visibility**
- **BUG-22** — Article count mismatch: subscriptions shows 23 new, article list shows 2 · server + app

**Error UX**
- **BUG-23** — Android shows repetitive "couldn't be parsed" error messages _(will be resolved by #79 landing; low priority until then)_ · android

**Typography**
- **BUG-25** — Android renders no serif font; serif/sans split absent (downloadable Google Fonts not bundled) · android

---

## Tier 3 — Background

*Real bugs and work, not in the daily critical path.*

**Spec hygiene**
- **#80** — Re-verify FEATURES.md scenarios (status column dropped) and open follow-up tickets for real gaps · all

**Server edge cases** _(batch into one session)_

**Feed errors on Subscriptions** _(#79 umbrella; land #81–#83 before the UI in #84/#85)_
- **#86** — remove superseded #57 big mid-pane + #58 list banner; re-point inspector · web + android

**Visual polish**
- **BUG-27** — Copy & visual-label drift across Android + web (settings labels, "All" vs "All Articles", placeholders, Logout colour) · app + web
- **BUG-28** — Web sidebar feeds not nested under their folder headers _(re-shoot with categorised feeds to confirm)_ · web
- **BUG-26** — Android Server-URL editor uses Material components + full-pill button _(BUG-24 landed; URL control now on login screen)_ · app

**Fetch-cadence UI follow-ups** _(server + shared landed in PRs #44–#51; only the widget is missing)_
- **#77** — Per-feed fetch-interval control in the UI · clients
- **#78** — "Refresh this feed" per-feed overflow-menu action · clients

**Feature roadmap**
- **#63** — Server-side rate limiting · server
- **#4** — Categories UI + filtering · clients
- **#5** — Full-text search UI · clients
- **#7** — Stats / health dashboard · clients
- **#9** — Batch read operations · clients

**Infra hygiene**
- **#22** — Investigate 5 `#[ignore]`'d server db tests · server
- **#24** — Contract tests between client models and server JSON · shared + server
- **#47** — Android release signing · android
- **#20** — `data_extraction_rules.xml` TODO · android
- **#74** — Reconsider `/logs` endpoint for observability · server

---

## Deferred

_Pick up only when adjacent code is being touched or a specific pain point appears._

- **#14** — Migration framework: inline migration chain gets awkward past ~15 · server
- **#21** — Metro DI investigation · android
- **#64** — Out-of-band article link probe job · server
- **#36** — Feed-hue collision investigation · shared

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
