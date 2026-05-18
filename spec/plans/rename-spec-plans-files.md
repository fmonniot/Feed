# Plan: Rename spec/plans files to match content

**Date:** 2026-05-17 13:36 PDT

## Context

Several plan files in `spec/plans/` have auto-generated session IDs as names (e.g., `alright-now-let-s-do-snug-quail.md`) that give no hint of the content. This makes navigating the directory confusing. The goal is to replace those names with descriptive slugs while updating any cross-references that would break.

## Files to rename

| Current name | New name | Rationale |
|---|---|---|
| `after-having-adding-subscriptions-inherited-yeti.md` | `fix-article-list-empty-after-subscription-click.md` | Documents a bug fix for `MissingFieldException` causing empty article list |
| `alright-now-let-s-do-snug-quail.md` | `new-design-rollout-implementation-plan.md` | Master 10-phase plan for the Paper design rollout |
| `alright-now-let-s-do-snug-quail-manual-review.md` | `e2e-test-catalog-manual-review-feedback.md` | Manual pass/fail review notes on the e2e catalog |
| `look-at-the-last-partitioned-scroll.md` | `fix-claude-background-approval-prompts.md` | Plan to reduce Claude background-run approval friction |
| `show-me-the-features-md-tranquil-boot.md` | `spec-features-md-draft-review-v2.md` | Plan to draft `spec/FEATURES.md` with v2 feedback |
| `progress.md` | `new-design-rollout-progress-log.md` | Tracking table for new-design phase landing SHAs |

## Files to leave as-is

- `cross-platform-support-kmp.md` — already descriptive
- `feed-management-ui-todo-item-3.md` — already descriptive
- `test-environment-hardening.md` — already descriptive
- `new-design/` — this is a directory (design files + README), not a plan file

## Cross-references to update

Renaming `alright-now-let-s-do-snug-quail.md` → `new-design-rollout-implementation-plan.md` breaks three links:

1. **`TODO.md` line 264** — `spec/plans/alright-now-let-s-do-snug-quail.md` → `spec/plans/new-design-rollout-implementation-plan.md`
2. **`spec/FEATURES.md` line 3** — `plans/alright-now-let-s-do-snug-quail.md` → `plans/new-design-rollout-implementation-plan.md`
3. **`spec/plans/spec-features-md-draft-review-v2.md` line 102** (the renamed show-me file) — same path replacement

No other files reference the other renamed plan files.

## Steps

1. `git mv` each of the 6 files listed above.
2. Patch the 3 cross-references in `TODO.md`, `spec/FEATURES.md`, and `spec/plans/spec-features-md-draft-review-v2.md`.

## Verification

- `grep -r "alright-now-let-s-do-snug-quail\|inherited-yeti\|tranquil-boot\|partitioned-scroll\|snug-quail-manual" spec/ TODO.md CLAUDE.md` returns no matches.
- `git status` shows 6 renames and 3 modified files, no untracked changes.
- Documentation-only change: no tests required per CLAUDE.md exception.
