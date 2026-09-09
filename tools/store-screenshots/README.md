# Store screenshot compositor

`compose.py` turns a raw device capture into the exact PNG a store listing
uploads: it crops the status bar, scales the frame into a rounded card and
draws a caption band above it. The captions are the listing's promises — a
visitor scrolling the Play or App Store carousel reads them before any pixel of
UI, and the category (Polycam, Sketchfab, Reality Composer) captions every
slot. Before this script the two sets shipped raw, uncaptioned captures.

It is a compositor, not a capture tool. Capturing stays manual and documented
where each set lives:

- Play: `samples/android-demo/distribution/play-store/en-GB/graphics/README.md`
  (`emulator-5554`, the one `Pixel_7a` AVD, `adb exec-out screencap`, 96 px of
  status bar to crop).
- App Store: `samples/ios-demo/appstore-screenshots/README.md`
  (`xcrun simctl`, `-demo <id> -qa_mode 1`, no status bar to crop).

## Running it

```bash
python3 tools/store-screenshots/compose.py --manifest tools/store-screenshots/slots.json      # Play, 6 phone slots + feature graphic
python3 tools/store-screenshots/compose.py --manifest tools/store-screenshots/slots-ios.json  # App Store, iphone-6.9
```

Each manifest entry names its source, its output and its exact pixel spec, so a
re-capture is one `cp` into `raw/` and one re-run — never a hand retouch. Three
kinds:

| `kind` | What it draws |
|---|---|
| *(default)* | caption band + the capture as a card |
| `split` | caption band + the `snippet` code panel + the frame that code renders |
| `banner` | Play feature graphic: `gradient-hero`, promise left, art right |

## Colours

Only DESIGN.md tokens, dark column: `surface` `#0d1117`, `surface-dim`
`#161B22`, `on-surface` `#f3f4f6`, `primary` `#a4c1ff`, the `syntax-*` set for
the code panel and `gradient-hero` (`#005bc1` → `#6446cd`) for the banner.

## `raw/` is not committed

The sources are heavy (3–7 MB a frame) and reproducible: the captures from the
procedures above, and `raw/ar-room-phone.png` / `raw/ar-room-iphone.png` from
`tools/demo-previews/gen.py --kind store` (item `ar-phone-room`, reference
`refs/printed_icosahedron.webp` — a crop of the app's own 3MF render, so the
marketing visual shows a model the app really loads). The simulator and the
emulator have no camera, so the AR slot cannot be captured; it is generated,
exactly as `appstore-screenshots/README.md` documents for its own `00-ar.png`.

Look at every output by eye before uploading. Nothing here checks that a frame
is not black, that a caption fits, or that the subject is centred.
