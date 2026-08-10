# React Native Demo — Setup Status

> **Status: iOS built, launched and driven on a simulator (2026-08-10, #3086).**
> The app builds from `ios/SceneViewRNDemo.xcworkspace`, boots against Metro,
> renders a bundled USDZ and dispatches `onTap` — that run is what measured the
> iOS tap. **Android is still unbuilt here**; the notes below about `bob build`,
> `settings.gradle` and asset `sourceSets` are still the expected first-run
> hazards there.
>
> Three things had to be fixed before it would build, and they are now committed:
> the module's `podspec` declares `SceneViewSwift` (a pod cannot see the host
> app's SwiftPM packages), the demo `Podfile` points that dependency at the repo
> root and re-pins `IPHONEOS_DEPLOYMENT_TARGET` to 18.0 after
> `react_native_post_install` lowers it, and three `@react-native/*` dev
> dependencies Metro needs were missing from `package.json`.
>
> This demo is deliberately NOT included in the root
> `settings.gradle` (comment: `samples/react-native-demo/ (JS, not
> Gradle)`). Root `./gradlew` does not touch it, and no CI job *builds
> the app* — unlike the other six demo apps, which are all covered by
> either `ci.yml` or `pr-check.yml`. Since #3036 its JS and CocoaPods
> install ARE exercised on every matching PR: `rn-ios-compile.yml` runs
> `npm ci` here and `pod install` in `ios/`, so a broken `package.json`,
> `Podfile` or lockfile now fails CI even though the app is never built
> or run.

## What is here

| File / dir | Purpose |
|---|---|
| `package.json` | JS dependencies, version (kept in sync with `gradle.properties`) |
| `index.js` | Root entry that registers `App` with `AppRegistry` |
| `app.json` | RN app name (`SceneViewRNDemo`) and display name |
| `babel.config.js` | Babel preset (`@react-native/babel-preset`) |
| `metro.config.js` | Metro bundler config — watches the linked bridge module and blocklists its duplicate `react*` copies |
| `tsconfig.json` | TypeScript config extending `@react-native/typescript-config`, with a path mapping to the local bridge source |
| `.watchmanconfig` | Empty watchman config (required by RN) |
| `Gemfile` | CocoaPods Ruby deps for `ios/` |
| `src/App.tsx` | Full feature showcase using `@sceneview-sdk/react-native` |
| `assets/environments/studio_small.hdr` | HDR environment for the demo scene |
| `android/` | Android Gradle project. Namespace is `io.github.sceneview.demo.rn`. The app `sourceSets` pulls `../assets` into `assets/environments/` so SceneView can load HDR files by relative path. MainActivity/MainApplication live under `android/app/src/main/java/io/github/sceneview/demo/rn/`. |
| `ios/` | Xcode workspace + `Podfile`. Display name still reads *SceneView RN Demo*. |
| `README.md` | High-level description of the demo |

## What's still needed to run

The scaffold was generated with
`npx @react-native-community/cli init SceneViewRNDemo --version 0.73.0`,
copied into this directory, and rewritten with the SceneView namespace.
Before a first `run-android` / `run-ios`, these steps are required:

1. **Install and build the bridge module first.** The linked
   `react-native/react-native-sceneview` module's `prepare` script runs
   `bob build`, which requires its own install:
   ```bash
   cd ../../react-native/react-native-sceneview
   npm install
   npm run build
   cd -
   ```
2. **Install the demo's JS deps:**
   ```bash
   npm install
   ```
3. **iOS only — install pods:**
   ```bash
   cd ios && LANG=en_US.UTF-8 pod install && cd -
   ```
   `bundle install` from the `Gemfile` fails on Ruby 4 (`activesupport`
   requires `ruby >= 3.1.0` and resolves against the wrong ruby gem), so use
   the system CocoaPods directly. `LANG` is not decoration: without a UTF-8
   locale `pod install` aborts with
   `Unicode Normalization not appropriate for ASCII-8BIT`.
4. **Run:**
   ```bash
   npm run android   # requires an emulator or connected device
   npm run ios       # requires an iOS simulator or connected device
   ```

## Known DX blocker on `npm install`

Step 1 above is the mitigation for the `bob build` failure described in
previous sessions. Longer term the fix is to publish
`@sceneview-sdk/react-native` to npm so the `file:` dependency can be
replaced with a registry version.

## Note on package name

The bridge module's `package.json` was renamed to
`@sceneview-sdk/react-native`, but its `podspec`, `tsconfig.json` path
mapping, `README.md`, `package-lock.json`, and `example/` still
reference the old name `react-native-sceneview`. The demo here imports
from the new scoped name (`@sceneview-sdk/react-native`) to match
`package.json`. The remaining stale references in the bridge module
should be reconciled in a follow-up that touches the bridge module
itself.

## Expected rough edges on first run

Because this demo was committed scaffold-only (no build verification),
these are the likely problem areas when someone first tries to run it:

1. **`npm install` fails with `bob build` error.** The linked bridge
   module `../../react-native/react-native-sceneview` has a `prepare`
   script that runs `react-native-builder-bob`. Fix by building the
   bridge module first (step 1 of *"What's still needed to run"*).

2. **Android Gradle settings can't find `node_modules/@react-native-*`.**
   `samples/react-native-demo/android/settings.gradle` applies
   `../node_modules/@react-native-community/cli-platform-android/native_modules.gradle`.
   That file only exists after `npm install` completes.

3. **Package rename seams.** MainActivity, MainApplication, and
   AndroidManifest use the renamed `io.github.sceneview.demo.rn`
   namespace. The iOS project still shows `SceneViewRNDemo` as the
   display name and `.xcodeproj` folder — this is expected (the RN
   CLI template couples them). If the iOS build fails because of a
   stale reference to `com.sceneviewrndemo`, check:
   - `ios/SceneViewRNDemo/Info.plist` bundle identifier
   - `ios/SceneViewRNDemo.xcodeproj/project.pbxproj` `PRODUCT_BUNDLE_IDENTIFIER`

4. **HDR assets not bundled in Android APK.** The app `build.gradle`
   adds `sourceSets.main.assets.srcDirs += ["$rootDir/../assets"]` to
   pull the shared HDRs into the APK's `assets/environments/`. If the
   app starts but can't load `environments/studio_small.hdr`, confirm
   the sourceSets hook resolved correctly.

5. **iOS pods not installed.** `cd ios && LANG=en_US.UTF-8 pod install` must
   succeed before the first Xcode build — see step 3 for why `bundle install`
   is not the command here.

6. **No model on iOS.** The demo's sample models are remote `.glb` URLs, which
   RealityKit cannot read and which the iOS bridge resolves as *bundle resource
   names* anyway. Models that ship a `.usdz` in `ios/SceneViewRNDemo/Models/`
   render; the others are disabled on iOS and labelled *(Android)*.

7. **iOS bundle id is still the scaffold's.**
   `org.reactjs.native.example.SceneViewRNDemo`, where Android uses
   `io.github.sceneview.demo.rn` — `simctl launch` needs the former.

When you do work through these, please update this section to record
what actually worked.
