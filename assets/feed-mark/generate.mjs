// Feed. brand icon-set generator.
//
// Single source of truth for every logo/icon touchpoint. The brand mark is a
// Source Serif 4 (weight 500) "F" + accent dot, per spec/story-board/feed-icon-set.jsx.
// SVG favicons and Android vector drawables can't rely on a loaded web font, so we
// OUTLINE the glyph once (opentype.js) and emit paths/PNGs that are font-independent.
//
// Run:  cd assets/feed-mark && npm install && node generate.mjs
// Requires: node 18+, rsvg-convert on PATH (brew install librsvg).
//
// Emits:
//   assets/feed-mark/                 master SVGs (mark, mono, tiles, wordmark, og)
//   web/src/jsMain/resources/         favicon.svg/.ico/.png, apple-touch, icon-192/512(-dark), og-image
//   app/src/main/res/drawable/        app_logo.xml, app_logo_mono.xml, ic_launcher_background.xml

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import opentype from 'opentype.js';
import pngToIco from 'png-to-ico';
import { decompress } from 'wawoff2';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE = path.join(__dirname, '.cache');
const REPO = path.resolve(__dirname, '..', '..');
const WEB = path.join(REPO, 'web', 'src', 'jsMain', 'resources');
const DRAWABLE = path.join(REPO, 'app', 'src', 'main', 'res', 'drawable');

// ── Palette (matches tokens.css / Color.kt) ────────────────────────────
const INK = '#1a1f28';      // F glyph (light)
const ACCENT = '#566073';   // dot
const BG = '#f3f5f7';       // tile background (light)
const INK_BG = '#1a1f28';   // tile background (dark)
const DARK_F = '#f9fafb';   // F glyph on dark
const DARK_DOT = '#a0aabb'; // dot on dark

// Source Serif 4 Medium (weight 500), OFL. Google Fonts publishes only the variable
// font (whose default master is ≈Regular/400), and opentype.js can't instance gvar.
// So we pull the true Medium static from @fontsource (shipped as woff2) and decompress
// it to TTF with wawoff2. The variable font is kept as a last-resort fallback.
const FONT_WOFF2 =
  'https://cdn.jsdelivr.net/npm/@fontsource/source-serif-4/files/source-serif-4-latin-500-normal.woff2';
const FONT_TTF_FALLBACK =
  'https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/sourceserif4/SourceSerif4%5Bopsz,wght%5D.ttf';

const isSfnt = (buf) => {
  const tag = buf.readUInt32BE(0); // 0x00010000 TTF · 'OTTO' · 'true'
  return tag === 0x00010000 || tag === 0x4f54544f || tag === 0x74727565;
};

async function getFont() {
  fs.mkdirSync(CACHE, { recursive: true });
  const ttf = path.join(CACHE, 'SourceSerif4-Medium.ttf');
  if (!fs.existsSync(ttf)) {
    try {
      const woff2 = Buffer.from(await (await fetch(FONT_WOFF2)).arrayBuffer());
      const out = Buffer.from(await decompress(woff2));
      if (!isSfnt(out)) throw new Error('decompressed font is not sfnt');
      fs.writeFileSync(ttf, out);
      console.log('· font: Source Serif 4 Medium (500) via @fontsource');
    } catch (e) {
      console.warn('· font: woff2 route failed (' + e.message + '), falling back to variable default (≈400)');
      const buf = Buffer.from(await (await fetch(FONT_TTF_FALLBACK)).arrayBuffer());
      if (!isSfnt(buf)) throw new Error('Could not obtain a Source Serif 4 TTF.');
      fs.writeFileSync(ttf, buf);
    }
  }
  return opentype.loadSync(ttf);
}

// ── Geometry helpers ───────────────────────────────────────────────────
const FS = 1000; // work in a large em so rounding stays clean; everything is scaled per-output

function capHeightPx(font) {
  const c = font.tables.os2 && font.tables.os2.sCapHeight;
  if (c && c > 0) return (c / font.unitsPerEm) * FS;
  // fallback: measure the 'H' cap box
  const bb = font.getPath('H', 0, 0, FS).getBoundingBox();
  return bb.y2 - bb.y1;
}

