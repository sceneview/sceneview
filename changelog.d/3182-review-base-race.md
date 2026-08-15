<!-- category: Fixed -->
The PR-review gate reported a false contamination whenever the base branch moved
during a review run. `pr-review.yml` pinned the base SHA before the fan-out, while
`claude-code-action` resolves the base *branch* at its own runtime — a 3-second
window in run 31820409662 was enough for the action to restore one commit's bytes
while the assertion demanded another's. `assert-review-tree-clean.sh` now accepts a
second trusted ref via `--also-base`; a file matching either the pinned SHA or the
current base tip is a restore, anything else is still contamination. An unresolvable
second ref degrades to the strict single-base behaviour and warns.
