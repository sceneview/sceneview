<!-- category: Fixed -->
- **The demo app's own chrome hid the "AR unavailable" card it was supposed to let through
  ([#3341](https://github.com/sceneview/sceneview/issues/3341)).**
  #3374 gave the SDK a real availability state and an explanation card, but on an
  unsupported device — every emulator (#2754) — the demos still showed a black viewport
  forever. `ARCameraInitScrim` is a full-screen opaque backdrop drawn as a later sibling of
  `ARSceneView`, dismissed when the first camera frame arrives; when ARCore rules the
  session out that frame never comes, so after its eight-second timeout the scrim settled
  into a permanent black cover *on top of* the card explaining why. It now takes the
  verdict as a required argument and steps aside the moment ARCore answers, at any point in
  the start sequence — waiting through the spinner phase is pointless once the answer is
  "this device cannot run AR". Every demo that draws the scrim now passes the verdict
  through, sourced from `onARCoreAvailability`.
  The demos' status copy no longer contradicts the card either: the Rooftop Anchor sheet
  and banner, the Cloud Anchor and Terrain Anchor guidance, and the Instant Placement
  scanning pill all keyed off "not tracking yet", which on an unsupported device is
  permanently true — so "Initializing camera…" sat next to a card saying the camera was
  never going to start. They now read the verdict first, matching what the Orbital demo
  already did.
  The same reading covers the shared "Scanning for surfaces…" banner: seven more demos
  gated it on a flag — `isTracking`, a detected-plane count, a first-plane boolean — that
  an unsupported device leaves untouched forever, so the banner promised a scan that could
  not start. Two of them, Depth Occlusion and AR Fog, only looked correct because the scrim
  was covering them; uncovering the card exposed the banner underneath. All seven now
  defer to the verdict.
  The demo scaffold's back control was already drawn above the scrim, so leaving the demo
  has worked throughout; what was missing was any reason to.
