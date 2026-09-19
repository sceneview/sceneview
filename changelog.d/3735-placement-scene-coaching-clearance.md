<!-- category: Fixed -->
<!-- breaking -->
<!-- RELEASE NOTE (maintainer-only):
     Source-compatible, binary-breaking, exactly like the `bottomClearance` half of #3503 and
     for the same mechanical reason: the new parameter's type is `Dp`, an inline value class,
     so the composable's JVM name is mangled from the whole signature. `PlacementScene-c8To9hI`
     becomes `PlacementScene-O_N-pM8` in `arsceneview.api` (regenerated in this PR, one line).
     A consumer who swaps the .aar without recompiling gets NoSuchMethodError, so this cannot
     ride a patch bump — there is no way to add a parameter to a public composable that does
     not do this. Recompiling is enough; no call site has to change.

     The parameter is appended at the END of the optional block rather than next to `coaching`,
     where it would read better, so that every existing positional slot keeps its index: a
     caller passing `groundShadows` or `playbackDataset` positionally still compiles unchanged.

     Nothing moves for a caller who does not pass it: the default IS the constant the guide
     was already using (`GUIDE_BOTTOM_CLEARANCE`, widened from file-private to `internal` so
     `PlacementScene.kt` can name it as its default — the two files are in the same module).
     The rest of this fragment is `samples/android-demo`, which ships in no artifact. -->
- **`PlacementScene` can be told about the chrome the host draws over it ([#3735](https://github.com/sceneview/sceneview/issues/3735)).** `PlacementScene(coaching = true)` builds the plane-discovery guide for you, and it called it with no `bottomClearance` — so the coaching pill anchored one 16 dp gutter off the safe area, underneath whatever dock, toolbar or call-to-action the host had parked down there. The guide has accepted that measurement since [#3503](https://github.com/sceneview/sceneview/issues/3503) and the app's other AR host has passed it since [#3712](https://github.com/sceneview/sceneview/issues/3712); the one screen that used the *batteries-included* entry point was the one with no way to say it. New `coachingBottomClearance: Dp` parameter, forwarded verbatim to `PlaneDiscoveryGuide(bottomClearance = …)`. **To migrate:** if you use `PlacementScene(coaching = true)` and draw your own bottom chrome, pass `coachingBottomClearance = <your chrome's height> + <your gutter>`; if your camera view is bare, pass nothing and nothing changes.

<!-- category: Fixed -->
- **Android demo — the placement demo's coaching pill clears the dock ([#3735](https://github.com/sceneview/sceneview/issues/3735)).** `PlacementSceneDemo` sat the pill inside the dock band on every device, because it is a single `PlacementScene` call and the parameter above did not exist. It now reads `LocalDemoChromeBottomInset` — the scaffold's measured dock band, the same source and the same `+ Space.md` arithmetic the tap-to-place screen already used — so both AR screens in the app now anchor their coaching to the same measured line.

<!-- category: Tests -->
- **The clearance hand-off is pinned by a differential Robolectric test.** `PlacementSceneCoachingClearanceTest` asserts that omitting the parameter leaves the pill exactly where passing the guide's own 16 dp gutter does (the claim that makes the addition free for existing hosts), and that naming an 80 dp band lifts the pill by exactly the difference — so that double-counting the term, or dropping it, both fail. It composes `PlaneDiscoveryGuide` rather than `PlacementScene`, which needs Filament and an ARCore session and cannot be composed on the JVM; the demo screen itself is checked on device.
