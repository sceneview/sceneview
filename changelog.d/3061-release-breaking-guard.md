<!-- category: Fixed -->
<!-- breaking: false -->
<!-- RELEASE NOTE (maintainer-only):
     This fragment describes the guard itself, so it says the word the prose
     heuristic looks for. The explicit opt-out above is why it does not refuse
     the next patch tag. Nothing here changes a public API. -->
- **The release pipeline now refuses to publish a patch version that carries a source-incompatible change ([#3061](https://github.com/sceneview/sceneview/issues/3061)).** `release.yml` performed five irreversible publications — Maven Central, three npm packages and pub.dev — without ever checking that the version being tagged was allowed to carry such a change. A single `breaking-change-guard` job now runs first and every publishing job waits on it, on both the tag and the manual-dispatch paths.
