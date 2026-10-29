# 3D Scan — coverage, camera and cinematics

Part of #4631. Design for the three things that make a scan *read*: seeing what is scanned and
what is not while scanning, a camera that behaves while the phone moves, and a result that
presents itself. Phase 2 (the library API in `io.github.sceneview.camera`) ships with this
document; phase 3 (the demo wiring) follows in its own PR.

The bar, from the product owner: "see clearly what is scanned and what is not", and "the best
possible render and the best scan result" — better renders *and* better use. Everything below
is chosen against that bar, not for show.

## 1. What the references do, and what we take

| Reference | What it does well | Taken |
|---|---|---|
| **Polycam** (LiDAR room / object) | Live mesh grows under the camera, textured on the fly; the unscanned side of a wall is simply *not there*, which is the clearest "not scanned" there is. Result opens on a slow orbit. | The principle: coverage is shown by *presence*, not by a colour legend. The result reveal. |
| **Scaniverse** | Live view tints the new surface for a second ("fresh paint") so you see what the last sweep added. Slider-driven mesh quality after capture. | The fresh-paint tint on new points, with a short fade. |
| **Matterport** (dollhouse / floor plan) | One continuous camera move between dollhouse and plan: the orbit cranes up to top-down, the walls stay, nothing cuts. Fly-to-room from the plan. | The dollhouse ↔ plan move as a single `flyTo` on elevation. Fly-to as a *carry* with a fly-over, never a cut. |
| **Apple RoomPlan** (live coaching) | Short imperative coach lines ("Move slower", "Point at the floor"), one at a time, bottom-centred. A faint white outline of walls as they are found. Never a heat-map. | One coach line at a time, in the existing `ScanCopy` voice. Frontier shown as an outline, not a gradient. |
| **Google Maps** (navigation camera) | Heading-up, the pitch tilts with speed, a turn is anticipated, and a route-overview zooms out above the way before diving back. | Heading-up follow (#4642). The fly-over bump in `CameraTransition`: pull back at mid-flight by half the subject separation. |
| **Luma** | Capture ring: a circle around the object with cells that fill as you cover each angle. The result's "hero" orbit with a rise. | The ring for *object* mode — the floor footprint is the same idea for a *room*. The tour's rise. |

Rejected on purpose: a coverage heat-map (RoomPlan never uses one and Polycam dropped it — a
legend is one more thing to read while walking), guidance arrows in 3D (they fight the camera
feed; a coach line and a mini-map do the job), and auto-rotation during the live scan (the
heading-up follow is the only camera motion the user did not cause).

## 2. Live: what is scanned and what is not

### 2.1 The footprint mini-map

A 2D map, bottom-start corner of `ScanLiveView`, drawn with Compose `Canvas`, heading-up like
the live 3D view (#4642), 112 dp square (`radius-lg`, `ar-scrim` behind it, `on-scrim`
strokes). Data is the `CoverageGrid`: 0.25 m cells on the floor (XZ) plane, from
`ArDebugFrame.mapPoints` and the camera trail.

- **Covered** cell: solid `accent-progress` at 60 % — "there is surface here".
- **Frontier** cell: a covered cell with an empty 4-neighbour — drawn as a 1 dp `on-scrim`
  outline, nothing inside. This is the edge of what you have; walk towards it.
- **Trail**: a 2 dp `on-scrim-muted` polyline, the phone's path.
- **You**: the same marker as the live view's "you are here", heading up, centred.
- **Coverage figure** under the map: `Floor 62 %` — the covered share of the grid's bounding
  box, the one number that says whether a room scan is done. Hidden in object mode.

Why a footprint and not a 3D overlay: the live 3D view already *is* the 3D overlay; what it
cannot show is the part of the room that is behind the phone. The map does, cheaply.

### 2.2 Fresh paint

Points added in the last 1.2 s are tinted `accent-progress` in the live 3D view and fade to the
cloud's colour (`duration-long` + 500 ms, `ease-expressive`). You see what the sweep you are
making adds, and you see a sweep that adds nothing — the second half of "what is not scanned".

### 2.3 One coach line

Bottom-centred over `ar-scrim`, `body-medium` on `on-scrim`, one at a time, each held at least
`duration-long` × 3 before it may change, in this priority:

1. Tracking lost → the existing `ScanCopy` line (unchanged).
2. Moving too fast (trail speed > 1.2 m/s over the last second) → "Slower".
3. Largest frontier gap is behind the user (bearing > 120° off heading) → "Turn around" with
   the map's frontier outline pulsing once (`motion-pulse`).
4. Nothing new for 3 s while moving → "Point at the floor" if the trail is level, else "Closer".
5. Otherwise, nothing. Silence is the default.

### 2.4 Live camera

The live camera is #4642's heading-up follow and is not changed here: 2.6 m back, 38° up,
turning with the phone, recentre pill when the user has taken over. The one addition is the
hand-off rule the library formalises: a touch during any automatic move inherits the move's
velocity (`CameraShotSink`), so the view never stops dead under the finger.

## 3. Result: the scan presents itself

All moves are `CameraPath` / `CameraTransition` played by a `CameraAnimator` under
`CinematicCameraManipulator`, so every one of them is interruptible by a touch and hands over at
matching velocity. Timings are DESIGN.md tokens.

| Move | Where | What it does | Timing |
|---|---|---|---|
| **Reveal** | once, when the dollhouse is ready (`DollhouseOpening.shown`) | Starts high (68°), wide (×2.3 distance) and 75° to the left of home, cranes down and round onto home. The same move as the replay entrance, now in the library (`CameraPath.reveal`). | 2.8 s, `ease-expressive` (`motion-entrance` lineage); first touch cancels |
| **Plan** | dock toggle | Elevation to 84° at the same target and distance: the dollhouse becomes the floor plan without a cut; toggling back cranes down again. Matterport's move. (`flyTo(home.copy(elevationDegrees = 84f))`) | `motion-entrance`, 700 ms |
| **Fly to room / object** | tap on a room label (after #4543's rooms exist) or an object | `CameraTransition` to that subject's home shot with the fly-over bump: pulls back by half the distance between the two targets at mid-flight so the camera never goes through a wall and the eye sees where it goes. | 700 ms, `ease-expressive` |
| **Tour** | dock item, and the share/video export | One lap at home elevation and distance, rising 6° at the half-lap, constant speed, looping seamlessly (`CameraPath.tour`). Luma's hero orbit. | 18 s per lap, linear |
| **Recenter** | existing dock item | `flyTo(home)` instead of the rig's own approach: one easing everywhere. | 700 ms |

What is *not* done: no auto-rotation at rest (the tour is explicit), no cinematic on every
screen change (`motion-screen` is the surface's job), no camera shake or lens effects.

## 4. Modes: object, room, home

The mode is decided from the result's bounds, not asked up front — scanning is the same gesture
in all three, only the framing and the coverage read differ.

| Mode | Longest side | Home shot | Coverage read | Reveal |
|---|---|---|---|---|
| **Object** | < 1.5 m | 32° up, 35° round, fit distance ×1.15 | Luma-style ring: 12 sectors around the object, a sector fills once ≥ N points were seen from that azimuth | reveal from −120° (more turn, the object is small) |
| **Room** | < 8 m | 32° / 35°, fit ×1.0 | Floor footprint, `Floor NN %` | reveal as above |
| **Home** | ≥ 8 m, or more than one room (#4543) | plan (84°) first, then reveal per room on fly-to | Footprint per room, rooms listed with their own % | reveal lands on the plan, not the dollhouse |

Mode is a pure function `ScanSubject.of(bounds, rooms)`; the UI reads it, nothing else decides.

Other modes considered and parked: **outdoor / façade** (needs Geospatial and a larger grid;
nothing in the scanner changes but the cell size) and **person** (a different product). Both
fit the same `ScanSubject` switch when they come.

## 5. The library (phase 2, `io.github.sceneview.camera`)

Five files, pure Kotlin but for the `remember` helper, JVM-tested:

- `CameraShot` — target, azimuth, elevation, distance; `eye`, `transform()`, `lookingAt`,
  `lerp` on the short arc. The orbit parameters interpolate on arcs *around* the subject,
  which is why there is no eye-position lerp here.
- `CameraTransition` / `cameraTransition(from, to, progress)` — one move with the fly-over bump;
  `CameraEasing.Expressive` is `ease-expressive`.
- `CameraPath` — Catmull-Rom over keyframes, C1 across a knot, loops; `reveal`, `tour`, `through`.
- `CameraAnimator` — time-based `advance(dt)` with a 50 ms cap (a stalled frame runs a move
  long, never jumps it), `play`, `flyTo` from wherever the camera is, `interrupt()` → velocity.
- `CinematicCameraManipulator` — one `cameraManipulator` that is the animator while it plays and
  the gesture manipulator otherwise; the first touch hands over, by shot + velocity to a
  `CameraShotSink`, by a 350 ms glide to anything else.

The AI-first test: `animator.play(CameraPath.reveal(home))` is the whole entrance;
`animator.flyTo(home.copy(elevationDegrees = 84f))` is the whole plan toggle.

## 6. Proof

Emulator (no AR): the result screen's reveal, plan toggle and tour as frame sequences (contact
sheets), light and dark, from a recorded scan; the live mini-map from the replay of the same
recording. Device (`needs-device`): the live footprint and coach lines on a real walk, and the
hand-off feel under a finger during the tour.
