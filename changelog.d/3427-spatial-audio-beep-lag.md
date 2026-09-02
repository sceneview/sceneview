<!-- category: Fixed -->
- **`SpatialAudioNode` no longer hitches the frame the sound starts on
  ([#3427](https://github.com/sceneview/sceneview/issues/3427), reported as "a slight lag
  right when the beep plays" in the Spatial Audio demo).** `SpatialAudioPlayer` built its
  private `MediaPlayer` with a synchronous, blocking `prepare()` — documented as
  "sub-millisecond" for a short in-`assets` clip, but the container parse and decoder setup
  it triggers is real main-thread work, and it landed inside the exact composition pass that
  also calls `play()` via `autoPlay`. Playback now goes through `prepareAsync()` and starts
  from the `onPrepared` callback instead, via a small `PreparePlayGate` state machine that
  defers a `play()` requested before the player is ready. Measured on the emulator with
  `dumpsys gfxinfo`: the frame around the bell's first loop went from a clear janky-frame
  spike to indistinguishable from the surrounding frames.
