# #2957 — legend chip drawn across the grounded box (`contact-shadow-preview`)

Device-QA evidence for the fix. Both APKs were built from source on the same host and
driven on the same emulator in the same session, so the two columns differ only by the
change under review.

| | |
|---|---|
| Device | QA pool AVD `Pixel_7a`, `emulator-5554`, 1080 × 2400, density 420 |
| Before | `origin/main` @ `640400a` — `:samples:android-demo:assembleDebug` |
| After | this branch — same task |
| Install | `adb install -r` + `pm clear io.github.sceneview.demo` before every capture |
| Entry | `am start -n io.github.sceneview.demo/.MainActivity --es demo contact-shadow-preview` |

`--ez qa_mode true` freezes the hop clock at t = 0, which is **ground contact** — the
landing pose, i.e. the worst case for this defect and a deterministic frame. The live-motion
bursts (`qa_mode` off, 8 frames ≈ one 2.6 s hop cycle) cover before / at / after landing.

## Measurement

Both rectangles come from pixels, not from the accessibility tree:

- **grounded box** — leftmost 4-connected component of `R > 140 ∧ R − B > 60 ∧ G > 80`;
- **legend chip** — leftmost dark component (`R,G,B < 95`) that is chip-shaped
  (20 < h < 140, 120 < w < 700), excluding full-width system bands;
- **viewport bottom** (after only) — lowest row whose median brightness is scene-bright.
  The legend chip is entirely below it, so the overlap is zero by layout. Cross-check:
  the a11y node ` Contact shadow` reports centre `[215, 2212]`, dead centre of the chip
  rect `x42–367, y2183–2242` derived from the 16 dp start gutter and the unchanged 325 px
  chip width — and `y2183` is exactly the measured viewport bottom.

## Numbers

| Frame | box bbox | legend chip | overlap |
|---|---|---|---|
| before, landing (QA mode) | x79–406, y1675–2054 | x232–557, y1879–1938 | **174 × 59 px** = 53 % of box width |
| before, live motion | — | — | 3 of 8 frames overlap, max 171 × 59 px |
| after, landing (QA mode) | x115–417, y1572–1922 | x42–367, y2183–2242 | **0 × 0 px**, 261 px clearance |
| after, live motion | — | — | 0 of 8 frames overlap, min clearance 107 px |
| after, font scale 1.5 | x157–429, y1551–1866 | band top y2101 | 0 × 0 px, 235 px clearance |
| after, landscape 2400 × 1080 | — | band top y863 | 0 × 0 px |

The reserved band is measured, not constant: it is 217 px at font scale 1.0 and 299 px at
1.5, because the chip row wraps and the scaffold subtracts whatever it actually measures.

`2957-legend-overlap-before-after.png` — landing pose, both builds. Yellow = box bbox,
blue = legend chip, red = overlap, green = viewport bottom.
`2957-legend-overlap-robustness.png` — after build at font scale 1.5 and in landscape.
