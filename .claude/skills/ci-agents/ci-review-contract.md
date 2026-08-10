# The CI review contract

Read by every `sv-ci-*` agent that `.github/workflows/pr-review.yml` spawns.
It is written once, here, because five copies of it would drift; the agent
files carry only their mandate pointer and their model.

## You have no shell and no write tool

Your toolset is `Read`, `Glob`, `Grep` and nothing else. That is not a rule you
are asked to respect — it is the set of functions you were given, and it is
deliberate.

Reviewers used to share one checkout with a process-wide `Write` grant and a
bare `Bash`. Three false `DO_NOT_MERGE` verdicts across
[#3009](https://github.com/sceneview/sceneview/pull/3009) and
[#3015](https://github.com/sceneview/sceneview/pull/3015) were the result: an
agent edited a file to test a hypothesis, another agent read the dirty tree and
reported the edit as a defect in the PR, and an adversarial verifier "confirmed"
it against the same poisoned tree. A reviewer that *cannot* write cannot
produce that failure. This is
[#3016](https://github.com/sceneview/sceneview/issues/3016).

Your mandate file may tell you to run `git diff`, `git show`,
`bash .claude/scripts/…`, a build or a test. In CI you cannot, and the absence
is not a finding — never report "I could not verify X" as a defect. Read files
directly, `Grep` for call sites and cross-platform mirrors, `Glob` for siblings.

## The diff is handed to you as a file

Your prompt names its path, outside the repository. `Read` it; it is plain
unified-diff text, and a large one paginates with `offset` / `limit`. The
workflow produced it from the committed merge ref, so it is the whole PR.

## The pull request is the committed diff, and nothing else

CI checks out a clean tree: every file in the checkout is byte-identical to
`HEAD`, **except eight config paths**. There is no uncommitted work to hunt for.
Outside those eight, a finding whose evidence is "the working tree differs from
what is committed" is structurally impossible — if you ever believe you see one,
it is a bug in this review, not a defect in the PR.

⛔ **The eight exceptions, and why they matter to you.** Before the CLI starts,
`claude-code-action` reverts `.claude/`, `.mcp.json`, `.claude.json`,
`.gitmodules`, `.ripgreprc`, `CLAUDE.md`, `CLAUDE.local.md` and `.husky/` to the
**base branch** — the CLI reads settings and hooks from the working directory
and a PR head is untrusted. So if the PR touches any of those paths, the files
**on disk are the base versions, not this PR's**. Reading one and concluding
"the change is missing" is a false finding about a change that is right there in
the diff. For those paths the PR's real content is the diff your prompt names,
or the snapshot the action leaves in `.claude-pr/<path>` — both of which you can
`Read`. (`git show HEAD:<path>` works too, but only for the orchestrator: you
have no shell.)

## Your final message is your report

Nothing you produce persists anywhere else — you cannot write a file, and the
orchestrator only sees what you say. Use your mandate's output shape: every
finding with its `severity`, a concrete `file:line`, the broken contract, and
the minimal fix; then your `verdict`. A clean `PASS` is a valid and expected
outcome — never invent findings to look thorough.
