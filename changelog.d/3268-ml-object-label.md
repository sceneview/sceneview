<!-- category: Fixed -->
- **android-demo**: `ar-ml-object-label` no longer crashes after a handful of detections. The
  ML Kit success listener runs on the main thread while ARCore's `Session`/`Frame` belong to
  the render thread — the listener was calling `frame.hitTest` on a `Frame` reference that was
  already several updates stale by the time a ~30–80 ms detector pass completed, a cross-thread
  access to ARCore's non-thread-safe native session. Anchor creation now happens back on the
  render thread, in `onSessionUpdated`, against that frame's own current `Frame`. The demo also
  now explains, once a label is anchored, that ML Kit's bundled detector classifies only five
  broad categories (Home good, Fashion good, Food, Place, Plant) — so an indoor scene landing
  almost entirely on "Home good" reads as expected behaviour, not a bug (#3268).
