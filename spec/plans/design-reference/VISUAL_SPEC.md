# Feed — Visual specification

This document is the **visual reference** for Feed, a self-hosted RSS reader with a web client and an Android client. It describes how the design looks and why it looks that way, independently of any codebase. For integration concerns — file structure, navigation model, state shape, what to copy and what to ignore — see `INTEGRATION.md` next to this file.

The running prototype at `prototype/index.html` (in this same handoff folder, also at the project root) is the **canonical visual source of truth**. Read this doc, but keep the prototype open: serve `index.html` and click into the **Paper** desktop artboard and the **Paper** Android artboard. Where this spec gives a pixel value, that value is what produces the look in the prototype; where it gives reasoning, that reasoning is meant to help you extrapolate into screens or states this doc doesn't cover.

The behavioural contract lives in `uploads/FEATURES.md` (also in the project root). When this spec and FEATURES.md disagree — for example, if this spec describes a UI element for a feature FEATURES.md has dropped — **FEATURES.md wins**. This visual spec describes only what the prototype actually renders today, against the current FEATURES.md.

---

## The design in one paragraph

Feed wants to look like **a paper notebook, not an app**. Quiet, serif-led, low-chroma, no visual noise competing with the article text. Everything that isn't the article itself recedes: hairline borders instead of shadows, one muted slate accent instead of a brand colour, unicode-weight icons instead of filled glyphs, no animation, no surface gradients, no rounded card aesthetic, no decorative imagery. The reader pane is the protagonist of the screen at all times.

When you have a choice between two implementations, **pick the quieter one.**

---

## Palette — "Paper"

A low-chroma cool grey-white system. Every surface across both platforms uses these eleven tokens. There is no second palette, no dark mode, no theme picker — Paper is the entire palette.

| Token | Hex / value | OKLCH (where colour) | Use |
|---|---|---|---|
| `bg` | `#f3f5f7` | L 96.9 · C .003 · h 248 | Page background ("paper") |
| `panel` | `#f9fafb` | L 98.5 · C .002 · h 248 | Sidebar, selected-row tint, input fields, popovers, button backgrounds |
| `border` | `rgba(20, 25, 40, 0.08)` | — | Hairline dividers, input + button borders |
| `borderStrong` | `rgba(20, 25, 40, 0.16)` | — | Popover outlines, login form field underlines |
| `ink` | `#1a1f28` | L 23.8 · C .019 · h 262 | Body text, headlines, primary buttons |
| `ink2` | `#4a5160` | L 43.4 · C .026 · h 266 | Secondary text (excerpts, author, button labels) |
| `ink3` | `#7c8290` | L 60.6 · C .022 · h 267 | Tertiary text (timestamps, captions, hint copy) |
| `muted` | `rgba(20, 25, 40, 0.5)` | — | Sidebar counts |
| `accent` | `#566073` | L 48.8 · C .033 · h 263 | Active nav, selected indicator, unread dot, link colour, primary-button fill on Subscriptions |
| `accentSoft` | `rgba(86, 96, 115, 0.10)` | — | Active-nav background, active mobile-tab pill |
| `onAccent` | `#f9fafb` | — | Text/icons on solid `accent` fills |
| `danger` | `#a05050` | L 47.6 · C .076 · h 23 | Destructive actions only (Logout button, Delete menu item, login error border) |

### Reasoning

- The palette is **cool grey** — every neutral token sits in OKLCH hue 248–267. This is deliberate; warm-grey paper feels nostalgic, cool-grey paper feels current and screen-native. Don't shift the hue.
- The accent is **not a brand colour.** It's a muted slate. The design has no second accent. If you find yourself reaching for one, you're solving the wrong problem — use `ink2` or `ink3` instead.
- `border` is **alpha**, not a solid value. This matters: it composites over `bg` and `panel` differently, which is what keeps dividers feeling like hairlines rather than lines. Don't flatten to a solid hex.
- `danger` is the **only saturated colour anywhere in the system.** It is reserved for destructive paths — the Logout row, the Delete menu item in subscription overflow, and the inline auth-error border on login. Do not use it for anything else.
- Body text on `bg` (`ink` on `#f3f5f7`) has a contrast ratio of ~12:1, well above WCAG AAA. Secondary `ink2` is ~7:1. `ink3` for timestamps is ~4.5:1 — readable, intentionally lower contrast so timestamps don't compete with titles.

### Rules of thumb

Body text uses `ink`. Metadata uses `ink2`. Timestamps, labels, and footer chrome use `ink3`. The accent is **never used as a fill for large surfaces** — only for 1–2px indicators (the selected-row inset bar, the active-nav pill, the unread dot) and small text on links and active states.

### Tokens as CSS custom properties

```css
:root {
  --bg:            #f3f5f7;
  --panel:         #f9fafb;
  --border:        rgba(20, 25, 40, 0.08);
  --border-strong: rgba(20, 25, 40, 0.16);
  --ink:           #1a1f28;
  --ink-2:         #4a5160;
  --ink-3:         #7c8290;
  --muted:         rgba(20, 25, 40, 0.5);
  --accent:        #566073;
  --accent-soft:   rgba(86, 96, 115, 0.10);
  --on-accent:     #f9fafb;
  --danger:        #a05050;
}
```

### Tokens as Jetpack Compose

```kotlin
object FeedColors {
  val bg            = Color(0xFFF3F5F7)
  val panel         = Color(0xFFF9FAFB)
  val border        = Color(0x14141928)         // alpha 0.08 over neutral 0x141928
  val borderStrong  = Color(0x29141928)         // alpha 0.16
  val ink           = Color(0xFF1A1F28)
  val ink2          = Color(0xFF4A5160)
  val ink3          = Color(0xFF7C8290)
  val muted         = Color(0x80141928)         // alpha 0.50
  val accent        = Color(0xFF566073)
  val accentSoft    = Color(0x1A566073)         // alpha 0.10
  val onAccent      = Color(0xFFF9FAFB)
  val danger        = Color(0xFFA05050)
}
```

Wire these into a Compose `ColorScheme` (Material 3) by mapping `bg → background`, `panel → surface / surfaceVariant`, `ink → onBackground / onSurface`, `accent → primary`, `onAccent → onPrimary`, `danger → error`. The mapping is approximate; the design is not Material — see the **No-Material rule** in `INTEGRATION.md`.

