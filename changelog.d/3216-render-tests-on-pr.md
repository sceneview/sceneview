<!-- category: Fixed -->
<!-- breaking: false -->
`render-tests.yml` now runs `:sceneview:connectedDebugAndroidTest` on a pull
request, path-gated to `sceneview/**`, `sceneview-core/**`, `arsceneview/src/**`
and the Gradle build files, so an instrumented test added or broken by a PR
fails before the merge instead of on whichever push to `main` came next
(#3216). The four advisory legs (demo screenshots, render goldens, AR playback,
iOS, web) stay push-to-main + nightly only. On `main`, `render-tests.yml` and
`device-qa.yml` no longer cancel the previous run on every merge — each run has
its own concurrency group, so every merge reaches a verdict instead of the
~50% that previously ended `cancelled` (#2917). Cancellation remains only for
superseded pushes to the same pull request.
