<!-- category: Fixed -->
- `pr-review.yml`'s orchestrator no longer backgrounds its reviewers. Subagents default to
  running in the background, which is fine interactively — a notification wakes the parent
  later — but a CI review is headless and the session ends with the turn. The orchestrator
  spawned all four, ended its turn, and the run died with them unread. Its own closing words
  on run 30801646272, now printed by the diagnostic: *"Now waiting for the four reviewers to
  report."* 0 denials, 11 turns, no `review-verdict.json`. The prompt now requires
  `run_in_background: false` on every reviewer and every adversarial verifier.
