# Play Store AR visuals — light variants (#2844)

The **light** lighting variants (window daylight, airy exposure, warm neutral
tones) of the #2844 generated AR set. The **dark** variants (evening interior,
warm floor lamp) are the ones committed to the live listing mirror in
[`en-GB/graphics/`](../en-GB/graphics/README.md) — see "The #2844 AR set" there
for full provenance (Gemini `gemini-3.1-flash-image`, reference model, crop and
resize pipeline).

Filenames and pixel sizes match the dark counterparts one-to-one, so swapping a
variant is a file copy:

| File | Replaces | Pixels |
|---|---|---|
| `feature-graphic.png` | `en-GB/graphics/feature-graphic.png` | 1024 × 500 |
| `phone-screenshot-1.png` | `en-GB/graphics/phone-screenshot-1.png` | 1080 × 2304 |
| `tablet7-screenshot-1.png` | `en-GB/graphics/tablet7-screenshot-1.png` | 1200 × 1872 |
| `tablet10-screenshot-1.png` | `en-GB/graphics/tablet10-screenshot-1.png` | 1600 × 2512 |

This directory lives **outside** `en-GB/graphics/` on purpose: that directory
mirrors the Play listing byte-for-byte, and `play_listing.py`'s test suite
fails on any file there that no `imageType` pattern claims. Nothing here is
ever uploaded.