---

## Typography

Two families. Both load from Google Fonts in the prototype; **bundle them into the production app** rather than relying on Google.

- **Serif — `Source Serif 4`** (opsz 8..60, weights 400 / 500 / 600). Fallback: `"Source Serif Pro", "Iowan Old Style", Georgia, serif`. Used for all headlines, article titles, article body, italic emphasis, the wordmark, the avatar letters in subscriptions, and the bottom-tab glyphs on mobile.
- **Sans — `IBM Plex Sans`** (weights 400 / 500 / 600). Fallback: `ui-sans-serif, system-ui, sans-serif`. Used for **all** UI chrome — sidebar, nav, buttons, metadata, settings labels, timestamps, counts.

### Reasoning — why two families

The serif/sans split is the central typographic move in the design. It maps the **content / chrome** distinction directly onto the **serif / sans** distinction, so the reader's eye learns that "the serif is the article and the sans is the app." Once that mapping is established, the chrome can recede: the user stops reading the sidebar as text and starts reading it as scaffold. Don't blur the split by setting a label in serif or a title in sans.

Source Serif 4 specifically (not Pro) is chosen because of its optical-size axis — the same font carries from 10px metadata to 36px H1 without losing weight or feeling computery at small sizes. If you can't ship Source Serif 4, use Source Serif Pro 500-weight as the closest substitute.

### Type ramp

| Role | Family | Size | Weight | Line-height | Letter-spacing |
|---|---|---|---|---|---|
| Page H1 (Subscriptions, Settings, login hero) — desktop | Serif | 28px (38px login) | 500 | 1.08–1.1 | −0.02em |
| Article-list section title (sticky header) | Serif | 22px | 500 | 1.1 | −0.015em |
| Article H1 (reader) — desktop | Serif | 36px | 500 | 1.12 | −0.02em |
| Article H1 (reader) — mobile | Serif | 26px | 500 | 1.15 | −0.02em |
| Article dek/excerpt (reader) — desktop | Serif italic | 18px | 400 | 1.45 | 0 |
| Article dek/excerpt (reader) — mobile | Serif italic | 16px | 400 | 1.5 | 0 |
| Article body | Serif | **configurable 14–24** (default 18 web / 17 Android) | 400 | 1.65 | 0 |
| List item title — desktop | Serif | 17px (15 compact) | 500 | 1.25 | −0.01em |
| List item title — mobile | Serif | 18px (16 compact) | 500 | 1.2 | −0.01em |
| Brand wordmark "Feed" | Serif | 17px (18px mobile login) | 500 | 1 | −0.01em |
| Mobile screen H1 (large title in screen header) | Serif | 30px | 500 | 1.05 | −0.02em |
| Settings row label — desktop | Sans | 14px | 500 | 1.4 | 0 |
| Settings row hint — desktop | Sans | 12px | 400 | 1.45 | 0 |
| Feed/folder list item (sidebar) | Sans | 12.5px | 400 | 1.4 | 0 |
| Primary nav item (sidebar) | Sans | 13px | 500 | 1.4 | 0 |
| Article-list excerpt | Sans | 12px web (12.5 mobile) | 400 | 1.4–1.45 | 0 |
| Reader meta line (eyebrow) | Sans | 11.5px web / 10.5px mobile | 400 | 1 | 0.06–0.08em uppercase |
| Settings section eyebrow | Sans | 11px web / 10px mobile | 500 | 1 | 0.1em uppercase, `ink3` |
| Folder name in sidebar | Sans | 10px | 500 | 1 | 0.1em uppercase, `ink3` |
| Time / min-read / counts | Sans | 10.5–12px | 400 | 1 | 0, **tabular-nums** |
| Tab-bar label (mobile) | Sans | 10px | 500 / 600 active | 1 | 0 |

### Important type details

- **`text-wrap: pretty`** is applied to the article body and to the longer hint paragraphs in the "Notes" card. This is non-negotiable for the reader — without it, the narrow 620px column produces orphans that immediately wreck the "reading a book" feel. The Android equivalent is `LineBreak.Paragraph` on the `Text` composable, or `androidx.compose.foundation.text.BasicText` with `lineBreak = LineBreak.Paragraph`.
- **`font-variant-numeric: tabular-nums`** on all counts, timestamps, and min-read labels. This keeps the unread counts in the sidebar from shifting horizontally as they tick. In Compose: `TextStyle(fontFeatureSettings = "tnum")`.
- **Negative letter-spacing on serif headlines** (−0.01 to −0.02em). Default serif tracking at large sizes looks airy; tightening pulls the headline together. Negative `letterSpacing` in Compose is in `.sp` (e.g. `(-0.72).sp` for a 36sp headline at −0.02em).
- **Italic** is used in exactly three places: the article dek (subtitle under the H1 in the reader), the empty-list/empty-state copy ("Nothing here yet."), and the login subtitle paragraph. It is not used for emphasis in the body — that's a content-level choice in the source HTML, not a design-system rule.

---

## Spacing

There is **no strict modular scale.** Values are picked from `{2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 52, 60, 80}` (px on web, dp on Android) as the design called for. When implementing in a system with a token scale (4dp or 8dp base), round to the nearest scale step — except inside the reader, where the prototype's exact values must be preserved because they were tuned against the body line-height.

### Container max-widths

| Container | Max-width |
|---|---|
| Reader body column (desktop) | **620px** |
| Reader body column (mobile / Android) | full bleed minus 24dp horizontal gutter |
| Subscriptions page content (desktop) | 720px |
| Settings page content (desktop) | 640px |
| Login form (desktop) | 420px |

The 620px reader is the most important number in the spec. It's intentionally narrower than most modern reader apps — at 18px body / 1.65 line-height it produces a ~65-character measure, which is the classic typographic target for long-form reading. **Don't widen it.** If the user complains the page feels narrow, increase the font size, not the column width.

---

## Radii

The design uses **very subtle rounding** — 2–4px corners across almost the entire system, with one named exception.

