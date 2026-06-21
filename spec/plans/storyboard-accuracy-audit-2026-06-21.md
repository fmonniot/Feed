# Story-board & spec accuracy audit — spec-document follow-ups

**Date:** 2026-06-21 07:21 PDT

Drift audit of `spec/story-board/`, `spec/VISUAL_SPEC.md`, and `spec/FEATURES.md`
against the **current implemented design** of the web (`web/src/jsMain/…`) and
Android (`app/src/main/java/eu/monniot/feed/ui/…`) clients.

This file now tracks **only the action items that change the spec documents (including
the story board).** The findings that require *code* changes were split out into the bug
backlog — see **BUG-25 … BUG-28** in [BUGS.md](../../BUGS.md) (serif font not bundled;
Android Server-URL editor Material styling; copy/label drift; web sidebar folder
nesting), with their session-order lines in [NEXT.md](../../NEXT.md). No code or spec
edits were made by the audit itself.

## Method & evidence quality

- Rendered evidence was available and **fresh**: `build/.shots/ref/`, `build/.shots/web/`,
  and `build/.shots/android/` were regenerated 2026-06-21 07:05–07:08, *after* the last
  web/Android UI commits (2026-06-20), so the screenshots reflect current code.
- Visual findings were grounded in matched ref↔impl screenshot pairs and then confirmed
  in source.
- Precedence rule from `spec/README.md` applied: behaviour (FEATURES.md) wins; a
  visual-only mismatch where behaviour is satisfied is a visual-only finding.
- `spec/plans/` treated as out-of-scope historical snapshots (not corrected).

---

## Spec-document findings & actions

| # | Doc / target | Contract currently says | Reality (implemented) | Class | Conf | Action on the doc |
|---|---|---|---|---|---|---|
| S1 | **FEATURES.md — `Status` column** | Marks ✗/⚠: FEED-8 (#40), READ-7 (#40), ERR-4 (#54), ERR-5 (#55), ERR-6 (#56), ERR-7 (#57), ERR-10/11 (#60), ERR-14 (#62); AUTH-5 (#34). | Built & wired on both clients. Ticket **#40 is closed `[x]`** (mark-read affordance). Web: offline + rate-limit banners ([ArticleList.kt:144-167](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt#L144)), server-unreachable / first-run / inbox-zero / dead-feed mid-panes ([FeedScreen.kt:193](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/FeedScreen.kt#L193) + [ArticleList.kt:232-259](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt#L232)), session-expired modal ([Main.kt:106](../../web/src/jsMain/kotlin/eu/monniot/feed/web/Main.kt#L106)). Android: mark-read `✓` ([ArticleRow.kt:165](../../app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt#L165)), first-run/caught-up/dead-feed mid-panes ([FeedScreen.kt:245-250](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L245)). `web/desktop/unread.png` renders the INBOX-ZERO pane. | **SPEC-STALE** | high | Re-walk those rows and flip `Status` to ✓ (or ⚠ with a *specific* residual gap) per platform, dropping the stale ticket refs. Biggest doc-vs-code gap; do first. |
| S2 | **VISUAL_SPEC.md — brand mark** (§Web·Sidebar L314, §Web·Login L426) | "22×22 circle outlined 1.5px … containing a 6×6 dot … followed by the wordmark **Feed**." | Sidebar/app wordmark is **"Feed" + trailing accent dot** ("Feed."), no ring — [BrandMark.kt](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/components/BrandMark.kt) / [FeedWordmark.kt](../../app/src/main/java/eu/monniot/feed/ui/components/FeedWordmark.kt), both citing `feed-icon-set.jsx`, which is titled *"Feed — Icon Set · «Feed.» direction"* (L1) — the story-board's current canonical mark. (Login keeps its own boxed-"F" mark, consistent with its ref artboards.) | **SPEC-STALE** | high | Rewrite the sidebar/brand prose to the "Feed." trailing-dot wordmark from `feed-icon-set.jsx` (dot = 15% of font-size, gap 0.07em, baseline-flush). |
| S3 | **story-board — `design-canvas.jsx` sidebar artboards** | Sidebar artboards (and the `state-*` / editorial reference renders generated from them) still draw the **ringed-circle** mark. | Diverges from the newer `feed-icon-set.jsx` "Feed." direction that the clients follow. The story board is internally inconsistent. | **SPEC-STALE** | med | Reconcile the design-canvas sidebar mark to the "Feed." wordmark so the canvas, the icon-set, and the clients agree; regenerate the affected `build/.shots/ref/*` artboards. |
| S4 | **VISUAL_SPEC.md — Android list header** (§Mobile·Article-list L442-444) | Header = title + subtitle only; sync-failed surfaces via **snackbar** (ERR-1, L727). | Android renders a persistent third line `Last sync failed · Retry` under the subtitle (`android/unread.png`, `android/all.png`), in addition to the snackbar ([FeedScreen.kt:206](../../app/src/main/java/eu/monniot/feed/ui/feed/FeedScreen.kt#L206)). A working, intentional-looking persistent status line the spec never describes. | **UNDOCUMENTED** | high | Document the Android list-header sync-status line as the Android analog of the web sidebar footer (states/copy). *If* the team instead decides it's redundant with the snackbar, that becomes a code removal — file a bug then; default action here is to document the shipped surface. |

---

## Verified consistent (no doc change needed)

- **Web Login / Settings / Reader / Subscriptions** structure, **web sidebar-footer sync
  states**, and the **web Inbox-Zero mid-pane** all match the spec/story-board (the only
  Inbox-Zero issue was the stale FEATURES *Status*, S1). The login ghost-buttons are
  correctly **absent** (intentional per FEATURES). The Android Feeds header `+` button is
  explicitly permitted by VISUAL_SPEC L481.

## Prioritised spec-document fixes

1. **S1 — FEATURES.md `Status` reconciliation.** The behavioural source-of-truth
   understates the product by ~9 rows; anyone reading it thinks shipped features are
   unbuilt. Highest priority.
2. **S2 — VISUAL_SPEC brand-mark prose** → the "Feed." wordmark.
3. **S3 — story-board `design-canvas.jsx`** brand-mark reconciliation (+ regenerate ref
   shots) so the canvas stops contradicting the icon-set.
4. **S4 — document the Android persistent sync-status line.**

> **Cross-reference:** code-side fixes for the same audit live in BUG-25 (serif font),
> BUG-26 (Server-URL editor styling), BUG-27 (copy/label drift), BUG-28 (web folder
> nesting). BUG-28 needs a categorised-feed re-shoot to confirm before fixing.

**Which spec docs would you like me to action — all of S1–S4, or a subset?** My
recommendation is S1 first (it's the substantive one), then S2+S3 together (one brand-mark
change across prose + canvas), then S4.
