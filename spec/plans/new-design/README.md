# Handoff: Feed — RSS reader

## Overview

**Feed** is a personal RSS reader. Single-user, local-first, no sync. The audience is a casual reader who follows ~10 feeds and reads 10–30 items a day.

It targets three platforms with a shared design language: **desktop web**, **iOS**, and **Android**. There are four primary screens — **Feed**, **Reader**, **Subscriptions**, and **Settings** — and the design is deliberately quiet, serif-led, and low-chroma. The intended feeling is "reading a paper notebook," not "scrolling a feed."

## About the design files

The files in `design-files/` are **design references created in HTML** — interactive prototypes showing intended look and behavior. They are **not production code to copy directly.** They use React with inline Babel transpilation, inline styles, and several "starter component" device-frames (iOS, Android, browser window) that exist only to present the design in context — none of those frames should be reproduced in the real app.

The task is to **recreate these designs in the target codebase's existing environment** — using its established component library, styling system, routing, and state patterns. If no environment exists yet, pick the most appropriate framework for the project (e.g. Next.js for web, SwiftUI for iOS, Jetpack Compose for Android) and implement there.

A single React component is shared between iOS and Android (`EditorialMobilePrototype`) because the mobile design is OS-agnostic at this fidelity — the only platform-specific moves are status-bar insets and (optionally) native navigation chrome. Treat the mobile design as **one mobile target** and implement once per platform with native components.

## Fidelity

**High-fidelity.** Final colors, typography, spacing, and layout. Recreate pixel-faithfully using the codebase's existing libraries. Copy is final.

## Design tokens

### Palette — "Paper"

The locked palette is a low-chroma cool grey-white system. All four screens use these tokens; no other colors appear.

| Token | Hex | OKLCH | Use |
|---|---|---|---|
| `bg` | `#f3f5f7` | L 96.9 · C .003 · h 248 | Page background ("paper") |
| `panel` | `#f9fafb` | L 98.5 · C .002 · h 248 | Sidebar, cards, selected-row highlight, input fields |
| `border` | `rgba(20, 25, 40, 0.08)` | — | Hairline dividers, input borders |
| `borderStrong` | `rgba(20, 25, 40, 0.16)` | — | Reserved (currently unused; available for stronger separators) |
| `ink` | `#1a1f28` | L 23.8 · C .019 · h 262 | Body text, headlines |
| `ink2` | `#4a5160` | L 43.4 · C .026 · h 266 | Secondary text (excerpts, author names) |
| `ink3` | `#7c8290` | L 60.6 · C .022 · h 267 | Tertiary text (timestamps, captions, folder labels) |
| `muted` | `rgba(20, 25, 40, 0.5)` | — | Disabled / very-low-emphasis text |
| `accent` | `#566073` | L 48.8 · C .033 · h 263 | Selected state, primary actions, star icon, link color |
| `accentSoft` | `rgba(86, 96, 115, 0.10)` | — | Selected-row background tint |
| `onAccent` | `#f9fafb` | — | Text/icons placed on solid accent fills |

**Rules of thumb.** Body text uses `ink`. Metadata uses `ink2`. Time/labels/footer use `ink3`. The accent is **never used as a fill for large surfaces** — only for 1–2px indicators (selected-row left bar, active dot, star fill) and small text on buttons. There is **no second accent color**; the design intentionally has one accent.

### Typography

Two fonts, both loaded from Google Fonts:

- **Headlines, titles, article body, italic emphasis** — `"Source Serif 4"` (opsz 8..60, weights 400/500/600). Fallback stack: `"Source Serif Pro", "Iowan Old Style", Georgia, serif`.
- **All UI text** (sidebar, nav, buttons, metadata, settings labels) — `"IBM Plex Sans"` (weights 400/500/600). Fallback: `ui-sans-serif, system-ui, sans-serif`.

Type scale used in the design:

