<!-- category: Fixed -->
- **Demo: the `materials` demo now produces a reproducible frame (#2874).** Two
  things made it non-reproducible, and both are fixed. **(1) The subject was
  streamed.** The **PBR Materials** section opened on a Sketchfab slug, so what
  the first frame showed depended on the API key, the network and the disk cache
  — two captures of the same demo id from the same build showed a different
  model. It now opens on a **bundled** subject: Khronos' `ToyCar`, already in the
  APK, whose GLB declares `KHR_materials_clearcoat`, `KHR_materials_sheen` and
  `KHR_materials_transmission` — the three extension families the section is
  about, on the car body, the seat fabric and the windows. That is strictly more
  than the old offline path showed, since every slug fell back to
  `khronos_damaged_helmet.glb`, which declares no `KHR_materials_*` extension at
  all. The streamed catalogue is unchanged and stays one chip tap away, so
  variety survives as an explicit user action. **(2) The backdrop was a
  photograph swept by the camera.** The sections drew the `studio_2k` skybox,
  which — despite its `neutral / studio / product` tags in `assets/catalog.json`
  — decodes to a domestic living-room interior; drawn behind a camera that
  orbits 360° every 18 s, one environment shows a different room feature in
  every capture, which is what #2874 saw as "a different HDRI each launch".
  Measured: swapping to a genuine photo-studio HDRI did **not** fix it (two
  cold launches came back with the same subject against the studio's dark side
  and its bright sweep), so the material sections now share one constant,
  `MATERIALS_SHOWCASE_HDR = environments/studio_warm_2k.hdr`, used as **IBL
  only** — the materials still read the environment through their reflections,
  the backdrop is the demo's own surface at every orbit angle. Framing is
  subject-independent too: every chip is normalised to the same size and viewed
  from the same orbit radius instead of each model's own `scaleToUnits`
  (0.15 m for the beetle, 0.90 m for the sofa), so the subject no longer reads
  as a speck — measured on the phone capture, its base now spans 98–100% of the
  frame width. The cold-launch contract (default subject is bundled, never
  streamed) is asserted by `MaterialsSubjectsTest` on the JVM, because this
  defect is invisible to a per-frame check: every capture looked fine, they just
  differed from each other.
