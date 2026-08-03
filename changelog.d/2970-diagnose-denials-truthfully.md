<!-- category: Fixed -->
- **The step written to prevent a false reassurance produced one.**
  `pr-review.yml`'s diagnostic ended its denial scan with `| max // 0`. jq's
  `max` over an *empty* array is `null`, and `// 0` turns that into `0` — so a
  scan that found `permission_denials_count` nowhere returned the same value as
  a run with genuinely no denials, and took the reassuring branch. Measured on
  run 30800040485: the step printed "0 denials" while the action's own summary
  in the same log said **13**. Its own comment had stated that a wrong path
  "would silently read 0 denials and print the reassuring branch" — it was only
  ever tested against record shapes invented for the test, never a real one.
  Absence now reports `unknown`, the count is extracted by regex over the raw
  bytes (the record's shape is not a promise — already seen both as a list and
  as a single object), and the branch structure is three-way so `unknown` can
  no longer fall through to "the reviewers ran fine".

<!-- category: Tests -->
- `test-grade-pr-review.sh` fails if the denial scan ever collapses "field
  absent" into `0` again, or stops reporting an unreadable count as `unknown`.
  Mutation-tested: restoring `max // 0` takes the suite from 15 passed to
  14 passed / 1 failed.
