# Add explicit "mark as read / unread" affordances to spec

**Date:** 2026-05-17 14:31 PDT

## Context

While playing with the new design, two missing affordances surfaced:

1. The article list has no explicit way to mark a single unread article as read short of opening it — and the "mark as read on scroll" mechanism isn't wired up yet (the setting exists but the detection logic doesn't, per [shared FeedViewModel.kt:344](../../shared/src/commonMain/kotlin/eu/monniot/feed/FeedViewModel.kt#L344)).
2. The reader has no way to put an article back into the unread bucket after it's been read — once read, it's gone from Unread view and only reachable via All articles.

Both surfaces share the same backend already: [`PUT /v1/articles/{article_id}/read`](../../server/src/main.rs#L120) (with `is_read` toggle). The shared API in `shared/src/commonMain/` already exposes it. This is a client-only addition.

The change is scoped to [spec/FEATURES.md](../FEATURES.md). Implementation follows in a new TODO ticket (#40), tracked separately.

## Decisions (from the clarifying questions)

- **List button:** rendered **only on unread rows**, paired with the unread dot. Action is unread→read. Read rows have neither dot nor button.
- **Reader button:** **always shown**, behaves as a toggle whose label reflects the article's current read state ("Mark unread" when read, "Mark read" when unread).
- **Ticketing:** one ticket (`#40`) covering both surfaces on both platforms.

## Edits to FEATURES.md

### 1. New row in **Feed list & navigation** scenarios

Append after FEED-7:

```
| FEED-8 | both | Populated server with at least one unread article | Click/tap the "mark as read" affordance next to the unread dot on an article row | `PUT /v1/articles/{id}/read` with `is_read=true` fires; the row loses its unread dot and the affordance; Unread badge decrements by one. Row stays in place in the All articles view; in the Unread view it disappears on next refresh per existing list semantics. | ✗ #40 |
```

### 2. New row in **Reader** scenarios

Append after READ-6:

```
| READ-7 | both | Reader open on any article | Tap/click the read-toggle button in the reader action group (web: next to ↗ Open / ⎙ Share; android: next to ⎙ Share) | The button reflects the current read state — labelled "Mark unread" when the article is read, "Mark read" when unread. Pressing it fires `PUT /v1/articles/{id}/read` with the inverted flag; the Unread badge updates; the source row's unread dot reflects the new state on return to the list. | ✗ #40 |
```

### 3. Downgrade "Mark as read on scroll" status in the Settings reference table

The toggle persists but no client wires it to actual scroll detection — the preference is read by nothing. Flip the existing row's Status from `✓` to `⚠ #41`.

Existing row (line ~81 of FEATURES.md):

```
| Mark as read on scroll (off / on) | ✓ | ✓ | on | ✓ | When on, an article row marked as visible for ≥1s in the list flips to read. |
```

becomes:

```
| Mark as read on scroll (off / on) | ✓ | ✓ | on | ⚠ #41 | When on, an article row marked as visible for ≥1s in the list flips to read. |
```

(The `Web` and `Android` columns stay `✓` — the *setting* is rendered on both platforms; only the *behavior* is broken, which is what `Status` captures.)

## Critical files (read-only context for the edit)

- [spec/FEATURES.md](../FEATURES.md) — only file edited.
- [server/src/main.rs:120](../../server/src/main.rs#L120) — confirms endpoint exists.
- [app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt:123-132](../../app/src/main/java/eu/monniot/feed/ui/feed/ArticleRow.kt#L123-L132) — current unread-dot rendering on Android (button slot lives here).
- [web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt:250-261](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ArticleList.kt#L250-L261) — current unread-dot rendering on web.
- [app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt:417-418](../../app/src/main/java/eu/monniot/feed/ui/reader/ReaderScreen.kt#L417-L418) — current Android reader top-bar buttons (`Aa`, `⎙`).
- [web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt:214-219](../../web/src/jsMain/kotlin/eu/monniot/feed/web/ui/feed/ReaderPane.kt#L214-L219) — current web reader action group (`↗ Open`, `⎙ Share`, `Aa`).

## Verification

This is a spec-only change. Verification is reading the rendered Markdown:

1. Open [spec/FEATURES.md](../FEATURES.md) in a Markdown previewer.
2. Confirm the new `FEED-8` row sits under the existing `FEED-7` row inside the same table.
3. Confirm the new `READ-7` row sits under the existing `READ-6` row inside the same table.
4. Confirm both new rows show `Status = ✗ #40`.
5. Confirm the "Mark as read on scroll" row's Status is now `⚠ #41`.
6. Grep for `#40` after editing — should return both new rows. Grep for `#41` — should return exactly the settings row.

Per [CLAUDE.md](../../CLAUDE.md), documentation-only changes are exempt from the new-test-required rule.

## Follow-ups (not part of this plan)

These are implementation tickets to be filed in [TODO.md](../../TODO.md) after the spec edit lands. The spec edit itself only references their numbers; filing the tickets is a separate step:

- `#40` — wire the new mark-as-read (list) and mark-read-toggle (reader) affordances on both clients. Route button taps through `FeedViewModel` to the existing shared `FeedApi` mark-read call; optimistic local state update; toast/snackbar on failure.
- `#41` — implement the "mark as read on scroll" detection. The preference already exists in `FeedViewModel`; what's missing is the visibility-tracking logic in both clients' article-list scroll callbacks, plus the call to mark-read on dwell ≥ 1s.
