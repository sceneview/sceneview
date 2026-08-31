# App Store AR visuals — light variants (#2844)

The **light** lighting variants (window daylight, airy exposure, warm neutral
tones) of the #2844 generated `00-ar` frames. The **dark** variants are the
ones committed to the upload set in
[`appstore-screenshots/`](../appstore-screenshots/README.md); full provenance
is documented in
`samples/android-demo/distribution/play-store/en-GB/graphics/README.md`,
"The #2844 AR set".

The class prefix replaces the folder, so swapping a variant is a copy + rename:

| File | Replaces | Pixels |
|---|---|---|
| `iphone-6.9-00-ar.png` | `appstore-screenshots/iphone-6.9/00-ar.png` | 1320 × 2868 |
| `ipad-13-00-ar.png` | `appstore-screenshots/ipad-13/00-ar.png` | 2064 × 2752 |

This directory lives **outside** `appstore-screenshots/` on purpose:
`asc_listing.py` uploads that directory's class folders as-is, and an extra
frame per class would change the live slot count. Nothing here is ever
uploaded.
