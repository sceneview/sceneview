<!-- category: Fixed -->
- `pr-review.yml`'s reviewers get a real shell again. Two fixes for the same denied-tools
  bug landed minutes apart — one allowlisting five git subcommands, one allowlisting `Bash`
  — and the merge between them was textually clean, so it kept the narrow form and silently
  reverted the broad one before it ever ran. Measured on the narrow form (run 30719795972,
  allowlist echoed back resolved in the SDK options): 26 turns, 10 permission denials, no
  `review-verdict.json`. A reviewer reads a diff with more than five git subcommands, and
  every other command was refused.
- `Bash` is now bare, and the `#2431` constraint it used to encode moved to where it
  belongs: `--disallowedTools` denies `git checkout`/`switch`/`reset`/`stash`, so the shared
  working tree is protected by the permission layer instead of by starving the shell.
- The missing-verdict diagnostic now prints the denial **messages**, not just the count.
  The count was what made the first diagnosis wrong: it went 7 → 10 across a "fix" while
  naming no tool, so the next guess was as blind as the last. Bounded to 20 × 300 chars,
  denial messages only — no diff, no transcript.
