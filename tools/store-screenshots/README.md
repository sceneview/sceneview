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
re-capture is one `cp` into `raw/` and one re-run — never a hand retouch. Four
kinds:

| `kind` | What it draws |
|---|---|
| *(default)* | caption band + the capture as a card |
| `split` | caption band + the `snippet` code panel + the frame that code renders |
| `ar` | the app's real AR chrome, lifted off a black-surface capture, over a generated room |
| `banner` | Play feature graphic: `gradient-hero`, promise left, art right |

`kind: ar` exists because an AR frame cannot be captured on hardware without a
camera, and a generated room photo alone shows no app. `merge_ar` composites the
two halves: `bg` (generated), `ui` (the capture), `ui_keep` (the chrome bands to
take — the same frame carries ARCore's "couldn't start" error on an AVD),
`matte` (`opaque`, the chrome as drawn; `add` for a premultiplied read) and
`shadow` (a multiplied contact ellipse). The demo must be launched with
`--ez qa_backdrop false` and **no** `qa_mode`, so the surface is black and no QA
chip is drawn, and with the model armed that the frame's subject shows. See the
Play graphics README for the full recipe and for what is deliberately absent
(the reticle and the coaching line, which need a live session).

## Colours

Only DESIGN.md tokens, dark column: `surface` `#0d1117`, `surface-dim`
`#161B22`, `on-surface` `#f3f4f6`, `primary` `#a4c1ff`, the `syntax-*` set for
the code panel and `gradient-hero` (`#005bc1` → `#6446cd`) for the banner.

## `raw/` is not committed

The sources are heavy (3–7 MB a frame) and reproducible: the captures from the
procedures above, and the generated room art from `tools/demo-previews/gen.py --kind store`:
`raw/ar-room-chair-phone.png` (item `ar-phone-chair-room`, reference
`refs/sheen_chair.webp`) under the Play AR composite, `raw/ar-room-phone.png` /
`raw/ar-room-iphone.png` (item `ar-phone-room`, reference
`refs/printed_icosahedron.webp`) under the feature graphic and the iOS AR slot.
Every reference is a crop of what the app itself renders, so a marketing visual
never shows a model the app cannot load (#3454). `raw/ar-chrome-black.png` is
the chrome capture the Play AR composite keys off — re-capture it, per the Play
graphics README, whenever the AR screen's chrome changes.

Look at every output by eye before uploading. Nothing here checks that a frame
is not black, that a caption fits, or that the subject is centred.
