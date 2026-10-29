# AR Measure — measuring a real space with SceneView

`ar-measure` in the demo app · [`ARMeasureDemo.kt`](src/main/java/io/github/sceneview/demo/demos/ARMeasureDemo.kt)
· deep link `sceneview://demo/ar-measure`

Three modes behind one mode pill, built against Apple Measure, Google Measure and magicplan:

- **Distance** — aim the centre reticle and press **Add point**. The reticle snaps to points you
  already placed, to the corners and edges of a detected plane, and to vertical and level guides
  from the previous point. Each segment draws itself and carries a camera-facing length label.
  Aim at the first point again to close the shape and read its area.
- **Wall** — add the two ends of a wall where it meets the floor. The wall then rises with your
  aim, a translucent surface whose top follows the phone; **Set height** locks it and reads
  width, height and area. **Door or window** then marks an opening by two opposite corners on
  the wall, and the readout adds what the openings take off and the net area left to cover.
- **Room** — trace the floor corner by corner and close it on the first corner; every wall then
  rises together to the ceiling: floor area, perimeter, ceiling height, wall area, volume. A
  corner within 3° of square snaps to 90°, within 6° when ARCore tracks the next wall: a
  vertical plane standing on the floor (its bottom within 30 cm), at least 1.5 m tall and 1.2 m
  long, within 3° of square to the last wall, its foot within 20 cm of the corner. An open door
  (a 0.93 m leaf included, even with ARCore's plane a few centimetres past each end), a
  cupboard's side or a wall cupboard does not count: a wrong confirmation would square a corner
  up to 6° off without a word, where a missed one only falls back to 3°. After a short wall (1 m or less) the few
  centimetres of noise on its corners eat that 3° margin, so the corner often stays at 3°.
  **Door or window** marks openings on any of its walls the same way, two opposite corners on
  one wall, and the readout adds the openings and the net wall area; the floor area and the
  volume do not change. **Plan** opens the room as 3D Scan's dimensioned plan, with its PDF and
  text shares; with openings marked, a card under the plan names each one by the plan's wall
  letter and reads the net wall area.

**Add point** commits the displayed candidate without a second raycast; tapping the camera never
adds a point. Undo steps back one action at a time (an opening's first corner, the last opening,
a locked height, then the raised walls, then the closure, then a point). Share sends every figure
as plain text. Units follow the language, as the room plan's do: feet and inches in US English,
metres everywhere else. Settings switches them for the screen and its plan alike.

In Wall and Room every point after the first is the camera ray's hit on the **floor plane of the
first point**, not the raw hit test: aimed at the foot of a wall, the hit test lands on the wall a
few centimetres up, the floor plane gives the corner.

## Why this demo exists

Surveying a real space so you can build something that fits into it.

You are about to 3D-print a bracket for a shelf, a spacer between two studs, a mount for a
cable run behind a workbench. Before you can model it you need the space's numbers: how wide
is the gap, how much clear height is above the bench, how far apart are those two uprights.
Fetching a tape measure and holding it against an awkward corner alone is the annoying part
of that workflow, and it is exactly what a phone that already understands the room's geometry
should be able to do.

That is the case this demo serves: **taking the room's dimensions, so you can design against
them afterwards.** It is not an abstract "look, AR can draw a line" demo.

It also answers a question users have actually asked and never had answered:
[#531 — "is it possible to measure dimension of object in compose"](https://github.com/sceneview/sceneview/issues/531),
which was closed by the stale bot in 2024 and re-asked by a second user in July 2025.

## How accurate is it — read this before trusting a number

**Short version: this is a layout tool, not a caliper.**

ARCore produces the 3D point at the visible center target in one of two ways:

- **On a phone with no depth sensor** (most Android phones), depth is *inferred* from motion
  stereo — the device compares camera frames as you move and solves for depth. Google
  documents this path as usable for occlusion and placement, not metrology, and the practical
  error on a single measured point is **on the order of several centimetres**. Two points
  compound: a 2 m span can be off by 5 cm or more, and the error is not a stable bias you
  could calibrate away — it varies with texture, lighting, how much parallax your movement
  gave the tracker, and how long the session has been running.
- **On a phone with a ToF / LiDAR-class depth sensor**, depth is measured rather than
  inferred, and the error comes down **towards the centimetre**.

### What that is good for

- Will this cabinet fit in this alcove? (alcove 84 cm, cabinet 78 cm → yes, comfortably)
- How far apart are these two studs? (roughly 40 cm → design a bracket that spans them, with
  slots rather than fixed holes)
- What is the clear height under this shelf?
- Rough floor outline of a room, for planning where things go.

### What it is emphatically NOT good for

- **Any fitting dimension on a printed part.** A press fit, a bearing seat, a snap hook, a
  shaft diameter — these live at ±0.1 mm, three orders of magnitude below what this can see.
- Anything where being wrong by 3 cm is a scrapped print or a part that will not go in.

The working rule for the 3D-printing use case: **use AR to get the envelope, use calipers for
the interface.** Measure the alcove with your phone; measure the rail the bracket clips onto
with a caliper. Design tolerance and adjustment slots into anything dimensioned from an AR
reading.

### Measured error

> **Not yet measured on hardware.** This table is deliberately empty rather than filled with
> a plausible number. Filling it requires an Android device with ARCore and a reference
> object of known size, which no CI machine or emulator can stand in for: the ARCore
> emulator replays a synthetic scene, so any figure obtained there would describe the
> simulation, not the sensor. The demo ships with the `In review` badge until this table has
> real rows in it.

Protocol to fill it in — please add your rows rather than replacing them, device class is
the whole point:

1. Pick a reference object whose true dimension you can measure with a tape or caliper to
   ±1 mm. A door frame width, a table edge, a printed calibration bar. Record the true value.
2. Move the phone around the object for ~10 s before the first point, so ARCore has parallax
   to work with. Wait until the plane renderer shows a stable plane on the surface.
3. Take **five** independent measurements of the same span: clear between each one, and
   re-approach from a slightly different angle. One reading tells you nothing about the
   spread, and the spread is the interesting number.
4. Record device, whether it has a ToF sensor, the true value, the five readings, and the
   lighting/texture conditions.

| Device | ToF? | True | Readings (5×) | Mean error | Spread | Conditions |
|---|---|---|---|---|---|---|
| _(pending a device pass)_ | | | | | | |

## How it works

### Hit-test strategy

Each center-target candidate is resolved in a fixed preference order, which is an *accuracy* order:

1. **A detected plane**, when the center target lands inside the plane polygon. ARCore has fitted this
   surface over many frames, so it is the most stable target available. The
   `isPoseInPolygon` check matters: ARCore will happily report a hit on the *infinite
   extension* of a plane, so without it a candidate past the edge of a table places a point in
   mid-air and reports a confidently wrong distance.
2. **A `DepthPoint`** returned by the same hit test — geometry ARCore has depth for but has
   not grown a plane over.
3. **The depth image directly**, via SceneView's `Frame.hitTestDepth(x, y)`. This is what
   makes a *cluttered* space measurable rather than just its flat floor: a sofa, a slope, the
   lip of a workbench, where no plane will ever appear. Used whenever the device supports
   ARCore depth and skipped on the others; no setting turns it off.
4. **A raw feature point**, last resort and the noisiest of the four.

Each aim keeps its source (`MeasureHitSource`), but the screen does not show it: the chip under the
reticle names what the aim snapped to (point, corner, edge, vertical, level, square corner, wall
foot, close) or why a tap would do nothing.

### Anchors, not positions

Every point is an ARCore `Anchor`, not a captured coordinate. ARCore corrects anchor poses as
it refines its map of the room, and a measurement that ignored those corrections would slowly
drift away from the marker the user is looking at. The demo re-reads every anchor pose on
every frame — but only pushes the result into Compose state when a point has actually moved
more than 1 mm, so sub-millimetre tracking jitter does not recompose the scene at 60 Hz.

## Testing

The arithmetic behind every displayed number is pure Kotlin with no ARCore or Filament types:
[`MeasureMath.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureMath.kt) (distances),
[`MeasureGeometry.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureGeometry.kt)
(snapping, rising walls, areas, floor triangulation),
[`MeasureBoard.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureBoard.kt) (the tap and
undo flow), [`MeasureUnits.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureUnits.kt) and
[`MeasureReport.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureReport.kt) (share text),
covered by `MeasureMathTest` and `MeasureGeometryTest`; doors and windows, on a wall and on a
room's walls, by `MeasureOpeningsTest` and `MeasureRoomOpeningsTest`:

```bash
./gradlew :samples:android-demo:testDebugUnitTest --tests '*MeasureMathTest*' --tests '*MeasureGeometryTest*'
```

This is not ceremony. A wrong distance formula renders a perfectly plausible centimetre label
— the app does not crash, the screenshot looks right, and the number is simply false. No
device pass, emulator run or visual QA catches that; only these tests do. (Verified by
mutation: dropping the `z` term from the distance formula fails 4 of the 18 tests.)

## Exporting a session to Rerun (not wired up)

For offline inspection of *why* a measurement came out the way it did — where the camera
actually went, which planes existed when the point was added, how dense the point cloud was around each
anchor — SceneView already ships a [Rerun](https://rerun.io) bridge:
`io.github.sceneview.ar.rerun.RerunBridge`, exercised by the `ar-rerun` demo. There is no
official Rerun SDK for Kotlin; the bridge streams JSON lines over TCP to a Python sidecar,
which writes an `.rrd` you can open in the Rerun viewer. No NDK work involved.

This demo deliberately **does not** wire it up — measuring and debugging are separate
concerns and the demo stays about measuring. If you want it, it is a handful of lines:

```kotlin
val rerun = rememberRerunBridge()
// in onSessionUpdated:
rerun.logCameraPose(frame.camera.pose, frame.timestamp)
rerun.logPlanes(session.getAllTrackables(Plane::class.java), frame.timestamp)
rerun.logAnchors(points.map { it.anchor }, frame.timestamp)
```

See [`RECORDING_PLAYBACK.md`](RECORDING_PLAYBACK.md) and the `ar-rerun` demo for the sidecar
setup.

## Related

- `ar-placement` — automatically place a model on the first usable surface
- `rolling-balls`, On your table mode — balls bouncing off the depth mesh
- `ar-raw-depth-point-cloud` — what the depth sensor actually returns
- [`AR_TESTING.md`](AR_TESTING.md) — running the AR demos on device and on the AR emulator
