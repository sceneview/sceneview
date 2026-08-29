<!-- category: Fixed -->
- **The demo app's web deep link can no longer be widened into a link hijacker
  ([#3366](https://github.com/sceneview/sceneview/issues/3366)).** The HTTPS
  intent-filter was already scoped to `sceneview.github.io/open`, matching
  `DeepLinkRouter.extractCandidate`, and never shipped host-less — but nothing in
  the build said so, so dropping `android:host` would have silently made the demo a
  candidate for *every* https link on the device. A new pure-JVM guard
  (`DeepLinkManifestScopeTest`) reads the scope back out of the manifest and pins it
  to the router's own constants, so the two layers can only move together. The
  manifest and `website-static/.well-known/README.md` also stopped claiming that
  App-Links verify on debug builds: `assembleDebug` is signed with the default
  `~/.android/debug.keystore`, which is not — and cannot be — listed in
  `assetlinks.json`, so an unverified domain on a debug install is expected, and
  `sceneview://demo/<id>` is the deep-link channel for QA.
