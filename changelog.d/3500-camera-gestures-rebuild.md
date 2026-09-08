<!-- category: Changed -->
- **The Android demo's Camera & Gestures screen is rebuilt from scratch — one stage, one
  camera, and every camera capability expressed as something you *do* to it
  ([#3500](https://github.com/sceneview/sceneview/issues/3500)).** The screen was three
  demos behind a segmented toggle: a manipulator-mode picker (Orbit / Free Flight / Map)
  with a distance slider, a per-node edit screen with four switches, and a third mode
  showing the editing affordances the second deliberately drew without. It was an inventory
  of API surface, not a demonstration — every mode tore down its own engine on a switch,
  and the distance slider rebuilt the Filament `Manipulator` on every step, so each change
  **teleported** the camera. The one thing a camera screen exists to convey, that the camera
  is a place you move through rather than a parameter you set, was nowhere on screen.
  What replaces it is a three-subject stage on a lit floor, driven by a single spherical
  rig that is never rebuilt: **one finger orbits, two pan, a pinch dollies, and a release
  coasts to a stop**; a **tap flies to the subject under your finger**, framed from that
  subject's own size, and a double-tap returns to the whole stage; five named views (Hero ·
  Front · Side · Top · Close) fly to an angle **relative to whatever has focus**, always by
  the shortest arc round it; the dock's Cinematic item hands the camera to the SDK's own
  eased orbit ramp, spinning the framing *you* chose instead of teleporting to a canonical
  one; and its Move item makes the focused subject editable, so the same drag / twist /
  pinch moves the **object** — with the SDK's on-model affordances drawn over it — right
  next to the camera it competes with for the same gesture. A glass HUD prints the live
  azimuth, elevation and distance, the name of what has focus, and the gesture *while it
  runs*, because a camera demo that never says where the camera is asks you to infer it
  from pixels. The floor is what makes the difference legible: orbit swings its perspective
  lines, a pan slides them.
<!-- RELEASE NOTE (maintainer-only):
     The rig is the demo's own `StudioCameraManipulator`, not `DefaultCameraManipulator`:
     three of the screen's claims are impossible through Filament's `Manipulator`, which
     takes touch deltas and yields a look-at pair. A readout needs the spherical pose to BE
     the state (otherwise azimuth/elevation/distance are re-derived every frame and the
     numbers belong to the demo rather than to the camera); inertia needs the angular
     velocity the gesture produced, which the black box never exposes; and flying between
     two poses needs interpolation, where an opaque manipulator leaves only "rebuild it at
     the destination" — which is exactly why the old screen snapped. The maths lives in
     `CameraRig` as pure functions (round-trips, shortest-arc interpolation, geometric
     distance lerp, framing clamps, per-second inertia decay) and is pinned by 25 JVM cases
     in `CameraRigTest`. The `cameragestures_default` render golden depicted the old
     single-model scene and was deleted with it; the slug is commented out of
     `BASELINED_GOLDENS` in the same commit, so `DemoRenderingScreenshotTest` takes its
     documented first-run path — it saves the fresh capture for promotion and `assumeTrue`-
     skips, and the run after that verifies. Put the slug back in the SAME commit that adds
     the new PNG. `DemoInteractionTest` was rewritten against the controls that now exist:
     the old case tapped a "Node Gestures" tab, an "Editable" switch and a "Reset Position"
     button, none of which survive. -->