// The F. mark in its own pen space (baseline y=0, F starts near x=0, glyph extends to -capH).
// Returns the F path-data, the dot circle (cx,cy,r), and the mark's tight bbox.
function markGeometry(font) {
  const cap = capHeightPx(font);
  const fPath = font.getPath('F', 0, 0, FS);
  const bb = fPath.getBoundingBox();
  const dotD = 0.22 * cap;          // dot = 22% of cap height
  const gap = 0.09 * cap;           // gap = 0.09 * cap height
  const dotR = dotD / 2;
  const dotCx = bb.x2 + gap + dotR; // dot sits to the right of F
  const dotCy = -dotR;              // baseline-aligned (bottoms flush at y=0)
  return {
    cap,
    fD: fPath.toPathData(2),
    dot: { cx: dotCx, cy: dotCy, r: dotR },
    left: bb.x1,
    right: bb.x2 + gap + dotD,
  };
}

// Solve a translate(tx,ty)scale(sc) that renders the mark at `targetCap` cap-height,
// centered in an S×S box. Math mirrors `point' = (tx,ty) + sc*point`.
function placeMark(geo, S, targetCap) {
  const sc = targetCap / geo.cap;
  const tx = S / 2 - sc * (geo.left + geo.right) / 2;
  const ty = (S + targetCap) / 2; // baseline render-y; top lands at (S-targetCap)/2
  return { sc, tx, ty };
}

const circlePathData = (cx, cy, r) =>
  `M${(cx - r).toFixed(2)},${cy.toFixed(2)}` +
  `a${r.toFixed(2)},${r.toFixed(2)} 0 1,0 ${(2 * r).toFixed(2)},0` +
  `a${r.toFixed(2)},${r.toFixed(2)} 0 1,0 ${(-2 * r).toFixed(2)},0Z`;

// ── SVG builders ───────────────────────────────────────────────────────
function markSvg(geo, S, { capRatio, fcol, dotcol, bg = null, radius = 0 }) {
  const { sc, tx, ty } = placeMark(geo, S, capRatio * S);
  const bgEl = bg
    ? `<rect width="${S}" height="${S}"${radius ? ` rx="${radius}" ry="${radius}"` : ''} fill="${bg}"/>`
    : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${S}" height="${S}" viewBox="0 0 ${S} ${S}">
${bgEl}
  <g transform="translate(${tx.toFixed(2)},${ty.toFixed(2)}) scale(${sc.toFixed(5)})">
    <path d="${geo.fD}" fill="${fcol}"/>
    <circle cx="${geo.dot.cx.toFixed(2)}" cy="${geo.dot.cy.toFixed(2)}" r="${geo.dot.r.toFixed(2)}" fill="${dotcol}"/>
  </g>
</svg>
`;
}

// Outlined "Feed" + dot for the wordmark / OG image.
function wordmarkSvg(font, W, H, { fontSize, ink = INK, accent = ACCENT, bg = null }) {
  const word = 'Feed';
  const wPath = font.getPath(word, 0, 0, fontSize);
  const adv = font.getAdvanceWidth(word, fontSize);
  const dotD = 0.15 * fontSize;
  const gap = 0.07 * fontSize;
  const dotR = dotD / 2;
  const wb = wPath.toPathData(2);
  const totalW = adv + gap + dotD;
  // centre the whole lockup
  const tx = (W - totalW) / 2;
  const ty = H / 2 + (font.tables.os2.sCapHeight / font.unitsPerEm) * fontSize / 2; // optical-ish vertical centre on caps
  const dotCx = adv + gap + dotR;
  const dotCy = -dotR;
  const bgEl = bg ? `<rect width="${W}" height="${H}" fill="${bg}"/>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
${bgEl}
  <g transform="translate(${tx.toFixed(2)},${ty.toFixed(2)})">
    <path d="${wb}" fill="${ink}"/>
    <circle cx="${dotCx.toFixed(2)}" cy="${dotCy.toFixed(2)}" r="${dotR.toFixed(2)}" fill="${accent}"/>
  </g>
</svg>
`;
}

// ── Android VectorDrawable (108dp) ─────────────────────────────────────
function androidMark(geo, fcol, dotcol) {
  const S = 108;
  const { sc, tx, ty } = placeMark(geo, S, 0.40 * S); // matches FIcon markH = 40%
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by assets/feed-mark/generate.mjs — do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
  <group
      android:translateX="${tx.toFixed(2)}"
      android:translateY="${ty.toFixed(2)}"
      android:scaleX="${sc.toFixed(5)}"
      android:scaleY="${sc.toFixed(5)}">
    <path
        android:fillColor="${fcol}"
        android:pathData="${geo.fD}" />
    <path
        android:fillColor="${dotcol}"
        android:pathData="${circlePathData(geo.dot.cx, geo.dot.cy, geo.dot.r)}" />
  </group>
</vector>
`;
}

const androidBackground = `<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by assets/feed-mark/generate.mjs — flat Paper background. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
  <path
      android:fillColor="${BG}"
      android:pathData="M0,0h108v108h-108z" />
</vector>
`;

