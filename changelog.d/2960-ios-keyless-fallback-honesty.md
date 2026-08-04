<!-- category: Fixed -->
- **iOS demo: a keyless build now says which model it is actually showing, and
  four stand-ins stopped contradicting their own label (#2960).** App Store
  builds ship no Sketchfab key, so `SketchfabAssetResolver` silently substitutes
  each slug's bundled USDZ — and iOS had no cue at all that a substitution had
  happened, so "Cushioned Sofa" over a mosquito in amber read as the real model
  (the #2913 failure mode: a confident wrong scene beats a visible stall). Every
  demo that streams (Scene Gallery, Materials, Physics, Animation, Multi-Model,
  Orbital AR, both AR placement demos) now shows an `AssetSourcePill` —
  "Streamed (cached)" / "Streaming…" / "Offline model" — driven by
  `AssetSourceProbe`, a port of Android's probe (#2989) that measures the file
  the resolver returned rather than trusting that a configured API key means the
  download succeeded. Four fallbacks were also re-pointed at bundled assets that
  match their label, with no new binary: *PBR Low-Poly Fox* → `khronos_fox`,
  *Desk Lamp* → `khronos_lantern` (both matching what Android already maps),
  *Walking Robot* and *Enforcer Mk1* → `cyberpunk_character` (verified to carry
  a baked `SkelAnimation`, so the playback demo still animates). The remaining
  mismatches in #2960 need a new bundled asset and stay open — the pill is what
  makes them honest in the meantime.
