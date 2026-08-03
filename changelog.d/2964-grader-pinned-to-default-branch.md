<!-- category: Fixed -->
- **The merge grader was being loaded from the code it was grading.**
  `pr-review.yml` checks out the PR's tree and then ran
  `.claude/scripts/grade-pr-review.sh` from it, so a PR that edited the grader
  would have had its own verdict computed by its own version of the grader. The
  generator≠evaluator split this workflow is built on is worth nothing if the
  generator can rewrite the evaluator — this is the `pull_request_target`
  footgun in different clothes, and the self-modification guard did not cover
  it (it watches `pr-review.yml` only). The grader is now read from the default
  branch with `git show`, so a PR improving the grader is graded by the current
  one, which is the correct semantics regardless of trust.
  The same bug had a second, louder symptom that is how it was found: a
  dispatch on a PR branched before the script existed died with `No such file
  or directory` at the very last step, after the four reviewers had already
  been paid for (measured, run 30764492028 on #2962). A failure to read the
  grader now posts an explicit comment saying it is a CI configuration problem
  and not a finding about the PR, instead of leaving another unexplained red
  check.

<!-- category: Tests -->
- `test-grade-pr-review.sh` pins the above: it fails if `pr-review.yml` ever
  runs the grader straight out of the checkout again, or stops reading it from
  `origin/$DEFAULT_BRANCH`. Mutation-tested — restoring the checkout-relative
  invocation takes the suite from 14 passed to 13 passed / 1 failed.
