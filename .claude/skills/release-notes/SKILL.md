---
name: release-notes
description: >
  Draft GitHub release notes for a new version of Feed. Gathers merged PRs and
  commits since the previous tag, enriches them from TICKETS.md/BUGS.md, groups
  changes thematically, and writes notes in the project's house style (v0.5.0
  plain style). Use when the user says "write release notes", "draft the release
  for 0.x.0", "release notes for the next version", "prep the GitHub release", or
  names a version to cut.
---

# Release Notes

Given a new version number (e.g. `0.6.0`), draft GitHub release notes for `fmonniot/Feed`
in the project's house style: gather everything merged since the previous tag, enrich each
change from `TICKETS.md`/`BUGS.md`, group by capability, and write reader-facing notes.

The output is a **draft** — a markdown file the user reviews, plus an optional GitHub draft
release. **Never publish a release or push a tag without explicit confirmation** (it's
outward-facing and hard to reverse).

> **Model:** this skill intentionally does **not** pin a model — it runs on whatever the session
> is using, so the user stays in control. The work is synthesis-heavy (thematic grouping,
> reader-facing prose, ID disambiguation), so prefer a strong model (Opus or Sonnet) before
> invoking. If the session is on a small model, say so and suggest switching rather than producing
> lower-quality notes.

## Input

The user names the new version. Forms accepted: `0.6.0`, `v0.6.0`, "the next minor", "cut a patch".

- Normalize to a `vMAJOR.MINOR.PATCH` tag (prepend `v` if missing).
- If the user says "next minor/patch" without a number, compute it from the latest tag.

## House style (decided with the owner)

- **Structure: v0.5.0 plain style** — narrative intro, `## Highlights` with themed `###`
  subsections grouped by *capability* (not by module), `## Bug fixes`, optional `## UI & polish`,
  and a `**Full Changelog**` compare link. **No emoji** in headers.
- **Strip internal IDs — but keep PR numbers.** `#N` is ambiguous: it denotes **both** internal
  ticket numbers **and** GitHub PR numbers (a known wart we may fix later). Internal *ticket* IDs
  and the unambiguous backlog tags (`BUG-N`, `ERR-N`, `READ-N`, `AUTH-N`) must **not** appear in
  the notes — use them only to look up richer descriptions in `TICKETS.md`/`BUGS.md`. **PR
  references are fine and encouraged** — cite them as full links like
  `https://github.com/fmonniot/Feed/pull/99`. See Step 3 for how to tell a ticket `#N` from a PR `#N`.
- **Source: PRs + commits + tickets** — list merged PRs and commits since the last tag, then
  cross-reference the backlog files to enrich wording and grouping.
- **Bold lead-ins** on highlight bullets: `- **Short capability name** — what it does.`
- **One format for every release** — major, minor, and patch all use the same template. There is
  no separate "short" patch layout. A small release simply omits the buckets it has nothing for
  (e.g. a fix-only release naturally renders as just `## Bug fixes` + the compare link). The older
  releases vary in shape only because this skill didn't exist when they were written.

---

## Step 1 — Resolve versions

```bash
PREV=$(git tag -l --sort=-v:refname | head -1)   # e.g. v0.5.2
echo "previous tag: $PREV"
```

Confirm `$PREV` is the real latest release (cross-check `gh release list -L 3`). Set `NEW` to the
user's version (e.g. `v0.6.0`).

---

## Step 2 — Gather raw material

Capture the commit count and the merged PRs in the range.

```bash
git rev-list --count $PREV..HEAD                 # commit count for the intro
DATE=$(git log -1 --format=%aI $PREV)            # timestamp of the previous tag

# Merged PRs since the last tag — titles encode ticket IDs (e.g. "fix(#47): ...")
gh pr list --state merged --base main \
  --search "merged:>$DATE" \
  --json number,title,body,labels,mergedAt -L 100
```

Also scan raw commits to catch anything that didn't go through a PR:

```bash
git log $PREV..HEAD --no-merges --format='%h %s'
```

For PRs whose impact isn't obvious from the title, read the body:
`gh pr view <N> --json title,body`.

---

## Step 3 — Enrich from the backlog, then strip the IDs

PR titles and commit subjects reference internal IDs (`#N`, `BUG-N`, and cluster tags like
`ERR-4`, `READ-5`, `AUTH-2`). For each one:

1. Extract the ID(s) from the title/body.
2. Look the ID up in `TICKETS.md` (for ticket `#N`) or `BUGS.md` (for `BUG-N`) to get a precise,
   human description of *what changed and why* — better than the terse PR title.
