// ref-shots.mjs — capture the design-reference artboards from the story board
// (ticket #75) so they sit next to the live-app shots for side-by-side review.
//
// The story board (spec/story-board/index.html) is a single React "design canvas"
// page; each artboard has a stable DOM id. We element-screenshot a curated set.
//
// Usage: node ref-shots.mjs [--base URL] [--out DIR]
// Run scripts/shot-ref.sh, which serves the story board first.

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { argv, exit } from "node:process";

function arg(name, fallback) {
  const i = argv.indexOf(`--${name}`);
  return i !== -1 && argv[i + 1] ? argv[i + 1] : fallback;
}

const BASE = arg("base", "http://localhost:6789").replace(/\/$/, "");
const OUT = arg("out", "build/.shots/ref");

// Artboard ids from spec/story-board/index.html (rendered as `data-dc-slot`),
// mapped to output names that match the live-app shot names where they correspond.
const ARTBOARDS = [
  ["paper-desktop", "desktop-editorial"],
  ["paper-android", "android-editorial"],
  ["login-desktop", "login-web"],
  ["login-android", "login-android"],
  ["state-empty", "state-empty"],
  ["state-sync-failed", "state-sync-failed"],
  ["state-syncing", "state-syncing"],
];

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
    await page.goto(BASE, { waitUntil: "networkidle" });
    await page.waitForTimeout(1500); // Babel compiles JSX in-browser + fonts load
    for (const [id, name] of ARTBOARDS) {
      const el = page.locator(`[data-dc-slot="${id}"]`);
      if ((await el.count()) === 0) {
        console.warn(`! artboard [${id}] not found — skipped`);
        continue;
      }
      await el.scrollIntoViewIfNeeded();
      await el.screenshot({ path: `${OUT}/${name}.png` });
      console.log(`  ✓ ${name.padEnd(20)} #${id}`);
    }
  } finally {
    await browser.close();
  }
  console.log(`\nDone. Reference shots in ${OUT}/`);
}

main().catch((e) => {
  console.error(`\n✗ ${e.message}`);
  exit(1);
});
