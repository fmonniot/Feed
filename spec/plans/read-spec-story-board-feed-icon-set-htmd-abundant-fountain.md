# Integrate the new "Feed." logo (web + Android)

**Date:** 2026-05-31 07:51 PDT

## Context

The design storyboard [Feed Icon Set.html](../story-board/Feed%20Icon%20Set.html) (rendered from [feed-icon-set.jsx](../story-board/feed-icon-set.jsx)) defines a new brand direction, **"Feed."** — purely typographic:

- **Wordmark:** the word `Feed` in **Source Serif 4, weight 500, letter-spacing −0.02em**, color `#1a1f28`, followed by an **accent dot** (`#566073`) sized **15% of font-size**, baseline-aligned, gap `0.07em`.
- **Mark (icon use), "F.":** a serif `F` (cap-height `h`) + dot sized **22% of `h`**, gap `0.09·h`, baseline-aligned.
- **App-icon tile:** rounded square, corner radius **22% of size**, background `#f3f5f7`, the `F.` mark at **~40% of size**, centered.
- **Dark variant:** bg `#1a1f28`, `F` `#f9fafb`, dot `#a0aabb`.

The current logo is an unrelated **green book/envelope** glyph (`app/.../drawable/app_logo.xml`, `assets/app-logo.*`) plus, on web, a **circle-outline + dot + "Feed"** sidebar mark. Both predate this direction.

**Key finding that narrows the work:** the app palette already *is* the storyboard palette on both platforms — web [tokens.css](../../web/src/jsMain/resources/css/tokens.css) (`--feed-bg #f3f5f7`, `--feed-ink #1a1f28`, `--feed-accent #566073`) and Android [Color.kt](../../app/src/main/java/eu/monniot/feed/ui/theme/Color.kt) (`PaperBg/PaperInk/PaperAccent` identical). So this is **not a recolor** — it is a **mark + icon-asset swap** plus two small in-app brand updates.

Goal: replace every logo/icon touchpoint (favicons, PWA icons, OG image, Android launcher/adaptive icon, web sidebar mark, Android login hero) with the typographic "Feed."/"F." mark, driven from a single generated source so all sizes stay consistent.

Scope confirmed with user: **full web set** (favicons + apple-touch + PWA manifest + OG image) **and** updating the **in-app marks**, not just launcher icons.

---

## The core challenge: glyph → vector/raster, font-independent

The mark is a Source Serif 4 glyph. SVG favicons and Android vector drawables can't rely on a loaded web font, and the local `rsvg-convert` won't have the font installed. So we **outline the glyphs once** and make outlined paths the single source of truth.

### Available local tooling (verified)
- `rsvg-convert` (homebrew) — SVG → PNG.
- `node` + `npx` — run `opentype.js` to extract glyph outlines; run `png-to-ico` to pack `.ico`. (No `fonttools`, no ImageMagick/Inkscape — avoid depending on them.)

### Generator script — `assets/feed-mark/generate.mjs` (new)
A self-contained Node ESM script (run with `npx --yes` for deps, or a tiny `assets/feed-mark/package.json`) that:

