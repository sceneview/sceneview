<!-- category: Fixed -->
- The `.well-known/` deep-link manifests (`assetlinks.json` + `apple-app-site-association`) are now deployed to the live site — the website assembly step's `cp website-static/*` glob silently skipped the dot-prefixed directory, so Android App Links / iOS Universal Links auto-verification returned HTTP 404 (#1998).
