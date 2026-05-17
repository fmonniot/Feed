# Feed — Integration guide

This is the **developer-facing** half of the handoff. It explains how to wire the design in `VISUAL_SPEC.md` into a real codebase — file layout, state shape, navigation model, platform gotchas, and what to take from the prototype vs. what to ignore.

**Read this alongside:**

- `VISUAL_SPEC.md` — pure visual reference, codebase-agnostic, no behaviour.
- `prototype/index.html` — the running prototype. Open it in a browser; it is the canonical source of truth for any visual question this doc doesn't answer.
- `uploads/FEATURES.md` (project root) — the functional contract. Behaviour wins over both this doc and the visual spec when they disagree.

The prototype is a React + Babel single-page demo wrapped in a design-canvas. It is **not** the production architecture. This document tells you what to lift out of it.

---

## TL;DR — the contract

- **Two clients**, sharing a server: web (Kotlin/JS SPA) and Android (Jetpack Compose). iOS is out of scope.
- **One palette**, one type pairing, one accent. No themes.
- **No animations** except a refresh spinner and a 100ms hover transition on one button.
- **No drop shadows** on layout chrome — popovers only.
- **Per-feed hue is OKLCH-derived** at three lightness/chroma stops; persist `hue` per feed, not the derived colours.
- **The mobile tab bar's blur + 94% panel is the design.** Don't flatten on web; do flatten on Android (no faked blur).
- **The desktop selected-row indicator is a 2px inset bar**, drawn inside the row's bounding box.

---

## What to take from the prototype

The prototype's job is to show you exactly what to build. These pieces port directly into production:

| Take | From | Notes |
|---|---|---|
| Colour palette | `prototypes/editorial.jsx` (`ED_PALETTES.paper`) | Lift the 12 tokens into your theme system. Compose snippets in `VISUAL_SPEC.md`. |
| Type ramp | `prototypes/editorial.jsx`, `editorial-mobile.jsx` | Sizes / weights / letter-spacing per role. See VISUAL_SPEC. |
| Sidebar layout & per-feed hue dots | `EdSidebar` | Component shape is canonical. |
| Article list row | `EdArticleList`, `EdMArticleCard` | Density + view-mode logic ports verbatim. |
| Reader pane + font-size picker | `EdReader`, `EdMReaderScreen`, `EdFontSizePicker` | Pixel values are intentional — preserve them. |
| Subscriptions page (Rename / Delete flows) | `EdSubsScreen` | SUBS-4's "render above rows" and "pre-select" rules are baked into the prototype; copy them. |
| Settings page (segmented control) | `EdSettings`, `EdMSettingsScreen` | Web and Android Settings are **deliberately asymmetric** — see Settings asymmetry below. |
| Login screens | `prototypes/login.jsx` (`LoginDesktop`, `LoginMobile`) | The whole layout, including the IME hints on mobile. |
| Mobile tab bar | `EdMTabBar` | Including the `backdrop-filter` + 94% alpha — see The mobile tab bar. |
| Seed `data.jsx` | top-level `data.jsx` | Useful as fixture for tests; not for prod. |

## What to ignore from the prototype

These pieces exist purely so the prototype can render itself inside a design-canvas. **Do not port them.**

| File | What it does | Why ignore |
|---|---|---|
| `prototype/index.html` (the `App()` component, `DesignCanvas` usage, `Chips`, `DesktopFrame`) | Lays the prototypes out on a Figma-ish canvas with paint-chip cards and a "Read me" note. | Presentation chrome. None of this ships. |
| `prototype/design-canvas.jsx` | Pan/zoom design canvas + sections + artboards. | Tool for showing options side by side. |
| `prototype/browser-window.jsx` | Fake browser chrome (tabs, URL bar, traffic lights) wrapping the desktop artboard. | Mock chrome. Web app has no fake chrome. |
| `prototype/android-frame.jsx` | Fake Android device bezel. | Mock chrome. Real Android app draws to the device. |
| `prototype/tweaks-panel.jsx` | The floating "Tweaks" panel and its `useTweaks` hook. | Designer/PM convenience. Real apps wire prefs into Settings only. |
| The `TWEAK_DEFAULTS` block | A second source of truth for prefs during prototype iteration. | Replace with your real preference store. |
| The `state` tweak (`normal / empty / sync-failed / auth-error / loading`) | Forces conditional UI for presentation. | Drive states from real network responses in prod. |

