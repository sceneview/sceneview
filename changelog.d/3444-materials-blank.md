<!-- category: Fixed -->
- **`SceneView(onFrame = …)` now fires only for frames that actually reached the surface, so the
  Materials demo no longer shows a blank viewport
  ([#3444](https://github.com/sceneview/sceneview/issues/3444)).** `onFrame` was invoked from the
  pre-render step, before `Renderer.beginFrame` had decided anything — and Filament refuses frames
  while the GPU is behind. On the QA emulator the Materials demo presents **4 frames in its first
  6.3 s** (Filament compiling the ToyCar's `KHR_materials_clearcoat` / `_sheen` / `_transmission`
  variants) before settling at 60 fps, so the callback fired on refused attempts, the demo
  scaffold's loading cover lifted on tick 1, and the viewport sat black for ~10 s with no spinner,
  no label and not even the 12 s "Still loading…" card — which is what the reporter, the QA
  screenshot and any store capture recorded. The callback is now gated on
  `SceneRenderer.presentedFrameCount` actually advancing; everything else in the tick (load
  updates, node ticks, framing passes, the camera manipulator) still runs every frame, or a
  stalled surface could never recover. No signature changed — only when the callback fires.
- **The demo scaffold waits for a *sustained* frame cadence before dropping its loading cover.**
  A presented frame means *submitted*, not *displayed*: a warming driver spends ~1.5 s on each of
  those first frames and still emits the occasional close pair, so "one frame arrived" — and even
  "two arrived quickly" — uncovered a surface the driver would not paint for another 8 s.
  `FirstFrameState` now needs 8 presented frames in a row no more than 250 ms apart (~133 ms once
  the loop runs at 60 fps, unreachable during warm-up); the 12 s "Still loading…" card remains the
  backstop for a device that never gets there.

- **The demo viewport names its own state.** It is "Scene loading" while the cover is up and
  "Scene ready" once a frame has reached the surface, so TalkBack announces whether there is
  anything to look at instead of leaving an unlabelled rectangle.
- **A QA launch (`--ez qa_mode true`) no longer lands on the "What's new" sheet.** A QA run
  deep-links into a single demo; a modal that opens itself over it swallowed the gestures and could
  end up in the capture. It stays silent rather than acknowledging itself, so the badge still has
  its list waiting for the next human who opens the app.

<!-- category: Tests -->
- **The Android device-QA flow waits for real pixels before it interacts, and asserts them before
  it captures.** Liveness alone passed a black demo — the Activity was perfectly alive the whole
  time — and waiting for the loading cover to be *absent* passed just as trivially, in the instant
  between `launchApp` and the first composition. `.maestro/android/flows/demo.yaml` now waits for
  the viewport's positive "Scene ready" node **before** the orbit swipes (the cover swallows
  touches, so swiping under it orbited nothing) and asserts it again at capture time. That is the
  app's own "there are pixels" signal, so it needs no pixel reader and no new script; a demo that
  never presents a frame now fails QA instead of passing it. The same signal replaces the zoom
  legs' fixed 9 s render-warm-up guess, which waited on a marker that never existed.
