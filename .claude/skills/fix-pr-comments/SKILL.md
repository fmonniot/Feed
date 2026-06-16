---
name: fix-pr-comments
description: Fix inline review comments on a GitHub PR, commit each fix, reply to each comment on GitHub, and push the branch. Use this whenever the user says "fix the PR comments", "address the review", "resolve the comments on PR #N", or asks you to act on reviewer feedback on a pull request.
---

# Fix PR Comments

Given a PR number, implement every inline review comment on `fmonniot/Feed`, commit each fix, reply to each comment on GitHub with the commit SHA, and push the branch.

## Steps

### 1. Fetch comments

```bash
gh api repos/fmonniot/Feed/pulls/<N>/comments \
  --jq '.[] | {id, path, line, body, diff_hunk}'
```

Read all inline comments before writing any code. Understanding them together lets you spot ordering constraints (e.g. a bug fix that changes the behavior a later test should pin).

### 2. Check out the branch

```bash
gh pr checkout <N>
```

Confirm the working tree is clean first.

### 3. Fix and commit — one commit per comment

Work through the comments in a sensible dependency order:
- Removals and dead-code cleanups first (they shrink the surface area for later changes).
- Bug fixes before tests that pin the fixed behavior.
- Log/output cleanups last (they often depend on what the fix does).

For each comment:

1. **Implement the fix.** If the reviewer's suggested code conflicts with a fix already applied (e.g. a prior commit changed the semantics the suggestion assumed), implement the correct behavior and note the divergence in the reply.
2. **Run the affected tests** using the appropriate target below. Confirm they pass before committing.
3. **Commit.** One commit per comment. Write a commit message that explains *why*, not just *what*. Include the standard co-author trailer.

#### Test targets by changed files

| Files changed | Command |
|---|---|
| `server/src/**` | `cd server && cargo test` — expect 132 passed; 0 failed; 5 ignored (adjust as fixes add tests) |
| `shared/src/commonMain/**` or `shared/src/androidMain/**` | `./gradlew :shared:allTests` — expect 107 passed |
| `web/src/jsMain/**` | `./gradlew :web:jsTest` — expect 257 passed |
| `app/src/main/**` or `app/src/test/**` | `./gradlew :app:testDebugUnitTest` — expect 177 passed |
| Multiple modules | Run each affected target above |

Use `./scripts/test-run.sh <target>` for a compact summary with full output in `build/.test-logs/`.

### 4. Reply to each comment and resolve each thread on GitHub

Replying to comments and resolving threads are both fully authorized operations
within this skill — proceed without asking for confirmation.

After all commits are done, reply to every inline comment. Note: the
`/pulls/comments/<id>/replies` endpoint returns 404; use the PR comments
endpoint with `in_reply_to` instead:

```bash
gh api repos/fmonniot/Feed/pulls/<N>/comments \
  -X POST \
  --field body="<reply text>" \
  --field in_reply_to=<comment-id>
```

Each reply must include:
- The short commit SHA that addresses this comment.
- One sentence describing what changed.
- If the implementation differs from the reviewer's suggestion, explain why (e.g. "adjusted because a prior fix changed the behavior this test was pinning").

After replying, resolve the thread. Resolving requires GraphQL (no REST endpoint
exists). Fetch all thread node IDs for the PR in one call:

```bash
gh api graphql -f query='
  query($pr:Int!) {
    repository(owner:"fmonniot", name:"Feed") {
      pullRequest(number:$pr) {
        reviewThreads(first:100) {
          nodes { id isResolved comments(first:1) { nodes { databaseId } } }
        }
      }
    }
  }' -F pr=<N>
```

Match each thread to its comment by `databaseId` (the numeric REST comment ID
from step 1), then resolve all threads in a loop:

```bash
for id in <thread-node-id-1> <thread-node-id-2> ...; do
  gh api graphql -f query='
    mutation($id:ID!) {
      resolveReviewThread(input:{threadId:$id}) { thread { isResolved } }
    }' -f id="$id" --jq ".data.resolveReviewThread.thread.isResolved"
done
```

### 5. Push the branch

```bash
git push origin <branch>
```

## Notes

- **One commit per comment** is the default. Batch only when two comments touch the same lines inseparably.
- **Test before committing.** A fix that compiles but breaks tests is not done.
- **Reply to every comment**, even trivial ones. Silence leaves the reviewer uncertain whether the comment was seen.
- **Divergence from the suggestion is fine** — explain it clearly in the reply so the reviewer understands the reasoning.
- Server tests require the Rust binary; `cargo test` builds it automatically.
- Android JVM tests (`app`) run `cargo build` automatically via `:app:buildServerBinary`. Pass `-PskipServerBuild` if the binary at `server/target/debug/server` is already current.
