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

### Maintainer-only notes

**Every HTML comment in a fragment is stripped before collation** — single-line,
multi-line, or trailing on a bullet. Comments are therefore the place to leave
notes that must never reach the public release notes:

```markdown
<!-- category: Fixed -->
<!-- RELEASE NOTE (maintainer-only):
     sign-off, publish mechanics, anything a user should not read.
     None of this reaches CHANGELOG.md. -->
- **The bullet users will actually see.** …
```

This is not cosmetic. Before #3037 the collator intercepted only the exact
`<!-- category: X -->` line and copied every other line through verbatim, so a
nine-line internal note in that PR was one release away from being published as
release notes — and the collator *deletes* the fragments it consumes, so the
source would have been gone by the time anyone read the page.

One rule follows from it: **close your comments.** An unterminated `<!--` would
swallow every bullet after it, so `collate-changelog.sh` refuses to collate and
names the file instead.

### Declaring a breaking change

SceneView freezes major `4` and ships breaking changes as a **minor** bump. Mark
a fragment that carries one:

```markdown
<!-- category: Changed -->
<!-- breaking -->
- **`TapEvent.nodeName` is now typed `string | null`.** …
```

`.claude/scripts/check-breaking-change-bump.sh` then refuses a patch-level tag
for that release. It runs inside `collate-changelog.sh` (which cannot be
skipped), early in `release-fast.yml`, and in the release checklist.

The marker is optional in practice: a fragment whose **public prose** says the
change is breaking is treated as breaking anyway — that is how #3037's fragment,
written before the marker existed, would have been caught. Negated forms
(`non-breaking`, `not breaking`) and words like `groundbreaking` do not count. If
a fragment trips the heuristic without being breaking, opt out explicitly with
`<!-- breaking: false -->`, which always wins.

Why it matters: `release.yml`'s `publish-rn` job derives the npm version straight
from the git tag, so a patch tag publishes a source-breaking change to
`@sceneview-sdk/react-native` as a semver patch — the one version class every
consumer's caret range picks up without review.

## At release time

`.claude/scripts/collate-changelog.sh X.Y.Z` reads every `*.md` fragment here
(ignoring `README.md` and `.gitkeep`), groups bullets by category, prepends a
new `## vX.Y.Z — <date>` section to `CHANGELOG.md`, and deletes the consumed
fragments. The `## Unreleased` section in `CHANGELOG.md` is preserved for
backward compatibility — any entries still living there are merged into the new
release section too.