| Use | Radius |
|---|---|
| Cards, buttons, inputs, popovers, segmented controls | 4px |
| Avatar tile (subscription row) | 4px |
| Thumbnail placeholders | 2px |
| Feed-colour dots, unread dots | 50% (circle) |
| Brand-mark outer ring | 50% (circle) |
| Mobile tab-bar active-tab pill (around the glyph) | 999px (full pill) |

### Reasoning

Modern UI defaults to 8–16px radius (Material 3, iOS 14+). Feed uses 4px because the design is referencing **printed forms and library cards**, not app surfaces. The mobile tab bar's active pill is the only place where the design admits a fully-rounded shape, and it's there specifically to read as a soft "you are here" mark rather than as a button — the glyph is what's tappable, the pill is just its highlight.

---

## Shadows & elevation

**No drop shadows on layout chrome.** Anywhere. The design uses 1px hairlines on the `border` token to separate surfaces.

Two specific exceptions, both popovers:

1. The desktop **font-size picker** (the `Aa` popover in the reader): `0 8px 24px rgba(0,0,0,.08)`, on a `panel` background with a `borderStrong` outline.
2. The desktop **subscription overflow menu** (Rename / Delete): `0 8px 24px rgba(0,0,0,.10)`, on a `panel` background with a `borderStrong` outline.

These are popovers, not "lifted cards," and the shadow is there only so the popover doesn't bleed into the list under it. **Do not extend this pattern to other surfaces.** If you find yourself wanting a shadow somewhere else, you're solving a wrong problem — increase the contrast on the divider or move the surface to `panel` instead.

The **mobile bottom tab bar** uses `backdrop-filter: blur(24px)` over a `panel`-at-94%-opacity background. This is the only visually critical effect in the entire design (see **The mobile tab bar** below).

---

## Iconography