| Role | Family | Size | Weight | Line-height | Letter-spacing |
|---|---|---|---|---|---|
| Page H1 (Subscriptions / Settings title) | Serif | 28px | 500 | 1.1 | −0.02em |
| Article list section title | Serif | 22px | 500 | 1.1 | −0.015em |
| Article H1 (reader) | Serif | 36px | 500 | 1.12 | −0.02em |
| Article dek/excerpt in reader | Serif italic | 18px | 400 | 1.45 | 0 |
| Article body | Serif | configurable (default 18px) | 400 | 1.65 | 0 |
| List item title | Serif | 17px (15px compact) | 500 | 1.25 | −0.01em |
| Brand wordmark "Feed" | Serif | 17px (16px mobile) | 500 | 1 | −0.01em |
| Settings row label | Sans | 14px | 500 | 1.4 | 0 |
| Settings row hint | Sans | 12px | 400 | 1.45 | 0 |
| Feed/folder list item | Sans | 12.5px | 400 | 1.4 | 0 |
| Nav item | Sans | 13px | 500 | 1.4 | 0 |
| Article-list excerpt | Sans | 12px | 400 | 1.4 | 0 |
| Section eyebrow / uppercase label | Sans | 10–11px | 500 | 1 | 0.06–0.10em, uppercase |
| Time/min-read | Sans | 10.5–12px | 400 | 1 | 0, tabular-nums |
| Folder name in sidebar | Sans | 10px | 500 | 1 | 0.1em, uppercase, `ink3` |

**Reader body size is user-controlled.** A range of 14–24px is exposed in Settings. Default 18px desktop, 17px mobile.

### Spacing

The design does not use a strict spacing scale; values are picked from `{ 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 52, 60, 80 }` (px). When implementing in a system with a token scale, round to the nearest 4px and use scale tokens.

Container max-widths:
- Reader body column: **620px** desktop, **660px** mobile (full bleed minus 24px gutter)
- Subscriptions page: **720px**
- Settings page: **640px**

### Radii

This design uses **very subtle rounding** — 2–4px corners, no large pill shapes except the bottom-tab-bar tap targets and badge dots.

| Use | Radius |
|---|---|
| Cards, buttons, inputs, panels | 4px |
| Thumbnail placeholders | 2px |
| Feed-color dots, unread dots | 50% (circle) |
| Brand-mark outer ring | 50% (circle) |
| Filter chips (mobile) | 99px (pill) |

### Shadows

**No drop shadows.** The design uses 1px hairlines on `border` tokens instead. The only "lifted" surface treatment is the bottom tab bar on mobile, which uses `backdrop-filter: blur(24px)` and a faintly transparent `panel` background — see Mobile / Tab bar below.

### Iconography

The design uses **unicode glyphs** rather than an icon library, to keep the tonal feel:

| Use | Glyph |
|---|---|
| Star (saved / starred) | `★` |
| Menu | `≡` |
| Search | `⌕` |
| Refresh | `↻` |
| Share | `⎙` |
| Open externally | `↗` |
| Today / inbox tab | `◔` |
| Feeds tab | `⌒` |
| Settings tab | `◌` |
| Chevron-right (list disclosure) | `›` |
| Unread dot | filled circle 6×6 in `accent` |

Replace with proper icons from the codebase's icon set in production — but match weight (1.5px outlined where possible) and keep the visual quietness.

## Screens

### Layout overview

**Desktop (≥1100px wide)** — three columns:

```
┌───────────┬───────────────┬───────────────────────────┐
│           │               │                           │
│  Sidebar  │  Article list │   Reader pane             │
│  (220px)  │  (380px)      │   (fills)                 │
│           │               │                           │
└───────────┴───────────────┴───────────────────────────┘
```

Sidebar and article list are independently scrollable. The reader pane is independently scrollable and centered within its column (max-width 620px).

On Subscriptions and Settings screens, the article-list + reader area collapses into a **single content area** that takes the full remaining width.

**Mobile (single-column)**:

```
┌──────────────────────┐
│  Status bar (OS)     │ ← Native status bar
├──────────────────────┤
│  Feed header         │ ← Custom (menu / wordmark / search)
│  + page title        │ ← Large title row when on Feed/Saved
├──────────────────────┤
│  Filter chips        │ ← Only on Feed/Saved
├──────────────────────┤
│  Article list        │ ← Scrolls
│                      │
│                      │
├──────────────────────┤
│  Tab bar             │ ← Sticky bottom (Today/Saved/Feeds/Settings)
├──────────────────────┤
│  Home indicator (OS) │
└──────────────────────┘
```

Tapping an article pushes a full-screen reader; tab bar hides. A back button (← Feed name) in the reader header returns to the list.

---

### Screen 1 · Feed (Desktop)

**Purpose:** Browse latest articles from all subscribed feeds, or filter to a single feed via the sidebar.

#### Sidebar (220px, full height, `panel` background, 1px right border in `border`)

Top to bottom, each block separated by a 1px `border` divider:

