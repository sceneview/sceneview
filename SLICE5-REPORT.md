# Slice 5 handoff — 2026-09-22

Implementation is left uncommitted in the provided placement-demos worktree. No Git
metadata was written; no network, Gradle, xcodebuild, simulator or device work was
attempted. No SDK source or API dump changed. This is an implementation handoff,
not a claim of validated AR rendering.

## Acceptance criteria (§4.5)

| Criterion | Implementation and evidence | Remaining verification |
|---|---|---|
| No retained placement-bearing demo advertises a center target but commits a different touch ray. | Depth/people/EIS/Cloud use SDK automatic placement with no placement reticle or tap handler. iOS depth/people/recording use ARPlacementExperience. Measurement alone shows a center target and computes its candidate from that same viewport center. | Device captures and replay with taps at off-center positions. |
| Occlusion toggles retain subject pose and scale. | Android preserves the session, placement result and AutoPlacementModel subtree; only camera-stream material flags change. iOS keeps configuration/controller fixed and mutates render options. Both use the bundled Damaged Helmet, corrected before grounding and sized to a 0.3 m preview. Swift tests check anchor identity and transforms across renderer toggles; Android control tests check support and applied effect state. | Run tests and compare rendered placement/scale before and after toggles on both platforms. |
| Host/Record remain explicit; neither starts on automatic placement. | Cloud onPlaced only stores the result and emits feedback; hostCloudAnchorAsync is called by Host. Recording starts only through Record/Stop. Cloud flow tests keep Host idle after local placement. Late async results are generation-gated and delivered on the main thread. | Verify no upload/recording begins until activation; exercise Host/Resolve and Record/Stop. |
| Add point commits exactly the displayed measurement candidate. | MeasureCandidateControl consumes the stored center candidate without another hit test. It clears on tracking loss, viewport changes or source-option changes; a consumed/failed candidate cannot be reused. Tests check object identity, invalidation, failure and one consumption. | Run unit tests and compare target position with anchored markers while moving the camera. |
| Unsupported features are rejected before opening an apparently working comparison. | Android probes required ARCore modes before exposing comparison controls or arming placement, then unmounts AR for the unsupported explanation. iOS ARExperienceContainer checks LiDAR/person semantics/recording availability before mounting; unsupported optional discovery entries are hidden. Swift routing tests cover rejection before permission. | Test supported and unsupported physical devices plus simulator explanations. |

Additional delivery: iOS retains `ar-placement`, the canonical Maestro/parity route.
`ar-instant-placement` and `placement-scene` scenes are removed (including project
entries and flow references), with no stubs. Host/Resolve UI and recording export
remain explicit. Moving a hosted anchor clears the old code and cancels upload;
Copy/Share also reject a code that no longer belongs to the current anchor.

## Checks actually run

- `git diff --check`: exit 0, no output.
- `bash samples/ios-demo/scripts/collate-ios-demos.sh --check`:
  `OK: GeneratedScenes.swift in sync with 45 scene(s).`
- `bash -n samples/ios-demo/scripts/collate-ios-demos.sh`: exit 0.
- `plutil -lint samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj`:
  `samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj: OK`
- `swiftc -frontend -parse` for all 10 changed existing Swift source/test files:
  `Swift syntax parse: 10 changed source/test files OK (no type checking)`.
- Python XML/YAML parsing of changed resource files and manifests/flows: successful.
  This validates syntax only, not Android resource compilation or Maestro behavior.
- Focused source review against local SDK APIs caught and fixed placement subtree
  reuse on Reset and Cloud code ownership after anchor replacement.

No executable unit tests were run. The Swift frontend parse does not check types,
link frameworks or substitute for an iOS build. No UI screenshots were captured;
no rendered-camera evidence is claimed. Light/dark, accessibility text sizing,
Android replay and iPhone P2/P7/P8/P10 remain for the verifier. No disk-heavy work
was started, so no Gradle/Xcode cache or device setup was attempted.

## Deliberate scope/path decisions

1. `docs/ux/parity-manifest.yml` does not exist. The canonical ledger explicitly
   declares its repository-root location. Updated `parity-manifest.yml` rather
   than creating a second divergent ledger.
