<!-- category: Changed -->
Removed the Claude Code harness layer from the repository: agent skills, subagent
definitions, slash commands, hooks, the agentic CI workflows and the bulk of
`.claude/scripts/`. What remains is verification (build, tests, emulator QA,
release/store pipelines, licence compliance) and the installable SceneView skills
under `agents/`, which are a product surface. `CLAUDE.md` is now 60 lines of
non-obvious facts instead of 263 lines of process.
