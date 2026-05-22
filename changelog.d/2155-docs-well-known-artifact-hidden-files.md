<!-- category: Fixed -->
- Fix `.well-known/assetlinks.json` and `apple-app-site-association` returning HTTP 404 on `sceneview.github.io` — `upload-artifact@v7` silently stripped dot-prefixed directories unless `include-hidden-files: true` is set, causing the deploy job's patch step to fail (#2155).
