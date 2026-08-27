<!-- category: Fixed -->
- **The Spatial Audio demo now says what is emitting the sound ([#3332](https://github.com/sceneview/sceneview/issues/3332)).** The
  scene was two unlabelled spheres — a big orbiting one carrying the bell and a tiny
  centre marker that meant nothing — with no cue about where the sound started or who
  was hearing it. The emitter now pulses translucent shells outward so it reads as the
  source at a glance, the meaningless centre marker is replaced by the faint ring of the
  orbit path it travels on, and a glass legend over the scene names both roles ("sound
  source" / "listener — the camera, i.e. you") next to a live readout of the
  source-to-listener distance and the gain the selected falloff curve is applying, taken
  from the same `AudioFalloff.gainFor` the audio backend uses.
