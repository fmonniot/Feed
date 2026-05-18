**Date:** 2026-05-17 14:58 PDT

# Promote design-reference into spec/, drop new-design/, file the two missing tickets

## Context

The user dropped a fresh design handoff at [spec/plans/design-reference/](design-reference/) (three markdown docs — `README.md`, `INTEGRATION.md`, `VISUAL_SPEC.md` — and a running React prototype under `prototype/`). It supersedes the older [spec/plans/new-design/](new-design/) folder that [spec/FEATURES.md](../FEATURES.md) still links to.

An audit of the new handoff against [spec/FEATURES.md](../FEATURES.md) found **no behavioural mismatches** — the design docs explicitly defer to FEATURES.md and don't re-render any of the dropped features (starring, theme picker, mobile filter chips, per-feed tag column, default-sort, web Server URL). What it found instead was four stale path references, an outdated comment in the Android theme, two ticket numbers cited by FEATURES.md that don't exist in [TODO.md](../../TODO.md), and a stale `new-design/` folder that needs to go.

The end-state the user wants:

- **`spec/FEATURES.md`** stays where it is — behavioural source of truth.
- **`spec/VISUAL_SPEC.md`** is promoted to live next to FEATURES.md (flat, no subfolder). It's durable: palette, typography, per-screen visual breakdowns survive the prototype's deletion.
- **`spec/prototype/`** holds the running React reference *and* `INTEGRATION.md`. INTEGRATION.md is fundamentally a porting guide ("what to take from the prototype", "what to ignore", "recommended build order", platform-specific gotchas calibrated against the prototype's source) — its lifetime equals the prototype's. When the prototype gets deleted, INTEGRATION.md goes with it.
- **`spec/plans/new-design/`** and **`spec/plans/design-reference/`** are both removed once their contents have moved.
- **TODO.md** gains tickets `#40` and `#41` so every status badge in FEATURES.md points at a real ticket.

## Target layout

```
spec/
  README.md            ← new, ~10 lines: pointers to FEATURES / VISUAL_SPEC / prototype
  FEATURES.md          ← unchanged location; links updated to siblings
  VISUAL_SPEC.md       ← moved from spec/plans/design-reference/
  prototype/           ← moved from spec/plans/design-reference/prototype/
    INTEGRATION.md     ← moved from spec/plans/design-reference/ into here
    index.html
    *.jsx
    prototypes/*.jsx
  plans/
    …historical plan files untouched…
    (design-reference/ removed)
    (new-design/        removed)
```

The historical plans under `spec/plans/` are intentionally left alone: they're dated snapshots and their references to `new-design/` are accurate as of the day they were written. Link rot in historical artefacts is acceptable; the live spec is what matters.

A couple of durable bits inside INTEGRATION.md (the "No-Material rule" called out from VISUAL_SPEC.md line 88, the platform-asymmetry of the Settings rows) are already covered elsewhere — VISUAL_SPEC.md keeps the no-Material framing as a one-line reminder, and FEATURES.md's Settings reference table is the contract for which rows live where. Nothing of long-term value is lost when INTEGRATION.md eventually goes.

## Steps

### 1 · Move the design docs and prototype into `spec/`

`git mv` so history follows:

- `spec/plans/design-reference/VISUAL_SPEC.md` → `spec/VISUAL_SPEC.md`
- `spec/plans/design-reference/prototype/` → `spec/prototype/` (whole subtree, preserve internal structure so the React + Babel SPA still resolves its `.jsx` paths)
- `spec/plans/design-reference/INTEGRATION.md` → `spec/prototype/INTEGRATION.md` (lives next to the thing it's a porting guide for)

The design-reference `README.md` (a 25-line "what's here" index for the handoff) is dropped in favour of a fresh, shorter `spec/README.md` (see step 4). The old README has no unique content worth preserving.

After the moves, `git rm -r spec/plans/design-reference/` to remove the now-empty wrapper.

### 2 · Fix the stale path references inside the moved docs

Both docs reference an `uploads/FEATURES.md` that doesn't exist. After the move the destinations differ — VISUAL_SPEC.md sits at `spec/`, INTEGRATION.md sits one level deeper inside `spec/prototype/` — so the relative paths are different:

- [spec/VISUAL_SPEC.md:7](../VISUAL_SPEC.md#L7) — `'uploads/FEATURES.md' (also in the project root)` → `[FEATURES.md](FEATURES.md)` (sibling).
- [spec/prototype/INTEGRATION.md:9](../prototype/INTEGRATION.md#L9) — `'uploads/FEATURES.md' (project root) — the functional contract.` → `[../FEATURES.md](../FEATURES.md)`.

While editing INTEGRATION.md, also tighten the "Read this alongside" block (lines 5–11) so the cross-references reflect the new locations: `VISUAL_SPEC.md` becomes `../VISUAL_SPEC.md`, `prototype/index.html (in this same handoff folder)` becomes `index.html` (sibling).

The rest of the `FEATURES.md` mentions in both docs (≈12 occurrences across the two files) are unqualified — those still read fine, but if any are inside a markdown link they need the same path correction. Sweep with `grep -n 'FEATURES.md\|VISUAL_SPEC.md\|INTEGRATION.md' spec/VISUAL_SPEC.md spec/prototype/INTEGRATION.md` and fix any that resolve to the wrong file.

### 3 · Update `spec/FEATURES.md` to point at the new visual material

- [spec/FEATURES.md:13](../FEATURES.md#L13) — `For visual fidelity, see [spec/plans/new-design/README.md](plans/new-design/README.md) and [spec/plans/new-design/design-files/index.html](plans/new-design/design-files/index.html)` → `For visual fidelity, see [VISUAL_SPEC.md](VISUAL_SPEC.md) and the running [prototype/index.html](prototype/index.html) (with [prototype/INTEGRATION.md](prototype/INTEGRATION.md) for porting notes)`.
- [spec/FEATURES.md:187](../FEATURES.md#L187) — `Visual fidelity questions go to [spec/plans/new-design/README.md](plans/new-design/README.md); behavior questions live here` → `Visual fidelity questions go to [VISUAL_SPEC.md](VISUAL_SPEC.md); behavior questions live here`.

[spec/FEATURES.md:3](../FEATURES.md#L3)'s reference to `plans/new-design-rollout-implementation-plan.md` stays as-is — the implementation-plan **file** still exists alongside the other historical plans.

### 4 · Add a small `spec/README.md`

The promotion leaves `spec/` with four siblings and the user will scan it cold one day. A 10-line `spec/README.md` removes the need to re-derive what's what:

```markdown
# Feed — spec

- FEATURES.md       — behavioural source of truth (scenario IDs FEED-3, READ-1c, …).
- VISUAL_SPEC.md    — visual reference, codebase-agnostic. Durable.
- prototype/        — running React+Babel reference + INTEGRATION.md porting guide.
                      Temporary; deleted once the real clients catch up.
- plans/            — dated, historical design notes. Snapshots, not living docs.

Behaviour wins over visuals when they disagree. See FEATURES.md.
```

### 5 · Update the lone source-code reference

[app/src/main/java/eu/monniot/feed/ui/theme/Color.kt:6](../../app/src/main/java/eu/monniot/feed/ui/theme/Color.kt#L6) currently reads:

```kotlin
// Paper palette — 11 design tokens (see .claude/plans/new-design/README.md)
```

The `.claude/plans/` path is also wrong (predates the move to `spec/`). Replace with:

```kotlin
// Paper palette — 12 design tokens (see spec/VISUAL_SPEC.md "Palette — Paper")
```

The count goes 11 → 12 because the new VISUAL_SPEC enumerates `bg`, `panel`, `border`, `borderStrong`, `ink`, `ink2`, `ink3`, `muted`, `accent`, `accentSoft`, `onAccent`, `danger` — twelve tokens, not eleven. Verify by counting the rows in the [Palette table in VISUAL_SPEC.md](VISUAL_SPEC.md#palette--paper); the comment's count should match whichever number the spec actually publishes.

The IDE artefact in `.idea/workspace.xml` (a `git-widget-placeholder` cached as "new-design") is ignored — it's a transient IDE cache, not a doc reference, and will fix itself the next time the user switches branches.

### 6 · Remove `spec/plans/new-design/`

After step 3 there are no live references to it; `git rm -r spec/plans/new-design/` is safe. Historical plan files in `spec/plans/` that reference the path are left as-is.

### 7 · File the two missing tickets in `TODO.md`

[FEATURES.md](../FEATURES.md) cites tickets `#40` (mark-read affordance on rows and in the reader, used by [FEED-8](../FEATURES.md#L122) and [READ-7](../FEATURES.md#L136)) and `#41` (mark-as-read-on-scroll, used by [SET-1 + the prefs row](../FEATURES.md#L81)). Neither exists in [TODO.md](../../TODO.md). Append both at the end of the "New-design rollout — bugs from manual verification" section, matching the format of `#25`–`#39`:

```markdown
### #40 — Mark-read affordance on article rows and in the reader `[ ]`

FEATURES.md FEED-8 and READ-7 both depend on a single read-toggle surface that
hits `PUT /v1/articles/{id}/read` with the inverted flag. The row-level button
sits next to the unread dot; the reader-level button lives in the reader's
action group (web: next to `↗ Open` / `⎙ Share`; Android: next to `⎙ Share`).
Both surfaces share the same source of truth, optimistically update the unread
dot and badge, and on the Unread route the row stays in place until the next
refresh.

**Acceptance criteria**
- Clicking/tapping the row-level affordance fires the PUT and decrements the
  Unread badge by one; the unread dot disappears.
- The reader-level button reflects the article's current read state (label
  "Mark unread" when read, "Mark read" when unread) and inverts on press.
- The Unread view does not optimistically drop the article; it stays put
  until the next list refresh.
- Tests cover both surfaces on both clients (web `:web:jsTest`, Android JVM
  test through `ServerRule`).

---

### #41 — "Mark as read on scroll" pref + behaviour `[ ]`

FEATURES.md SET-1 lists `markOnScroll` as a stored preference (default `on`).
The semantics: when on, an article row that stays visible for ≥1 s in the list
flips to read (same PUT as #40). Currently the preference is neither persisted
nor honoured by the list.

**Acceptance criteria**
- The Settings page exposes an on/off toggle bound to `UserPrefs.markOnScroll`.
- When on, an article row visible in the viewport for ≥1 s flips to read via
  the same endpoint #40 uses; the badge updates; the unread dot disappears.
- When off, scrolling has no effect on read state.
- A `:web:jsTest` and an Android JVM test cover both directions of the toggle.
```

The numbers slot in cleanly after `#39`; the "New-design rollout" section already explains the framing.

## Files touched

| File | Change |
|---|---|
| [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) | Move (`git mv`) + 1-line path fix at line 7 |
| [spec/prototype/INTEGRATION.md](../prototype/INTEGRATION.md) | Move into `spec/prototype/` + path fixes at line 9 + small tweak around lines 5–11 |
| [spec/prototype/](../prototype/) | Move whole subtree |
| [spec/README.md](../README.md) | New, ~10 lines |
| [spec/FEATURES.md](../FEATURES.md) | Two link rewrites (lines 13, 187) |
| [app/src/main/java/eu/monniot/feed/ui/theme/Color.kt](../../app/src/main/java/eu/monniot/feed/ui/theme/Color.kt) | One comment rewrite at line 6 |
| [TODO.md](../../TODO.md) | Append `#40`, `#41` |
| [spec/plans/new-design/](new-design/) | Delete |
| [spec/plans/design-reference/](design-reference/) | Delete after moves |

## Verification

This is a documentation + tiny-comment-edit change set. The verification surface is small:

1. **No live references to the deleted folders.**
   ```sh
   grep -rn "new-design/\|design-reference/" \
     --exclude-dir=.git --exclude-dir=build --exclude-dir=plans \
     --exclude=workspace.xml .
   ```
   Expected output: empty (the `--exclude-dir=plans` flag is deliberate; historical plans keep their snapshot-era references).

2. **No `uploads/FEATURES.md` leftovers.**
   ```sh
   grep -rn "uploads/FEATURES.md" .
   ```
   Expected: empty.

3. **Prototype still renders.** Open `spec/prototype/index.html` in a browser. The Paper desktop and Paper Android artboards must render exactly as they did under `spec/plans/design-reference/prototype/index.html`. Toggle the Tweaks panel through `density / view / font-size / sync states / auth-error` and confirm no broken JSX imports in the console. The new `spec/prototype/INTEGRATION.md` is a Markdown file in the same folder; it doesn't affect rendering, but it should be there next to `index.html`.

4. **Existing test suites still pass.** Only one production file changed (a comment in `Color.kt`); nothing should care, but per [CLAUDE.md](../../CLAUDE.md#testing-requirement) re-run the four targets:
   ```sh
   ( cd server && cargo test ) \
     && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest
   ```
   Expected: `97 passed; 0 failed; 6 ignored` on server, `16 passed` each on `shared` JS + wasmJs, `11 passed` on `:web:jsTest`, `50 passed` on `:app:testDebugUnitTest`.

5. **TODO.md ticket grep.** `grep -E "^### #4[01]" TODO.md` returns the two new entries; FEATURES.md mentions of `#40` and `#41` now resolve to real tickets.

## Non-goals

- Restructuring [spec/plans/](.) historical plan files (`new-design-rollout-*`, `rename-spec-plans-files.md`, etc.). They're dated artefacts and stay where they are.
- Re-litigating any feature decision. The audit found zero behavioural drift between design-reference and FEATURES.md; the spec is internally consistent and this plan does not change it.
- Implementing `#40` or `#41`. They're filed here; the implementation lands in a separate plan.
- Deleting the prototype. The user has flagged it as temporary; it lives in `spec/prototype/` for as long as the real clients lag behind it, and gets removed in a future cleanup pass.
