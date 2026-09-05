<!-- category: Fixed -->
- **CI on `main` no longer ends `cancelled` in "KMP native unit tests (iOS sim)" when
  the job runs on the self-hosted Mac
  ([#3469](https://github.com/sceneview/sceneview/issues/3469)).**
  The tests were green in ~20 s on every affected push; the 30 minutes were spent in
  `Post Run ./.github/actions/setup-gradle`. The composite action writes the Gradle
  cache on `push` events, and on a self-hosted runner the Gradle User Home is the
  machine's own `~/.gradle` — a home the action never restores into ("Gradle User Home
  already exists: will not restore from cache") but did tar and upload in full on every
  `main` push: 2.2 GB + 3.0 GB entries still at `Sent 1073741824 of 2201734690 (48.8%),
  0.6 MBs/sec` when the job timeout fired (job 100769714909), or a transfer stuck at
  `Sent 0 of 1269721491 (0.0%), 0.0 MBs/sec` for the whole 30 minutes (job 100796430540).
  The same commits' PR runs are read-only and spent 0 s in that step, which is why the
  failure only ever showed on `main`. All four `main` runs that reached this job on the
  self-hosted runner spent 17 to 30 minutes there. The save also ran `gradle --stop`
  against the developer's live daemons (one of the two it stopped belonged to a local
  Flutter build) and pushed multi-GB macOS entries into a repository cache already at
  14.5 GB active against the 10 GB quota.

  `setup-gradle` now stays `cache-read-only` whenever `runner.environment` is
  `self-hosted`, on every workflow that uses it; hosted runners keep repopulating the
  cache on `push` exactly as before. The job's `timeout-minutes` was not the problem and
  is unchanged; a comment next to it points at the post-step for the next reader.
