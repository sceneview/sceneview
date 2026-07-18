<!-- category: Changed -->
- Point & Ask demo — film-mode pass (#2648): the capture is now the **composited
  AR frame** (window `PixelCopy` — camera + placed virtual objects, overlays
  hidden during capture), so the on-device model sees the augmented scene;
  long-press places a 3D prop (`hitTest` → `AnchorNode` + `ModelNode`); tap ping
  animation; answer card shows the asked question and a
  "Gemini Nano · on-device · no network" badge (`ConnectivityManager`
  active-network check) and auto-dismisses after 12 s; single auto-hiding
  instruction pill. QA-mode path (canned engine + synthetic frame) unchanged.
