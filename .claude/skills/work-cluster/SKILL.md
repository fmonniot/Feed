---
name: work-cluster
description: Spawn parallel agents to implement every item in a NEXT.md tier or cluster. Each item (or grouped pair) gets its own git worktree, fixes the bug/ticket, writes tests, and opens a PR. Use when the user says "work on [tier/cluster]", "fix everything in [cluster]", "implement [tier] items", "coordinate the work in [cluster]", or asks to run a parallel work session for a NEXT.md group.
---

# Work Cluster

Orchestrate parallel agents — one per item or paired group — to fix every bug/ticket in the requested NEXT.md cluster. Each agent works in an isolated git worktree, writes tests, and opens a PR against `main`.

## Input

The cluster is identified by tier + cluster name, e.g.:
- `Tier 1 / Auth & session`
- `Tier 1` (entire tier)
- `Tier 3 / Server edge cases`

The user may also specify explicit IDs (e.g. `BUG-7 BUG-18 #65`) to override discovery.

---

## Step 1 — Identify the work items

Read `NEXT.md` and collect every item (ticket ID + description + module) in the requested tier or cluster.

```bash
cat NEXT.md
```

**Detect groupings.** Items with an inline italic note containing "fix together", "pairs well with", or "side-effect of" (e.g. `_(side-effect of BUG-7; fix together)_`) must be bundled into a single agent. The note names the primary item; bundle all mentioned IDs together.

Build a list of **work units** — each unit is either a single item or a group:

```
unit 1: BUG-1  (single)
unit 2: BUG-7 + BUG-18  (grouped — "fix together" note on BUG-18)
unit 3: BUG-5  (single)
…
```

If the user asks for clarification (e.g. "should BUG-X and BUG-Y share one PR?"), resolve it before proceeding. Otherwise default to: one PR per work unit.

---

## Step 2 — Load full context for each work unit

For each work unit, read the relevant entries from `BUGS.md` and/or `TICKETS.md`. Every entry contains:
- **Status** — skip any item already `FIXED` or `IN PROGRESS`
- **Module** — determines which test suite(s) to run
- **Files** — exact paths and line numbers to touch
- **Root cause** — confirmed diagnosis
- **Fix direction** — step-by-step guidance
- **Validation** — which test suite, expected counts before the change

Collect all of this; you will embed it in each agent prompt.

---

## Step 3 — Capture baseline test counts

Before spawning agents, run the test suites that the work units touch and record the live pass counts. This gives each agent an up-to-date baseline to compare against after their changes — no hardcoded numbers to go stale.

Determine which suites are needed from the modules identified in Step 2:

| Module(s) touched | Suite |
|---|---|
| `server/` | `./scripts/test-run.sh server` |
| `shared/` | `./scripts/test-run.sh shared` |
| `web/` | `./scripts/test-run.sh web` |
| `app/` | `./scripts/test-run.sh android` |

Then read the counts:

```bash
./scripts/test-counts.sh <target>   # one compact line per suite
```

Record the output — you will embed these live numbers in every agent prompt in Step 4.

If a suite is too slow to justify running here, tell each agent instead: "Run the suite before your first edit to record a live baseline."

---

## Step 4 — Compose and spawn one agent per work unit (all in parallel)

### Before spawning — write the session manifest

Write a manifest file **before** making any `Agent` tool calls. This is the recovery anchor if this session hits a spend limit mid-run.

```bash
mkdir -p .claude/sessions
git rev-parse --short HEAD   # for the Base field
```

Create `.claude/sessions/<cluster-slug>-<YYYY-MM-DD>.md`:

```markdown
# Work Cluster Session
Cluster: [cluster name, e.g. "Tier 1 / Auth & session"]
Date: [YYYY-MM-DD HH:MM ZZ]
Base: main @ [short SHA]

## Baseline counts
[paste ./scripts/test-counts.sh output — one line per suite]

## Work units
| IDs | Branch | Description |
|-----|--------|-------------|
| BUG-5 | bug/5-feed-title-nullable | Short description |
| BUG-7, BUG-18 | bug/7-18-android-session | Short description |
```

`.claude/` is gitignored, so this file stays off the repo. Files on disk survive session limits (a spend/context limit is not a process restart), so no commit is needed.

Agents do **not** update this file — their completion status is reconstructed at resume time from git and GitHub.

---

### Spawn agents

Spawn all agents in a **single message** (multiple `Agent` tool calls) so they run in parallel. Each agent gets:
- `isolation: "worktree"` — its own clean worktree branched from `main`
- `model: "sonnet"`
- `run_in_background: true`

### Agent prompt template

Write a self-contained prompt for each agent. The prompt must include **all** of the following — the agent starts cold and has no other context:

---

**[Work unit header]**
You are fixing [ID(s)] in the Feed RSS reader project (a self-hosted single-user RSS reader with a Rust/Axum server and Kotlin/Compose Android + KMP web clients). Work in the git worktree you've been given — do NOT touch the main worktree at `/Users/francoismonniot/Projects/github.com/fmonniot/Feed`.

**The bug / ticket**
[Copy the full description from BUGS.md or TICKETS.md: symptom, root cause, fix direction, validation.]

**Relevant files**
[List every file path + line number mentioned in the entry.]

**Fix direction**
[The exact steps from the entry. If multiple bugs in the unit, order them so dependencies are clear: fix the root cause bug first, then the side-effect.]

**Tests**
[Name the test suite(s) to run. Baseline (captured by orchestrator before spawning): [insert live counts here, e.g. "177 passed; 0 failed; 2 ignored"]. After your changes confirm: pass count ≥ baseline and 0 new failures. Include any `-PskipServerBuild` guidance.]

