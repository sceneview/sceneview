<!-- category: Fixed -->
- **AR — session teardown:** `ARSceneView` now pauses a still-resumed ARCore session before
  closing it, so a playback (or live) session is always torn down in the order ARCore expects.
- **Demo — AR Recording replay:** the replay closes every placement `TrackData` right after
  decoding it instead of leaving it to the finalizer, which could release native track data
  after Back had already closed the playback session (#4026).