If something in the prototype reads `tweak.X` or `setTweak(...)`, it's reading the shared prefs store — replace those with reads/writes against your real preference layer (web: localStorage-backed observable; Android: DataStore).

---

## Recommended build order

The functional spec (`FEATURES.md`) gives you scenario IDs as a checklist. Build in roughly this order — each step lights up one cluster of scenarios:

1. **Theme foundation** — palette + type + per-feed-hue helper. No screens yet. Validates that your token system can express the design (it should be boring).
2. **Sidebar** + **article list** + **reader pane** on **web**, hard-coded against `data.jsx` (or your equivalent fixture). Goal: render FEED-3 / FEED-4 / READ-1 / READ-6 visually. No real API yet.
3. **Routing** — wire `/unread`, `/all`, `/feed/:id`, `/article/:id`, `/subscriptions`, `/settings`, `/sign-in`. The reader updates the URL but never changes the layout columns (READ-1).
4. **Real article API** + **selection state** + **read state** (FEED-1 / FEED-1a / FEED-2 / FEED-8 / READ-7). The read-state surface lives in two places — the row dot and the reader action button — both off the same source of truth.
5. **Auth** — login screen, session persistence, AUTH-1 through AUTH-4. AUTH-5 (debounced redirect on stale session) needs a top-level fetch interceptor; wire it now while the auth shape is fresh.
6. **Settings (web)** — segmented controls, live application to the reader, persistence (SET-1 / SET-2 / SET-3 / SET-4). The settings store is the same store that drives the renderer; the prototype's `useTweaks` is one valid shape (a simple observable map).
7. **Subscriptions (web)** — Add / Search / Rename / Delete (SUBS-1 → SUBS-5). The Rename overflow has two web-specific requirements baked into the design — render-above and pre-select; the prototype implements both.
8. **Sync state + footer** — "Synced … ago" and "Last sync failed · retry" (FEED-7 / ERR-1). Now the sidebar's bottom-left feels alive.
9. **Android shell** — bottom tab bar, four routes, Compose `NavController` with the Reader as a pushed destination over the tab shell (READ-1b / READ-1c).
10. **Android article list + reader** — match the web behaviour. Pull-to-refresh (FEED-6) and the `↩` mark-unread button (READ-7) land here.
11. **Android Settings** — note the asymmetric row set vs. web. Server URL belongs on Android only.
12. **OPML import** (SET-5) and the back-half of the Settings reference: retention sweep (SET-8) and refresh interval (SET-9). These are mostly server-side; the client just submits the choice.

After step 4 the product is usable. Everything past it is filling in scenarios.

---

## Web stack — suggested file layout

The prototype is React, but it is **not the web stack**. FEATURES.md describes the web client as a Kotlin/JS SPA. Translate the prototype's components into your stack's idioms; the shapes below are framework-agnostic.

```
web/
  theme/
    tokens.css            // The :root custom-properties from VISUAL_SPEC
    typography.css        // Font imports + .ramp utility classes (or a JS module)
    hues.ts               // oklch helpers + per-feed colour memoizer
  app/
    routes.ts             // /unread, /all, /feed/:id, /article/:id, /subscriptions, /settings, /sign-in
    auth/                 // Login form, session persistence, fetch interceptor (AUTH-5)
    prefs/                // The shared preference store — fontSize, density, viewMode, markOnScroll, refresh, retention
    sync/                 // "Synced … ago" footer, retry, ERR-1
  features/
    feedlist/             // Sidebar (primary nav + folders + sync footer)
    articles/             // List header, list rows, density/view variations
    reader/               // Reader pane, empty state, action group, font-size popover
    subscriptions/        // Page + add form + search + rows + overflow menu
    settings/             // Page + grouped rows + segmented control
  shared/
    components/           // SegmentedControl, IconButton, BorderInput, OverflowMenu, ConfirmDialog
    icons/                // Replaces unicode glyphs with real icons (see VISUAL_SPEC)
```

### CSS architecture

Use **CSS custom properties at `:root`** for the palette and a small set of layout constants. Don't reach for a runtime theme provider — there is only one theme, and the visual stability of the palette is the design.

