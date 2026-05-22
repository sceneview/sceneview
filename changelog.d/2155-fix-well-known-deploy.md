<!-- category: Fixed -->
- `docs.yml`: fix `/.well-known/` files returning HTTP 404 on `sceneview.github.io` — `peaceiris/actions-gh-pages`'s internal `shelljs cp` glob does not expand dot-prefixed subdirectories, so `assetlinks.json` and `apple-app-site-association` were silently dropped on every deploy; a post-deploy patch step now adds the missing directory via a direct SSH git commit (#2155).