1. **Brand mark** — 20/20 padding-top, 18px horizontal. A 22×22 circle outlined 1.5px in `ink`, containing a 6×6 dot in `accent`, followed by the wordmark "Feed" in serif 17/500 −0.01em letter-spacing. 10px gap between mark and wordmark.

2. **Primary nav** (4px padding, gap 1px between items). Each item is a button: 6/10 padding, 4px radius. Label left, count right (11px `muted`, tabular-nums). Active state: `accentSoft` background, `accent` text. Items: **All articles** (count = total unread), **Starred** (count = starred items), **Subscriptions** (count = number of feeds), **Settings** (no count).

3. **Folder list** (10px padding, scrollable). Folder header is 10/0.1em-uppercase in `ink3`, padding 4/10. Below it, each feed is a row: 5/10 padding, 4px radius, 12.5px sans font. Layout: a 6×6 colored dot at left (per-feed hue, see "Feed colors" below), feed name (truncated with ellipsis), unread count right-aligned in 10.5px `muted`. Active feed: `accentSoft` background, `accent` text. Folders shown in the design: **Craft**, **Tech**, **Reading**.

4. **Footer** (12/18 padding, 1px top border). Sans 11px, `ink3`. Left: `Synced 2m ago`. Right: refresh glyph `↻`.

#### Article list (380px, `bg` background, 1px right border in `border`)

- **Sticky header** (22/22 padding-top, 14 padding-bottom, `bg` background, 1px bottom border). Serif 22/500 title (e.g. "All articles") + sans 12px `ink3` subtitle (e.g. "12 unread · 24 total"). Title swaps when a feed is selected (feed name + author).
- **List rows** — each row is a `<button>` with full-row click target. Default padding 14/20; **compact** 10/18; **comfy** 20/22. Bottom border in `border` between rows.
- **Row contents** (top to bottom, 6px gap):
  - Meta line (sans 11px `ink3`): colored dot, feed name (500, `ink2`), `·`, time ago. Right-aligned: star icon `★` in `accent` (if starred), or 6×6 unread dot in `accent` (if unread, not starred).
  - Title (serif 17/500 −0.01em, `ink`).
  - Excerpt (sans 12px `ink2`, line-height 1.4, clamped to 2 lines; hidden in compact density).
  - If **card view** OR density is **comfy**, a 64×64 striped thumbnail (per-feed hue) shows to the left of a 2-line excerpt.
  - Min-read line (sans 10.5px `ink3`, tabular-nums): `8 min read`.
- **Selected row**: `panel` background, plus a 2px-wide vertical `accent` bar pinned to the left edge.

#### Reader pane (fills remaining width)

- **Empty state**: centered, serif italic 16px `ink3`. A 32px em-dash glyph in `ink2`, then "Select an article to begin reading."
- **Article view**: max-width 620px, padding `52px 48px 80px`. Top-to-bottom:
  - Meta row (sans 11.5px uppercase 0.06em letter-spacing, `ink3`): feed dot + feed name (`ink2`) + `·` + author. Right-aligned (no uppercase, no tracking): `2h ago · 11 min read`.
  - H1 (serif 36/500 −0.02em line-height 1.12, `ink`), 24px below meta.
  - Dek (serif italic 18px 1.45 `ink2`), 14px below H1.
  - Action row, 32px below dek: `★ Star`, `↗ Open`, `⎙ Share` left-aligned; `Aa` right-aligned. Each is a 6/12 padded button, 4px radius, 1px `border`, `panel` background, sans 12px `ink2`. Gap 8px.
  - Body (serif, font-size = user setting, 1.65 line-height, paragraph spacing 1.1em, `text-wrap: pretty`).
  - Footer (44px above body end, 24px top padding, 1px top border, sans 12px `ink3`): "End of article" left, feed URL right.

---

### Screen 2 · Reader (Desktop)

Same layout as Feed — selecting a row updates the reader pane in place. There is no separate "Reader" route; the reader is always the rightmost pane on Feed/Starred/per-feed views.

---

### Screen 3 · Subscriptions (Desktop)

Sidebar unchanged. The article list + reader area is replaced by a single full-width content surface (max-width 720px centered).

