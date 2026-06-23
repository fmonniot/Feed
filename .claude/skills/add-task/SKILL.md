---
name: add-task
model: haiku
description: >
  Add a new bug or feature ticket to the backlog. Determines the next available ID,
  writes a full entry to BUGS.md or TICKETS.md, and places it in the right tier and
  cluster in NEXT.md. Use when the user says "add a task", "file a bug", "new ticket",
  "add to backlog", "create a bug for", "track this as a ticket", or describes a new
  piece of work they want recorded.
---

# Add Task

Given a description of new work (bug report or feature request), assign the next
available ID, write a properly formatted entry in the right backlog file, and add
the item to NEXT.md in the appropriate tier and cluster.

## Input

The user provides a natural-language description of the task. It may include:
- What the problem or feature is
- Which module(s) are involved
- Severity / priority hints
- Any file paths or root-cause analysis they already have

If the description is ambiguous, make reasonable choices and note them — don't block
on clarification.

---

## Step 1 — Classify: bug or ticket

Read the user's description and decide:

- **Bug** → goes in `BUGS.md`. Bugs are defects in existing behavior: something is
  broken, wrong, produces incorrect results, has a regression, or behaves contrary
  to the spec.
- **Ticket** → goes in `TICKETS.md`. Tickets are new features, enhancements,
  improvements, infrastructure work, investigations, or spec gaps.

If the user explicitly says "bug" or "ticket", respect that. Otherwise infer from
the description.

---

## Step 2 — Assign the next ID

### For bugs

Scan `BUGS.md` for the highest existing `BUG-N` number:

```bash
grep -oP 'BUG-\d+' BUGS.md | sed 's/BUG-//' | sort -n | tail -1
```

The new ID is `BUG-<max + 1>`.

### For tickets

Scan `TICKETS.md` for the highest existing `#N` number (ticket IDs, not PR refs):

```bash
grep -oP '(?<=^#### #|^### #)\d+' TICKETS.md | sort -n | tail -1
```

The new ID is `#<max + 1>`.

---

## Step 3 — Determine the severity / classification

### Bugs (BUGS.md)

Pick the severity section based on the description:

| Section | When |
|---|---|
| `## P1 — Security / broken core behavior` | Security vulnerabilities, data loss, core feature completely broken |
| `## P2 — Wrong results / data integrity / major UX` | Wrong data shown, significant UX failures, data integrity issues |
| `## P3 — Robustness / leaks / polish` | Edge cases, resource leaks, visual polish, minor robustness issues |

Default to **P3** when uncertain.

### Tickets (TICKETS.md)

Pick the classification section:

| Section | When |
|---|---|
| `## P0 — Unblockers` | Blocking all progress |
| `## P1 — Spec gap fixes` | Closes a gap against the spec (FEATURES.md / VISUAL_SPEC.md) |
| `## P2 — Feature roadmap` | New user-facing feature, server endpoint exists but client is missing |
| `## P3 — Infra hygiene` | Build, CI, tooling, code quality, non-user-facing |
| `## P4 — Deferred investigations` | Low priority, pick up only when adjacent code is touched |

Default to **P3** when uncertain.

---

## Step 4 — Determine module(s)

Identify which module(s) the work touches from:
`server` · `shared` · `android` · `web` · `clients` · `all` · `tooling`

Use the module ownership table from CLAUDE.md:

| What you're changing | Module |
|---|---|
| REST API, DB schema, feed fetching | `server` |
| Data models, Ktor networking, FeedApi/AuthApi | `shared` |
| FeedRepository interface, FeedViewModel, RelativeTime | `shared` |
| Android Room DB, Android FeedRepository impl | `app` (use `android` in NEXT.md) |
| Android Compose UI | `app` (use `android` in NEXT.md) |
| Web UI (DOM screens, router) | `web` |
| Both clients | `clients` |
| Everything | `all` |

If multiple modules, join with ` + ` (e.g. `server + shared`).

---

## Step 5 — Write the backlog entry

### Bug entry format (BUGS.md)

Append the new bug entry at the end of the appropriate severity section (before the
`---` separator). Use this template:

```markdown
### BUG-{N}: {Title — short, specific}

- **Status:** OPEN
- **Module:** `{module}/`
- **Files:** {file paths with line numbers if known, or "TBD" if not}
- **Symptom:** {What the user sees or what goes wrong}
- **Root cause:** {Known root cause, or "TBD — investigate {hint}" if unknown}
- **Fix direction:** {Concrete steps to fix, or "TBD" if investigation is needed first}
- **Validation:** {Which test suite to extend, expected test command}
```

### Ticket entry format (TICKETS.md)

Append the new ticket entry at the end of the appropriate classification section.
Use this template:

```markdown
### #{N} — {Title — short, specific} `[ ]`

{1-2 sentence description of what needs to happen and why.}

**Acceptance criteria**
- {Concrete, testable criterion 1}
- {Concrete, testable criterion 2}
- {Test coverage expectation}
```

If the ticket belongs to an existing group (e.g. "Group: Android visual polish"),
use `####` instead of `###` for the heading and place it inside that group.

---

## Step 6 — Add to NEXT.md

Read `NEXT.md` and determine the best placement:

### Choose the tier

| Tier | When |
|---|---|
| `## Tier 1 — Blocking` | Fix before the app is usable day-to-day |
| `## Tier 2 — Degraded` | App works but something visible is wrong |
| `## Tier 3 — Background` | Real work, not in the daily critical path |
| `## Deferred` | Pick up only when adjacent code is touched |

Map from severity/classification:
- Bug P1 → Tier 1 or Tier 2
- Bug P2 → Tier 2
- Bug P3 → Tier 3 or Deferred
- Ticket P0 → Tier 1
- Ticket P1 → Tier 2 or Tier 3
- Ticket P2/P3 → Tier 3
- Ticket P4 → Deferred

### Choose or create a cluster

Look at existing clusters in the target tier. If one fits thematically, add the
item there. If none fits, create a new cluster with a **bold** name on its own line.

### Write the NEXT.md line

Add a bullet under the chosen cluster using the exact format:

```
- **{ID}** — {short description} · {module(s)}
```

Where:
- `{ID}` is `BUG-N` or `#N`, bold
- `{short description}` is a brief phrase (symptom for bugs, title for tickets), no trailing period
- `{module(s)}` is one or more of the module tags

Optionally add a trailing italic note in parentheses if relevant:
```
- **{ID}** — {short description} _(optional note)_ · {module(s)}
```

### Update the "Last updated" date

Update the `**Last updated:**` line near the top of NEXT.md to today's date
(YYYY-MM-DD format).

---

## Step 7 — Report

After writing both files, report:

1. The assigned ID (e.g. `BUG-29` or `#87`)
2. Whether it was classified as a bug or ticket
3. The severity/classification chosen (e.g. P2, P3)
4. The tier and cluster in NEXT.md
5. A one-line summary of the entry

If any fields were left as "TBD", mention that so the user knows investigation is
still needed.

---

## Notes

- **Never overwrite existing entries.** Always append.
- **IDs are monotonically increasing.** Never reuse a closed/superseded ID.
- **NEXT.md is the session order; BUGS.md/TICKETS.md carry the detail.** The NEXT.md
  line is a summary pointer — keep it short.
- **When in doubt, default to lower severity / later tier.** It's easier to promote
  than to demote.
- If the user provides multiple tasks in one request, process each one separately
  with its own ID and entry.
