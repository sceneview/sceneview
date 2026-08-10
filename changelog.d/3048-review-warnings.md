<!-- category: Fixed -->

- **The MCP's Flutter setup guide no longer hands out a `pubspec.yaml` line `flutter pub get` cannot resolve.** `mcp/src/platform-setup.ts` interpolated `LATEST_SCENEVIEW_RELEASE` — the in-flight SDK version — into `flutter_sceneview: ^X.Y.Z`, emitting `^4.26.0` while pub.dev's newest was 4.24.0. The Flutter plugin is a separate release track and its caret range must name a version that already exists on the registry; this is the same bug [llms.txt](llms.txt) carried until it was corrected. `generate-version.js` now also emits `LATEST_FLUTTER_PUB_RELEASE`, read from the plugin's own README (the coordinate a human updates after a successful publish) and **fatal** if that line is missing — a silent fallback to `VERSION_NAME` is exactly how the wrong version shipped. Guarded by a test that asserts the guide does *not* name the SDK version.
- **The React Native surfaces no longer state as fact that the 3D `onTap` reaches iOS.** That bridge routes its iOS 3D tap through the same `hostView.onTapEntity` hook whose Flutter counterpart was measured never to fire ([#3045](https://github.com/sceneview/sceneview/issues/3045)), so the claim is an untested inference. It is now marked unverified and probably broken, pointing at [#3086](https://github.com/sceneview/sceneview/issues/3086), which will measure it — deliberately *not* flipped to "Android-only", because asserting the opposite without measuring would repeat the original mistake.
- The React Native README no longer says `SceneViewSwift` ships as SwiftPM only three lines above a callout announcing that a root podspec exists; the podspec exists, it is simply unpublished on the CocoaPods trunk.
- The Flutter demo's viewer uses `defaultTargetPlatform` instead of `dart:io`'s `Platform`, which made the file uncompilable on Flutter web.
- **`sync-versions.sh` no longer bumps `llms.txt`'s `flutter_sceneview: ^X.Y.Z` to `VERSION_NAME` at release time**, and the row is report-only rather than critical. This is the same defect as the MCP one above, in the surface that feeds it — and the `4.27.0` release ([`fe4d30b42`](https://github.com/sceneview/sceneview/commit/fe4d30b42)) proved it is not theoretical: the `--fix` sweep rewrote the caret to `^4.27.0` while pub.dev's `flutter_sceneview` had exactly **one** published version, `4.24.0` (checked against the registry API, not inferred). A guard that *repairs* a value it has no view of does not prevent drift, it manufactures it; what keeps this line honest is the absence of an autofix.
- **Flutter iOS setup in the MCP server now ships a Podfile that actually
  resolves.** Both Flutter guides stopped at `platform :ios, '18.0'`, so a
  generated project failed with `Unable to find a specification for
  'SceneViewSwift'`; they now carry the `pod 'SceneViewSwift', :podspec => …`
  line and say why a Swift package cannot replace it.
- **Fixed the Desktop setup guide, which named four APIs that do not exist**
  (`DesktopScene`, `WireframeCube`, `WireframeSphere`, `Float3`) and leaked a
  TypeScript `import` into a Kotlin block. It now shows `WireframeCubeViewer()`,
  the only public entry point, and states plainly that no
  `io.github.sceneview:sceneview-desktop` artifact is published.
- **The Flutter plugin's podspec floor on `SceneViewSwift` is now enforced.**
  It sat at `~> 4.26` through the 4.27.0 release because `sync-versions.sh`
  watched only `s.version`; a stale floor lets an older SceneViewSwift satisfy
  the dependency, so the bridge can link against a runtime predating the APIs
  it calls. Bumped to `~> 4.27` and registered as a checked, autofixable row.
- **React Native's quickstart no longer implies the iOS 3D `onTap` works.** It
  is described from source, never measured; the sibling Flutter bridge with the
  same RealityKit hit test never fires (#3045), and the RN measurement is #3086.
- `changelog.d/3041-flutter-platformview-tap-arena.md` now carries an explicit
  `<!-- breaking -->` marker. It describes a behaviour break in prose without
  ever using the token `breaking`, so the patch-level guard would have let it
  ship in a patch release.
- **The Flutter demo's About tab read `v4.26.0` while the SDK shipped 4.27.0.**
  `sync-versions.sh` only checked that its two slots agreed with *each other*,
  so a pair that drifted together stayed green. Both now track `VERSION_NAME`
  and the row reads OK rather than WARN.
- **`llms.txt` and its two mirrors said the React Native iOS `onTap` is
  delivered.** It routes through the same RealityKit `.targetedToAnyEntity`
  hook the Flutter bridge measured never to fire (#3045); RN's own measurement
  is #3086. All three AI-facing copies now say Android-only-for-now.
- **`llms.txt` taught a Flutter install that cannot `pod install`.** The
  mandatory `pod 'SceneViewSwift', :podspec => …` line existed in the
  quickstart, the plugin README and the MCP server but not in the file AI
  assistants actually read. Added, with the Swift-package dead end spelled out.
- **`llms.txt` taught `modelPath: 'models/helmet.glb'` with no platform
  caveat** — RealityKit cannot read glTF at all, so that line renders nothing on
  iOS while compiling fine.
- **Both bridge guides in the MCP server invented props** — `modelUrl`,
  `onModelLoaded`, `tapToPlace`, `onAnchorCreated`, `PlaneDetection.horizontal`.
  Rewritten against the real surface (`initialModels` / `ModelNode(modelPath:)`
  in Dart, `modelNodes={[{ src }]}` in TSX) and guarded by a test.
- `flutter_sceneview.podspec`'s `swift_version` lagged at 5.9 while the root
  podspec that declares the sync invariant sets 5.10.
- Three `--fix` handlers in `sync-versions.sh` read a version through an
  unguarded `grep | grep | head` pipeline. Under `set -euo pipefail` a
  non-matching inner grep aborts the entire sweep before the emptiness guard
  runs, silently skipping every later autofix.
- Two internal contradictions this PR introduced: `SceneViewSwift.podspec`'s own
  comment called `:git =>` "the one form that works" while every install
  document in the same PR says only `:podspec =>` resolves today, and the React
  Native README's `onTap` topic sentence still said "both platforms" three lines
  above the callout walking iOS back to *probably broken*.
- The MCP React Native AR guide no longer sets `depthOcclusion={true}`. The prop
  is declared on the bridge but configured nowhere native ([#909]), so the
  example promised LiDAR occlusion the runtime never delivers — and said the
  opposite of the RN README in the same repo. Real-but-inert props are invisible
  to the invented-symbol test (the identifier exists), so a dedicated guard now
  forbids enabling this one in either RN guide.

[#909]: https://github.com/sceneview/sceneview/issues/909
- `sync-versions.sh`'s new SceneViewSwift floor row mis-handled a pre-release
  `VERSION_NAME`. Both the check (`$FLOOR.${SOURCE_VERSION##*.}`) and its
  autofix (`${SOURCE_VERSION%.*}`) took the last dot-segment as the patch, so
  `4.27.0-rc.1` produced the expected value `4.27.1` — a *blocking* MISMATCH on
  a floor that was correct — and `--fix` would then have written `4.27.0-rc` as
  the floor. Both now strip the pre-release suffix before slicing; table-tested
  across `X.Y.Z`, `X.Y.Z-rc.N` and `X.Y.Z-SNAPSHOT`, matching and mismatching.
- The React Native bridge's own TypeScript source — the fifth copy of the same
  AI-facing claim, and the one consumers read as an IDE tooltip — still said the
  3D `onTap` payload arrives "on both Android and iOS". Corrected in
  `src/index.tsx` (both the `onTap` and `TapEvent.nodeName` doc comments) and the
  packaged `lib/typescript/**/*.d.ts` regenerated with `bob`, never hand-edited.
- The MCP Flutter 3D guide branched its asset path on `dart:io`'s
  `Platform.isIOS` — the exact import this PR removed from `viewer_page.dart`,
  because it makes a Flutter file uncompilable on web. It now uses
  `defaultTargetPlatform`, matching both the demo and the guidance this PR added
  to `llms.txt`. Guarded and mutation-tested for both Flutter guides.