**Test suite commands:**
- Server (Rust): `cd server && cargo test`
- Android JVM: `./gradlew :app:testDebugUnitTest`
- Shared KMP: `./gradlew :shared:allTests`
- Web JS: `./gradlew :web:jsTest`
- Use `-PskipServerBuild` for Android/Shared/Web when server code is unchanged

**Process**
1. Check for existing work in this worktree: `git log --oneline main..HEAD`. If commits exist, read them before touching any files — they are completed steps; continue from where they left off rather than starting over.
2. Read `CLAUDE.md` for project context
3. Read the `BUGS.md` / `TICKETS.md` entries for [IDs] in full
4. Explore the relevant files to verify root cause before editing
5. Implement the fix; prefer minimal, scoped changes
6. Add tests (new test > existing test; see CLAUDE.md "Testing requirement")
7. Run the test suite(s) listed above; confirm pass count ≥ baseline and 0 new failures
8. Update [ID(s)] status in `BUGS.md` / `TICKETS.md` to `FIXED`
9. Remove the [ID] line(s) from `NEXT.md` Tier [N]
10. Commit all changes with a clear message explaining *why*
11. Push the branch: `git push -u origin [branch-name]`
12. Open a PR: `gh pr create --base main --head [branch-name]` with a title referencing the IDs and a body covering root cause, fix, and test plan

**Branch name:** `[branch-name]` (e.g. `bug/7-18-android-session` or `ticket/65-remove-filter-chips`)

---

### Branch naming convention

| ID type | Pattern | Example |
|---|---|---|
| Single bug | `bug/<N>-<slug>` | `bug/5-feed-title-nullable` |
| Paired bugs | `bug/<N>-<M>-<slug>` | `bug/7-18-android-session` |
| Single ticket | `ticket/<N>-<slug>` | `ticket/65-remove-filter-chips` |
| Mixed | `bug/<N>-ticket/<M>-<slug>` | use the primary ID |

The slug is 2–4 words from the description, kebab-cased.

---

## Step 5 — Monitor and recover

Wait for all background agents. When each one completes, note the PR URL or failure.

**Recovery when an agent is cut short** (e.g. spend limit, timeout):

1. Check the worktree for commits:
   ```bash
   cd <worktree-path> && git log --oneline main..HEAD
   ```
2. **Commits exist → work is done:** push the branch and open the PR yourself:
   ```bash
   git push -u origin <branch>
   gh pr create --base main --head <branch> --title "..." --body "..."
   ```
3. **No commits → work not started:** spawn a continuation agent pointing it to the existing worktree branch. The agent prompt must include the worktree path as the working directory and tell it to use that path for all commands. Do NOT use `isolation: "worktree"` (the worktree already exists); pass the directory path in the prompt instead and tell the agent to `cd` there.

---

## Step 6 — Report

When all PRs are open, post a summary table and ping the user:

| Bug/Ticket | PR | Module(s) | Summary |
|---|---|---|---|
| BUG-N | #N | server | One-line fix summary |
| BUG-X + BUG-Y | #N | android + shared | One-line fix summary |

Call out any items skipped (already FIXED, already IN PROGRESS, or had no BUGS.md/TICKETS.md entry).

---

## Resuming after a session limit

If this orchestrator session ends (spend limit, timeout) before all agents complete, restart with:

> "Resume the work-cluster session for [cluster]"

### 1. Read the manifest

```bash
ls .claude/sessions/   # find the manifest for this cluster
cat .claude/sessions/<file>
```

The manifest has every work unit, its planned branch, and the baseline counts — no need to re-run tests or re-read the cluster.

### 2. Determine each unit's state

For each work unit, run:

```bash
# PR already open?
gh pr list --head <branch-name> --json number,url -q '.[] | "\(.number) \(.url)"'

# Worktree exists?
git worktree list | grep <branch-name>

# Commits in the worktree?
cd <worktree-path> && git log --oneline main..HEAD
```

| State | Signal | Action |
|-------|--------|--------|
| **Done** | PR exists | Skip |
| **Partial** | Worktree has commits, no PR | Push + open PR (see Step 5 recovery) |
| **Not started** | No commits (or no worktree) | Re-spawn agent |

### 3. Re-spawn not-started agents

Use the same prompt template as Step 4 with two adjustments:
- Pull baseline counts from the manifest instead of re-running tests.
- If a worktree **exists** but has no commits, pass its path in the prompt and tell the agent to `cd` there; do **not** use `isolation: "worktree"` (the worktree already exists).
- If no worktree exists, use `isolation: "worktree"` as normal.

Batch all re-spawns into a single message.

---

## Notes

- **Always batch agent spawns into one message.** Sending them one at a time serializes the work.
- **Agent prompts must be self-contained.** The agent has no memory of this conversation. Include the full bug description, files, fix direction, and process checklist.
- **Baseline counts are captured live by the orchestrator (Step 3)**, not hardcoded anywhere. The orchestrator runs the relevant test suites before spawning agents and embeds the actual counts in each prompt. Agents verify pass count ≥ baseline after their changes.
- **Grouped items → single PR.** BUG-18 says "side-effect of BUG-7; fix together" → one agent, one branch, one PR covering both. The PR title should reference both IDs.
- **NEXT.md and BUGS.md must be updated by the agent.** Each agent removes its own item from NEXT.md Tier N and marks the bug/ticket FIXED. This keeps the file consistent without a separate cleanup pass.
- **`-PskipServerBuild` is safe** when the agent is not changing `server/` code. This avoids rebuilding the Rust binary on every Android test run.
- **Server tests require the Rust toolchain.** If a work unit only touches Kotlin/KMP files, server tests can be skipped. If it touches `server/`, `cd server && cargo test` is required.
- Do **not** use `/code-review`, `/code-review ultra`, or any other review skill inside the per-item agents.
