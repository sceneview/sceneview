<!-- category: Added -->

- **Android animated-model framing:** `FitMode.Silhouette` fits side mid-lines and top/bottom
  face centres; `ModelInstance.animationEnvelope(clip, samples)` samples joint transforms over
  a clip in small main-thread batches, retaining each pose separately. Results are cached per
  asset/clip/sample count; private sampling instances leave visible playback unchanged.
  `fitCameraToBounds`, `CameraNode.fitToBounds` and `frameToBounds` accept the envelope.
- **Intentional auto-fit default change:** skinned animated models now use Silhouette and their
  clip envelopes. Static models retain Corners. `SceneView(fitMode = FitMode.Corners)` restores
  historical auto-fit behaviour. The Android Model Viewer demonstrates the SDK fit with the
  bundled Fox; static product framing is unchanged. Other platforms are unchanged.