```css
/* tokens.css — verbatim from VISUAL_SPEC */
:root {
  --bg: #f3f5f7;
  --panel: #f9fafb;
  --border: rgba(20, 25, 40, 0.08);
  --border-strong: rgba(20, 25, 40, 0.16);
  --ink: #1a1f28;
  --ink-2: #4a5160;
  --ink-3: #7c8290;
  --muted: rgba(20, 25, 40, 0.5);
  --accent: #566073;
  --accent-soft: rgba(86, 96, 115, 0.10);
  --on-accent: #f9fafb;
  --danger: #a05050;

  --sidebar-w: 220px;
  --list-w:    380px;
  --reader-max: 620px;
  --subs-max:  720px;
  --settings-max: 640px;
}
```

Spacing is hand-picked, not scale-tokenised; either inline values or a small set of `--space-N` properties is fine. The prototype uses inline values; that is fine production code too if your stack supports it.

### Routing / URL model

The web client is a single-page app served by the Rust server on the same origin (FEATURES.md). URLs:

| Route | Renders |
|---|---|
| `/sign-in` | `LoginDesktop` only — no sidebar, no chrome. |
| `/unread` | Sidebar + article list (Unread filter) + reader (empty or selected). Default after login. |
| `/all` | Same shape, All filter. |
| `/feed/:id` | Same shape, filtered to one feed. |
| `/article/:id` | A side-effect of selecting an article. Layout doesn't change; the reader fills with the article and the URL updates so it's deep-linkable (READ-1). |
| `/subscriptions` | Sidebar + Subscriptions content (no article list, no reader). |
| `/settings` | Sidebar + Settings content. |

Selecting an article anywhere in the list updates `/article/:id` **without** swapping the underlying route — you don't "navigate to the reader," the reader is always rendered. The URL is the selection model. On a fresh route with no `:id`, the reader shows its empty state.

### State shape (web)

The prototype keeps a tiny set of states; production needs them split.

```ts
// Auth — singleton observable; the fetch interceptor reads `session`.
interface AuthState { session: 'unknown' | 'present' | 'absent'; user?: { name: string } }

// Prefs — the shared store both Settings UI and the renderer read from.
// All keys live in localStorage (web) / DataStore (Android).
interface Prefs {
  fontSize: 14 | 16 | 18 | 20 | 22 | 24;     // px (web) / sp (android)
  density:  'compact' | 'regular' | 'comfy';
  viewMode: 'list' | 'card';
  markOnScroll: boolean;
  refresh:   '15m' | '1h' | '6h' | 'Manual';
  retention: '30d' | '90d' | '1y' | '∞';
  // android-only
  serverUrl?: string;
}

// Data — derived from server responses, cached client-side.
interface Feed { id: string; name: string; author: string; url: string; hue: number; folder: string; unread: number }
interface Article { id: string; feed: string; title: string; excerpt: string; link: string; minutes: number; publishedAt: string; isRead: boolean }

// Sync — singleton observable powering the sidebar footer.
interface SyncState { lastSyncAt?: number; status: 'idle' | 'syncing' | 'failed' }
```

