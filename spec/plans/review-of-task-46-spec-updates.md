**Date:** 2026-05-24 20:18 PDT

# Review of task #46 spec updates — verdict + required edits

## Context

The branch `style-guide-task-46` carries Claude Design's first pass at the
non-happy-path audit called for by [TODO.md #46](../../TODO.md#L176-L184). The
staged diff touches two files:

- [spec/FEATURES.md](../FEATURES.md) — adds rows ERR-4…ERR-15 (with a numbering
  gap at ERR-12), inserts a header pointer to the new VISUAL_SPEC section, and
  rewrites ERR-1 / ERR-2 / ERR-3 to reference the new visual surfaces.
- [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) — replaces the four-line "State
  surfaces (happy-path callouts)" section with a full "States & feedback"
  chapter (~200 lines) covering tones, banner, big mid-pane state, inline form
  error, modal interrupt, raw-response inspector, inline reader note, sidebar
  badge, sidebar-footer states, list-level empty state, loading, snackbars, the
  catalogue table, and the rationale for retiring the old Tweaks panel.

The user has asked for a review against the broader repo context, with any
suggested copy changes spelled out verbatim so they can be reviewed before being
applied.

## Verdict

**Land-worthy with a small set of fixes.** The new content is internally strong:

- Every token it leans on (`panel`, `accent`, `accentSoft`, `ink`/`ink2`/`ink3`,
  `border`, `borderStrong`, `danger`) already exists in the palette and is
  reused, not reinvented. The new `warn-*` / `err-*` CSS custom properties are
  scoped to feedback surfaces and tied back to the existing `danger` hue family.
- Every cross-reference (AUTH-2, AUTH-5, SUBS-2, SET-5, #33, #34) resolves to
  the right row in FEATURES.md.
- The "Web · Login" auth-error component the new section cites as a `compact`
  variant of the inline form error is in fact spec'd at
  [VISUAL_SPEC.md:433](../VISUAL_SPEC.md#L433).
- Every scenario in the catalogue table maps to a real artboard in
  [spec/story-board/index.html](../story-board/index.html) — banners, big
  mid-pane states, the modal, sidebar badges, the raw-response inspector, and
  the dead-feed treatment all have implementations in
  `spec/story-board/prototypes/edge-cases.jsx` and `edge-cases-mobile.jsx`.

Five small fixes follow, all text-only.

## Required edits

### Edit 1 — Renumber to close the ERR-12 gap

Per your decision, shift ERR-13 → ERR-12, ERR-14 → ERR-13, ERR-15 → ERR-14, and
ERR-16 *(reserved)* → ERR-15 *(reserved)*. Three places need touching.

**In [spec/FEATURES.md](../FEATURES.md), at the ERR-3 row** (currently line 181)
— update the trailing parenthetical:

- **Find:** `See AUTH-5; the visual treatment is the session-expired modal (ERR-15).`
- **Replace with:** `See AUTH-5; the visual treatment is the session-expired modal (ERR-14).`

**In [spec/FEATURES.md](../FEATURES.md), the ERR-13 → ERR-16 rows** —
re-number the leading ID column only; row bodies are unchanged:

| Old ID | New ID |
|---|---|
| `ERR-13` (bad URL) | `ERR-12` |
| `ERR-14` (duplicate URL) | `ERR-13` |
| `ERR-15` (session expired) | `ERR-14` |
| `ERR-16 *(reserved)*` | `ERR-15 *(reserved)*` |

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md), the catalogue table** under
"States & feedback" — three rows reference the renamed IDs:

- **Find:** `| Add feed · not a feed | Inline form error | error | ERR-13 |`
  **Replace with:** `| Add feed · not a feed | Inline form error | error | ERR-12 |`
- **Find:** `| Add feed · duplicate | Inline form error | warn | ERR-14 |`
  **Replace with:** `| Add feed · duplicate | Inline form error | warn | ERR-13 |`
- **Find:** `| Session expired | Modal | warn | ERR-15 |`
  **Replace with:** `| Session expired | Modal | warn | ERR-14 |`

### Edit 2 — Snackbars are now rendered in the story board

The new snackbar subsection claims they aren't in the prototype, but
`EdgeMSnackbar()` is defined at
[spec/story-board/prototypes/edge-cases-mobile.jsx:81](../story-board/prototypes/edge-cases-mobile.jsx#L81)
and is used in the offline / rate-limit / parse-fail / add-error mobile
artboards. Update the lead sentence:

- **Find:** `Web uses banners; Android uses snackbars where ephemeral confirmation is needed (failed pull-to-refresh, OPML-import summary, "marked 12 articles read"). Snackbars are **not yet rendered in the prototype** — they are spec'd here as the Android counterpart to the web banner:`
- **Replace with:** `Web uses banners; Android uses snackbars where ephemeral confirmation is needed (failed pull-to-refresh, OPML-import summary, "marked 12 articles read"). The story board's mobile edge-case artboards render them; this section codifies the rules behind those artboards:`

### Edit 3 — Inline the No-Material rationale and drop the broken INTEGRATION.md links

`prototype/INTEGRATION.md` has never existed in this repo. Three references to
it sit in VISUAL_SPEC.md (lines 88, 258, plus the new line 741), one in
[spec/README.md](../README.md), and one in [spec/FEATURES.md](../FEATURES.md).
The introductory mention at VISUAL_SPEC.md line 3 also points at the file.
Strip every reference; inline the load-bearing rationale at the two sites that
actually relied on it.

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) line 3** — drop the dead pointer
and tighten the intro:

- **Find:** `This document is the **visual reference** for Feed, a self-hosted RSS reader with a web client and an Android client. It describes how the design looks and why it looks that way, independently of any codebase. For integration concerns — file structure, navigation model, state shape, what to copy and what to ignore — see [prototype/INTEGRATION.md](prototype/INTEGRATION.md) (porting notes that live alongside the prototype itself).`
- **Replace with:** `This document is the **visual reference** for Feed, a self-hosted RSS reader with a web client and an Android client. It describes how the design looks and why it looks that way, independently of any codebase.`

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) line 88** — fold the No-Material
rationale into the paragraph so it stops pointing at a missing file:

- **Find:** `Wire these into a Compose `ColorScheme` (Material 3) by mapping `bg → background`, `panel → surface / surfaceVariant`, `ink → onBackground / onSurface`, `accent → primary`, `onAccent → onPrimary`, `danger → error`. The mapping is approximate; the design is not Material — see the **No-Material rule** in [prototype/INTEGRATION.md](prototype/INTEGRATION.md).`
- **Replace with:** `Wire these into a Compose `ColorScheme` (Material 3) by mapping `bg → background`, `panel → surface / surfaceVariant`, `ink → onBackground / onSurface`, `accent → primary`, `onAccent → onPrimary`, `danger → error`. The mapping is approximate. **No-Material rule:** map the palette into Material's `ColorScheme` so existing Compose components (text fields, sliders, dialogs) still pick up the right colours, but do not adopt Material 3 components, elevation, ripple, or motion. The aesthetic is editorial — paper, hairlines, no shadows, no tonal surfaces. The single exception is the snackbar (see §Toasts / snackbars below); everything else is built directly from the primitives in this doc.`

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) line 258** — drop the dangling
pointer; the surrounding paragraph already says "memoize," which is the only
guidance that was actually load-bearing:

