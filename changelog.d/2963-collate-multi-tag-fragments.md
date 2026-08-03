<!-- category: Fixed -->
- **A changelog fragment carrying several `<!-- category: -->` tags no longer
  files every bullet under the last one.** `collate-changelog.sh` reassigned the
  category on each tag line but accumulated the whole fragment into a single
  buffer, written once at EOF — so a `Fixed` + `Tests` fragment shipped its
  Fixed bullets under `### Tests`. The parser now flushes at every tag
  transition, so each tag owns the bullets that follow it (bullets before any
  tag still default to `Changed`). Two uncollated fragments already carried the
  pattern and would have misfiled at the next release. Multi-tag fragments are
  now documented in `changelog.d/README.md`.

<!-- category: Tests -->
- `test-collate-changelog.sh` pins the fragment→category contract in
  `repo-hygiene`: single-tag, multi-tag, untagged, unknown category name, and a
  tag with odd spacing/casing, plus `--dry-run` immutability. The collator runs
  once per release and **deletes** the fragments it consumed, so a misfiled
  bullet is otherwise found only after the notes are public, with the source
  already gone. Mutation-tested on the per-tag flush.
