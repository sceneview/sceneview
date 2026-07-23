- **CI:** close a latent false-green hole in the doc-snippet guard. `:snippets-check`
  compiles every ` ```kotlin ` block of `llms.txt`, but it only ran transitively inside
  ci.yml's `Build libraries & samples` job — which is `paths-ignore`d for `llms*.txt`, so a
  PR editing only `llms.txt` never compiled its snippets and a broken block could reach
  `main` (this is how #2871's own fix went un-verified by CI). A standalone
  `snippets-check.yml` now compiles the snippets whenever their real inputs change
  (`llms.txt`, `agents/sceneview/references/**`, the extractor, the guard module), and its
  check run is gated by CI Gate. (#2875)

<!-- category: Fixed -->
