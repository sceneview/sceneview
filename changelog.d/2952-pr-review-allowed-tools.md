<!-- category: Fixed -->
- `pr-review.yml` now grants the orchestrator the `Task` and git tools it needs. They
  are not in `claude-code-action`'s default set, so the four reviewers could never be
  spawned and the diff could never be computed: every review since the workflow landed
  ended as `REVIEW_INCOMPLETE` with no `review-verdict.json`. The git allowlist is
  per-subcommand — reviewers share one working tree, and a branch switch corrupts it
  for the others (#2431).
- A **dispatched** review used to review the wrong code entirely.
  `actions/checkout` defaults to `github.ref`, which on a `workflow_dispatch` is
  whatever `--ref` said — `main` — and not the PR named in `inputs.pr`. The
  reviewers would have diffed `main...HEAD`, found nothing, and reported a clean
  PASS on a PR they never read. Unlike the missing-tools failure above, which the
  grader caught and blocked, this one is a **false green**, and it lands on the one
  path that exists to rescue reviews which cannot run automatically (fork PRs). The
  dispatch path now checks out `refs/pull/N/head`, which resolves on the base repo
  even for fork PRs. The `pull_request` path is untouched — it already resolved the
  right ref, and merging the two would have silently switched the review from the
  merge ref to the head ref.
- Relatedly, the self-modification guard no longer fires on a dispatch. What
  `claude-code-action` validates is the workflow file it is *running*, which on a
  dispatch comes from `--ref`, not from the checkout; comparing the checkout would
  flag every older PR whose copy of the file has merely been superseded, making the
  documented rescue path unusable as soon as this workflow changes.
- A blocking verdict now names its own cause. The failing runs put a red check on
  the PR reading `REVIEW_INCOMPLETE` and nothing else — correct, and useless: the
  real reason sat in the action's JSON log, and the natural reading ("a reviewer
  crashed") was wrong. A new `Diagnose a missing verdict file` step reads the run
  record and distinguishes *denied tools* — a configuration failure, not a finding
  about the PR — from *ran but wrote nothing*, before the grader compresses it to
  one word. It scans the record recursively rather than at a fixed path, because
  the action writes either a list or a single object and a wrong path would
  silently report zero denials, printing the reassuring branch this step exists to
  prevent.
