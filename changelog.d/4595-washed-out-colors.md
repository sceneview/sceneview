<!-- category: Changed -->
- **`createView` now tone-maps with PBR Neutral instead of Filmic, so base colours display as
  authored.** Filmic's shoulder bleached light and pastel colours and its toe crushed darks:
  measured on the demo's brand ramp, a light blue (`#A4C1FF`) kept 40–57 % of its chroma and a
  lilac (`#D2A8FF`) 45–65 %, the "washed-out" pink and blue of every 3D demo. Khronos PBR Neutral
  is the identity up to 0.76 and only compresses what is brighter: 90–99 % of the chroma survives.
  `createARView` keeps Filmic (the camera-stream materials invert it), and `SplatNode` now inverts
  whichever of the two its view uses. To get the old look back:
  `view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(engine)`.
  The web viewer keeps Filmic — Filament's JavaScript bindings have no PBR Neutral tone mapper.
- **Demo: the Materials studio linearises its sRGB colours.** Car Paint, Velvet, Glazed Ceramic,
  Crystal and Signal Glow handed sRGB bytes to a linear `baseColor` and rendered as pastels; the
  measured metals keep their linear reflectance. The home hero and Cosmos keep Filmic, the curve
  their look was tuned on.