- **Find:** `Memoize. See [prototype/INTEGRATION.md](prototype/INTEGRATION.md) for an OKLCH-to-ARGB conversion snippet.`
- **Replace with:** `Memoize: compute each feed's colours once when its hue is first seen, then cache by hue. Use any OKLCH-to-ARGB routine that round-trips the Display-P3 → sRGB clamp the same way the browser does.`

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) in the new snackbar section** —
the No-Material rationale is now inline above; cross-reference it instead of
linking to the missing file:

- **Find:** `This is the only Material-flavoured pattern in the mobile design — see the **No-Material rule** in [prototype/INTEGRATION.md](prototype/INTEGRATION.md) for the carve-out reasoning.`
- **Replace with:** `This is the only Material-flavoured pattern in the mobile design — see the **No-Material rule** under §Palette for the carve-out reasoning.`

**In [spec/README.md](../README.md) line 5** — drop the INTEGRATION.md half of
the line; keep the prototype-deprecation note:

- **Find:** `- [prototype/](prototype/) — running React + Babel reference + [INTEGRATION.md](prototype/INTEGRATION.md) porting guide. Temporary; deleted once the real clients catch up.`
- **Replace with:** `- [story-board/](story-board/) — running React + Babel visual reference. Kept as a stable visual contract; not a runtime artifact.`

