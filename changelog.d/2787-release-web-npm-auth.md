<!-- category: Fixed -->
- **Release pipeline — `sceneview-web` npm publish no longer fails on a missing npm auth token.**
  `actions/setup-node` writes an `.npmrc` containing `_authToken=${NODE_AUTH_TOKEN}`; the
  Kotlin/JS `:kotlinNpmInstall` task shells out to yarn, which expands that file and aborts
  with `Failed to replace env in config` when the variable is unset. The `publish-web` job is
  the only one combining `registry-url` with a Gradle task, so its *build* step now exports
  `NODE_AUTH_TOKEN` too. Surfaced by the `setup-node` v6 → v7 bump (#2787): it broke the
  v4.25.0 release after Maven Central had already published, which also skipped the
  GitHub Release job. Invisible to PR CI, since no pull-request job publishes to npm.