The prototype uses **unicode glyphs as placeholders.** These need to be replaced with a real icon set in production (Lucide, Heroicons, Phosphor, Material Symbols Outlined — choice is the developer's). Match weight to **1.5px outlined**; don't use filled icons. The unread dot stays as-is; it is not an icon.

| Use | Prototype glyph | Production icon |
|---|---|---|
| Menu / hamburger | `≡` | menu |
| Search | `⌕` | search |
| Refresh (sidebar footer) | `↻` | refresh / arrow-clockwise |
| Share | `⎙` | share |
| Open externally | `↗` | external-link / arrow-up-right |
| Mark read (the `✓` button next to the unread dot on each row) | `✓` | check |
| Mark unread (the `↩` button in the reader action group) | `↩` | undo / corner-down-left |
| Font size picker trigger | `Aa` | (keep as text — this is a label, not an icon) |
| Reader back chevron (mobile) | `‹` | chevron-left |
| Overflow (subscription row) | `⋯` | more-horizontal |
| Mobile-tab: Unread | `◉` | circle-dot / filled-circle |
| Mobile-tab: All | `☰` | list / lines |
| Mobile-tab: Feeds | `⌒` | rss |
| Mobile-tab: Settings | `◌` | settings / sliders |
| Sign-in button trailing arrow | `→` | arrow-right |
| Sidebar brand mark | 22×22 ringed circle with 6px `accent` dot in centre | (keep — this is a design element, not an icon) |
| Unread row indicator | 6×6 dot in `accent` | (keep — this is a design element, not an icon) |

The unread dot and the brand mark are real design elements, not placeholder icons. Keep them.

**What is explicitly NOT in the icon set** (per FEATURES.md): the star / favorite glyph. Starring is dropped. Don't add a star icon back in any production iconography pass.

---

## Per-feed hue system

Each feed carries a single integer `hue` (0–360). That hue drives three things and nothing else:

1. The **6×6 dot** in the sidebar feed list and in article meta lines: `background: oklch(0.65 0.12 <hue>)`.
2. The **avatar tile** in subscription rows (36×36 desktop / 34×34 mobile): background `oklch(0.85 0.05 <hue>)`, letter `oklch(0.35 0.08 <hue>)`, serif 16/500 (15/500 mobile).
3. The **placeholder thumbnail** in card view (64×64 desktop / 56×56 mobile): 135° diagonal stripes alternating `oklch(0.90 0.03 <hue>)` and `oklch(0.85 0.04 <hue>)`, 6px stripe width, 2px radius, 1px `border` outline.

Hue assignments used by the prototype seed data (`data.jsx`):

| Feed | Hue |
|---|---|
| Field Notes | 22 |
| The Loop | 215 |
| Cold Take | 0 |
| Atlas | 152 |
| The Garden | 88 |
| Frequencies | 285 |
| The Plot | 38 |

### Reasoning

The hue system gives each feed a subtle identity without committing to logos, favicons, or brand colour. It scales: a user with 50 feeds gets 50 distinct hues that all sit at the same lightness and chroma, so no feed visually shouts over another. **Don't let hue drift across surfaces.** All three uses (dot, avatar, thumbnail) must use the OKLCH formulas above; pulling hue into hex defeats the whole system because hex doesn't preserve perceptual lightness across hues.

**Persist `hue` per feed.** Default deterministically from feed id (e.g. `Math.abs(hashCode(feedId)) % 360`) so feeds get stable colours before any server-side or user customization, and so the same feed renders the same hue across reloads on web and across the web/Android clients pointing at the same server. FEED-5 in FEATURES.md depends on hue stability — see ticket #36 in the spec for the collision-tracking work.

On Android, Compose has no native OKLCH — compute the three colours in Kotlin once per feed at load:

```kotlin
fun feedDot(hue: Float)       = oklchToArgb(0.65f, 0.12f, hue)
fun feedAvatarBg(hue: Float)  = oklchToArgb(0.85f, 0.05f, hue)
fun feedAvatarInk(hue: Float) = oklchToArgb(0.35f, 0.08f, hue)
fun feedStripeA(hue: Float)   = oklchToArgb(0.90f, 0.03f, hue)
fun feedStripeB(hue: Float)   = oklchToArgb(0.85f, 0.04f, hue)
```

Memoize. See `INTEGRATION.md` for an OKLCH-to-ARGB conversion snippet.

---

## Layout

### Desktop layout (≥1100px wide)

Three-column grid, columns scrolling independently:

```
┌───────────┬───────────────┬───────────────────────────┐
│           │               │                           │
│  Sidebar  │  Article list │   Reader pane             │
│  220px    │  380px        │   fills, content 620px    │
│           │               │   centred                 │
└───────────┴───────────────┴───────────────────────────┘
```

The reader pane's content is centred within the column at max-width 620px; the column itself fills the remaining width to accommodate wide viewports without stretching the reading measure.

On **Subscriptions** and **Settings**, the article-list + reader columns collapse into a single content area at max-width 720px and 640px respectively. The sidebar is unchanged. The page-screen route (`feed/:id`, `unread`, `all`) shows three columns; `subscriptions` and `settings` show two (sidebar + content).

Below 1100px, the desktop layout is not designed — the design assumes the responsive break to mobile happens here, not a tablet-specific layout. (If the codebase needs a tablet breakpoint, the desktop layout simply downsizes until it can't fit, then jumps to mobile.)

### Mobile layout (Android)

```
┌──────────────────────┐
│  OS status bar       │  ← top inset (~14dp on Android)
├──────────────────────┤
│  Large title header  │  ← serif H1 + small subtitle (no top icons)
├──────────────────────┤
│  Article list        │
│  (scrolls)           │
├──────────────────────┤
│  Tab bar             │  ← absolutely positioned, full-bleed,
│                      │     panel @ 94% + backdrop-filter blur,
│                      │     30dp bottom inset
└──────────────────────┘
```

Tapping an article row pushes a full-screen reader; the tab bar **hides** while the reader is open. The reader's own top bar gets a back chevron (`‹ {feedName}`) in `accent` that pops back to the list at its previous scroll position (READ-1c).

The Android header **does not have any top icons** — no hamburger, no search affordance. The tab bar IS the primary nav (per FEATURES.md — the mobile filter chips are dropped). Search lives inside the Feeds screen on its own content row.

---

## Screens

For each screen, the spec describes structure top-to-bottom and gives the values that produce the prototype's look. **When in doubt, open `prototype/index.html` and inspect.**

### Web · Sidebar (shared across all logged-in screens)

220px wide, `panel` background, 1px right border in `border`, full viewport height. `IBM Plex Sans`, `ink` text. Top to bottom:

1. **Brand mark** — 20/18 padding-top, 18px horizontal. A 22×22 circle outlined 1.5px in `ink`, containing a 6×6 dot in `accent`, followed by the wordmark "**Feed**" (serif 17/500, −0.01em). 10px gap between mark and wordmark.
2. **Primary nav** — 4/10 padding, 1px gap between items. Each item is a button: 6/10 padding, 4px radius. Label left, count right (sans 11px `muted`, tabular-nums). Active state: `accentSoft` background, `accent` text. Items, in this exact order:
   - **Unread** — count = unread articles across all feeds
   - **All articles** — count = total articles
   - **Subscriptions** — count = number of feeds
   - **Settings** — no count
3. **Divider** — 1px `border`, 14/18 vertical margin.
4. **Folder list** — 10px horizontal padding, scrollable. Per folder: header (sans 10/500 0.1em uppercase `ink3`, padding 4/10). Per feed row: 5/10 padding, 4px radius, sans 12.5px. Contents: 6×6 hue dot at left, feed name (truncate with ellipsis), unread count right-aligned (sans 10.5px `muted`, tabular-nums; hidden when 0). Active feed: `accentSoft` background, `accent` text. Folder names come from the server's category field; the design's "Craft / Tech / Reading" folders are seed data, not built-in.
5. **Footer** — 12/18 padding, 1px top border in `border`. Sans 11px.
   - **Normal state:** `Synced 2m ago` (`ink3`) on the left, refresh glyph `↻` (`ink3`, button) on the right.
   - **Sync-failed state (ERR-1):** `Last sync failed · retry` (`ink2` body, `retry` link in `accent` with 1px underline, 2px underline offset), and a `!` indicator on the right in `accent`. Clicking `retry` fires a fresh sync.

### Web · Article list (380px, on Unread / All / per-feed routes)

380px wide, `bg` background, 1px right border in `border`. Independent vertical scroll.

- **Sticky header** — 22/22 padding-top + horizontal, 14px padding-bottom, `bg` background, 1px bottom border in `border`. Two lines:
  - Title (serif 22/500, −0.015em). Swaps to feed name when a feed is selected, "Unread" / "All articles" otherwise.
  - Subtitle (sans 12px `ink3`): article counts, e.g. `7 unread articles` or `7 articles · M. Quinn` (author shown for per-feed views).
- **List rows** — full-row click target. Density-driven padding: compact `10/18`, regular `14/20`, comfy `20/22`. 1px bottom border in `border` between rows; the last row has none. Selected-row background: `panel`. Vertical flex, 6px gap between children.
- **Row meta line** — sans 11px `ink3`: hue dot · feed name (sans 500 `ink2`) · `·` · time-ago. Right-aligned: a **52px-wide cluster** reserved for the unread state, which always slots in the same horizontal position so titles don't shift between read and unread rows. The cluster contains, when the article is unread:
  - the 6×6 `accent` unread dot, and
  - a **22×22 mark-read button** (`✓` glyph): 1px `border`, transparent background; on hover, border becomes `borderStrong`, background becomes `panel`, glyph colour shifts from `ink3` to `ink2`. The button stops event propagation so clicking it does not select the row.
- **Title** — serif `ink`, 500, −0.01em. 17px regular/comfy, 15px compact.
- **Excerpt** — sans 12px `ink2` line-height 1.4, clamped to 2 lines via `-webkit-line-clamp: 2`. **Hidden in compact density.**
- **Thumbnail** — 64×64 striped placeholder (per-feed hue). Visible **only** when `viewMode === 'card'` OR `density === 'comfy'`. Sits to the left of the excerpt with 12px gap, both items aligned to their tops.
- **Min-read line** — sans 10.5px `ink3`, tabular-nums: `8 min read`.
- **Selected row indicator** — a 2px-wide vertical `accent` bar pinned to the **left edge** of the row (drawn at `position: absolute; left: 0; top: 0; bottom: 0;` inside the row's bounding box). The row's background is `panel`. There is no other styling difference for selection.

#### Empty state

When the list has zero articles (initial fixture is empty, the current filter has no matches, or the user has the `state` tweak set to `empty`): centred serif italic 16px `ink3` reading "Nothing here yet.", 80px vertical padding from the sticky header. This is the same string used everywhere an empty-list state appears (ERR-2).

### Web · Reader pane

Fills the remaining viewport width. `bg` background. Independent vertical scroll. Content max-width 620px, centred, padding `52px 48px 80px`.

- **Empty state** (no article selected) — centred vertically + horizontally, `ink3` serif italic 16px, with a 32px em-dash above the text in `ink2`. Copy: "Select an article to begin reading." (READ-6).
- **Article view**, top to bottom:
  - **Meta row** — sans 11.5px uppercase 0.06em letter-spacing `ink3`: hue dot + feed name (`ink2`) + `·` + author. Right-aligned (no uppercase, no tracking, `ink3`): `2h ago · 11 min read`. 24px below this row.
  - **H1** — serif 36/500 1.12 −0.02em `ink`. 14px below.
  - **Dek** — serif italic 18/1.45 `ink2`. 32px below.
  - **Action row** — left-aligned cluster: `↗ Open` (a real `<a>` to the article URL, target `_blank`, `rel="noopener noreferrer"`), `⎙ Share`, `↩ Mark unread` / `Mark read` (label swaps with the article's read state, per READ-7). Right-aligned: `Aa` font-size trigger. Each button: 6/12 padding, 4px radius, 1px `border`, `panel` background, sans 12px `ink2`. 8px gap. 36px below.
  - **Body** — serif user-size (default 18px), line-height 1.65, paragraph-spacing 1.1em, `text-wrap: pretty`.
  - **Footer** — 44px below body, 24px top padding, 1px top border in `border`. Sans 12px `ink3`: "End of article" left, the article's link as a real `<a>` right (`ink3`, 1px underline, 2px underline offset, max-width 70%, ellipsis-truncated).

#### Font-size picker

Anchored under the `Aa` button (right: 0, top: 38). `panel` background, 1px `borderStrong` outline, 4px (read 6px) radius, 8px padding, 4px gap between items, `0 8px 24px rgba(0,0,0,.08)` shadow. Six options as 30×30 cells (4px radius): 14, 16, 18, 20, 22, 24. Active value cell: `ink` background, `panel` foreground. Inactive: transparent, `ink2`. Sans 12px tabular-nums. Picking a value sets the global preference and closes the popover immediately (SET-3).

### Web · Subscriptions

Sidebar unchanged. Middle + right columns collapse into a single content area at **max-width 720px**, centred. `bg` background, 48/40/60 padding.

- **Header row** — 28px below itself. Serif H1 28/500 "Subscriptions" left. **`+ Add feed`** button right: 8/14 padding, 4px radius, solid `accent` background, `onAccent` text, no border, sans 12px. When open, the button toggles to "Cancel" with `panel` background, `ink2` text, and a `border` outline.
- **Add-feed form (collapsible)** — appears between the header row and the search bar when active. 12/14 padding, 1px `borderStrong`, 4px radius, `panel` background, 16px gap below. Contains: a transparent input (sans 13px `ink`, placeholder `https://example.com/feed.xml`) and a **Subscribe** submit button — 6/12 padding, `ink` background, `panel` text, no border.
- **Search bar** — 10/14 padding, 1px `border`, 4px radius, `panel` background, 24px below. 8px gap between elements: search glyph `⌕` in `ink3`, input (sans 13px `ink`, placeholder "Search subscriptions…"), right-aligned count `4 of 7` (sans 11px `ink3`). Filter is client-side (SUBS-3).
- **Feed rows** — vertical stack, 14px top/bottom padding per row, 1px bottom `border` between (none on the last row), 14px gap between row elements:
  - **Avatar** — 36×36, 4px radius, hue-tinted background, letter in deeper hue, serif 16/500. Letter is `name[0]`.
  - **Body block** (flex: 1, min-width: 0):
    - Name (serif 16/500). In rename mode, swap to an `<input>` with 1px `borderStrong` bottom border, no fill, prepopulated and **pre-selected** (`el.select()` after focus — SUBS-4).
    - URL (sans 11.5px `ink3`), 2px below name.
  - **Folder cell** — 74px wide, right-aligned, sans 11px `ink3`. Shows the folder name.
  - **Unread cell** — 60px wide, right-aligned, sans 11px `ink3` tabular-nums. Shows `N new`.
  - **Overflow button** `⋯` — 4/8 padding, transparent, `ink3`. Opens the menu below.

#### Subscription overflow menu

Anchored to the `⋯` button (right: 0, top: 28). `panel` background, 1px `borderStrong` outline, 4px radius, `0 8px 24px rgba(0,0,0,.10)` shadow, min-width 140px, 4px inner padding. Two items, each: full-width button, 7/12 padding, 3px radius, sans 13px `ink`, transparent background:
- **Rename…** — `ink` text. Opens the inline rename input (see above).
- **Delete** — `danger` text. Confirms via `confirm()` dialog, then removes the row (SUBS-5).

The menu **must render above adjacent rows**, not be clipped by the row's bounding box. In CSS this means `position: absolute; z-index: 50` on the menu, with a `position: fixed; inset: 0; z-index: 40` click-away catcher behind it.

#### Empty state

If the search filter has no matches: centred serif italic 16px `ink3` "Nothing here yet.", 60px vertical padding.

### Web · Settings

Sidebar unchanged. Content area **max-width 640px**, centred. `bg` background, 48/40/60 padding.

- **H1** — "Settings", serif 28/500. 28px below.
- **Section labels** — sans 11px 500 0.1em uppercase `ink3`. Section labels render with 32px top margin and 4px bottom margin. Sections, in order: **Reading**, **Sync**, **Account**.
- **Rows** — 18px top/bottom padding, 1px `border` between, 24px gap between label-block and control:
  - Left: label (sans 14/500 `ink`), max-width 360px, optional hint below (sans 12px line-height 1.45 `ink3`).
  - Right: a control. For all preference rows the control is a **segmented control** (see below). For one-shot rows (Import OPML, Logout) the control is a single button.

#### Segmented control

A row of 1px-`border` outlined cells, 4px radius, `panel` background, no inter-cell border. Each cell: 6/12 padding, sans 12px, tabular-nums, button reset. Active cell: `ink` background, `panel` text. Inactive cell: transparent, `ink2` text.

#### Rows shown (web)

Order matters here; this is the canonical web Settings surface:

| Section | Label | Hint | Control |
|---|---|---|---|
| Reading | Reader font size | "Applies to the article body. Live-updates the open reader without reload." | Segmented: 14 / 16 / 18 / 20 / 22 / 24 |
| Reading | Article-list density | "Compact hides excerpts; Comfy shows thumbnails." | Segmented: Compact / Regular / Comfy |
| Reading | Mark as read on scroll | "A row visible for ≥ 1s flips to read." | Segmented: Off / On |
| Sync | Refresh interval | "Client-side auto-poll cadence for the article list." | Segmented: 15m / 1h / 6h / Manual |
| Sync | Keep articles | "Retention window. ∞ disables retention." | Segmented: 30d / 90d / 1y / ∞ |
| Account | Import OPML | "Bring in a backup or export from another reader." | Button: "Choose file…" (same styling as reader action buttons) |
| Account | About | "Client v1.0.0 · Server v0.7.2" | Em-dash placeholder `—` in `ink3` |
| Account | Logout | "Clears the local session and returns to the login screen." | Button: "Sign out" — same shape as the action buttons but with `danger`-coloured text and `danger`-coloured border |

The web Settings surface has **no Server URL row** (FEATURES.md ticket #32).

### Web · Login

Centred form on a full-bleed `bg` page. Form width 420px, vertical flex with 32px gap between the wordmark and the form block; 26px gap within the form block.

- **Wordmark** — the 22×22 outlined-circle mark + serif 22/500 "Feed" (this version omits the inner accent dot; it's a smaller serif "F" rendered inside the ring at 60% of the ring size, italic).
- **Eyebrow** — sans 11px 0.18em uppercase `ink3`, "Sign in", 14px above the H1.
- **H1** — serif 38/500 1.08 −0.02em `ink`, `text-wrap: balance`. Copy: "Welcome back to your reading room."
- **Subtitle** — serif italic 15/1.5 `ink2`, 12px below H1, `text-wrap: pretty`. Copy: two sentences about quiet feeds and no algorithm.
- **Form fields** — 22px gap between fields. Each field is a `<label>` with:
  - **Field label** — sans 11px 0.14em uppercase `ink3`, 6px above the input.
  - **Input row** — a flex row with `borderBottom: 1px solid borderStrong`, 8px bottom padding. Input has no chrome (`border: none; outline: none; background: transparent`), sans 16px `ink`. The Password field has a trailing **Show** button (transparent, no border, sans 12px 0.06em `ink3`).
- **Auth error** (visible only when credentials are invalid — AUTH-2) — between the password field and the secondary controls. Sans 13px `danger` on `accentSoft` background, 1px `danger` border, 10/14 padding, with a leading serif italic `!`. Copy: "Invalid username or password." Focus stays on the password field.
- **Secondary row** — between fields and primary button. Left: a custom "Keep me signed in" checkbox — 14×14 `borderStrong` square on `panel`, 8px gap, sans 13px `ink2` label. Right: "Forgot password?" link in `ink2` with a 1px `border` underline.
- **Primary button** — full-width, `ink` background, `panel` text, 14/22 padding, sans 14/500 0.02em, no border. Trailing serif 18px `→` arrow.
- **Divider** — `or` between two flex `border` rules. Sans 11px 0.18em uppercase `ink3`.
- **Ghost buttons** — two side-by-side, equal width, 12/18 padding, 1px `borderStrong`, transparent background, sans 14/500 `ink`. Each leads with a 18×18 outlined letter glyph (`G` for Google, `@` for magic link).
- **Footer line** — sans 12px `ink3`. Left: "New here? Create an account" (link in `ink` with 1px `borderStrong` underline). Right: "© Feed Press".

The Google + magic-link buttons are decoration; the only path FEATURES.md cares about is username + password.

### Mobile (Android) · Article list — Unread / All

Vertical flex, full-bleed `bg`. Top to bottom:

- **Screen header** — 22px horizontal padding, top-inset + 14px padding-top, 18px padding-bottom, `bg` background, 1px bottom `border`. Contains:
  - **Large title** — serif 30/500 −0.02em line-height 1.05. "Unread" or "All".
  - **Subtitle** — sans 12px `ink3`, 6px below title. Format: `7 articles` (Unread) or `7 unread · 24 total` (All).
- **Article rows** — full width, density-driven padding: compact 12/22, regular 16/22, comfy 20/22. 1px bottom `border` between rows. Vertical flex with 8px gap.
  - **Meta line** — same shape as desktop: hue dot · feed name (sans 500 `ink2`) · `·` · time-ago, in sans 11px `ink3`. Right-aligned 52px-wide cluster: 6×6 unread dot + 28×28 mark-read button (`✓`), same role as the desktop button but sized for touch — 28×28 hit target, 1px `border`, `panel` background, `ink3` glyph at sans 12px.
  - **Title** — serif `ink`, 500, −0.01em. 18px regular/comfy, 16px compact.
  - **Excerpt** — sans 12.5px `ink2` line-height 1.45, 2-line clamp. Hidden in compact density.
  - **Thumbnail** — 56×56 striped placeholder, only in card or comfy mode.
  - **Min-read line** — sans 10.5px `ink3` tabular-nums.
- **Pull-to-refresh indicator** — a centred row above the list, only visible while loading. Sans 11px `ink3` "Refreshing…" preceded by a 14×14 spinner: 2px `border` ring with the top quadrant in `accent`, rotating 360° on an `.8s linear infinite` keyframe. (This is the one motion exception in the design — see Animation.)

### Mobile (Android) · Reader

Vertical flex on `bg`. Top to bottom:

- **Top bar** — top-inset + 10px padding-top, 16px horizontal, 10px padding-bottom, `bg` background, 1px bottom `border`.
  - **Back chevron** — `‹ {feedName}` in sans 14px `accent`, 4/6 padding. Pops back to the list at its previous scroll position (READ-1c).
  - **Right cluster** — 4px gap: three small buttons. **`↩` Mark unread** (only when the article is read), **`Aa`** (font-size popover), **`↗`** (real `<a>` to `article.link`, target `_blank`). Each: 6/10 padding, 4px radius, 1px `border`, `panel` background, sans 12px `ink2`.
- **Font-size popover** — anchored top: 56, right: 16; otherwise identical to the desktop picker (see Reader / Font-size picker above), at 6px padding gap.
- **Body** — 24/24/80 padding, scrollable.
  - **Meta eyebrow** — sans 10.5px 0.08em uppercase `ink3`: `Field Notes · M. Quinn · 2h`.
  - **H1** — serif 26/500 1.15 −0.02em `ink`. 12px below.
  - **Dek** — serif italic 16/1.5 `ink2`. 22px below.
  - **Body** — serif user-size (default 17px on mobile = web font size minus 1, clamped at 15), line-height 1.65, paragraphs 1.1em apart, `text-wrap: pretty`.
  - **Footer** — 28px below body, 18px top padding, 1px top `border`. Sans 11px `ink3`: "End of article" left, the article link right (`ink3`, 1px underline, max-width 60%, ellipsis).

### Mobile (Android) · Feeds (Subscriptions)

Vertical flex.

- **Screen header** — same shape as the list header. Title "Feeds", subtitle "`N` subscriptions".
- **Search row** — 14/22 padding-top + horizontal: a single search box: 10/14 padding, 1px `border`, 4px radius, `panel` background. Glyph `⌕` in `ink3`, then the input (sans 13px `ink`, placeholder "Search or paste a URL…"). 8px gap.
- **Folder groups** — per folder:
  - **Folder header** — 14/22 padding-top, 6px padding-bottom, sans 10/500 0.1em uppercase `ink3`. This is the canonical "uppercase folder header" — Android groups subscriptions by folder, web lists them flat with the folder name in the right column (SUBS-1).
  - **Feed rows** — 12/22 padding, 1px bottom `border`, `bg` background, 14px gap between elements:
    - Avatar — 34×34, 4px radius, hue-tinted, serif 15/500 letter.
    - Body — name (serif 15/500 `ink`), URL (sans 11px `ink3`, ellipsis, 2px below).
    - Right — unread count (sans 11px `ink3` tabular-nums).

The mobile Feeds screen has no `+ Add feed` button in the prototype; the search bar copy implies "or paste a URL" as the affordance. Production may add a FAB or header `+` button if needed — that's an implementation decision, not a spec change.

### Mobile (Android) · Settings

Vertical flex.

- **Screen header** — title "Settings", subtitle "Signed in as admin" (or whatever the username is).
- **Groups** — same uppercase header pattern as Feeds. In order: **Reading**, **Sync**, **Account**.
- **Rows** — full-width button, 12/22 padding, 1px bottom `border` (none on the last in a group), `bg` background, flex row:
  - Left block — label (sans 14px `ink`, or `accent` if the row is destructive), with an optional hint below (sans 11.5px `ink3`, ellipsis-truncated to one line).
  - Right block — a control (for segmented prefs) **or** a chevron-like indicator (`›` in `ink3` for routing rows, or "Choose…" for the OPML row).

#### Rows shown (Android)

| Section | Label | Hint | Right |
|---|---|---|---|
| Reading | Reader font size | "Applies to article body — live." | Segmented 14 / 16 / 18 / 20 / 22 / 24 |
| Reading | Article-list density | "Compact hides excerpts. Comfy shows thumbnails." | Segmented Compact / Regular / Comfy |
| Reading | Mark as read on scroll | "Mark a row read after ≥ 1s visible." | Segmented Off / On |
| Sync | Refresh interval | "Client-side auto-poll cadence." | Segmented 15m / 1h / 6h / Manual |
| Sync | Keep articles | "Retention window for the server sweep." | Segmented 30d / 90d / 1y / ∞ |
| Sync | **Server URL** | the current server URL | `›` (routes to an editor) |
| Account | Import OPML | "Upload a backup or another reader's export." | `Choose…` in `ink2` |
| Account | About | "Client v1.0.0 · Server v0.7.2" | `›` |
| Account | Logout | (no hint) | `›` in `accent` (destructive row — label is in `accent`) |

The Android surface has the **Server URL** row; the web surface does not. This asymmetry is intentional (FEATURES.md, Settings reference).

### Mobile (Android) · Login

Vertical flex on `panel` (not `bg` — the login page sits on the "card" background to feel slightly distinct from the main app). 22px horizontal padding throughout, top-inset honoured.

- **Top bar** — 14/22 padding. Left: the wordmark at 18px. Right: a "Sign up" link in `ink2` with a 1px `border` underline.
- **Hero** — 24/22 padding-top, 8px padding-bottom.
  - **Eyebrow** — sans 10/500 0.18em uppercase `ink3`, "Sign in". 10px below.
  - **H1** — serif 30/500 1.1 −0.02em `ink`, `text-wrap: balance`. "Welcome back to your reading room."
  - **Subtitle** — serif italic 14/1.45 `ink2`, 10px below H1, `text-wrap: pretty`. One sentence.
- **Form** — 20/22 padding-top, 20px gap. Fields are the same shape as web. The password field's `enterKeyHint="go"` and the username field's `enterKeyHint="next"` configure the Android IME action labels (AUTH-1b).
- **Auth error** — `compact` variant of the same component: sans 12px `danger` on `accentSoft` with 1px `danger` border, 8/12 padding.
- **Secondary row + primary button + divider + ghost buttons** — same shapes as web, stacked vertically (ghost buttons are full-width rather than side-by-side; 10px gap).

### Mobile (Android) · Tab bar

The defining piece of mobile chrome.

- **Position** — `position: absolute; left: 0; right: 0; bottom: 0;`. 30px padding-bottom (gesture clearance), 6px padding-top, 1px top `border`. `zIndex: 20` so it sits over the scrolling list.
- **Background** — `panel` at ~94% opacity (`#f9fafb` with alpha `f0` = 240/255), plus `backdrop-filter: blur(24px)` and `-webkit-backdrop-filter: blur(24px)`. This is the only visually critical effect in the entire design. **Do not flatten** the alpha background to opaque `panel`; the slightly-translucent layer on top of a blurred article-list under-layer is what makes the bar feel quiet rather than heavy.
- **Layout** — four equal flex items.
- **Per tab** — vertical flex, 3px gap. Top: a serif glyph 18px in a 4/18 pill (4px vertical, 18px horizontal padding), 999px radius. Bottom: sans 10px label.
  - **Active**: tab colour = `accent`, glyph pill background = `accentSoft`, label weight = 600.
  - **Inactive**: tab colour = `ink3`, glyph pill background = transparent, label weight = 500.
- **Tabs**, in this exact order: **Unread** (`◉`) · **All** (`☰`) · **Feeds** (`⌒`) · **Settings** (`◌`). The Reader screen hides the tab bar entirely (READ-1b).

On Android, replace `backdrop-filter` with a translucent `Modifier.background(Color(0xF0F9FAFB))` over the parent's scroll content — the platform doesn't support a true backdrop blur on the tab bar surface yet, and overlay-based fakes look worse than the flat translucent fallback. The blur is the web-only luxury.

---

## Density, view mode, font size

Three user-controlled visual variables, persisted globally. All three apply to article lists; font-size applies additionally to the reader body.

| Variable | Values | Default (web / Android) | Affects |
|---|---|---|---|
| `density` | `compact` / `regular` / `comfy` | `regular` | Article list row padding + whether excerpt + thumbnail show |
| `viewMode` | `list` / `card` | `list` | Whether the article list shows thumbnails (card forces them on) |
| `fontSize` | 14–24 (px web, sp Android) | 18 / 17 | Reader body font size only |

### Density behaviour

- **Compact** — tightest row padding (10/18 desktop, 12/22 mobile). **No excerpt.** **No thumbnail.** Title at smaller size (15px desktop, 16px mobile).
- **Regular** — standard padding. 2-line excerpt visible. Thumbnail visible only if `viewMode === 'card'`.
- **Comfy** — wider padding (20/22). 2-line excerpt **and** thumbnail always visible (overrides `viewMode` for thumbnail visibility).

### View-mode behaviour

- **List** — no thumbnails (unless density is comfy).
- **Card** — thumbnails always visible alongside the excerpt.

### Font-size behaviour

- The slider / picker is the same six steps everywhere: 14 / 16 / 18 / 20 / 22 / 24. (No continuous slider.)
- Web stores the value in px and applies it to the reader body's `font-size`. Android stores it in sp.
- The mobile prototype reads `web fontSize - 1` (clamped at 15) so a single shared "18" in tweaks renders as 18px on web and 17sp on Android. In production, use the platform's stored sp value directly rather than re-deriving from a web value.
- Changing the value while the reader is open re-renders the body in place — no reload, no scroll-position reset (SET-3).

---

## State surfaces (happy-path callouts)

The prototype exposes a `state` tweak (`normal` / `empty` / `sync-failed` / `auth-error` / `loading`) so designers can walk through the happy path and four common branches. These are the visual rules per state:

- **`empty`** — list views render the centred italic "Nothing here yet." (covered above).
- **`sync-failed`** — sidebar footer swaps to "Last sync failed · retry" in `ink2` with the `retry` link in `accent`, and a `!` indicator on the right (covered above).
- **`auth-error`** — login form shows the inline error component and clears the password field; focus stays on Password (covered above).
- **`loading`** — Android article list shows the pull-to-refresh spinner row above the list (covered above).

These are not separate screens — they are conditional pieces of the same screen. Implement them in-place.

---

## Animation

The design is **almost entirely static**. There are no transitions on hover, no fade-ins on navigation, no easings on selection — clicking a row swaps the reader pane instantly. Two exceptions that are part of the spec:

1. **The mobile pull-to-refresh spinner** — a 14×14 ring rotating 360° every 0.8s, `linear` timing, infinite. Visible only while a refresh is in flight.
2. **Hover micro-feedback on the mark-read button** — border, background, and glyph colour all transition over `100ms` (`transition: border-color .1s, color .1s, background .1s`) so the cursor-on-target signal isn't jarring.

Everything else is unanimated. This is a positive design choice, not an oversight — please don't add transitions during integration.

---

## Copy

All product copy in the design is **final**. Notable strings:

- Brand name: **Feed**
- Empty reader pane (web): "Select an article to begin reading."
- Empty list (anywhere): "Nothing here yet." (serif italic 16px `ink3`).
- Sidebar footer normal: `Synced 2m ago` · sync-failed: `Last sync failed · retry`.
- Login eyebrow: "Sign in". Login H1: "Welcome back to your reading room." Login subtitle (web): "Your feeds, quietly waiting. No algorithm, no infinite scroll — just the few writers you chose."
- Login submit: "Sign in" (with trailing `→`).
- Auth error: "Invalid username or password."
- Subscriptions H1: "Subscriptions". Add-feed button: "+ Add feed". Add-feed submit: "Subscribe". Search placeholder (web): "Search subscriptions…" (mobile: "Search or paste a URL…").
- Settings group headers: "Reading" / "Sync" / "Account".
- Settings actions: "Choose file…" (Import OPML), "Sign out" (Logout).
- Mobile tab labels: "Unread" / "All" / "Feeds" / "Settings" (note: web nav uses "All articles", mobile uses "All").
- Reader action labels: "↗ Open" / "⎙ Share" / "↩ Mark unread" / "Mark read" (label swaps with the article's read state).

The seed feed names and article titles in `data.jsx` are placeholders — they exist only to make the layout look populated. Replace with real user data.

---

## Assets

- **No bitmap or vector assets ship with the design.** Zero images, zero logos beyond the procedural ringed circle, no decorative SVGs.
- **Two web fonts** — Source Serif 4, IBM Plex Sans. Bundle them; don't rely on Google Fonts in production.
- **Procedural thumbnail placeholders** — diagonal `oklch()` stripes keyed off per-feed hue. Replace with real OG-image fetches if a feed provides one; otherwise keep the procedural fallback. The fallback is not a stopgap — it's the canonical card-view thumbnail.

---

## When this spec is ambiguous

The prototype at `prototype/index.html` (and its source files in `prototype/prototypes/`) is the visual source of truth. If a value in this spec disagrees with the prototype, the prototype wins. If a value is missing from both, default to the **quietest, most paper-like** interpretation and flag for design review. If the spec describes a UI element FEATURES.md drops (or vice versa), FEATURES.md wins — delete the visual element.
