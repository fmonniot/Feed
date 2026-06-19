# #75 Part 2 — design-accuracy comparison sweep

**Date:** 2026-06-18 20:25 PDT

Evidence-backed re-verification of #70, #71, #72 (web) and #67 (Android) against
the captured reference artboards (`build/.shots/ref/`) and `spec/VISUAL_SPEC.md`,
using the #75 screenshot tooling. This is the audit deliverable — concrete
current-vs-reference deltas, not the fixes. Each ticket's acceptance criteria in
[TICKETS.md](../../TICKETS.md) was sharpened from this.

## Method

- Web shots: `build/.shots/web/{desktop,narrow}/<scenario>.png` (desktop = 1280×900,
  narrow = 900×900).
- Reference: `build/.shots/ref/desktop-editorial.png` (1180×760),
  `ref/android-editorial.png` (412×892 CSS px).
- Android shot: `build/.shots/android/unread.png` (1080×2520, density 3.0 → 360×840 dp;
  also consistent with ~411 dp at 2.625× — see note in #67).
- Column/border positions measured by detecting faint full-height vertical
  separators (the `border` token, alpha 0.08); text-block extents by scanning for
  the leftmost/rightmost dark pixels in the body band.

## #70 — Web: article list "too narrow" → **matches reference, close**

| Boundary | Live (desktop 1280) | Reference (1180) | Spec |
|---|---|---|---|
| Sidebar right border | x = 219 | x = 220 | 220 px |
| List right border | x = 599 | x = 601 | 220 + 380 = 600 px |
| **Article-list width** | **380 px** | **381 px** | **380 px** |

The list renders at exactly the spec width and matches the reference artboard to
within 1–2 px. [FeedScreen.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt#L79-L91)
fixes it at `width: 380px`. The ticket's "wider would use space better" is a
preference that contradicts the design — the spec deliberately pins the list at
380 px so the reader stays the protagonist. **No drift. Recommend closing #70.**

> **Update (2026-06-18, later same day):** #71 was reopened and **resolved by a
> deliberate spec change**, not closed. The audit below was correct — 620 px was
> spec-compliant — but the owner decided the wasted pane margin on wide screens was
> not acceptable. Reader `max-width` was raised **620 px → 900 px** (~100-char
> measure); `VISUAL_SPEC.md` § Container max-widths was updated to match. Measured
> result: ~99 chars/line and ~61 % pane fill at 1920 px (was ~40 %). The cap
> engages at viewport ≳ 1500 px; below that the column is pane-limited (≈91 chars
> at 1440, ≈72 at 1280). The original audit reasoning is kept below for the record.

## #71 — Web: reader "uses only half the width" → ~~matches reference, close~~ **superseded: spec widened to 900 px**

| Measure | Live (desktop 1280) | Reference (1180) |
|---|---|---|
| Reader pane | x 600 → 1280 (680 px) | x 601 → 1180 (579 px) |
| Rendered text block | x 679 → 1199 (**520 px**) | x 650 → 1125 (**475 px**) |
| Content box (≈ text + 48 pad) | ≈ 616 px (cap 620) | ≈ 571 px (pane-limited) |

[ReaderPane.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt#L121-L123)
matches spec exactly: `max-width: 620px; padding: 52px 48px 80px`. The live text
column (520 px) is actually **wider** than the reference (475 px) — the reference
just happens to be captured at a narrower viewport (1180), where the 579 px pane
is below the 620 cap so content fills it edge-to-edge with no centering gap. At
1280 the pane (680) exceeds 620, so the cap leaves ~80 px of intentional
whitespace each side. That whitespace is the "half width" impression, and it is
the spec's explicit behaviour: *"the column itself fills the remaining width …
without stretching the reading measure … Don't widen it. If the user complains
the page feels narrow, increase the font size, not the column width."* Confirmed
at the narrow viewport too (900 → pane 300 < 620 → fills fully). **No drift.
Recommend closing #71.**

## #72 — Web: identity/account "box" → **real drift, sharpen + fix**

The "inconsistent box" is a **card wrapper** that contradicts the spec's flat,
no-card aesthetic ("no rounded card aesthetic", "no tonal surfaces", sections =
eyebrow + hairline-separated rows). It is systematic, not just around account
language:

- **Settings** — every section (Reading / Sync / Account) is wrapped by
  `settingsGroup` in
  [SettingsScreen.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/SettingsScreen.kt#L459-L471):
  `background: var(--feed-panel); border: 1px solid var(--feed-border);
  border-radius: 4px; max-width: 700px`. Spec §Web · Settings wants flat rows on
  `bg` (no panel fill, no border box, no radius), content **max-width 640 px**
  (live is 700 px). The box is most conspicuous around the Account section
  (About "Client v… · Server v…", Logout) — which is what the ticket reporter saw.
- **Subscriptions** — the feed-row list is wrapped by a bordered/rounded card in
  [SubscriptionsScreen.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/subs/SubscriptionsScreen.kt#L384-L391):
  `border: 1px solid var(--feed-border); border-radius: 4px; overflow: hidden`.
  Spec §Web · Subscriptions wants a flat vertical stack with a 1px bottom border
  between rows (none on the last) — no surrounding card. The **search bar's** own
  border/radius/panel ([L330-L341](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/subs/SubscriptionsScreen.kt#L330-L341))
  is spec-correct and stays.

**Fix:** drop the `settingsGroup` card chrome (panel fill, border, radius), set
Settings content max-width to 640 px, and drop the feed-list card box on
Subscriptions — keep hairline row dividers.

## #67 — Android: top bar + nav bar padding → **real drift, sharpen + fix**

Full-frame `android/unread.png` vs `ref/android-editorial.png`. Status-bar-bottom
→ large-title-top gap: **live ≈ 235 px (~78–90 dp) vs reference 48 px (~48 dp)** —
roughly **2× the reference top padding**. Two compounding causes:

1. **Status-bar inset applied twice (root cause of the bulk of the gap).** The app
   is edge-to-edge ([MainActivity.kt:79](../../app/src/main/java/eu/monniot/feed/MainActivity.kt#L79)
   `enableEdgeToEdge()`). The outer
   [MainTabShell](../../app/src/main/java/eu/monniot/feed/ui/shell/MainTabShell.kt#L94-L117)
   `Scaffold` consumes `systemBars` and pads the content `Box` by `innerPadding`.
   Each tab screen is **also** wrapped in its own `Scaffold`
   ([FeedScreen.kt:250-269](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L250-L269),
   [SettingsScreen.kt:193](../../app/src/main/java/eu/monniot/feed/ui/settings/SettingsScreen.kt#L193))
   with no `contentWindowInsets` override, so it re-applies the status-bar inset a
   second time. → roughly an extra status-bar height (~26 dp) above every tab
   screen header.
2. **Header padding too large.** The header uses
   [`padding(horizontal = 22.dp, vertical = 22.dp)`](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L276).
   Spec §Mobile header wants **top = inset + 14 dp, bottom = 18 dp** (horizontal 22 dp
   is correct). So top is 8 dp over and bottom 4 dp over.

Nav bar / bottom: the original "articles hidden ~10 dp behind the nav bar"
complaint does **not** reproduce in the current shot — the outer Scaffold's
`innerPadding` already insets the list by the nav-bar height, and the last article
("EXT4 Reworks …") sits fully visible above the bar (live and reference both place
the bar top at ~0.915 of frame height). Treat the bottom clipping as a device-only
scroll check; the measurable, reproducible drift is the top bar.

**Fix:** stop double-consuming the status-bar inset (set
`contentWindowInsets = WindowInsets(0)` on the inner per-tab Scaffolds, or drop the
nested Scaffold for inset purposes), and change the header padding to
`top = 14.dp, bottom = 18.dp` (keep horizontal 22 dp). Re-verify on device with
both gesture-nav and 3-button nav.

## Summary

| Ticket | Verdict | Evidence |
|---|---|---|
| #70 | **Matches reference — close** | list = 380 px live / 381 px ref / 380 px spec |
| #71 | **Done `[x]` — spec widened** | audit found 620 px spec-correct but wasteful on wide screens; cap raised to 900 px (~100 chars), spec + impl updated |
| #72 | **Drift — fix** | `settingsGroup` + feed-list card boxes vs flat spec; Settings 700→640 px |
| #67 | **Drift — fix** | top gap ~2× ref: doubled status-bar inset + header 22 dp (vs 14/18) |