- **Header row** (28px below page top, 28px below itself): Serif H1 28/500 "Subscriptions" left; "+ Add feed" button right (8/14 padding, 4px radius, solid `accent` background, `onAccent` text, sans 12px 500, no border).
- **Search bar** (10/14 padding, 1px `border`, 4px radius, `panel` background, 24px below header, 8px gap): search glyph `⌕` in `ink3`, input ("Search subscriptions or paste a URL…"), right-aligned `12 feeds` text in 11px `ink3`.
- **Feed rows** (14px top/bottom padding, 1px `border` between):
  - 36×36 square avatar with 4px radius. Background `oklch(0.85 0.05 <hue>)`, foreground letter `oklch(0.35 0.08 <hue>)`, serif 16/500.
  - Name (serif 16/500), `·`, tag in 11px `ink3` (e.g. "Design", "Tech").
  - Sub-line: URL in 11.5px `ink3`.
  - Right column: folder name (11px `ink3`, 64px wide right-aligned), unread count `4 new` (11px `ink3`, 60px wide tabular-nums), overflow glyph `⋯` (button, 4/8 padding).

---

### Screen 4 · Settings (Desktop)

Sidebar unchanged. Content area max-width 640px centered.

- **H1** "Settings", serif 28/500.
- **Sections**, each preceded by an uppercase label (11px 0.1em letter-spacing `ink3`): **Reading**, **Sync**, **Account**.
- **Settings rows** (18px top/bottom padding, 1px `border` between, 24px gap between label/control):
  - Left: label (sans 14/500 `ink`), max-width 360px, optional hint below (sans 12px 1.45 `ink3`).
  - Right: a control. In the design, only one control type exists — a **segmented control**: a 1px `border`-outlined row of options, each 6/12 padded. Active segment: `ink` background, `panel` text. Inactive: transparent background, `ink2` text.
- **Specific rows shown:**
  - Reading → Mark as read on scroll (Off/On, value On)
  - Reading → Reader theme (Paper/Soft/Dim, value Paper)
  - Reading → Default sort (Newest/Priority, value Newest)
  - Sync → Refresh interval (15m/1h/6h/Manual, value 1h)
  - Sync → Keep articles (30d/90d/1y/∞, value 90d)
  - Account → Signed in as: text "local · this device"
  - Account → Import OPML: a "Choose file…" button (same style as reader action buttons)

---

### Mobile screens

A single component handles iOS and Android. The only differences in production should be:

- **Top inset** under the OS status bar. iOS: 56px (clears Dynamic Island). Android: 14px (status bar is in-flow above content).
- **Bottom inset** for the home indicator / nav gesture: 30px below the tab bar in both cases.
- **Use platform-native components** for the back button affordance and overflow menus; the unicode glyphs in the prototype are placeholders.

#### Mobile · Feed / Saved

Header (22/22 padding, `bg` background, 1px bottom border):
- Top row: menu glyph `≡` left (36×36 hit area), brand mark + wordmark centered, search glyph `⌕` right. 16px below.
- Large title (serif 30/500 −0.02em line-height 1.05). 6px below.
- Subtitle (sans 12px `ink3`): "12 unread · 24 total".

Filter chip row (12/22 padding, horizontally scrollable, 6px gap, 1px bottom border): chip is 6/12 padded, 99px radius, sans 12px, white-space nowrap. Active chip: `ink` background, `panel` text. Inactive: `panel` background, `ink2` text, 1px `border`. Chips shown: All · Unread · Long reads · Short reads · Today.

Article rows (full width, 16/22 padding default; 12/22 compact; 20/22 comfy; 1px bottom border):
- Same meta + title + excerpt + min-read structure as desktop, slightly smaller type (title 18px default, 16px compact).
- In card view or comfy density, a 56×56 thumbnail joins the layout.

#### Mobile · Reader

Top bar (sticky, 56px top inset on iOS / 14px on Android, 16px horizontal, 12px bottom padding, `bg` background, 1px bottom border):
- Back button left: `← {feedName}` in 14px `accent`, padded 4/6.
- Right cluster (4px gap): three small buttons (Aa, ★, ⎙), each 6/10 padded, 4px radius, 1px `border`, `panel` background, 12px `ink2`.

Body (24/24/80 padding, serif):
- Meta line: sans 10.5px uppercase 0.08em `ink3`. "Field Notes · M. Quinn · 2h".
- H1 serif 26/500 1.15 −0.02em, 12px below.
- Dek serif italic 16/1.5 `ink2`, 22px below.
- Body serif user-size 1.65, paragraphs 1.1em apart, `text-wrap: pretty`.
- Footer (28px below, 18px top padding, 1px top border, sans 11px `ink3`): "End of article" left, feed URL right.

#### Mobile · Subscriptions

Header same pattern as Feed (large title "Feeds", subtitle "7 subscriptions").

