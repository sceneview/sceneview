<!-- category: Fixed -->

- **PR review agents can no longer modify the checkout they are judging**
  ([#3016](https://github.com/sceneview/sceneview/issues/3016)). CI now spawns
  five `sv-ci-*` agent types whose `tools:` frontmatter grants `Read, Glob,
  Grep` and no shell, so a reviewer is structurally incapable of writing rather
  than merely asked not to. `Write` is gone from the workflow's tool grant, the
  diff and the verdict file both moved out of the repository into `RUNNER_TEMP`,
  and the clean-tree assertion now demands a pristine checkout with no
  exclusions. Measured: dropping `Write` alone changes nothing — a subagent that
  still has `Bash` overwrites a tracked file with one `echo`.
