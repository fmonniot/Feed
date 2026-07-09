---
name: review-cluster
description: Review all open PRs associated with a NEXT.md cluster and post inline comments + a summary on each PR. Use when the user says "review the PRs for [cluster]", "review [tier] / [cluster]", "do a PR review for this cluster", or names a NEXT.md tier/group and asks for a review.
---

# Review Cluster

Given a NEXT.md cluster (a group within a tier), find all open PRs for its tickets and review each one in parallel. Each PR gets its own agent that posts inline comments and a top-level summary directly to GitHub.

Do **not** use `/code-review`, `/code-review ultra`, or any other review skill inside the per-PR agents — the ultra cloud service is too expensive on this plan.

## Input

The cluster is identified by tier + cluster name, e.g.:
- `Tier 1 / Auth & session`
- `Tier 3 / Server edge cases`

The user may also supply explicit PR numbers (e.g. `#3 #5 #7`) to skip discovery.

## Steps

### 0. Read tracker files from a clean worktree, never the main repo

The primary working directory may have `NEXT.md`/`BUGS.md`/`TICKETS.md` mid-edit or be checked out to a branch other than `main` — reading them there would discover the wrong tickets. Use a shared, persistent read-only worktree pinned to `origin/main` instead of the main repo — this same worktree is reused by `work-cluster`, so check for it before creating one:

```bash
if [ -d ".claude/worktrees/main-readonly" ]; then
  git -C ".claude/worktrees/main-readonly" fetch origin main
  git -C ".claude/worktrees/main-readonly" reset --hard origin/main
else
  git fetch origin main
  git worktree add ".claude/worktrees/main-readonly" origin/main
fi
```

(`.claude/worktrees/` is gitignored, so this never touches `git status` on the main repo. Checking out `origin/main` — not local `main` — gives a detached-HEAD worktree, avoiding git's "branch already checked out" error if `main` is checked out elsewhere. It's read-only scratch space, so a hard reset to refresh it is always safe — nothing is ever committed there.)

### 1. Identify the ticket IDs for the cluster

Read `NEXT.md` from the worktree set up in step 0 and collect every ticket ID (e.g. `BUG-7`, `BUG-18`, `#8`) that appears under the requested tier + cluster heading.

```bash
cat ".claude/worktrees/main-readonly/NEXT.md"
```

### 2. Discover the open PRs

For each ticket ID, search open PRs whose title or body mentions it:

```bash
gh pr list --repo fmonniot/Feed --state open \
  --json number,title,body,headRefName \
  | jq '.[] | select(.title + " " + .body | test("<ID>"))'
```

Repeat for each ticket ID and deduplicate by PR number. If a ticket has no open PR, note it and move on — don't block the review on unfinished work.

If the user supplied explicit PR numbers, skip discovery and use those directly.

### 3. Spawn one review agent per PR (all in parallel)

For each PR number discovered, launch a background Agent with the following prompt (substituting `<N>` with the actual number):

---

**Per-PR agent prompt template:**

> Review PR #<N> on `fmonniot/Feed` and post your findings as inline GitHub comments.
>
> **Do not use any review skills or slash commands.** Do the work directly.
>
> **Never run `git checkout` or `gh pr checkout` locally, and never touch the main repo's working directory.** Everything you need — the description, the diff, and the ability to post comments/reviews — comes from `gh pr view`, `gh pr diff`, and `gh api`/`gh pr review`, none of which touch local git state. If you find yourself wanting to check out the branch to read more context, don't — pull additional context via `gh api` instead.
>
> Steps:
>
> 1. Fetch the PR description:
>    ```bash
>    gh pr view <N> --repo fmonniot/Feed
>    ```
>
> 2. Fetch the full diff:
>    ```bash
>    gh pr diff <N> --repo fmonniot/Feed
>    ```
>    Note the `head` commit SHA from step 1 — you need it for inline comments.
>
> 3. Analyze the diff. Focus on:
>    - **Correctness bugs** — wrong logic, off-by-one, missing guard, silent data loss
>    - **API / contract misuse** — wrong types, missing null handling, incorrect serialization
>    - **New regressions** — behavior the fix introduces that wasn't present before
>    - **Test coverage gaps** — boundary cases, error paths, or invariants with no pinning test
>
>    Skip style, formatting, and naming nits.
>
> 4. For each significant finding, post an inline comment:
>    ```bash
>    gh api repos/fmonniot/Feed/pulls/<N>/comments \
>      -X POST \
>      -f commit_id="<head-sha>" \
>      -f path="<file-path>" \
>      -F line=<line-number> \
>      -f side="RIGHT" \
>      -f body="<comment text>"
>    ```
>    Use `position` instead of `line` if the line is in a hunk header or deletion. If a finding applies to the PR as a whole rather than a specific line, include it in the summary (step 5) instead.
>
> 5. Post a top-level review summary:
>    ```bash
>    gh pr review <N> --repo fmonniot/Feed --comment \
>      --body "<summary: what the PR does, overall assessment, list of findings>"
>    ```
>
> Return a short summary of what you found.

---

### 4. Wait and report

When all agents complete, summarize findings across all PRs in a table:

| PR | Title | Comments posted | Top finding |
|---|---|---|---|
| #N | … | N inline | … |

Call out any PRs where no agent was spawned (ticket has no open PR) so the user knows what's still pending.

## Notes

- **Leave the `main-readonly` worktree in place.** It's shared with `work-cluster` and reused on every run — don't remove it. Refreshing it (step 0) is a cheap fetch + reset, far cheaper than tearing it down and recreating it each time.
- **Never touch the main repo.** Tracker files are read from the shared read-only worktree from step 0; per-PR agents only call `gh` (view/diff/api/review), which never mutates local git state. Nothing in this skill should ever run `git checkout` or `gh pr checkout` in the primary working directory.
- If two tickets share one PR, spawn only one agent for that PR (pass it both ticket IDs as context).
- If a PR's diff is very large (> ~500 lines), the agent should focus on the highest-risk areas first: new logic paths, removed guards, changed error handling.
- The `gh pr diff` output uses `+` / `-` line prefixes. Line numbers in inline comments must be the **new file** line number (the `+` side), not the hunk offset.
- Getting the line number wrong causes the GitHub API to return a 422. If that happens, fall back to a top-level comment rather than dropping the finding.
