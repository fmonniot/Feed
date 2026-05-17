**Date:** 2026-05-17 14:48 PDT

# Retroactively add creation dates to existing plan files

## Context

CLAUDE.md was updated to require that plan files include their creation date at the top in `YYYY-MM-DD HH:MM ZZ` format. Eleven existing plan files predate this rule and lack the date header. This plan adds the date retroactively using each file's oldest git commit timestamp.

## Reference format

From `i-was-playing-a-radiant-swan.md` (the only file already dated):

```
# Title

**Date:** 2026-05-17 14:31 PDT

## Context
...
```

The date line uses bold (`**Date:** ...`) and sits between the title and first section heading, separated by blank lines on both sides.

## Files to update

All timestamps are from `git log --follow --format="%ai" -- <file> | tail -1`, converted to PDT (offset `-0700`).

| File | Date to insert |
|---|---|
| `cross-platform-support-kmp.md` | `2026-05-15 15:17 PDT` |
| `e2e-test-catalog-manual-review-feedback.md` | `2026-05-17 13:24 PDT` |
| `feed-management-ui-todo-item-3.md` | `2026-05-14 21:47 PDT` |
| `fix-article-list-empty-after-subscription-click.md` | `2026-05-17 09:19 PDT` |
| `fix-claude-background-approval-prompts.md` | `2026-05-16 11:51 PDT` |
| `let-s-tackle-ticket-number-functional-dewdrop.md` | `2026-05-17 13:58 PDT` |
| `new-design-rollout-implementation-plan.md` | `2026-05-16 07:55 PDT` |
| `new-design-rollout-progress-log.md` | `2026-05-17 13:24 PDT` |
| `rename-spec-plans-files.md` | `2026-05-17 13:36 PDT` |
| `spec-features-md-draft-review-v2.md` | `2026-05-17 13:24 PDT` |
| `test-environment-hardening.md` | `2026-05-14 20:52 PDT` |

`i-was-playing-a-radiant-swan.md` is already dated — skip it.

## Insertion rules

**Files starting with `# Heading` (10 files):** insert the date line after the heading and its trailing blank line:

```
# Original Title
                          <- existing blank line
**Date:** YYYY-MM-DD HH:MM PDT
                          <- new blank line
## First section...
```

**`e2e-test-catalog-manual-review-feedback.md` (no heading):** this file starts directly with `Context: ...`. Insert the date at the very top:

```
**Date:** 2026-05-17 13:24 PDT

Context: ...
```

## Verification

This is a documentation-only change (no code paths, no tests needed). Verify by:

1. Opening each modified file and confirming the date line is present in the right location and format.
2. `grep -l "\*\*Date:\*\*" spec/plans/*.md` should now return all 12 files.
