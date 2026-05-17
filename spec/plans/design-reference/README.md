# Feed — Claude Code handoff

Everything in this folder is the developer-handoff package for **Feed**, a self-hosted RSS reader with a web client and an Android client.

## What's here

| File | Purpose |
|---|---|
| `INTEGRATION.md` | How to wire the design into a real codebase. Stack-specific notes for web (CSS custom properties) and Android (Jetpack Compose). Read this first. |
| `VISUAL_SPEC.md` | Pure visual reference, codebase-agnostic. Palette, type ramp, per-feed hue formulas, layout grids, every screen broken down structurally. |
| `FEATURES.md` | The behavioural contract — scenarios with stable IDs (`FEED-3`, `READ-1c`, …) you can port to Playwright (web) / Compose UI tests (Android). Behaviour wins over visuals when they disagree. |
| `prototype/` | The running prototype. Open `prototype/index.html` in a browser; it's the canonical visual source of truth referenced from both docs. |

## How to read

1. Open `prototype/index.html` in a browser. Click into the **Paper** desktop artboard and the **Paper** Android artboard. Toggle the floating **Tweaks** panel to walk through density / view / font-size / sync states / auth error.
2. Read `VISUAL_SPEC.md` with the prototype open as visual oracle.
3. Read `INTEGRATION.md` once you're ready to plan the build.
4. Use `FEATURES.md` as the QA / test checklist.

## Scope reminder

Web + Android only. **iOS is out of scope.** Starring, the per-feed `tag` column, the reader theme picker, the default-sort setting, and the mobile filter chips are all dropped — see *Features explicitly NOT supported* in `FEATURES.md`.
