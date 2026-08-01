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
- A dispatched review used to review the wrong code. `actions/checkout`
  defaults to `github.ref`, which on a `workflow_dispatch` is whatever `--ref`
  said — `main` — and not the PR named in `inputs.pr`. The reviewers would have
  diffed `main...HEAD`, found nothing, and reported a clean PASS on a PR they
  never read: a **false green**, landing on the one path that exists to rescue
  reviews which cannot run automatically (fork PRs). The dispatch path now
  checks out `refs/pull/N/head`, which resolves on the base repo even for fork
  PRs. The `pull_request` path is untouched — it already resolved the right
  ref, and merging the two would have silently switched the review from the
  merge ref to the head ref.
- Relatedly, the self-modification guard no longer fires on a dispatch. What
  `claude-code-action` validates is the workflow file it is *running*, which on
  a dispatch comes from `--ref`, not from the checkout; comparing the checkout
  would flag every older PR whose copy of the file has merely been superseded,
  making the documented rescue path unusable as soon as this workflow changes.
