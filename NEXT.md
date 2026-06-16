# Feed — Next

> **Session order lives here.** P-levels in [TICKETS.md](TICKETS.md) and [BUGS.md](BUGS.md) describe severity/classification only — this file decides what to work on next.

**Last updated:** 2026-06-15

---

## Now — pick from the top

1. **BUG-1** — XSS bypass in web HTML sanitizer (`javascript:` allowlist bypass) · web · _security_
2. **BUG-3** — `getParseError` 404 handling dead code → stale parse-error shown for wrong feed · shared
3. **BUG-2** — `fetch_interval_minutes` never honored for healthy feeds · server
4. **BUG-5** — `Feed.title` non-nullable vs server nullable → feed list can permanently fail · shared
5. **BUG-4 + BUG-6 + BUG-9 + BUG-10** — four small server fixes (one session) · server
6. **BUG-7** — Android: network error at startup forces login; session not persisted · android
7. **BUG-8 or #65** — Android filter chips "Today" / "Long reads" never match · android · _decide: fix (BUG-8) or remove (#65)_
8. **BUG-13** — First-run pane shows for users who already have feeds · shared + clients

## Soon

- **BUG-11** — Web: `hashchange` listener leak on FeedScreen mount · web
- **BUG-14** — Android cookie storage drops `Max-Age`; blocking I/O in init · android
- **BUG-15** — OPML import: dropped children, wrong "already exists", N² scans · server
- **BUG-16** — `ServerConfigScreen` shows "Saved" before any save · android
- **BUG-17** — `getRelativeTime` grammar and future timestamps · shared
- **BUG-12** — Refresh interval and Keep articles prefs are decorative · all · _product decision needed_
- **BUG-18** — Android login screen flashes on every launch · android · _pairs with BUG-7_
- **BUG-19** — Android Settings → Import OPML → Choose does nothing · android
- **#75** — Screenshot tooling + design-accuracy audit · _prerequisite for visual polish group (#43, #44, #65–#73)_
- **#37** — Wire "Keep articles" retention to server setting · server + clients
- **#38** — Refresh interval: client-side auto-poll timer · clients
- **#22** — Investigate the 5 `#[ignore]`'d server db tests · server

## Backlog (not scheduled)

See [TICKETS.md](TICKETS.md) P2–P4 and [BUGS.md](BUGS.md) for full details. Key items:

| ID | Title | Where |
|----|-------|--------|
| #43 | Android: scroll indicator on article list | android |
| #44 | Android: article entry padding + unread dot | android |
| #65 | Android: remove filter chips (see also BUG-8) | android |
| #66 | Android: pull-to-refresh on inbox-zero screen | android |
| #67 | Android: reduce top bar and nav bar padding | android |
| #68 | Android: remove screen transitions | android |
| #69 | Android: move "Add feed" to app bar | android |
| #70 | Web: article list items too narrow | web |
| #71 | Web: article reader padding too large | web |
| #72 | Web: identity box in Settings / Subscriptions | web |
| #73 | Login page redesign (web + Android) | clients |
| #63 | Server-side rate limiting | server |
| #4 | Categories UI + filtering | clients |
| #5 | Full-text search UI | clients |
| #7 | Stats / health dashboard | clients |
| #9 | Batch read operations | clients |
| #24 | Contract tests: client models vs server JSON | shared + server |
| #47 | Android release signing | android |
| #8 | OPML import UI — needs verification | clients |

## Deferred

See [TICKETS.md](TICKETS.md) P4: #14 (migration framework), #21 (Metro DI), #64 (link probe job), #36 (feed-hue collisions).

---

## Decisions needed

These are not implementation tasks — they are calls you need to make before the related work can be scheduled.

- **Daily-use baseline:** Decide which features the app needs before it is usable day-to-day, and which are nice-to-have (e.g. are retention settings and refresh interval required, or can they wait?). The answer reshapes the _Soon_ ordering — BUG-12 / #37 / #38 move up or down depending on this call.
- **Visual polish gate (#75):** Decide whether to require the screenshot-access + design-audit (#75) before scheduling any visual polish work (#43, #44, #65–#73). Currently treated as a soft prerequisite in TICKETS.md.

---

## How to use this file

- **Starting a session:** pick the top unblocked item in _Now_. If it is the wrong size or wrong module for the session, skip it with a one-line note and take the next.
- **Adding new work:** bugs → [BUGS.md](BUGS.md); features/UX → [TICKETS.md](TICKETS.md); then add a line to the right section here.
- **When done:** remove the line from _Now_ / _Soon_. No need to archive here — BUGS.md and TICKETS.md carry the done history.
- **P-levels in TICKETS.md / BUGS.md** describe severity, not order. This file overrides them.
