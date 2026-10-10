<!-- category: Maintenance -->
- **A 2.x maintenance release no longer starts a website deploy.** Publishing the GitHub release of `v2.3.4` would have run "Deploy website + docs" on the 2.x tree, which has no website and fails at its first step. The job now skips `v2.*` release tags, as it already skips data releases.
