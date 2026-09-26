# #3795 — Picking & Collision never published "Scene ready" in `qa_mode`

Device-QA evidence for the fix in PR #3853 (`fix/3795-picking-scene-ready`,
commit `02f80c905`).

| | |
|---|---|
| Device | QA pool AVD `Pixel_7a`, `emulator-5554`, 1080 × 2400 |
| Build | this branch — `:samples:android-demo:assembleDebug` |
| Install | `adb -s emulator-5554 install -r android-demo-debug.apk` |
| Entry | `am start -n io.github.sceneview.demo/.MainActivity --es demo picking-collision --ez qa_mode true --ez qa_backdrop true` |
| Settle | 25 s before capture, both runs |

Before the fix, `qa_mode` freezes the card's hero-yaw rotation for deterministic
screenshots, which starved the local 18-frame warmup counter of the render-on-demand
activity it relied on: the loading cover never cleared and the "Scene ready" a11y node
was never published. The fix requests frames explicitly via `renderInvalidator` until the
scene has actually rendered, independent of any animation.

## Evidence

- `picking-qa-light.png`, `picking-qa-dark.png` — full-screen captures in light and dark
  theme. Both show the rendered scene (five shapes, "Live Compose in 3D" card drawn with
  real content) with no "Still loading…" cover.
- `picking-qa-light.xml`, `picking-qa-dark.xml` — `uiautomator dump` of the same runs.
  Both contain `content-desc="Scene ready"` and neither contains a "Still loading" node,
  confirming the readiness signal (`R.string.demo_scene_ready_cd`) is published in
  `qa_mode`, in both themes.
