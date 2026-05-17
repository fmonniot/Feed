# Reduce Claude approval prompts during background runs

**Date:** 2026-05-16 11:51 PDT

## Context

When Claude works in the background, each unfamiliar command (find/grep/awk pipelines, full-suite runs piped through tail) triggers an approval prompt that the user has to react to — defeating the point of "let it run." Two compounding issues:

1. **Worktree paths vary** (`.claude/worktrees/agent-<hash>/...`), so even with previously-allowed commands the next agent's command rarely matches a prior approval.
2. **Glob patterns in `.claude/settings.local.json` use the wrong syntax** in several places (e.g. `Bash(cargo test *)` instead of `Bash(cargo test:*)`), so allowlist entries the user thought were active aren't actually matching.

The last item in [TODO.md](TODO.md#L242-L258) lists the exact pipelines that keep triggering: test-count rollups across `shared/build/test-results`, `web/build/test-results`, `app/build/test-results`, plus the combined `cargo test && ./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest` pipeline.

Outcome: Claude reaches for stable wrapper scripts (allowlisted once, work from any worktree) instead of bespoke pipelines, and the existing settings entries actually match the commands they were written for.

## Approach

Three layers, smallest-blast-radius first:

### 1. Add `scripts/` at repo root

Tracked in git, available in every worktree. Each script anchors paths via `git rev-parse --show-toplevel` so it works regardless of cwd or worktree path. Each prints **compact** output by default (no follow-up `| tail -40` needed).

Scripts:

- **`scripts/test-counts.sh [target]`** — Print test counts by reading `build/test-results/**/TEST-*.xml`. Targets:
  - `android` — sums `tests=` across `app/build/test-results/testDebugUnitTest`
  - `shared-js` — `shared/build/test-results/jsBrowserTest`
  - `shared-wasmjs` — `shared/build/test-results/wasmJsBrowserTest`
  - `web` — `web/build/test-results/jsBrowserTest`
  - `server` — runs `cargo test 2>&1 | grep "test result"` in `server/`
  - `all` (default) — prints one line per target with pass/fail/ignored counts
- **`scripts/test-run.sh [target]`** — Runs the target's test command and prints a one-line summary at the end (`✓ android: 50 passed`, `✗ server: 95 passed, 2 failed — see ...`). Targets: `server`, `android`, `shared`, `web`, `all`. `all` chains them in a single invocation matching the existing combined pipeline.
- **`scripts/test-failures.sh [target]`** — Extracts failing test names + first error line from the most recent `TEST-*.xml` (gradle) or re-runs `cargo test --no-fail-fast 2>&1 | grep -E "^test .* FAILED"` (server). Use after `test-run.sh` reports a failure.
- **`scripts/server-build.sh`** — `(cd server && cargo build)` with concise status output. Mirrors the prerequisite step for Android JVM tests when running outside gradle.

Implementation notes:
- Each script starts with `set -euo pipefail` and `ROOT=$(git rev-parse --show-toplevel)`.
- No interactive prompts. Non-zero exit on failure.
- Help text via `scripts/<name>.sh -h` so Claude can self-discover usage.

### 2. Fix and tighten `.claude/settings.local.json`

Critical files to modify: [.claude/settings.local.json](.claude/settings.local.json).

**Fix incorrect prefix patterns** — replace space-glob with `:*` (canonical Claude Code prefix syntax):

| Current | Fixed |
|---|---|
| `Bash(./gradlew *)` | `Bash(./gradlew:*)` |
| `Bash(cargo test *)` | `Bash(cargo test:*)` |
| `Bash(grep -E *)` | `Bash(grep:*)` |
| `Bash(cargo build:*)` | already correct — keep |
| `Bash(cargo check *)` | `Bash(cargo check:*)` |
| `Bash(find /Users/.../Feed/*)` | drop in favor of `Bash(find:*)` (find is read-only unless `-delete`/`-exec rm` is used; the scripts wrap most find calls anyway) |

**Add new entries:**
- `Bash(./scripts/*:*)` — covers every helper invocation.
- `Bash(cargo:*)` — collapses `cargo build:*`, `cargo test:*`, `cargo check:*` into one entry; also covers `fmt`, `clippy`.
- `Bash(head:*)`, `Bash(tail:*)`, `Bash(wc:*)`, `Bash(awk:*)`, `Bash(sed:*)` — safe text utilities that pipelines invoke.

**Leave alone**: the two oddly-specific `cp -r` / `cat > ...` entries (they're narrow enough to be harmless, and removing them risks new prompts on whatever workflow added them).

### 3. Document the scripts in CLAUDE.md

Critical file to modify: [CLAUDE.md](CLAUDE.md).

Under the existing "Running tests" section, add a short "Helper scripts" subsection listing each script with one-line purpose. This makes future agents reach for `./scripts/test-counts.sh all` instead of inventing a new find pipeline. Keep it tight — three or four lines.

## Files to modify

- `scripts/test-counts.sh` *(new)*
- `scripts/test-run.sh` *(new)*
- `scripts/test-failures.sh` *(new)*
- `scripts/server-build.sh` *(new)*
- [.claude/settings.local.json](.claude/settings.local.json) — fix patterns, add script + utility entries
- [CLAUDE.md](CLAUDE.md) — add "Helper scripts" subsection
- [TODO.md](TODO.md) — mark the trailing "Write a set of scripts to analyze test results" item resolved (or convert it to a numbered ticket if you prefer to leave a paper trail)

## Verification

End-to-end checks the implementer should run before declaring done:

1. **Scripts work from repo root**:
   - `./scripts/test-counts.sh all` after a fresh `./gradlew :shared:allTests :web:jsTest :app:testDebugUnitTest` → matches the known baseline (shared 16/platform, web 11, android 50, server 97 pass / 6 ignored per [CLAUDE.md](CLAUDE.md)).
   - `./scripts/test-run.sh all` exits 0 and prints a single summary block.
   - `./scripts/test-failures.sh server` returns empty (or one line per known `#[ignore]`) on a clean tree.

2. **Scripts work from a worktree**: `cd .claude/worktrees/<any>/ && ../../../scripts/test-counts.sh android` — should resolve paths via `git rev-parse --show-toplevel` of the worktree, not the main checkout.

3. **Settings entries match**: after editing, in a new conversation, run `./scripts/test-counts.sh all` and the combined `cargo test` pipeline — neither should prompt.

4. **No regression**: existing allowlist behavior is preserved (the changes are strictly additive for fixed patterns + new scripts).
