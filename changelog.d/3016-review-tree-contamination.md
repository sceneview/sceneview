<!-- category: Fixed -->
- **The PR review workflow no longer reports its own edits as defects, and can no
  longer make them.** Its four reviewers shared one working tree with a
  process-wide `Write` grant, and the deny list stopped them from moving the
  *branch* but not from reverting a *file*, while the prompt told the orchestrator
  to treat uncommitted changes as part of the review surface. A reviewer that
  touched the checkout therefore produced a `DO_NOT_MERGE` naming an "uncommitted
  revert" nobody had made — three times across
  [#3009](https://github.com/sceneview/sceneview/pull/3009) and
  [#3015](https://github.com/sceneview/sceneview/pull/3015). `git restore`,
  `git apply` and `git clean` are now denied, the prompt states that CI checkouts
  are clean by construction so uncommitted work can only be the review's own
  damage, and an assertion fails the job outright if the tree is dirty rather than
  letting a poisoned verdict reach the pull request. Above all, the reviewers are
  now five `sv-ci-*` agent types whose `tools:` frontmatter grants `Read, Glob,
  Grep` and no shell, so contamination is impossible rather than forbidden: the
  diff and the verdict file moved out of the repository into `RUNNER_TEMP`, and
  the clean-tree assertion demands a pristine checkout with no exclusions.
  Measured — dropping `Write` alone would have changed nothing, since a subagent
  that still has `Bash` overwrites a tracked file with one `echo`. Closes
  [#3016](https://github.com/sceneview/sceneview/issues/3016).