3. Write the user-facing line from that understanding, **with the internal ID removed**. The reader
   sees the capability, not the tracking number.

### Disambiguating a bare `#N`: ticket vs. PR

`#N` means a ticket in some places and a PR in others. Apply these rules in order:

- **The `fix(#N):` / `feat(#N):` title prefix always names a *ticket*** → strip that `N`. (The PR
  that fixed it has its *own*, different number — that one is fine to cite.)
- **A `#N` that is a key in the merged-PR list** (the `number` field from `gh pr list` in Step 2)
  is a **PR** → keep it, rendered as `https://github.com/fmonniot/Feed/pull/N`.
- **A `#N` that appears as a heading in `TICKETS.md`** (`### #N` / `#### #N`) is a **ticket** → strip.
- When still unsure, treat it as an internal ticket and strip it — a missing PR link is harmless;
  a leaked ticket number is not.

`BUG-N`, `ERR-N`, `READ-N`, `AUTH-N` are always internal → always strip.

Example: PR #99, title `fix(#87): redesign Android add-feed modal with custom design tokens` →
`#87` is a ticket (strip it), `#99` is the PR (citeable) → notes line:
`- **Add-feed modal** — redesigned on Android with the new design tokens ([#99](https://github.com/fmonniot/Feed/pull/99)).`

---

## Step 4 — Group thematically

Sort every change into one of three buckets, grouped by **capability**, not module:

- **`## Highlights`** — new features and meaningful enhancements. Cluster related changes under a
  short `###` subsection title that names the capability (e.g. `### Adaptive fetch cadence`,
  `### Feed health & error surfacing`). A web change and an Android change to the same capability
  belong in the *same* subsection.
- **`## Bug fixes`** — defects fixed (most `BUG-N` items, `fix(...)` PRs that restore correct
  behavior). Flat bulleted list, lowercase imperative ("fix X", "stop the Y flash").
- **`## UI & polish`** — small visual/UX refinements that aren't headline features and aren't bugs.

If a bucket is empty, omit its header. Lead with the most significant capability.

---

## Step 5 — Write the notes

One template for every release. Omit any bucket (and its header) that has nothing in it — a
fix-only release ends up as just `## Bug fixes` + the compare link, with no intro or Highlights.

```markdown
{One- or two-sentence narrative intro naming the theme of the release. Optionally end with the
commit count, e.g. "162 commits since 0.5.2." Omit for a small release with no headline theme.}

## Highlights

### {Capability name}
- **{Lead-in}** — {what it does, reader-facing}.
- **{Lead-in}** — {…}.

### {Capability name}
- **{Lead-in}** — {…}.

## Bug fixes
- {imperative, lowercase}.
- {…}.

## UI & polish
- {…}.

**Full Changelog**: https://github.com/fmonniot/Feed/compare/{PREV}...{NEW}
```

Write the result to a working file so the user can review and edit before anything is published:

```bash
# scratchpad path is session-specific; use the one from the environment preamble
NOTES=<scratchpad>/release-notes-{NEW}.md
```

---

## Step 6 — Review, then publish only on confirmation

1. **Show the drafted notes** to the user and ask for edits. Do not proceed to publish on your own.
2. When the user approves, offer to create a **draft** GitHub release (still not public):

   ```bash
   gh release create {NEW} --draft --title "{NEW}" --notes-file "$NOTES"
   ```

   This requires the tag `{NEW}` to exist or be created by the command. Creating/pushing the tag
   and publishing the release are outward-facing — confirm explicitly before running, and prefer
   `--draft` so the user does the final publish in the GitHub UI unless they ask otherwise.

---

## Notes

- **Sweep the final draft for leaked internal IDs.** Confirm there are no `BUG-N`, `ERR-N`,
  `READ-N`, `AUTH-N` tokens, and no *ticket* `#N` references. Any surviving `#N` must be a **PR**
  number (ideally already a full `/pull/N` link) — verify each one against the merged-PR list before
  showing the draft. PR references are encouraged; ticket references are not.
- **Group by capability, not module.** "Web reader" and "Android reader" changes to the same
  feature go under one subsection.
- **One format, every release** — no special patch layout. Empty buckets are omitted, so a small
  release comes out short on its own.
- **Never publish without confirmation.** Default to `--draft`; never push a tag silently.
- **The previous tag is the comparison base.** Always build the `compare/{PREV}...{NEW}` link from
  the real latest tag, not a guess.
- If the range is large (100+ commits), it's fine to roll minor changes into a single bullet rather
  than listing every PR — the Full Changelog link is the exhaustive record.
