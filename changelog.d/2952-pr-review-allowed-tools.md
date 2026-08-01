<!-- category: Fixed -->
- `pr-review.yml`'s reviewers could not actually run. `claude-code-action`
  ships only "the base GitHub tools" by default, and the step passed nothing
  but `--model` — so `Bash` (no diff), `Task` (no fan-out) and `Write` (no
  verdict file) were all refused. Measured on run 30719225423, the first real
  dispatch after the workflow landed on `main`: 56 turns, 7m16s of quota,
  `is_error: false`, and `permission_denials_count: 30`. The fan-out never
  happened and no `review-verdict.json` was written. Fixed with an explicit
  `--allowedTools Read,Glob,Grep,Bash,Write,Task`.
- A `REVIEW_INCOMPLETE` verdict now names its own cause. The failing run put a
  red check on the PR reading `REVIEW_INCOMPLETE` and nothing else — correct
  (the grader failed closed exactly as designed, which is the only reason this
  was caught rather than merged as a silent green) but useless: the real reason
  sat in the action's JSON log, and the natural reading — "a reviewer crashed"
  — was wrong. A new `Diagnose a missing verdict file` step reads the run
  record and distinguishes *denied tools* (a configuration failure, not a
  finding about the PR) from *ran but wrote nothing*, before the grader
  compresses it to one word. It scans the record recursively rather than at a
  fixed path, because the action writes either a list or a single object and a
  wrong path would silently report zero denials — printing the reassuring
  branch is precisely the failure this step exists to prevent.
