# Feed — Next

> **Session order lives here.** P-levels in [TICKETS.md](TICKETS.md) and [BUGS.md](BUGS.md) describe severity/classification only — this file decides what to work on next.

**Last updated:** 2026-06-15 20:13 PDT

---

## Tier 1 — Blocking

*Fix before the app is usable day-to-day. Pick from the top.*

---

## Tier 2 — Degraded

*App works but something visible is wrong or a promised feature does nothing.*

---

## Tier 3 — Background

*Real bugs and work, not in the daily critical path.*

**Server edge cases** _(batch into one session)_
- **BUG-4** — `/v1/logs` returns wrong/old lines after log rotation · server
- **BUG-6** — Retention silently deletes unread articles; never deletes undated ones · server
- **BUG-9** — `ParseFailed` doesn't reset the consecutive-410 counter · server

**Auth & session**
- **BUG-14** — Android cookie storage drops `Max-Age`; blocking I/O on init · shared

**Web internals**
- **BUG-11** — `hashchange` listener leak on every FeedScreen mount · web

**Refresh & retention**
- **BUG-12** — Refresh interval + Keep articles are decorative; behavior not wired · all
- **#38** — Client-side auto-poll timer for refresh interval · clients
- **#37** — Wire "Keep articles" retention to server setting · server + clients

**Visual polish** _(gate RESOLVED 2026-06-18: #75 Part 1 — screenshot tooling — is a hard prerequisite; do it first. Rationale: #67/#70/#71/#72 were already built straight from VISUAL_SPEC.md and still drifted off-spec — the gap is the missing verification loop, not target precision. Screenshot-vs-reference comparison is now the definition-of-done for every item below. Skip the heavyweight "rewrite all acceptance criteria" reading of #75 Part 2; run it as a lightweight current-vs-reference sweep instead.)_
- **#75** — Screenshot tooling (Part 1, gates the rest) + lightweight design-accuracy sweep · tooling — **Part 2 sweep DONE 2026-06-18** ([spec/plans/ticket-75-design-accuracy-sweep.md](spec/plans/ticket-75-design-accuracy-sweep.md)): **#67 confirmed real drift** with sharpened acceptance criteria — ready to fix.
- **#43** — Android: scroll indicator on article list · android
- **BUG-20** — Android: article list briefly flashes "no articles" on cold start _(same structural fix as BUG-13; pairs with BUG-18 follow-up)_ · android + shared
- **BUG-21** — Code blocks not rendering nicely in reader _(article: "Mixed-Reality Tour Guide…"; web confirmed, Android TBD)_ · web + app
- **#73** — Login page redesign — **web done `[~]`**; Android login still TODO · clients

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
