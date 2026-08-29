# `.well-known/` — verified deep-link manifests

These two files turn `https://sceneview.github.io/open?demo=<id>` into a
**verified** App-Link / Universal-Link, so a single QR code or HTTPS URL
opens the demo app **without** the Android "Open with" picker / iOS
disambiguation popup.

## `assetlinks.json` — Android App-Links

Lists the Android packages allowed to handle `https://sceneview.github.io/`
URLs. Two `sha256_cert_fingerprints` are listed:

1. **`96:AC:55:C2:1B:74:87:4E:…:9C:2A`** — the **Play App Signing key**
   used by Google to sign every build distributed via the Play Store.
   This is the fingerprint that matters for users who installed the app
   from Google Play, and it was copied from
   [Play Console → SceneView Demo → Test and release → App integrity →
   App signing][playconsole] under "App signing key certificate".
2. **`5E:59:9D:AA:62:74:BC:DF:…:6D:8C`** — the **upload key** of
   `samples/android-demo` (alias `sceneview`, see
   `project_play_store_signing.md`). Lets App-Links verify on a
   **release** build signed with that upload keystore and installed
   locally via `adb install`, so the pre-upload artifact behaves like
   the Play Store one.

   It does **not** cover `assembleDebug`: `samples/android-demo/build.gradle`
   declares no `debug` signing config, so debug builds are signed with the
   default `~/.android/debug.keystore`
   (`D8:C9:77:2C:F3:23:CA:49:…:B0:ED:D5` on a stock SDK install, and
   different on every developer machine that generated its own). A debug
   install therefore **never** verifies — see the expected-output note
   below.

Both are public artifacts of public certificates — the **private** keys
are not in this file (they live in `~/sceneview-upload.jks` for the
upload key, and on Google's signing service for the App Signing key).

[playconsole]: https://play.google.com/console

The HTTPS intent-filter in `samples/android-demo/src/main/AndroidManifest.xml`
already has `android:autoVerify="true"` so the OS fetches this file at
install time. That filter is scoped to host `sceneview.github.io` **and**
path prefix `/open` — it must never be widened to a bare
`<data android:scheme="https" />`, which would make the demo a candidate
for every https link on the device (#3366).

Verify on a phone with:

```bash
adb shell pm verify-app-links --re-verify io.github.sceneview.demo
adb shell pm get-app-links io.github.sceneview.demo
```

Expected output depends on how the APK was signed:

| Build | `get-app-links` |
|---|---|
| Play Store install, or release APK signed with the upload keystore | `sceneview.github.io: verified` |
| `assembleDebug` / `installDebug` (default debug keystore) | unverified — `Domain verification state: sceneview.github.io: 1024` |

The debug row is **expected**, not a regression: the debug keystore is not
listed above and cannot be, since it differs per machine. Use the custom
`sceneview://demo/<id>` scheme for debug/QA deep links, or opt the domain
in by hand for one device:

```bash
adb shell pm set-app-links-user-selection --user 0 \
  --package io.github.sceneview.demo true sceneview.github.io
```

## `apple-app-site-association` — iOS Universal Links

Wires `https://sceneview.github.io/open?demo=<id>` to the iOS
**SceneView** app (App Store id `6761329763`, bundle
`io.github.sceneview.demo`, TEAM_ID `5G3DZ3TH45`).

The Associated Domains entitlement (`applinks:sceneview.github.io`) is
already wired in
[`samples/ios-demo/SceneViewDemo/SceneViewDemo.entitlements`](../../samples/ios-demo/SceneViewDemo/SceneViewDemo.entitlements).
iOS fetches the AASA on first install or after an OS upgrade — so any
build at-or-after the version that landed Universal Links picks the
mapping up automatically.

The custom scheme `sceneview://demo/<id>` (also wired in
`Info.plist > CFBundleURLTypes`) keeps working as a fallback for users
running pre-Universal-Links builds.

## Serving notes

GitHub Pages serves `.well-known/*` with `Content-Type` based on file
extension. Apple expects `apple-app-site-association` (no extension) to
be served as `application/json`; GitHub Pages does this correctly when
the file has no extension, which is why we ship it that way.

If you ever migrate off GitHub Pages, double-check both:

```bash
curl -I https://sceneview.github.io/.well-known/assetlinks.json
# → Content-Type: application/json

curl -I https://sceneview.github.io/.well-known/apple-app-site-association
# → Content-Type: application/json   (NOT text/plain)
```
