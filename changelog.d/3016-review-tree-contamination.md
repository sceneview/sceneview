<!-- category: Fixed -->
- **The PR review workflow no longer reports its own edits as defects.** Its four
  reviewers share one working tree, and the deny list stopped them from moving the
  *branch* but not from reverting a *file*, while the prompt told the orchestrator to
  treat uncommitted changes as part of the review surface. A reviewer that touched the
  checkout therefore produced a `DO_NOT_MERGE` naming an "uncommitted revert" nobody had
  made — three times across
  [#3009](https://github.com/sceneview/sceneview/pull/3009) and
  [#3015](https://github.com/sceneview/sceneview/pull/3015). `git restore`, `git apply`
  and `git clean` are now denied, the prompt states that CI checkouts are clean by
  construction so uncommitted work can only be the review's own damage, and a new
  assertion fails the job outright if the tree is dirty rather than letting a poisoned
  verdict reach the pull request. That assertion was itself fail-open at first — a
  failed `git status` left its output variable empty and the step announced a pristine
  checkout it had never managed to look at, the same "absent is not zero" trap this
  workflow already carries two steps below — so a failed probe is now treated as
  contamination rather than as a clean result. Closes
  [#3016](https://github.com/sceneview/sceneview/issues/3016).