There is **no client-side `starred` flag, no `tag` column, no priority field, no theme key.** If you find one in older code, delete it (FEATURES.md ticket #35).

### The mark-read affordance

Both the row-level `✓` button (FEED-8) and the reader-level toggle (READ-7) fire the same `PUT /v1/articles/{id}/read` with the inverted flag. The button's label/glyph swaps with the article's current read state; the row's unread dot disappears immediately on optimistic update; the Unread badge decrements. On the Unread route the article stays in place until the next refresh — don't optimistically remove it.

### The mobile tab bar on web — there isn't one

The web client does **not** render a bottom tab bar at any viewport width. The sidebar is the only nav surface. Don't add a tab bar as a "mobile web" affordance.

---

## Android stack — Jetpack Compose

FEATURES.md describes the Android client as Compose-only. The visual spec contains Compose colour snippets and a sketch of the typography mapping; this section covers the structural decisions.

### Suggested module layout (single module is fine for a project this size)

```
app/src/main/kotlin/feed/
  theme/
    FeedColors.kt          // Color objects + colour scheme
    FeedTypography.kt      // Two FontFamily defs + a Typography{} mapping
    OklchHue.kt            // Per-feed hue → 5 colours, memoized
    FeedTheme.kt           // The @Composable wrapper
  ui/
    components/
      SegmentedControl.kt
      MarkReadButton.kt    // The 28×28 ✓ button
      FeedDot.kt           // The 6×6 hue dot
      FeedAvatar.kt        // 34×34 letter avatar
      StripedThumbnail.kt
      BottomTabBar.kt
    screens/
      ArticleListScreen.kt
      ReaderScreen.kt
      FeedsScreen.kt
      SettingsScreen.kt
      LoginScreen.kt
  navigation/
    FeedNavGraph.kt        // Tab routes + the pushed Reader destination
  data/                    // Repositories, API, DataStore prefs
```

### Compose theme

There's exactly one theme. Wrap the app once:

```kotlin
@Composable
fun FeedTheme(content: @Composable () -> Unit) {
  val colors = lightColorScheme(
    background = FeedColors.bg,
    surface = FeedColors.panel,
    onBackground = FeedColors.ink,
    onSurface = FeedColors.ink,
    primary = FeedColors.accent,
    onPrimary = FeedColors.onAccent,
    error = FeedColors.danger,
    // ...
  )
  MaterialTheme(
    colorScheme = colors,
    typography = FeedTypography.compose,
    shapes = Shapes(  // 4dp across the board
      extraSmall = RoundedCornerShape(2.dp),
      small  = RoundedCornerShape(4.dp),
      medium = RoundedCornerShape(4.dp),
      large  = RoundedCornerShape(4.dp),
    ),
    content = content,
  )
}
```

### The No-Material rule

You can sit on top of Material 3 (it's the path of least resistance in Compose), but **the design is not Material.** Concretely, this means:

- **No FABs.** The mobile Feeds screen does not have a "+ Add feed" FAB in the design. If you need one for parity with web, draw your own in the spec's shape: a 4dp-radius button with `accent` background and `onAccent` text, not a Material FAB.
- **No NavigationBar.** The bottom tab bar uses `accentSoft` pill behind the active glyph and `accent` text colour — Material's `NavigationBar` doesn't match the shape, and trying to bend it produces a worse result than drawing the bar yourself with `Row`s. See `BottomTabBar.kt` shape in VISUAL_SPEC.
- **No Material elevation.** Almost no surface in the design has a shadow; `Surface(elevation = N.dp)` and `Card` will introduce shadows you have to clear out. Prefer `Box(modifier = Modifier.background(...).border(...))`.
- **No Material ripples on tap.** Use `Modifier.clickable(indication = null, ...)`. The design has no tap ripple. If you need press feedback, fade the background to `panel` for the duration of the press.
- **Material text fields are wrong.** The login form uses an underline-only input with no Material outline / label-float behaviour. Use `BasicTextField` with a custom `decorationBox`.

### Hue maths — OKLCH → ARGB

Compose has no OKLCH primitive. Convert once per feed at load and memoize. The full conversion is small; here's an inline reference (OKLCH → OKLab → Linear sRGB → sRGB → ARGB):

```kotlin
private fun oklchToArgb(L: Float, C: Float, hDeg: Float): Color {
  val h = Math.toRadians(hDeg.toDouble())
  val a = C * Math.cos(h).toFloat()
  val b = C * Math.sin(h).toFloat()
  val l_ = L + 0.3963377774f*a + 0.2158037573f*b
  val m_ = L - 0.1055613458f*a - 0.0638541728f*b
  val s_ = L - 0.0894841775f*a - 1.2914855480f*b
  val l = l_*l_*l_; val m = m_*m_*m_; val s = s_*s_*s_
  val r =  4.0767416621f*l - 3.3077115913f*m + 0.2309699292f*s
  val g = -1.2684380046f*l + 2.6097574011f*m - 0.3413193965f*s
  val b2 = -0.0041960863f*l - 0.7034186147f*m + 1.7076147010f*s
  fun g2l(c: Float) = if (c <= 0.0031308f) 12.92f*c else 1.055f*c.toDouble().pow(1.0/2.4).toFloat() - 0.055f
  return Color(
    red   = g2l(r).coerceIn(0f, 1f),
    green = g2l(g).coerceIn(0f, 1f),
    blue  = g2l(b2).coerceIn(0f, 1f),
  )
}

object FeedHue {
  fun dot(hue: Float)       = oklchToArgb(0.65f, 0.12f, hue)
  fun avatarBg(hue: Float)  = oklchToArgb(0.85f, 0.05f, hue)
  fun avatarInk(hue: Float) = oklchToArgb(0.35f, 0.08f, hue)
  fun stripeA(hue: Float)   = oklchToArgb(0.90f, 0.03f, hue)
  fun stripeB(hue: Float)   = oklchToArgb(0.85f, 0.04f, hue)
}
```

Wrap each function in a small `LruCache<Float, Color>` keyed on `hue` so the hot path (a long list of rows, each with a dot) doesn't recompute.

### Compose navigation model

Use a single `NavHost`. The four bottom-nav routes share the tab-shell `Scaffold`; the Reader route pushes **above** the shell so the bar disappears:

```kotlin
NavHost(navController, startDestination = "unread") {
  // Tab destinations — share a Scaffold with bottomBar = BottomTabBar(...)
  composable("unread")   { TabScaffold(...) { ArticleListScreen(filter = Unread) } }
  composable("all")      { TabScaffold(...) { ArticleListScreen(filter = All) } }
  composable("feeds")    { TabScaffold(...) { FeedsScreen() } }
  composable("settings") { TabScaffold(...) { SettingsScreen() } }
  // Reader — full screen, no tab bar (READ-1b)
  composable("article/{id}") { backStack ->
    ReaderScreen(articleId = backStack.arguments!!.getString("id")!!)
  }
  // Login — no tab bar, default after first install / logout
  composable("sign-in") { LoginScreen() }
}
```

`ReaderScreen` calls `navController.popBackStack()` from its back chevron; because the Reader sits on top of a tab destination, popping returns to the list at its scroll position (READ-1c).

### Prefs / DataStore

```kotlin
@Serializable
data class FeedPrefs(
  val fontSize: Int = 17,        // sp
  val density: Density = Density.Regular,
  val viewMode: ViewMode = ViewMode.List,
  val markOnScroll: Boolean = true,
  val refresh: RefreshInterval = RefreshInterval.OneHour,
  val retention: Retention = Retention.Ninety,
  val serverUrl: String = "http://10.0.2.2:3000/",
)
```

Expose as `Flow<FeedPrefs>` from a `PrefsRepository`. The Reader collects it; updating from Settings re-renders the Reader in place (SET-3).

### Per-platform Settings rows — the asymmetry

The Settings surface is **deliberately asymmetric** across platforms (FEATURES.md Settings reference). Don't try to unify them.

- **Web rows**: font size, density, mark-on-scroll, refresh, retention, OPML, About, Logout.
- **Android rows**: font size, density, mark-on-scroll, refresh, retention, **Server URL**, OPML, About, Logout.

Android needs Server URL because it talks to a configurable host (the dev default is `http://10.0.2.2:3000/`, the emulator's loopback to the host machine). The web client is served by the Rust server itself and is always same-origin — there is no Server URL row on web (FEATURES.md ticket #32). If you find one in older web code, delete it.

---

## Platform-specific gotchas

A short list of things the prototype gets right that you will have to be careful to preserve.

### 1. The inset-bar selected-row indicator (web)

The selected article row is marked by a **2px-wide vertical `accent` bar** pinned to the row's **left edge**, drawn *inside* the row's bounding box:

```css
.article-row { position: relative; }
.article-row.selected::before {
  content: "";
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 2px;
  background: var(--accent);
}
```

Common mistakes to avoid:

- A `border-left: 2px solid var(--accent)` will shift the row's content 2px right, making the title visibly jump between selected and unselected rows. **Never use a left border.** The inset bar is the only correct implementation.
- A box-shadow inset (`inset 2px 0 0 var(--accent)`) is shifted by the row's outer 1px `border` divider on some browsers. Use the pseudo-element.
- The bar should disappear entirely on unselected rows, not become transparent — `display: none` semantics, not `opacity: 0`. The 52px-wide unread cluster on the right of the meta line uses a parallel trick: a fixed-width slot that reserves space so the title never reflows between read and unread.

### 2. The mobile tab bar — backdrop-filter on web, flat on Android

```css
.tab-bar {
  position: absolute;
  left: 0; right: 0; bottom: 0;
  padding-bottom: 30px;     /* gesture clearance */
  padding-top: 6px;
  background: rgba(249, 250, 251, 0.94);    /* panel @ 94% */
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border-top: 1px solid var(--border);
  z-index: 20;
}
```

If you ship a mobile-web view, **both** the alpha and the `backdrop-filter` are required — neither alone produces the right effect. On Android (Compose), drop the blur and ship the alpha background only. Do not implement a fake blur by capturing the list and rendering it blurred behind the bar; the result looks worse than a flat translucent layer, performs worse, and creates a race between scroll and capture.

### 3. Hue persistence

Per-feed hue must be stable across reloads and identical for the same feed on web and Android. Two options that both satisfy FEED-5:

- **Server-assigned.** Server stamps each feed with a `hue` integer at creation; clients render whatever the server returns. This is the prototype's posture (`FEEDS[].hue`).
- **Deterministically derived client-side.** `hue = Math.abs(hashCode(feedId)) % 360`, computed identically on both clients. Cheaper to ship; loses per-feed editability without server changes.

Don't roll a `Random()` per session. The article list reloading should not reshuffle the dots.

### 4. The no-shadows rule

Two popovers carry shadows (font-size picker, subscription overflow). Everything else uses 1px hairlines on `border`. If a code review surfaces a `box-shadow` outside those two components, it is wrong.

Common temptations:

- "Floating action button needs a shadow." — There is no FAB.
- "The reader should feel elevated over the list." — It doesn't. 1px right border on the list column is the divider.
- "Selected row should have a small drop shadow." — The 2px inset bar is the selection signal; the row's `panel` tint is enough.

### 5. The no-animations rule

The prototype has zero CSS transitions on hover or selection. Two intentional exceptions:

- Mark-read button hover: `transition: border-color .1s, color .1s, background .1s` — keep this.
- Pull-to-refresh spinner: `animation: edSpin .8s linear infinite` — keep this.

If you discover yourself adding a `transition: all .15s ease` on a clickable element, the answer is no. The design's stillness is a feature.

### 6. The reader URL footer + `↗ Open` button

Both are **real `<a>` anchors** to `article.link`, `target="_blank"`, `rel="noopener noreferrer"` (READ-5). Don't wire them through a JS click handler that fakes a window.open — the rel attributes are doing real work, and right-click "Open in new tab" should work normally.

### 7. The subscription overflow menu — render-above and pre-select

SUBS-4 has two requirements the prototype encodes:

- **Render above adjacent rows.** The menu is an `absolute`-positioned element with `zIndex: 50`, anchored to its trigger. A `position: relative; overflow: hidden` ancestor will clip it; don't add one. Use a click-away catcher (`position: fixed; inset: 0; zIndex: 40`) to dismiss on outside click.
- **Prepopulate and pre-select the rename input.** Programmatically `focus()` then `select()` the input as soon as it appears so the user can immediately type a new name. The prototype does this on a `setTimeout(0)` after the input mounts.

### 8. Density renders three layouts, not one with toggles

`compact` is not "regular with the excerpt hidden via CSS." Compact:

- Uses a smaller title size (15/16px).
- Removes the excerpt **and** the thumbnail outright.
- Tightens row padding.

Conditional rendering — not a `display: none` — keeps the row height accurate for virtualization. If you later add a virtualized list (the prototype doesn't), measure each density's row height once and pass it to the virtualizer.

### 9. The auth-error path

AUTH-2: invalid creds shows the error and **keeps focus on the password field**. The prototype's login component achieves this by setting `autoFocus` on the Password field when `authError === true` (and otherwise on Username). Match this in production: after a failed submit, blur Username if focused, clear the password value, focus the password field. Don't redirect, don't show a toast.

### 10. The empty-state copy is one string

"Nothing here yet." appears in three places (article list empty, subscriptions search empty, mobile article list empty). It is **the same string** in the same style (centred serif italic 16px `ink3`) every time. ERR-2 makes this explicit. Use one component / one string constant.

---

## Testing & spec mapping

FEATURES.md is structured as scenarios with stable IDs (`FEED-3`, `READ-1c`, etc.). The recommendation in the spec is to **reuse those IDs in test method names** so the spec ↔ test mapping is greppable.

- Web: Playwright preferred. The scenarios map cleanly to `test.describe` blocks.
- Android: Compose UI tests. Use the same ID structure: `@Test fun FEED_3_clicking_a_row_selects_it() { … }`.

Run the prototype next to the test as you write each one — the prototype is the visual oracle.

---

## When this guide is ambiguous

`FEATURES.md` is the behavioural source of truth. `VISUAL_SPEC.md` (next to this file) is the visual one. The running `prototype/index.html` (also in this folder) is the visual fallback for anything the spec doesn't pin down. When all three disagree:

1. Behaviour: FEATURES.md wins.
2. Visual: the prototype wins.
3. Architecture and integration concerns (this doc): default to the **quietest, most-platform-idiomatic** choice and ship it; flag with a comment.

If you're ever unsure whether to add an affordance ("should there be a settings cog in the article list header?"), the answer is almost always no. The design's restraint is the product.
