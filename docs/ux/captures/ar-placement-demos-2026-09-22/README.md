# AR placement demos — capture set, 2026-09-22

Visual proof for the slice that moves every placement-bearing demo onto the shared
automatic-placement controller and leaves iOS with a single placement entry.

Neither an iOS Simulator nor the QA emulator can open an AR session, so these captures
prove the **honest pre-camera states**, not tracking. Tracking behaviour is device-only
and is not claimed here.

## iOS Simulator — iPhone 17 Pro Max, iOS 26.3

Reached with `sceneview://demo/<id>`, one pass in light appearance and one in dark.

| Screen | Files | State |
|---|---|---|
| Depth Occlusion | `ios-ar-depth-occlusion-{light,dark}.png` | "This feature isn't available on this device." / "Requires LiDAR." |
| People Occlusion | `ios-ar-people-occlusion-{light,dark}.png` | same card / "Requires an A12 chip or later." |
| AR Recording | `ios-ar-record-playback-{light,dark}.png` | same card / "Requires an iPhone with ARKit and screen recording availability." |
| AR Placement (the one placement entry) | `ios-ar-placement-{light,dark}.png` | same card / "Requires an iPhone with ARKit." |

Each card is shown **before** any camera starts, with a single `Back` action — the
unsupported case is explained, never faked and never compared against a fake.

The light and dark files are near-identical: the AR experience container renders its
requirement card on the camera-dark chrome in both appearances. That is pre-existing
behaviour of the shared container, not something this slice introduced.

## Android emulator — Pixel_7a, `emulator-5554`

ARCore never opens a session there, so every AR demo lands on its failure card. That is
the expected state and the reason these are single captures.

| Screen | File | State |
|---|---|---|
| Measure | `android-ar-measure.png` | "AR could not start. Return to the catalog and reopen this demo to try again." — Measure title and Settings chrome alive |
| Depth Occlusion | `android-ar-depth-occlusion.png` | "Couldn't start AR / Google Play Services for AR is installed, but this device could not start an AR session", with "Try again" |
| Cloud Anchor | `android-ar-cloud-anchor.png` | same card, plus "AR is unavailable on this device" and the Host / Resolve dock, both disabled |

The measure screen sits on a light surface while the two others sit on the dark AR
surface; the QA camera backdrop (#3308) is off unless QA mode is enabled, so none of the
three shows a room photo here.

## Not captured

- Automatic placement itself, occlusion toggles retaining the pose, Host/Record/Resolve
  and the measured chain: all need a real ARCore / ARKit device.
