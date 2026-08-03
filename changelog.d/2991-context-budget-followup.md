<!-- category: Fixed -->
- `impact-check.sh` no longer skips its Android build leg inside a git worktree.
  It tested `[[ -d .git ]]`, but in a linked worktree — how `.claude/worktrees/*`
  and every agent-isolated session runs — `.git` is a regular file, so the leg
  that catches a sample app which no longer configures was skipped exactly where
  most work happens, and announced itself as "not a git repository" so the skip
  read as an environment limitation rather than a bug (#2988).
- `context-budget.sh` now sorts by size and names the over-spec file. It
  previously printed rows in authoring order with a one-character flag, which is
  how the largest item in the budget sat unnoticed through three passes while the
  smaller one got optimised.
- `context-budget.sh` stops describing the skills as bytes "NOT in the standing
  cost". They are deferred, not free: opening one 15 Ko skill is ~19% of the whole
  standing budget, measured on the first real use (#2986). It now prints the three
  most expensive skills with their price if opened.

<!-- category: Changed -->
- `CLAUDE.md`'s "Before EVERY push" list adds `impact-check.sh` and says out loud
  that it is a floor, not the full set. A session ran impact-check from agent
  memory alone — it is not in that list — and surfaced 10 pre-existing failures
  (#2987) plus #2988. Shortening the file to 217 lines made its lists read as
  authoritative: at 1126 lines nobody believed they held the whole picture.
- The `device-qa` and `android-tooling` skills both claimed "QA on an emulator" and
  neither said which was which. `device-qa` is the scripted harness and the release
  gate; `android-tooling` is driving a device by hand. Both descriptions and both
  index rows now say so.
