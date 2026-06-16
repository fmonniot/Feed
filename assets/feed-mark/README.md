# Feed. brand mark

The **"Feed."** identity is purely typographic: the word `Feed` (or a standalone `F`)
in **Source Serif 4, weight 500**, followed by an accent dot. Proportions and palette
come from [`spec/story-board/feed-icon-set.jsx`](../../spec/story-board/feed-icon-set.jsx):

| token   | value     | use                        |
|---------|-----------|----------------------------|
| ink     | `#1a1f28` | the `F` / wordmark glyph   |
| accent  | `#566073` | the dot                    |
| bg      | `#f3f5f7` | icon tile background       |
| dark bg | `#1a1f28` | dark icon variant          |

- `F.` mark: dot = 22% of cap-height, gap = 0.09·cap, baseline-aligned.
- Wordmark: dot = 15% of font-size, gap = 0.07em, baseline-aligned.
- Icon tile: `F.` at 40% of tile size, centered.

## Regenerating the icon set

Everything below is produced from outlined glyphs, so no web font has to be present at
render time. The single source of truth is [`generate.mjs`](./generate.mjs).

```sh
cd assets/feed-mark
npm install           # opentype.js, png-to-ico, wawoff2
node generate.mjs     # needs rsvg-convert on PATH  (brew install librsvg)
```

The script downloads Source Serif 4 **Medium** (from `@fontsource`, decompressing the
woff2 to TTF) into `.cache/`, outlines the `F`/`Feed` glyphs, and writes:

**Master SVGs (here, committed for inspection):**
`mark.svg`, `mark-mono.svg`, `tile-light.svg`, `tile-dark.svg`, `wordmark.svg`, `og.svg`.

**Web** → `web/src/jsMain/resources/`:
`favicon.svg`, `favicon.ico`, `favicon-16.png`, `favicon-32.png`,
`apple-touch-icon.png`, `icon-192.png`, `icon-512.png`, `icon-512-dark.png`, `og-image.png`.

**Android** → `app/src/main/res/drawable/`:
`app_logo.xml` (adaptive foreground), `app_logo_mono.xml` (monochrome layer),
`ic_launcher_background.xml` (flat Paper background).

The generated `.xml`/asset files carry a "GENERATED" header — edit `generate.mjs`, not
the outputs. `.cache/` and `node_modules/` are git-ignored.
