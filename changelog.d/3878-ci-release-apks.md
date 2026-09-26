<!-- category: Internal -->

- **Release APKs attach to GitHub releases again ([#3878](https://github.com/sceneview/sceneview/issues/3878)).** `tag-release.sh` now dispatches `build-apks.yml` on the release tag the same way it already dispatches `release.yml`, `play-store.yml` and `app-store.yml`, and `build-apks.yml`'s upload steps run on that dispatch instead of only on a tag push. No release since v4.34.0 had shipped with attached APKs. `ios.yml`'s concurrency group also switched to the PR-number-keyed rule `ci.yml` uses, so pushes to `main` no longer cancel each other's run.