// ── Rasterize ──────────────────────────────────────────────────────────
function rasterize(svg, size, outPng) {
  const tmp = path.join(CACHE, `_tmp_${size}_${path.basename(outPng)}.svg`);
  fs.writeFileSync(tmp, svg);
  execFileSync('rsvg-convert', ['-w', String(size), '-h', String(size), tmp, '-o', outPng]);
  fs.rmSync(tmp);
  console.log('· png', path.relative(REPO, outPng), `${size}px`);
}

function write(file, content) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
  console.log('· wrote', path.relative(REPO, file));
}

// ── Main ───────────────────────────────────────────────────────────────
const font = await getFont();
const geo = markGeometry(font);

// 1. Master SVGs (committed, human-inspectable)
const masterMark = markSvg(geo, 108, { capRatio: 0.40, fcol: INK, dotcol: ACCENT });
const masterMono = markSvg(geo, 108, { capRatio: 0.40, fcol: '#000000', dotcol: '#000000' });
write(path.join(__dirname, 'mark.svg'), masterMark);
write(path.join(__dirname, 'mark-mono.svg'), masterMono);
write(path.join(__dirname, 'tile-light.svg'),
  markSvg(geo, 512, { capRatio: 0.40, fcol: INK, dotcol: ACCENT, bg: BG, radius: 512 * 0.22 }));
write(path.join(__dirname, 'tile-dark.svg'),
  markSvg(geo, 512, { capRatio: 0.40, fcol: DARK_F, dotcol: DARK_DOT, bg: INK_BG, radius: 512 * 0.22 }));
const ogSvg = wordmarkSvg(font, 1200, 630, { fontSize: 220, bg: BG });
write(path.join(__dirname, 'og.svg'), ogSvg);
write(path.join(__dirname, 'wordmark.svg'), wordmarkSvg(font, 640, 220, { fontSize: 150 }));

// 2. Web — favicon (transparent SVG primary)
const faviconSvg = markSvg(geo, 32, { capRatio: 0.60, fcol: INK, dotcol: ACCENT });
write(path.join(WEB, 'favicon.svg'), faviconSvg);

// favicon raster + .ico (solid Paper bg, F at 60%)
const faviconTile = (S) => markSvg(geo, S, { capRatio: 0.60, fcol: INK, dotcol: ACCENT, bg: BG });
rasterize(faviconTile(16), 16, path.join(WEB, 'favicon-16.png'));
rasterize(faviconTile(32), 32, path.join(WEB, 'favicon-32.png'));
rasterize(faviconTile(48), 48, path.join(CACHE, 'favicon-48.png'));
const ico = await pngToIco([
  path.join(WEB, 'favicon-16.png'),
  path.join(WEB, 'favicon-32.png'),
  path.join(CACHE, 'favicon-48.png'),
]);
write(path.join(WEB, 'favicon.ico'), ico);

// Apple touch + PWA maskable tiles (full-bleed bg, F at 40% — inside the 80% safe zone)
const maskTile = (bg, fcol, dotcol) => (S) => markSvg(geo, S, { capRatio: 0.40, fcol, dotcol, bg });
rasterize(maskTile(BG, INK, ACCENT)(180), 180, path.join(WEB, 'apple-touch-icon.png'));
rasterize(maskTile(BG, INK, ACCENT)(192), 192, path.join(WEB, 'icon-192.png'));
rasterize(maskTile(BG, INK, ACCENT)(512), 512, path.join(WEB, 'icon-512.png'));
rasterize(maskTile(INK_BG, DARK_F, DARK_DOT)(512), 512, path.join(WEB, 'icon-512-dark.png'));

// OG / social (1200×630)
{
  const tmp = path.join(CACHE, '_og.svg');
  fs.writeFileSync(tmp, ogSvg);
  execFileSync('rsvg-convert', ['-w', '1200', '-h', '630', tmp, '-o', path.join(WEB, 'og-image.png')]);
  fs.rmSync(tmp);
  console.log('· png', path.relative(REPO, path.join(WEB, 'og-image.png')), '1200×630');
}

// 3. Android vector drawables
write(path.join(DRAWABLE, 'app_logo.xml'), androidMark(geo, INK, ACCENT));
write(path.join(DRAWABLE, 'app_logo_mono.xml'), androidMark(geo, '#000000', '#000000'));
write(path.join(DRAWABLE, 'ic_launcher_background.xml'), androidBackground);

console.log('\n✓ Done. cap-height ratio =', (geo.cap / FS).toFixed(3));