Search bar (14/22 padding, 1px `border`, 4px radius, `panel` background, 10/14 inner padding, 8px gap).

Folder groups (14/22 padding-top, 6px padding-bottom for the label; sans 10px 0.1em uppercase `ink3`). Each feed row inside (12/22 padding, 1px `border` bottom, 14px gap, `bg` background):
- 34×34 letter avatar (4px radius, hue-tinted).
- Name (serif 15/500), URL (sans 11px `ink3`, ellipsis-clipped) below.
- Right: unread count (sans 11px `ink3`, tabular-nums).

#### Mobile · Settings

Header pattern matches above ("Settings", subtitle "Personal · this device").

Groups: same uppercase label header (14/22 padding-top, sans 10px 0.1em uppercase `ink3`). Each row (14/22 padding, 1px `border` bottom, `bg` background):
- Left: label (sans 14px `ink`).
- Right: value (sans 13px `ink3`) + a small chevron `›` in `muted`. The whole row is tappable; tap opens a native picker.

Rows shown (grouped):
- **Reading**: Mark as read on scroll (On), Reader theme (Paper), Default sort (Newest)
- **Sync**: Refresh interval (1h), Keep articles (90 days)
- **Account**: Import OPML (Choose…), About Feed (v1.0.0)

#### Mobile · Tab bar

Sticky bottom. Absolute-positioned over content, 30px bottom padding (home indicator clearance). Background: `panel` at 94% opacity. **`backdrop-filter: blur(24px)`** (and `-webkit-backdrop-filter`). 1px top border in `border`. Four equally-sized tabs, each containing a serif glyph (18px) above a sans 10/500 label, with 3px gap. Active: `accent` color. Inactive: `ink3`. Tabs: **Today** (`◔`), **Saved** (`★`), **Feeds** (`⌒`), **Settings** (`◌`).

In production, replace the unicode glyphs with native-platform icons (SF Symbols on iOS, Material Symbols on Android).

---

## Per-feed colors

Each feed carries a **hue value** (0–360) used only for two small accents:
- The 6×6 dot in the sidebar and article-list meta line: `background: oklch(0.65 0.12 <hue>)`.
- The avatar in subscription rows: `background: oklch(0.85 0.05 <hue>)` with text `oklch(0.35 0.08 <hue>)`.
- The placeholder thumbnail in card view: stripes alternating `oklch(0.90 0.03 <hue>)` and `oklch(0.85 0.04 <hue>)`.

Hue values used in the design (one per feed; arbitrary aesthetic assignment):

| Feed | Hue |
|---|---|
| Field Notes | 22 |
| The Loop | 215 |
| Cold Take | 0 |
| Atlas | 152 |
| The Garden | 88 |
| Frequencies | 285 |
| The Plot | 38 |

In production, persist `hue` as a per-feed user-editable attribute (default deterministic from feed-id hash).

## Interactions & behavior

### Navigation

**Desktop:**
- Clicking a primary nav item (All articles / Starred / Subscriptions / Settings) deselects any currently-selected feed and navigates to that screen.
- Clicking a feed name in the sidebar selects that feed and switches to the Feed screen filtered to its articles, regardless of the previous screen.
- Clicking an article row in the list updates the reader pane in place. The list does not navigate.
- There is **no separate URL/route per article in the prototype** — implement deep linking in production (`/feed`, `/feed/:feedId`, `/article/:articleId`, `/saved`, `/subscriptions`, `/settings`).

**Mobile:**
- Tab bar switches between Today / Saved / Feeds / Settings (no per-feed filter on mobile in this design; if needed, expose via a "filter by feed" sheet from the Today header).
- Tapping an article pushes a full-screen reader. The tab bar is hidden in the reader. The back button (top-left of reader header) pops back to the list, preserving scroll position.

### State

Top-level state needed:

```
screen:          'feed' | 'starred' | 'subs' | 'settings'
selectedFeedId:  string | null           // filters the feed list
openArticleId:   string | null           // null on mobile = list view, set = reader view
                                          // desktop: always set to last-selected article
fontSize:        number (px, 14..24, default 18)
density:         'compact' | 'regular' | 'comfy'
viewMode:        'list' | 'card'
```

Per-article state (persisted): `read`, `starred`. The prototype hard-codes these but the model is straightforward.

Per-feed state: `unread` count (derived), `folder` (string), `tag` (string), `hue` (number), `author`, `url`.

### Transitions

The prototype uses **no animations**. The push transition from feed → reader on mobile should use the platform default (iOS push-from-right, Android shared-axis or fade-through). Other transitions: none required.