2. `AutoPlacementScene` lacks session configuration/camera-stream hooks needed by
   these Android features. An app-only adapter consumes the existing SDK controller,
   surface selector and model node. No public SDK change or handwritten API dump
   was necessary.
3. iOS wrappers hold the actual feature views; `Scenes/*Scene.swift` files are
   catalog adapters. Changed the wrappers and shared experience; removed only the
   redundant placement adapter inputs and wrapper. The existing collator regenerates
   the ignored registry, which was not hand-edited.
4. Android still has a registered `placement-scene` SDK-wrapper entry in this
   checkout, despite the plan's shorthand description of one Android entry. Its
   removal was not requested. Per the explicit iOS consolidation instruction,
   removed its duplicate iOS scene and parity row; the root manifest documents this
   exception instead of asserting that all Android IDs are represented.
5. Native occlusion providers cannot produce identical masks: ARCore depth/Scene
   Semantics versus RealityKit LiDAR mesh/person segmentation. The common model,
   scale and placement interaction are shared; provider limits are documented in
   the parity ledger and UX source documentation. No fake comparison is added.
6. Cloud codes localize an anchor; they do not serialize local model scale or pivot
   rotation. The Settings explanation now states that resolved lanterns use default
   size/rotation, and that moving invalidates the prior code. No backend or SDK
   contract was changed.

## Files touched

