# AR Measure — measuring a real space with SceneView

`ar-measure` in the demo app · [`ARMeasureDemo.kt`](src/main/java/io/github/sceneview/demo/demos/ARMeasureDemo.kt)
· deep link `sceneview://demo/ar-measure`

Tap two points on the real world; read the distance between them in centimetres, on a label
anchored in 3D at the midpoint of the segment. Keep tapping to build a chain, close the loop
to get a perimeter, and read the bounding box of every point placed so far.

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

ARCore produces the 3D point under your finger in one of two ways:

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
2. Move the phone around the object for ~10 s before the first tap, so ARCore has parallax
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

Each tap is resolved in a fixed preference order, which is an *accuracy* order:

1. **A detected plane**, when the tap lands inside the plane polygon. ARCore has fitted this
   surface over many frames, so it is the most stable target available. The
   `isPoseInPolygon` check matters: ARCore will happily report a hit on the *infinite
   extension* of a plane, so without it a tap past the edge of a table places a point in
   mid-air and reports a confidently wrong distance.
2. **A `DepthPoint`** returned by the same hit test — geometry ARCore has depth for but has
   not grown a plane over.
3. **The depth image directly**, via SceneView's `Frame.hitTestDepth(x, y)`. This is what
   makes a *cluttered* space measurable rather than just its flat floor: a sofa, a slope, the
   lip of a workbench, where no plane will ever appear. Toggleable in the settings sheet, and
   disabled automatically on devices that report no depth support.
4. **A raw feature point**, last resort and the noisiest of the four.

The demo names the source of each point on screen ("Point 3 on depth map"), because a
measuring tool that hides how it got its number is a measuring tool you cannot calibrate.

### Anchors, not positions

Every point is an ARCore `Anchor`, not a captured coordinate. ARCore corrects anchor poses as
it refines its map of the room, and a measurement that ignored those corrections would slowly
drift away from the marker the user is looking at. The demo re-reads every anchor pose on
every frame — but only pushes the result into Compose state when a point has actually moved
more than 1 mm, so sub-millimetre tracking jitter does not recompose the scene at 60 Hz.

### Bounding box caveat

The `W · H · D` readout is axis-aligned to the **ARCore world frame**, not to the object.
`+Y` is gravity-up, so `H` is a physically meaningful vertical extent. `X` and `Z`, though,
are fixed at session start from wherever the device happened to be pointing — so `W` and `D`
are extents along two arbitrary horizontal axes, not along the object's own edges. A table
sitting at 45° to the session axes yields a box noticeably larger than the table. Start the
session roughly square to what you are measuring.

An object-aligned box would need a horizontal-plane PCA over the point set plus a convention
for which side is "width"; that would become the thing the demo teaches, instead of AR
measurement. It is left out on purpose.

## Testing

The arithmetic behind every displayed number is a pure Kotlin file with no ARCore or Filament
types, [`MeasureMath.kt`](src/main/java/io/github/sceneview/demo/demos/internal/MeasureMath.kt),
covered by [`MeasureMathTest`](src/test/java/io/github/sceneview/demo/demos/internal/MeasureMathTest.kt):

```bash
./gradlew :samples:android-demo:testDebugUnitTest --tests '*MeasureMathTest*'
```

This is not ceremony. A wrong distance formula renders a perfectly plausible centimetre label
— the app does not crash, the screenshot looks right, and the number is simply false. No
device pass, emulator run or visual QA catches that; only these tests do. (Verified by
mutation: dropping the `z` term from the distance formula fails 4 of the 18 tests.)

## Exporting a session to Rerun (not wired up)

For offline inspection of *why* a measurement came out the way it did — where the camera
actually went, which planes existed at tap time, how dense the point cloud was around each
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

- `ar-placement` — tap-to-place a model on a plane (the hit-test brick this demo builds on)
- `ar-depth-collider` — the depth mesh, rendered
- `ar-raw-depth-point-cloud` — what the depth sensor actually returns
- [`AR_TESTING.md`](AR_TESTING.md) — running the AR demos on device and on the AR emulator
