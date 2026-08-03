<!-- category: Fixed -->
- **The step written to prevent a false reassurance produced one.**
  `pr-review.yml`'s diagnostic ended its denial scan with `| max // 0`, so a
  scan that found `permission_denials_count` *nowhere* returned the same `0` as
  a run with genuinely no denials. Measured on run 30800040485: the step
  announced "0 denials" while the action's own summary said **13**. Its own
  comment had stated that a wrong path "would silently read 0 denials and print
  the reassuring branch" — it was only ever tested against record shapes
  invented for the test, never against a real one. Absence now reports
  `unknown` and says so loudly, and the count is extracted with a regex over
  the raw bytes rather than a jq path, because the record's shape is not a
  promise (already observed both as a list and as a single object).
- The diagnostic also names the tools the fan-out invoked. "13 denials" does
  not say *which* allowlist entry is missing, which is the only actionable part.
- The fan-out's run record is kept as an artifact (7 days) whenever no verdict
  file was produced. It is the only place the refused tool names live and it
  dies with the runner — four of this workflow's five debugging rounds needed a
  fact that existed only inside that file.

<!-- category: Tests -->
- `test-grade-pr-review.sh` fails if the denial scan ever collapses "field
  absent" into `0` again, or stops reporting an unreadable count as `unknown`.
  Mutation-tested: restoring `max // 0` takes the suite from 15 passed to
  14 passed / 1 failed.