### Loading / empty / error states

The prototype shows one empty state: the reader pane when no article is selected (desktop). Use this pattern for analogous states:

- **No articles** in current filter: centered serif italic 16px `ink3`, optional em-dash glyph above, copy: "Nothing here yet."
- **Loading**: not designed; use a 1px progress line in `accent` at the very top of the content area, fading in after 200ms.
- **Sync error**: replace the sidebar footer "Synced 2m ago" with "Last sync failed · retry" in `ink2`, with `retry` as an `accent` text link.

### Density / view-mode / font-size

Density and view-mode apply to **article lists** only. Font-size applies to the reader body only. These are persisted to user preferences and apply globally.

- `compact` density: tighter row padding, no excerpt visible.
- `regular`: 2-line excerpt.
- `comfy`: 2-line excerpt + 64×64 thumbnail.
- `card` view (independent of density): forces thumbnail on, slightly heavier padding.

## Copy

**All product copy in the design is final.** Notable strings:

- Brand name: **Feed**
- Empty reader: "Select an article to begin reading."
- Sidebar footer: "Synced 2m ago"
- Subscriptions H1: "Subscriptions" / button "+ Add feed" / placeholder "Search subscriptions or paste a URL…"
- Settings groups: "Reading" / "Sync" / "Account"
- Mobile filter chips: "All", "Unread", "Long reads", "Short reads", "Today"

The seed feed names and article titles in `data.jsx` are placeholders — replace with real user data; the strings exist only to make the layout look populated.

## Assets

**No bitmap or vector assets.** The design uses:
- 2 web fonts loaded from Google Fonts (Source Serif 4 + IBM Plex Sans).
- Unicode glyphs for all icons (to be replaced by the codebase's icon set in production).
- Procedural striped/colored placeholder thumbnails (`oklch()` gradients keyed off per-feed hue). Replace with real OG-image fetches if a feed provides one, otherwise keep the procedural fallback.

## Files in this bundle

- `design-files/index.html` — final canvas showing the Paper palette on desktop + iPhone + Android. Open in a modern browser; everything is self-contained (React + Babel via CDN, fonts via Google Fonts).
- `design-files/data.jsx` — seed feeds + articles + the long-form body string used in the reader. **The feed/article shapes are the canonical data model.**
- `design-files/prototypes/editorial.jsx` — **desktop** components and the `ED_PALETTES` token map. The `paper` key is the locked palette; the other keys are historical declinations and can be deleted in production.
- `design-files/prototypes/editorial-mobile.jsx` — **mobile** components (shared between iOS + Android via a `topInset` prop).
- `design-files/design-canvas.jsx`, `browser-window.jsx`, `ios-frame.jsx`, `android-frame.jsx`, `tweaks-panel.jsx` — **presentation-only** scaffolding (canvas, device frames, tweaks panel). **Do not port these to production.** They exist only so this handoff bundle is openable as a standalone file.

## Implementation notes for the developer

1. **Re-token the palette in your styling system** (CSS variables, Tailwind config, `:root` custom props, or whatever the codebase uses). All eleven tokens are listed above.
2. **Wire the type ramp** as text styles / utility classes. Don't inline every size/weight/letter-spacing combination at the component level.
3. **Don't ship `style={...}` inline objects** — the prototype uses them for speed; the production code should use the codebase's normal styling approach.
4. **Replace unicode icon glyphs** with the codebase's icon set (Heroicons / Lucide / Material Symbols / SF Symbols / etc). Match weight to outlined-1.5px where possible.
5. **The mobile tab bar's `backdrop-filter: blur(24px)`** is the only visually critical effect in the design — preserve it on platforms where it's free (iOS uses `UIVisualEffectView` natively; web supports it directly). On Android, fall back to an opaque `panel` background — do not attempt to recreate it with overlays.
6. **The selected-row indicator** on desktop is a 2px `accent` bar pinned to the left edge of the row, drawn inside the row's bounding box (not a left border of the row, which would shift the row contents). Implement with absolute positioning or `box-shadow: inset 2px 0 0 <accent>`.
7. **Font fallback handling** — Source Serif and IBM Plex are large fonts; size-adjust the fallbacks via `@font-face` `size-adjust` if FOUT shifts feel jarring. The fallback stacks above are chosen to be close in x-height.

If anything in this document is ambiguous, the HTML prototypes are the source of truth for visual fidelity — match them pixel-faithfully.