**In [spec/FEATURES.md](../FEATURES.md) line 13** — drop the broken pointer
and update the path:

- **Find:** `For visual fidelity, see [VISUAL_SPEC.md](VISUAL_SPEC.md) and the running prototype at [prototype/index.html](prototype/index.html) (with [prototype/INTEGRATION.md](prototype/INTEGRATION.md) for porting notes) — except where this spec calls for the removal of an element, in which case this spec takes precedence over the design.`
- **Replace with:** `For visual fidelity, see [VISUAL_SPEC.md](VISUAL_SPEC.md) and the story board at [story-board/index.html](story-board/index.html) — except where this spec calls for the removal of an element, in which case this spec takes precedence over the design.`

### Edit 4 — `prototype/` → `story-board/` path sweep

The directory was renamed to `spec/story-board/` in commit `84db794`; the spec
documents still use the old path everywhere. From `spec/VISUAL_SPEC.md` and
`spec/FEATURES.md` the correct relative path is `story-board/...`. The
remaining hits after Edit 3 are listed below.

**In [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md):**

- **Line 5 — Find:** `The running prototype at [prototype/index.html](prototype/index.html) is the **canonical visual source of truth**. Read this doc, but keep the prototype open: serve `index.html` and click into the **Paper** desktop artboard and the **Paper** Android artboard. Where this spec gives a pixel value, that value is what produces the look in the prototype; where it gives reasoning, that reasoning is meant to help you extrapolate into screens or states this doc doesn't cover.`
  **Replace with:** `The story board at [story-board/index.html](story-board/index.html) is the **canonical visual source of truth**. Read this doc, but keep the story board open: serve `index.html` and click into the **Paper** desktop artboard and the **Paper** Android artboard. Where this spec gives a pixel value, that value is what produces the look in the story board; where it gives reasoning, that reasoning is meant to help you extrapolate into screens or states this doc doesn't cover.`

- **Line 7 — Find:** `The behavioural contract lives in [FEATURES.md](FEATURES.md) (sibling to this file). When this spec and FEATURES.md disagree — for example, if this spec describes a UI element for a feature FEATURES.md has dropped — **FEATURES.md wins**. This visual spec describes only what the prototype actually renders today, against the current FEATURES.md.`
  **Replace with:** `The behavioural contract lives in [FEATURES.md](FEATURES.md) (sibling to this file). When this spec and FEATURES.md disagree — for example, if this spec describes a UI element for a feature FEATURES.md has dropped — **FEATURES.md wins**. This visual spec describes only what the story board actually renders today, against the current FEATURES.md.`

- **Line 94 — Find:** `Two families. Both load from Google Fonts in the prototype; **bundle them into the production app** rather than relying on Google.`
  **Replace with:** `Two families. Both load from Google Fonts in the story board; **bundle them into the production app** rather than relying on Google.`

- **Line 142 — Find:** `There is **no strict modular scale.** Values are picked from `{2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 52, 60, 80}` (px on web, dp on Android) as the design called for. When implementing in a system with a token scale (4dp or 8dp base), round to the nearest scale step — except inside the reader, where the prototype's exact values must be preserved because they were tuned against the body line-height.`
  **Replace with:** `There is **no strict modular scale.** Values are picked from `{2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 52, 60, 80}` (px on web, dp on Android) as the design called for. When implementing in a system with a token scale (4dp or 8dp base), round to the nearest scale step — except inside the reader, where the story board's exact values must be preserved because they were tuned against the body line-height.`

