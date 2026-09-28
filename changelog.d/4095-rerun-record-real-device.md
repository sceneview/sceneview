<!-- category: Fixed -->
- **Demo (Android):** Rerun AR Replay's Record mode records the room again on a real phone. The recorder's first feature-point and plane sample was never due (a `Long.MIN_VALUE` timestamp overflowed), so a scan kept the camera path but no point and no plane, and its `.ply` export had zero vertices.
- **Demo (Android):** the replay's photo-coloured feature points draw again (#4095). Their colour atlas was sampled upside down, on rows it never wrote, so the whole layer was transparent.
- **arsceneview:** `PlaneRendererV2` builds its depth-driven plane mesh again. The same `Long.MIN_VALUE` overflow kept the depth path from ever running, so V2 always fell back to the flat polygon.
