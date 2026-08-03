# `changelog.d/` — changelog fragments

This directory holds **changelog fragments**: one small Markdown file per pull
request. At release time the fragments are collated into `CHANGELOG.md` by
`.claude/scripts/collate-changelog.sh`, then deleted.

## Why

In the high-merge-rate continuous cycle, nearly every parallel PR used to add a
one-line entry under `CHANGELOG.md`'s `## Unreleased` section. Because they all
inserted at the same anchor, the 2nd..Nth PRs of a wave reliably hit a merge
conflict the moment one of them merged — even though the entries never
semantically conflict.

A fragment is a **distinct file** named after the PR/issue, so two PRs never
touch the same path. Zero conflicts.

## How to add a changelog entry

When you open a PR, **do not edit `CHANGELOG.md`**. Instead create one file:

```
changelog.d/<issue-or-pr-number>-<short-slug>.md
```

Examples: `changelog.d/1337-changelog-fragments.md`,
`changelog.d/1408-arrecorder-camera-restore.md`.

### Fragment format

Each fragment is plain Markdown — the bullet(s) exactly as they should appear in
the release notes. Prefix the file with a **category tag line** so the
collation script can group it:

```markdown
<!-- category: Fixed -->
- **Short headline ([#1408](https://github.com/sceneview/sceneview/issues/1408)).** One- or two-sentence description of the change.
```

Recognised categories (case-insensitive): `Added`, `Changed`, `Fixed`,
`Removed`, `Tests`, `Docs`, `Performance` (#1844). If the tag line is omitted the
entry is filed under `Changed`. Use `Performance` for pure perf wins (no behaviour
change, no bug fix) — that's the distinction between this bucket and `Fixed` or
`Changed`.

You may include more than one bullet in a single fragment if the PR genuinely
ships several related changes, but keep it to one PR's worth of notes.

A fragment may also carry **more than one category tag**, when a PR ships
changes that genuinely belong in different buckets. Each tag owns the bullets
that **follow** it, up to the next tag or the end of the file; bullets written
before any tag land in `Changed`:

```markdown
<!-- category: Fixed -->
- **The bug this PR fixes.** …

<!-- category: Added -->
- **The API it adds along the way.** …
```

## At release time

`.claude/scripts/collate-changelog.sh X.Y.Z` reads every `*.md` fragment here
(ignoring `README.md` and `.gitkeep`), groups bullets by category, prepends a
new `## vX.Y.Z — <date>` section to `CHANGELOG.md`, and deletes the consumed
fragments. The `## Unreleased` section in `CHANGELOG.md` is preserved for
backward compatibility — any entries still living there are merged into the new
release section too.
