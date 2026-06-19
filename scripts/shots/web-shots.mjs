// web-shots.mjs — capture screenshots of the running web client for design-accuracy
// verification (ticket #75). Logs in through the real UI, then walks the hash routes
// from web/src/jsMain/.../Router.kt at fixed viewports and writes PNGs.
//
// Usage:
//   node web-shots.mjs [--base URL] [--user U] [--pass P] [--out DIR]
// Defaults target the webpack dev server (`./gradlew :web:jsBrowserDevelopmentRun`),
// which proxies /v1/* to the Rust server on :3000.
//
// This script does NOT seed data — run scripts/shots/seed.sh first so the list,
// reader and feeds screens have content.

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { argv, exit } from "node:process";

function arg(name, fallback) {
  const i = argv.indexOf(`--${name}`);
  return i !== -1 && argv[i + 1] ? argv[i + 1] : fallback;
}

const BASE = arg("base", "http://localhost:8080").replace(/\/$/, "");
const USER = arg("user", "admin");
const PASS = arg("pass", "admin");
const OUT = arg("out", "build/.shots/web");

// Viewports: the ≥1100px desktop layout, and a narrow one to catch the breakpoint.
const VIEWPORTS = [
  { name: "desktop", width: 1280, height: 900 },
  { name: "narrow", width: 900, height: 900 },
];

// Resolve a real feed id and article id so the per-feed / reader routes are
// non-empty. Runs inside the page so the `session` cookie (set by UI login) is
// sent automatically — protected routes reject Bearer headers.
async function discoverIds(page) {
  return page.evaluate(async () => {
    const ids = { feedId: null, articleId: null };
    try {
      const feeds = (await (await fetch("/v1/feeds")).json()).data ?? [];
      if (feeds.length) {
        ids.feedId = feeds[0].id;
        const arts = (await (await fetch(`/v1/feeds/${ids.feedId}/articles`)).json()).data ?? [];
        if (arts.length) ids.articleId = arts[0].id;
      }
    } catch (_) { /* leave nulls; routes get skipped */ }
    return ids;
  });
}

async function shoot(page, route, name, outDir) {
  await page.goto(`${BASE}/#${route}`, { waitUntil: "networkidle" });
  await page.waitForTimeout(400); // let any post-route render settle
  const file = `${outDir}/${name}.png`;
  await page.screenshot({ path: file, fullPage: false });
  console.log(`  ✓ ${name.padEnd(16)} #${route}`);
}

async function main() {
  await mkdir(OUT, { recursive: true });

  const browser = await chromium.launch();
  try {
    for (const vp of VIEWPORTS) {
      const outDir = `${OUT}/${vp.name}`;
      await mkdir(outDir, { recursive: true });
      console.log(`\n[${vp.name}] ${vp.width}x${vp.height}`);

      // Logged-in context: log in once through the UI, then walk the routes.
      const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
      const page = await ctx.newPage();
      await page.goto(`${BASE}/#login`, { waitUntil: "networkidle" });
      await page.fill("#login-username", USER);
      await page.fill("#login-password", PASS);
      await page.click("#login-btn");
      // The app re-renders by auth state, not by hash — wait for the login form
      // to be replaced rather than for a hash change (the hash stays #login).
      await page.waitForSelector("#login-username", { state: "detached", timeout: 15000 });

      const { feedId, articleId } = await discoverIds(page);
      // Output names are the canonical scenario stems from SCENARIOS.md so web /
      // android / reference shots line up by filename for side-by-side review.
      const routes = [
        ["list", "unread"],
        ["all", "all"],
        feedId != null ? [`feed/${feedId}`, "feed"] : null,
        articleId != null ? [`article/${articleId}`, "reader"] : null,
        ["subscriptions", "feeds"],
        ["settings", "settings"],
      ].filter(Boolean);
      if (feedId == null) console.warn("! no feeds found — run seed.sh; feed/reader shots skipped");

      for (const [route, name] of routes) await shoot(page, route, name, outDir);
      await ctx.close();

      // Fresh (logged-out) context for the login screen itself.
      const anon = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
      const anonPage = await anon.newPage();
      await shoot(anonPage, "login", "login", outDir);
      await anon.close();
    }
  } finally {
    await browser.close();
  }
  console.log(`\nDone. Screenshots in ${OUT}/<viewport>/`);
}

main().catch((e) => {
  console.error(`\n✗ ${e.message}`);
  exit(1);
});
