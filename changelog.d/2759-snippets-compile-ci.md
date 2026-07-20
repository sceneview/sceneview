<!-- category: Tests -->
- CI now compiles every Kotlin snippet embedded in `llms.txt` and the agent-skill references (`tools/extract-doc-snippets.js` + the new `:snippets-check` module): an API change that breaks documented code is a deterministic CI red instead of a silently stale doc (#2759)
