<!-- category: Changed -->
- **The installable SceneView skill now says that `.3mf` needs no special handling
  ([#3482](https://github.com/sceneview/sceneview/issues/3482)).** The SDK reading 3MF is only
  half the job: an AI that has not been told will invent a branch, a format check or a
  "3MF support" flag before calling the loader, and worse, will decide the format from the
  file extension or the MIME type — which Android does not reliably report. `SKILL.md` gains
  that as a critical rule, `references/cheatsheet.md` states it on the two loader rows an AI
  actually reads, and `references/recipes.md` gains a worked recipe whose Kotlin is compiled by
  `:snippets-check` like every other snippet in the reference set.