- **Line 194 — Find:** `The prototype uses **unicode glyphs as placeholders.** These need to be replaced with a real icon set in production (Lucide, Heroicons, Phosphor, Material Symbols Outlined — choice is the developer's). Match weight to **1.5px outlined**; don't use filled icons. The unread dot stays as-is; it is not an icon.`
  **Replace with:** `The story board uses **unicode glyphs as placeholders.** These need to be replaced with a real icon set in production (Lucide, Heroicons, Phosphor, Material Symbols Outlined — choice is the developer's). Match weight to **1.5px outlined**; don't use filled icons. The unread dot stays as-is; it is not an icon.`

- **Line 230 — Find:** `Hue assignments used by the prototype seed data (`data.jsx`):`
  **Replace with:** `Hue assignments used by the story board seed data (`data.jsx`):`

- **Line 308 — Find:** `For each screen, the spec describes structure top-to-bottom and gives the values that produce the prototype's look. **When in doubt, open `prototype/index.html` and inspect.**`
  **Replace with:** `For each screen, the spec describes structure top-to-bottom and gives the values that produce the story board's look. **When in doubt, open `story-board/index.html` and inspect.**`

- **Line 485 — Find:** `The mobile Feeds screen has no `+ Add feed` button in the prototype; the search bar copy implies "or paste a URL" as the affordance. Production may add a FAB or header `+` button if needed — that's an implementation decision, not a spec change.`
  **Replace with:** `The mobile Feeds screen has no `+ Add feed` button in the story board; the search bar copy implies "or paste a URL" as the affordance. Production may add a FAB or header `+` button if needed — that's an implementation decision, not a spec change.`

- **Line 566 — Find:** `- The mobile prototype reads `web fontSize - 1` (clamped at 15) so a single shared "18" in tweaks renders as 18px on web and 17sp on Android. In production, use the platform's stored sp value directly rather than re-deriving from a web value.`
  **Replace with:** `- The mobile story board reads `web fontSize - 1` (clamped at 15) so a single shared "18" renders as 18px on web and 17sp on Android. In production, use the platform's stored sp value directly rather than re-deriving from a web value.`

- **Line 585 (new content) — Find:** `The prototype renders one artboard per common scenario under the **Edge cases · …** sections of the design canvas. Open `prototype/index.html` and zoom each card to see the canonical pixel values; this section gives you the rules behind them. Each scenario also maps to an `ERR-*` row in [FEATURES.md](FEATURES.md).`
  **Replace with:** `The story board renders one artboard per common scenario under the **Edge cases · …** sections of the design canvas. Open `story-board/index.html` and zoom each card to see the canonical pixel values; this section gives you the rules behind them. Each scenario also maps to an `ERR-*` row in [FEATURES.md](FEATURES.md).`

- **Line 745 (new content, catalogue lead) — Find:** `The prototype's design canvas carries one artboard per scenario under the **App states** and **Edge cases** sections. Current set:`
  **Replace with:** `The story board's design canvas carries one artboard per scenario under the **App states** and **Edge cases** sections. Current set:`

- **Line 766 (new content, catalogue tail) — Find:** `Add a new row whenever a new scenario ships in the prototype, and back-fill the FEATURES.md column once it has an `ERR-*` ID.`
  **Replace with:** `Add a new row whenever a new scenario ships in the story board, and back-fill the FEATURES.md column once it has an `ERR-*` ID.`

