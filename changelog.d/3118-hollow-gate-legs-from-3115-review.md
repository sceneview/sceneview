<!-- category: Tests -->
- Fixed two quality-gate legs that could not report what they claimed to check:
  `quality-gate.sh`'s Filament background-thread check reported a THREADING
  VIOLATION (with an empty log) whenever `git diff HEAD` itself failed, because
  the failure propagated through the pipeline under `pipefail`; and
  `cross-platform-check.sh --with-apk`'s demo-inventory leg counted its Android
  demos in a file that no longer holds any, and its iOS demos with a pattern that
  matched doc comments. Both sides now count the ids their collator parses, and a
  count of zero is reported as a broken probe instead of as "no drift".
- `cross-platform-check.sh` now shares `lib/as-count.sh` with `quality-gate.sh`
  instead of carrying its own two counter idioms, only one of which was correct.
- A bad invocation of `lib/detect-filament-bg-thread.py` prints its usage text
  instead of a blank line, pinned by a new assertion in its self-test.
- `automation-map` now documents `lib/as-count.sh`, `test-as-count.sh`,
  `lib/detect-filament-bg-thread.py` and `test-detect-filament-bg-thread.sh`.

<!--
Maintainer note: this is the follow-up to the four review warnings on #3115,
which auto-merged before the review landed. Warning (a) was a true positive that
contradicted #3115's own changelog claim — and probing it surfaced two defects
the review had not seen: the Android counter's source file had been emptied by
#1797, and the iOS counter's `-eq 0` guard was unreachable because the filename
itself matches the pattern. So the leg was hollow from both ends simultaneously,
which is why no inequality had ever been reported.

Every claim above was probed, not reasoned about:
  - broken vs fixed counter: `0\n0` + "COMPARISON DIED" vs `0` + compare ok
  - old pipeline in a commitless repo: "THREADING VIOLATION (log: 0 bytes)";
    new form: "WARN git diff HEAD failed — check NOT run"
  - parity leg on the real tree (54 android / 69 ios, 0 issues) and on three
    degraded trees (ios dir missing, ios dir with no @sceneId, android fragments
    gone) — each raises exactly one issue
  - the usage assertion was mutation-tested: a copy carrying the old
    `__doc__…[-4]` slice fails it with an empty stderr, which is the bug
Not run here: shellcheck is clean, but the Gradle-bound legs of pre-push-check
were not exercised — nothing in this PR is Kotlin or Swift.
-->
