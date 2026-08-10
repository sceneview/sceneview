<!-- breaking: false -->
<!-- This fragment is ABOUT breaking changes without being one — the documented
     false-positive shape for the prose heuristic, and the first real use of the
     opt-out. Both of these comments are stripped before collation. -->

<!-- category: Fixed -->

- **Maintainer-only notes can no longer leak into the published release notes ([#3037](https://github.com/sceneview/sceneview/pull/3037)).** `collate-changelog.sh` intercepted exactly one comment shape — the single-line `<!-- category: X -->` tag — and copied every other line of a fragment into `CHANGELOG.md` verbatim, so a multi-line `<!-- RELEASE NOTE: … -->` block reached the public page intact. Every HTML comment in a fragment is now stripped, whether it is single-line, multi-line, or trailing on a bullet; the bullet text around it survives untouched. An unterminated `<!--` is a hard error naming the file rather than a silent truncation, because the collator deletes the fragments it consumes and bullets missing from a release section would have no source left to recover them from.

<!-- category: Added -->

- **A release that ships a breaking change can no longer be tagged as a patch.** `release.yml`'s `publish-rn` job derives the npm version straight from the git tag, so tagging `v4.26.1` would publish a source-breaking `@sceneview-sdk/react-native` change as a semver patch — the one version class a consumer's caret range picks up without review. `.claude/scripts/check-breaking-change-bump.sh` refuses that combination. A fragment declares a breaking change with a `<!-- breaking -->` line or simply by saying so in its public prose (`non-breaking` and `groundbreaking` do not count; `<!-- breaking: false -->` opts out). The check is category-independent — a removed public symbol is as breaking as a changed one — and runs from `collate-changelog.sh`, from `release-fast.yml` right after the version input is validated, and from `release-checklist.sh` §6 — every path that *creates* a release tag. A tag pushed by hand, bypassing collation, still reaches `publish-rn` unguarded: the guard reads the fragments, and collation is what consumes them, so there is nothing left to read afterwards.

<!-- category: Tests -->

- `test-collate-changelog.sh` gains the confidentiality contract the collator never had a test for: internal notes in three comment shapes must not reach `CHANGELOG.md`, the bullets around them must, a category tag quoted inside a note must stay inert, and an unterminated comment must fail loudly without consuming a fragment. A second mutation test neutralises the stripper and asserts all six fixture note lines come back.
- `test-check-breaking-change-bump.sh` pins the new guard in both directions on fixtures taken verbatim from real fragments — #3037's prose must refuse a patch tag, `changelog.d/3008-contentid.md`'s "non-breaking" must not — with one mutation test per direction, plus the post-collation path where the previous version must be read past a `CHANGELOG.md` section that already names the target.