| File | Why |
|---|---|
| `.maestro/android/ar.yaml` | Assert the single Add point action and that tapping the camera keeps it visible. |
| `.maestro/android/flows/ar-cloud-anchor.yaml` | Replace tap-placement coaching assertions with automatic-placement coaching. |
| `.maestro/ios/ar.yaml` | Remove duplicate placement routes; update feature preflight assertions and the recording route. |
| `.maestro/ios/catalog.yaml` | Remove the obsolete duplicate placement reference. |
| `changelog.d/3777-placement-demos-migrated.md` | Required Changed fragment for slice 5. |
| `docs/docs/cheatsheet-ios.md` | Document the canonical placement route and migrated feature demos; remove obsolete scene references. |
| `docs/docs/samples-ios.md` | Update sample links and automatic-placement behavior after consolidation. |
| `docs/ux/placement-demos.md` | Document migrated behavior, renderer differences, Cloud transform limits and device verification. |
| `parity-manifest.yml` | Update the canonical root ledger, remove duplicate iOS placement coverage and document occlusion provider differences. |
| `samples/android-demo/AR_MEASURE.md` | Document center-target Add point behavior and updated verification steps. |
| `samples/android-demo/src/androidTest/java/io/github/sceneview/demo/ar/ARDepthOcclusionToggleTest.kt` | Replace session-rebuild/spinner expectations with repeated explicit renderer toggles; skip explicitly unsupported hardware. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/common/placement/ARFeatureComparison.kt` | Shared capability-gated comparison shell, bundled helmet, ticketed loading/retry, stable effects, recovery and accessible adjustments. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/common/placement/FeatureComparisonControl.kt` | Gate rendering changes on confirmed support and retain the last applied state after a failed change. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/common/placement/FeaturePlacementScene.kt` | App-only ARCore feature adapter consuming SDK policy/state/model APIs; owns anchor teardown and keys model subtrees on placement. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARCloudAnchorDemo.kt` | Automatic local placement; explicit Host/Resolve; main-thread async completions, cancellation generations and stale-code invalidation; real camera only. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARDepthOcclusionDemo.kt` | Consume the shared depth comparison shell instead of tap placement/session rebuilding. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARImageStabilizationDemo.kt` | Replace free-space/tap placement with the shared grounded subject and preserve in-place EIS configuration. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARMeasureDemo.kt` | Compute the displayed center candidate on frames; Add point consumes that candidate; remove camera-tap placement, plane fill and synthetic QA camera. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARPeopleOcclusionDemo.kt` | Consume the shared people comparison shell with honest support requirements. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/internal/CloudAnchorFlow.kt` | Use automatic-placement copy and require tracking for Host; placement never starts hosting. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/internal/CloudRequestGeneration.kt` | Reject native completions after reset, dismissal or a hosted-anchor replacement. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/internal/DepthOcclusionCopy.kt` | Use shared scanning coaching instead of asking for a placement tap. |
| `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/internal/MeasureCandidateControl.kt` | Store and consume the exact displayed candidate once, including failed commits. |
| `samples/android-demo/src/main/res/values/strings_demo_ar_cloud_anchor.xml` | Explain that Cloud codes share anchor localization, not local model scale/rotation, and moving clears the code. |
| `samples/android-demo/src/main/res/values/strings_demo_ar_depth_occlusion.xml` | Comparison/requirement copy and shared unavailable/error/toggle labels. |
| `samples/android-demo/src/main/res/values/strings_demo_ar_image_stabilization.xml` | Describe anchored comparison, EIS requirement and explicit toggle labels. |
| `samples/android-demo/src/main/res/values/strings_demo_ar_measure.xml` | Center-target/Add point instructions, action and accessible target labels. |
| `samples/android-demo/src/main/res/values/strings_demo_ar_people_occlusion.xml` | Describe people comparison, outdoor-classifier limits and actual requirements. |
| `samples/android-demo/src/test/java/io/github/sceneview/demo/common/placement/FeatureComparisonControlTest.kt` | Test unknown/unsupported gating, failed configuration retention and one apply per explicit toggle. |
| `samples/android-demo/src/test/java/io/github/sceneview/demo/demos/internal/CloudAnchorFlowTest.kt` | Test automatic-placement coaching, explicit hosting and tracking guard. |
| `samples/android-demo/src/test/java/io/github/sceneview/demo/demos/internal/CloudRequestGenerationTest.kt` | Test rejection of callbacks after invalidation. |
| `samples/android-demo/src/test/java/io/github/sceneview/demo/demos/internal/DepthOcclusionCopyTest.kt` | Update the pre-placement coaching expectation to automatic placement. |
| `samples/android-demo/src/test/java/io/github/sceneview/demo/demos/internal/MeasureCandidateControlTest.kt` | Test exact candidate identity, invalidation, single consumption, rearming and failed anchor creation. |
| `samples/ios-demo/SceneViewDemo.xcodeproj/project.pbxproj` | Remove all references/build entries for deleted Swift files. |
| `samples/ios-demo/SceneViewDemo/Views/Components/ARExperienceContainer.swift` | Add ARKit + ReplayKit recording preflight before the live route opens. |
| `samples/ios-demo/SceneViewDemo/Views/Components/ARPlacementExperience.swift` | Shared feature configuration/accessories, renderer-only occlusion toggles, common corrected/grounded helmet and matching 3D preview. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/ARDepthOcclusionDemo.swift` | Replace independent cube/tap placement with the shared helmet and depth comparison mode. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/ARInstantPlacementDemo.swift` | Delete redundant placement wrapper; no replacement stub. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/ARPeopleOcclusionDemo.swift` | Replace independent cube/tap placement with the shared helmet and people comparison mode. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/ARRecorderDemo.swift` | Use the shared placement shell; retain explicit Record/Stop, export and teardown behavior. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/Scenes/ArInstantPlacementScene.swift` | Delete redundant Automatic Placement collator input. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/Scenes/PlacementSceneScene.swift` | Delete redundant One-Call AR collator input. |
| `samples/ios-demo/SceneViewDemo/Views/Tabs/ARTab.swift` | Remove the duplicate placement card and hide unsupported optional discovery entries. |
| `samples/ios-demo/SceneViewDemo/Views/Tabs/ShowcaseTab.swift` | Hide unsupported optional AR features while direct links still explain requirements. |
| `samples/ios-demo/SceneViewDemoTests/ARExperienceModelTests.swift` | Test preflight routes and renderer toggles preserving anchor identity and subject transforms. |
| `samples/ios-demo/SceneViewDemoTests/BundledAssetPrimBudgetTests.swift` | Remove obsolete duplicate/multi-placement commentary and test naming. |
| `samples/ios-demo/SceneViewDemoTests/DemoRegistryGuardTests.swift` | Assert ar-placement is the sole retained iOS placement ID. |
| `samples/ios-demo/scripts/collate-ios-demos.sh` | Use one discovered source-input set for the consolidated registry. |
| `samples/ios-demo/SceneViewDemo/Views/Demos/GeneratedScenes.swift` (ignored generated output) | Regenerated through the existing collator; 45 scene inputs, no manual registry edit. |
| `SLICE5-REPORT.md` | This handoff inventory, acceptance mapping, verification limits and deviations; delete before PR. |