- **Line 770 (new content, tweaks-retirement section) — Find:** `The prototype previously exposed a floating **Tweaks** panel that mirrored the in-product Settings page — density, font size, refresh cadence, retention, plus a `state` selector for walking through `empty` / `sync-failed` / `auth-error` / `loading`. That entire surface has been retired.`
  **Replace with:** `The story board previously exposed a floating **Tweaks** panel that mirrored the in-product Settings page — density, font size, refresh cadence, retention, plus a `state` selector for walking through `empty` / `sync-failed` / `auth-error` / `loading`. That entire surface has been retired.`

- **Line 772 — Find:** `- User-facing **preferences** (density, font size, refresh, retention) live in exactly one place: the in-product **Settings page** on each device. Editing them there updates the live prototype in place.`
  **Replace with:** `- User-facing **preferences** (density, font size, refresh, retention) live in exactly one place: the in-product **Settings page** on each device. Editing them there updates the live story board in place.`

- **Line 821 — Find:** `The prototype at `prototype/index.html` (and its source files in `prototype/prototypes/`) is the visual source of truth. If a value in this spec disagrees with the prototype, the prototype wins. If a value is missing from both, default to the **quietest, most paper-like** interpretation and flag for design review. If the spec describes a UI element FEATURES.md drops (or vice versa), FEATURES.md wins — delete the visual element.`
  **Replace with:** `The story board at `story-board/index.html` (and its source files in `story-board/prototypes/`) is the visual source of truth. If a value in this spec disagrees with the story board, the story board wins. If a value is missing from both, default to the **quietest, most paper-like** interpretation and flag for design review. If the spec describes a UI element FEATURES.md drops (or vice versa), FEATURES.md wins — delete the visual element.`

(The Edit 3 changes already covered the rest of the `prototype/` references in
FEATURES.md and README.md.)

### Edit 5 — `ERR-3` row Status flag

Currently the new ERR-3 row reads `✗ #34 #41`, which suggests #41 also tracks
the auth case. #41 is the omnibus implementation ticket for the new edge-case
visuals, but ERR-3's failure mode (stale cookie) is purely an AUTH-5 problem
already represented by #34. Drop the duplicate so the status column stays
truthful about ownership:

- **Find:** `| ERR-3 | both | Stale cookie (server restarted with new secret) | Trigger any API call | See AUTH-5; the visual treatment is the session-expired modal (ERR-14). | ✗ #34 #41 |`
- **Replace with:** `| ERR-3 | both | Stale cookie (server restarted with new secret) | Trigger any API call | See AUTH-5; the visual treatment is the session-expired modal (ERR-14). | ✗ #34 |`

(This depends on Edit 1 already renumbering the modal reference from ERR-15 to
ERR-14.)

## Critical files to modify

- [spec/FEATURES.md](../FEATURES.md) — Edits 1, 3, 5.
- [spec/VISUAL_SPEC.md](../VISUAL_SPEC.md) — Edits 1, 2, 3, 4.
- [spec/README.md](../README.md) — Edit 3 only.

## Out of scope

The terminology question — whether "the prototype" should also be renamed to
"the story board" in *every* prose sentence (Edit 4 already catches the
load-bearing cases; this would be a deeper editorial pass) — is left as a
follow-up. Same for the broader question of whether the story-board directory
should keep its inner `prototypes/` folder name or also be renamed.

## Verification

This is a spec-only change with no code or runtime impact. After applying the
edits:

1. **`grep -n "prototype" spec/VISUAL_SPEC.md spec/FEATURES.md spec/README.md`** —
   confirm zero remaining hits *outside* the `story-board/prototypes/...`
   pathnames (which are an internal directory inside the story board).
2. **`grep -n "INTEGRATION.md" spec/`** — should return zero hits.
3. **`grep -n "ERR-1[2-5]" spec/FEATURES.md spec/VISUAL_SPEC.md`** — every
   ID resolves to a row of the same number; the only `*(reserved)*` row is
   ERR-15; ERR-3 points at ERR-14, not ERR-15.
4. **Open [spec/story-board/index.html](../story-board/index.html)** in a
   browser and walk the four **Edge cases · …** sections; every artboard maps
   to a catalogue-table row in VISUAL_SPEC.md.
