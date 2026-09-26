# Wall placement — capture set, 2026-09-22

Visual evidence for the direct wall placement slice. Android: `Pixel_7a` AVD on
`emulator-5554`. iOS: iPhone 17 Pro Max Simulator, Xcode 26.3.

**Neither runtime can start a live AR session**: the arm64 AVD has no ARCore build
(#2754) and the iOS Simulator has no ARKit. These captures therefore prove routing,
copy, theming and the failure/recovery UI — *not* live wall tracking, contact
preservation or gestures, which still need a physical device.

| File | What it shows |
|---|---|
| `android-wall-chooser-light.png` | Wall mode selected. Chooser copy no longer tells the user to point at the ground, and the wall help line promises automatic placement with a 0.3 m preview — no floor prerequisite, no seam, no "life size" claim. |
| `android-wall-chooser-dark.png` | Same screen in dark theme; tokens resolve, no hardcoded colors. |
| `android-wall-ar-state.png` | The wall AR route on the emulator. ARCore cannot create a session, so the SDK's own "Couldn't start AR" recovery screen owns the failure — a single `Try again`. |
| `android-wall-ar-state-dark.png` | The same state in dark theme. |
| `android-surface-ar-state.png` | The pre-existing Surface route in the same condition, kept as the parity reference: it shows one error affordance. The wall route's draft stacked a second "Camera couldn't start" card on top; that duplicate was removed to match. |
| `ios-wall-light.png` / `ios-wall-dark.png` | The iOS wall route reached via `sceneview://demo/wall-placement`, titled "Wall Placement", honestly reporting that ARKit is unavailable rather than simulating a placement. The two files are identical: this screen uses `DemoScaffold(chromeMode: .ar)`, whose AR chrome is deliberately dark in both appearances. |

## Not captured

- A TV placed on a real wall, contact-preserving drag/twist/pinch, tracking loss
  mid-gesture, and the drag-to-another-wall path. All require an ARCore device or a
  physical iPhone.
- ARCore replay: the wall route forwards a dataset, but no recording containing a
  usable vertical plane was available.