1. **Fetches Source Serif 4 Medium TTF** (OFL, Adobe `source-serif`) into `assets/feed-mark/.cache/` (cache; don't re-download). Pin a known URL/version in a comment.
2. **Extracts outlines** with `opentype.js`:
   - `F` glyph path → used by every `F.` mark.
   - the string `Feed` path → used by the wordmark / OG image.
   Normalize to a 0–108 viewBox for the mark and to pixel coords for the wordmark, applying the storyboard proportions (dot = 22%·capHeight for `F.`, 15%·fontSize for the wordmark; baseline alignment; gaps).
3. **Emits master SVGs** into `assets/feed-mark/` (committed, human-inspectable source of truth):
   - `mark.svg` — `F.` transparent (F `#1a1f28`, dot `#566073`).
   - `mark-mono.svg` — single-color `F.` (`#000`/currentColor) for monochrome/notification.
   - `tile-light.svg`, `tile-dark.svg` — rounded `#f3f5f7` (and `#1a1f28`) tile + centered `F.` (maskable-safe padding for PWA/Android, F. ≈ 40% / inside 72dp safe zone).
   - `wordmark.svg` — `Feed.` outlined, for OG.
   - `og.svg` — 1200×630 `#f3f5f7` canvas with centered wordmark.
4. **Rasterizes** with `rsvg-convert` and **packs** `.ico` with `png-to-ico`, writing the per-platform outputs listed below.

The script is idempotent; re-running regenerates every asset. Document how to run it in `assets/feed-mark/README.md`.

---

## Web changes (`web/`)

Static assets live in [web/src/jsMain/resources/](../../web/src/jsMain/resources/) (already serves `index.html` + `css/` at dist root). Drop new files at the resources root so they serve from `/`.

**New asset files (generated):**
- `favicon.svg` (from `mark.svg`), `favicon.ico` (16/32/48), `favicon-16.png`, `favicon-32.png`
- `apple-touch-icon.png` (180, light tile, no rounding — iOS masks)
- `icon-192.png`, `icon-512.png`, `icon-512-dark.png` (maskable tiles)
- `og-image.png` (1200×630, from `og.svg`)
- `site.webmanifest` — `name`/`short_name` "Feed", `icons` 192+512 (`purpose: "any maskable"`), `theme_color #566073`, `background_color #f3f5f7`, `display: standalone`

**Edits:**
- [index.html](../../web/src/jsMain/resources/index.html) `<head>`: add
  `<link rel="icon" href="favicon.svg" type="image/svg+xml">`, `<link rel="icon" href="favicon.ico" sizes="any">`,
  `<link rel="apple-touch-icon" href="apple-touch-icon.png">`, `<link rel="manifest" href="site.webmanifest">`,
  `<meta name="theme-color" content="#566073">`, plus OG/Twitter meta (`og:title`, `og:image` → `og-image.png`).
- [BrandMark.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/BrandMark.kt): replace the **circle-outline + inner-dot** structure with the new wordmark — `span[data-part="wordmark"].type-brand "Feed"` immediately followed by `div[data-part="dot"]` (the accent dot, ≈15% of brand font-size, baseline-aligned, `align-items: flex-end`). Drop the `data-part="mark"` circle. Update the KDoc.
- Verify the `.type-brand` rule in [tokens.css](../../web/src/jsMain/resources/css/tokens.css) still matches the storyboard (serif 500, −0.02em); adjust tracking only if needed.

**Test to update:** [BrandMarkTest.kt](../../web/src/jsTest/kotlin/eu/monniot/feed/web/ui/components/BrandMarkTest.kt) currently asserts a 22px `border-radius:50%` circle containing the dot. Rewrite its assertions for the new shape: wordmark text == "Feed", `.type-brand` class present, a `data-part="dot"` accent (`--feed-accent`) circle exists as a **sibling after** the wordmark, wrapper is flex. This keeps `:web:jsTest` green and validates the new DOM (satisfies the testing requirement for the BrandMark code change).

---

## Android changes (`app/`)

The app ships **adaptive-only** icons (`mipmap-anydpi/`, no density PNG buckets) — keep that; no legacy raster buckets to add.

**Drawable swaps (vector XML):**
- [drawable/app_logo.xml](../../app/src/main/res/drawable/app_logo.xml) → replace the green-book paths with the **`F.` mark** on a transparent 108×108 viewport: the outlined `F` path (`#1a1f28`) + a dot `<path>`/circle (`#566073`), sized/positioned per `mark.svg` so it lands within the 72dp safe zone (cap-height ≈ 41). Port the path data straight from the generated `mark.svg`.
- [drawable/ic_launcher_background.xml](../../app/src/main/res/drawable/ic_launcher_background.xml) → collapse the steel-blue grid to a single flat fill `#f3f5f7` (`M0,0h108v108h-108z`).
- **New** `drawable/app_logo_mono.xml` → single-fill `F.` (from `mark-mono.svg`) for the `<monochrome>` layer (themed icons tint it regardless of fill).

**Adaptive icon XML:**
- [ic_launcher.xml](../../app/src/main/res/mipmap-anydpi/ic_launcher.xml) and [ic_launcher_round.xml](../../app/src/main/res/mipmap-anydpi/ic_launcher_round.xml): keep `<adaptive-icon>`; point `<background>` at the flat `ic_launcher_background`, `<foreground>` at `app_logo` (drop the `inset` now that sizing is baked into the vector, or keep a small inset if visual check wants it), `<monochrome>` at `app_logo_mono`.

**In-app login hero:**
- [MainActivity.kt](../../app/src/main/java/eu/monniot/feed/MainActivity.kt) (~L264–270): replace the 100dp `Image(painterResource(R.drawable.app_logo))` + "Welcome to Feed" with the new brand wordmark — a small composable rendering serif **"Feed"** (using the existing `SourceSerif4` family / brand type in [Type.kt](../../app/src/main/java/eu/monniot/feed/ui/theme/Type.kt), weight 500, −0.02em) with a trailing accent-dot `Box` (`PaperAccent`, ≈15% of font-size, baseline-aligned). Keep or drop the now-redundant "Welcome to" line. This reuses the already-configured downloadable Google Font, so no new font asset is needed.

**Strings/manifest:** `app_name` ("Feed") and the manifest icon refs are unchanged.

**No notifications / no splash exist** → nothing to do there. (The `mark-mono`/notification-white asset is produced for completeness/future use but isn't wired up.)

---

## Cleanup
- Remove the stale old-logo files once nothing references them: `assets/app-logo.svg`, `assets/app-logo.png`, `assets/app-logo-orig.svg` (replaced by `assets/feed-mark/`). Confirm with `grep -r app-logo` first.

---

## Verification

**Automated (must stay green / updated):**
- `./gradlew :web:jsTest` — 257 tests; includes the **rewritten BrandMarkTest** validating the new mark DOM. *(validates the BrandMark code change directly)*
- `./gradlew :app:testDebugUnitTest` — 177 expected; confirms the MainActivity/login change compiles and Robolectric UI still renders. If a login-screen test asserts content, update it; otherwise this is the compile+render guard.
- `cd server && cargo test` — unaffected (`119 passed`), run once as a regression backstop.
- `./scripts/test-counts.sh all` for the quick roll-up.

**Asset / visual (manual — icons can't be unit-tested, per CLAUDE.md):**
- Re-run `assets/feed-mark/generate.mjs`; confirm every output file is produced and open `mark.svg`/`tile-*.svg`/`og.svg` to eyeball the glyph outline matches the storyboard proportions.
- **Web:** `./gradlew :web:jsBrowserDevelopmentRun` (or build + serve dist), then check: browser-tab favicon, `/favicon.svg` & `/site.webmanifest` load, DevTools → Application → Manifest shows 192/512 icons, sidebar shows "Feed" + trailing dot. Compare against the storyboard's *Browser Tab* / *Favicons* artboards.
- **Android:** build/install debug, verify the launcher adaptive icon (light + themed/monochrome), the round-mask variant, and the updated login hero. Compare against the storyboard's *Adaptive Icon* / *Home Screen* artboards.
- Quick A/B: open [Feed Icon Set.html](../story-board/Feed%20Icon%20Set.html) beside the running apps.
