# iOS App Store screenshots

Fresh, correctly-sized App Store Connect screenshots for the SceneView demo
app — real iOS-simulator captures of rendered 3D content, each one under an
English caption.

> ✅ **The committed set is the captioned six-slot v3, captured 2026-09-09**
> from this branch's build on the iOS 26.3 simulators (iPhone 17 Pro Max →
> `iphone-6.9/`, iPad Pro 13-inch (M4) → `ipad-13/`). It replaces the three
> uncaptioned frames of #3384: a visitor scrolling the carousel reads a caption
> before any pixel of UI, and the category (Polycam, Sketchfab, Reality
> Composer) captions every slot.
>
> | Slot | File | Caption | Source |
> |---|---|---|---|
> | 1 | `00-open-file.png` | Open any 3D file | `-open_file` on a bundled `printed-icosahedron.3mf` — the frame also shows the app's own real-size read-out (127.6 mm) |
> | 2 | `01-ar.png` | Real size, your room | the **generated** AR visual (see below) — no simulator has a camera |
> | 3 | `02-demos.png` | Nearly fifty demos | the Showcase home (47 `@sceneId` scenes ship today) |
> | 4 | `03-dynamic-sky.png` | HDR lighting, real sky | `-demo dynamic-sky -qa_mode 1` |
> | 5 | `04-materials.png` | Materials that catch the light | `-demo materials -qa_mode 1` — the keyless offline stand-in, i.e. what a store visitor actually gets |
> | 6 | `05-swiftui.png` | A few lines of SwiftUI | a SwiftUI snippet over the `reflection-probes` frame |
>
> Composited by `tools/store-screenshots/compose.py` from
> `tools/store-screenshots/slots-ios.json`: the captions, the card and the code
> panel are drawn there, from DESIGN.md tokens only. Re-capture, `cp` into
> `tools/store-screenshots/raw/ios/`, re-run the manifest — never retouch a PNG
> by hand.
>
> **`model-viewer` is deliberately not in this set.** Its frame is the #3383
> defect in the open: the hovercar sits at 62 % of frame width with the left
> third empty, dark grey bodywork on a light grey cyclorama. It reads as an
> off-centre thumbnail next to five composed frames. Re-add it when #3383
> lands, not before.
>
> ⚠️ **`-qa_mode` persists.** It is stored, not per-launch: a device that once
> ran `-qa_mode 1` keeps drawing the "QA ×" chip beside the title pill, even on
> a later launch with no argument, and `DemoSheet` only suppresses it for
> `-demo` launches (`DeepLinkRouter.isScriptedCapture`). The `-open_file`
> capture therefore has to pass `-qa_mode 0` explicitly (or run on a
> freshly-created device). A shipped frame must never carry that chip.
>
> ⚠️ **The iPad frames carry their capture date, and `simctl` cannot pin it
> (#3004).** `simctl status_bar override --time "9:41"` fixes the clock, but
> iPadOS draws the date beside it. Keep the plain `"9:41"` form and read the
> iPad class as reproducible within a capture day, not across days.
>
> Nothing enforces any of this — the upload is manual and `asc_listing.py`
> compares checksums, not pixels — so it is a note, not a gate. Look at the
> mosaic before you upload.

## The AR slot is generated, not captured (#2844)

The listing text sells AR and no capture can show it: the simulator has no
camera. `01-ar.png` in each class is an **AI-generated marketing visual**
(Gemini `gemini-3.1-flash-image`, image-to-image, cropped to the class's exact
pixel spec) — the `khronos_damaged_helmet.glb` helmet anchored in a real
photographed room, per DESIGN.md's "Preview Image Art Direction" (real camera
background, no text/UI/device frame/people). It is the same art the #3461
`00-ar.png` carried, recomposited under its caption; the prompts live in
`tools/demo-previews/store.json` (`ar-phone`, `ar-tablet`). Replace it with a
real device capture whenever an authorized device session produces one.

## Background — issue #917

The App Store Connect listing previously carried stale screenshots:

- **iPhone 6.5"** — 4 images, all *Android-device* captures (Android status
  bar + UI chrome). Two of them were blank white AR scenes showing only a
  "Move your phone…" toast.
- **iPad 13"** — the same phone-aspect images letterboxed onto an iPad
  canvas, not genuine iPad captures.
- **iPhone 6.9"** (newest required size) — not populated.

These images are genuine iOS-simulator captures of demos that render rich,
full 3D content. No blank/loading AR scenes.

## Required device classes & dimensions

App Store Connect rejects off-spec images, so dimensions must be exact:

| Folder        | Device class   | Pixels      | Captured on                  |
|---------------|----------------|-------------|------------------------------|
| `iphone-6.9/` | iPhone 6.9"    | 1320 × 2868 | iPhone 16/17 Pro Max sim     |
| `ipad-13/`    | iPad 13"       | 2064 × 2752 | iPad Pro 13-inch (M4/M5) sim |

The iPhone 16 Pro Max and iPhone 17 Pro Max share the **same 6.9" display
class** (identical 1320×2868 screenshot spec); likewise the iPad Pro 13-inch
M4 and M5. Either generation produces an App Store Connect-compliant image
for its class.

## Demos captured (#2854, #2896, #3384)

Five of the six slots are real simulator captures (slot 2 is generated, see
above). All of them render rich 3D content with **no network** — deliberately
not empty or loading AR scenes:

1. `00-open-file` — `-open_file` on the bundled `printed-icosahedron.3mf`,
   the frame the "Open any 3D file" promise is made of. It is the only slot
   that proves the claim rather than illustrating it: the app's own read-out
   reads `127.6 × 127.6 × 127.6 mm (12.8 × 12.8 × 12.8 cm) · 20 triangles`,
   i.e. a 3MF parsed at real-world scale. Launch it with **`-qa_mode 0`**
   (see the warning at the top) — `-open_file` is not a `-demo` launch, so
   the QA chip is not suppressed for it.
2. `01-ar` — generated, not captured. See the AR section above.
3. `02-demos` — the Showcase home, no launch argument, scrolled to the top.
   It carries the caption "Nearly fifty demos"; there are 47 `@sceneId`
   scenes today, so the caption stays true as the catalog grows and does not
   need re-editing on every added demo.
4. `03-dynamic-sky` — the `khronos_damaged_helmet` hero under a live HDRI
   sky, its metal and rough-dielectric regions carrying the time-of-day
   light. Same subject Android's Lighting Lab shows for this id (#3003).
5. `04-materials` — the material sphere grid. Since #2874 the id opens on a
   bundled subject with a reproducible backdrop, so what the frame shows is
   what a **keyless** user gets, which is the whole point of putting it on a
   store listing.
6. `05-swiftui` — the `reflection-probes` frame with a SwiftUI snippet
   composited over it by `tools/store-screenshots/compose.py`. The code is
   real, copy-pasteable SceneView API; the panel is drawn from DESIGN.md
   syntax tokens, never from a screenshot of an editor.

⚠️ **Committing these PNGs is not uploading them.** The live App Store listing
keeps showing the previous set until someone runs
`store-sync/asc_listing.py --apply-screenshots` (or uploads through App Store
Connect). Until that happens, do not describe the two stores as in sync.

### Why `multi-model` is not here

⛔ **Do not re-add it by symmetry with the Android phone set.** An App Store
capture build has no Sketchfab key, so `SketchfabAssetResolver` substitutes the
registered bundled stand-ins (`SampleAssets.swift` — bench → `retro_piano.usdz`,
dog → `animated_butterfly.usdz`, bird → `phoenix_bird.usdz`). The captured frame
is therefore *not* the park diorama the demo documents: measured on the 6.9"
simulator (`-demo multi-model -qa_mode 1`), it renders an upright wooden piano
with a blossoming-tree diorama growing through it and a brightly-coloured bird
mid-frame — the tree slot's stand-in supplies both the trees and the ground
they stand on, so it dominates the composition rather than reading as one slot
of four. The whole formation also sits small in a tall portrait frame.

That frame passes every mechanical check — right dimensions, settled,
byte-reproducible — so only looking at the mosaic catches it. It is the same
defect class that took `multi-model` out of the Android **tablet** set
(#2913/#2915: "do not re-add on the strength of a green capture"), and shipping
it would advertise a scene no keyless user can ever see.

The exclusion is **not** gated on an issue. #2913 is closed and the re-add did
not become due — the reason is structural (keyless resolver substitution), not
a bug someone was going to fix. Re-add only after looking at a freshly captured
frame next to the other slots.

Two further ids that used to be in this set were retired from Android's for
defects that are platform-independent, so do not reach for them here either.
`double-pendulum` renders as a tiny linkage in a mostly-black frame, and
`multi-model` is covered above.

`geometry` clipped its primitives in a portrait frame (#2873). That is **fixed
on Android** — but the fix is a layout change in the Android demo, so it does
not carry across: the iOS scene builds its own primitives, and whether it clips
in an iPhone-portrait frame has **not** been measured. Judge it on a captured
frame before either re-adding or retiring the id here; do not inherit the
Android verdict in either direction. Even on Android the id stays out of the
set: the fixed 2 × 2 cluster leaves the frame centre empty, which that capture
script's variance guard reads as blank.

Captured with the simulator in **dark appearance** and a cleaned status bar
(fixed 9:41, full signal/battery), mirroring the Android capture's dark-mode +
status-bar crop. The captured frame is the *input*: every committed PNG is the
compositor's output, cropped under its caption card, so the raw captures live
in `tools/store-screenshots/raw/ios/` (gitignored, reproducible) and are never
committed here.

⚠️ Dark appearance styles the **system chrome only** — it does not decide how
dark a frame is. Each scene's look comes from the HDRI it draws: the six slots
run from a daylit sky to a near-black reflection-probe stage, so do not read
"dark appearance" as "dark frame". This has been wrong in both directions
before — the paragraph claimed both frames were light while `model-viewer` was
in fact rendering on black, then claimed they sat at opposite ends the same day
the stage was fixed. Re-check it against the committed PNGs rather than
trusting it.

The status-bar glyphs are **not** the same colour across the set — iOS picks
for legibility against what is behind them, so a light sky gets dark glyphs and
a dark stage gets light ones. Both are legible; neither is a capture bug to
"fix". The caption card sits below the status bar and does not cover it.

One more thing the mosaic shows: the demos carry different app chrome — some a
multi-item glass dock, some a single round control, some a title pill and some
none. That is the demos' own shape, not a capture artefact, but it is visible
when the six sit side by side on the listing.

⚠️ **The capture pass must not paint QA chrome.** `qa_mode` normally draws a
"QA ×" chip beside the title pill so a human who enabled it can turn it back
off. On a capture pass there is no human and the frame ships to Apple, so
`DemoSheet` suppresses the chip when `DeepLinkRouter.isScriptedCapture` is true
(the `-demo <id>` launch argument, which only a script passes). The chip landed
with the redesign (#3308), three weeks after the previous set was captured, so
no shipped frame ever carried it — but the first refresh to run after #3308
would have, since the pipeline launches with `-qa_mode 1`. If it reappears in a
capture, check that gate before re-capturing.

The set is captured under `-qa_mode 1`, which freezes each demo's orbit
auto-rotation on its authored pose. That is what makes a re-capture comparable
to the committed one: two independent captures produce byte-identical iPhone
frames (measured, 0 differing pixels), and byte-identical iPad frames on the
same day — the iPad status-bar date is the one thing that cannot be pinned
(#3004, see the note at the top). Without `-qa_mode 1` the shot landed on
whatever azimuth the sweep had reached, so both the subject's pose and the
slice of HDRI behind it changed every run.

> **Two things that had to be fixed in the app before this set could ship
> (#2896)**, worth knowing if a future capture looks wrong again:
>
> - Every bundled HDR environment is a Radiance `.hdr`, which
>   `EnvironmentResource(named:)` cannot load. The failure was swallowed, so
>   scenes ran with **no custom IBL and no skybox** — the
>   `ImageBasedLightComponent` was never set, so RealityView's own default
>   environment lighting remained (dim, not unlit; #2842/#2868) — and
>   `dynamic-sky` with no sky at all. `SceneEnvironment.load()` now falls back to
>   ImageIO. A `[SceneViewSwift] Failed to load environment '…'` line in the
>   console means this regressed.
> - `model-viewer` frames tighter under `qa_mode` than it does interactively,
>   because the looser interactive value — needed so an auto-rotating model does
>   not clip at its broadside — left the subject small in a mostly-empty frame.
>   The plinth that used to drive the bounding sphere is gone (#3315); the fit
>   is now the car's own bounds. Since #2785, iOS also accepts a
>   `-camera_distance <float>` launch argument (Android's framing lever,
>   `DeepLinkRouter.validateCameraDistance` range `0.05...100`) that overrides
>   both `.framingMargin(_:)` defaults on `model-viewer`.
>
> **Do not reach for `-camera_distance` to fix the off-centre hovercar.**
> Measured on the #3384 set, the car sits right of frame centre with the left
> third empty: horizontal centre at 62 % of width on `iphone-6.9` (747 px wide,
> 57 % of the frame, 126 px of right clearance) and 60 % on `ipad-13` (1040 px,
> 50 %, 304 px of right clearance). The cause is #3383 — `CameraControls.fitRadius`
> inscribes the union AABB's *space diagonal* in a sphere and fits it to the
> narrower FOV axis — so the defect is an **offset**, not a scale.
> `-camera_distance` only scales: it makes the asymmetry more pronounced and
> pushes the already-tight tail toward the right edge, which #3006 measured
> clipping at margin `0.5`. Leave the documented `captureFramingMargin = 0.62`
> pipeline alone (that value is measured, not a preference) and let the fix land
> in #3383.

## How to regenerate

The capture script (`.claude/scripts/capture-appstore-screenshots.sh`) was
removed with the rest of the agent harness in #3244; this is the procedure it
ran, by hand. Build the demo for the simulator, then for each device class
erase the simulator, pin its chrome, install the app, and launch each demo
through the `-demo <id>` launch argument — which routes straight to the demo on
first frame (see `SceneViewDemoApp.swift`) and avoids the SpringBoard "Open
in …?" confirmation that `simctl openurl sceneview://demo/<id>` raises; the
`sceneview://` URL scheme remains the user-facing deep link.

```bash
cd samples/ios-demo
xcodebuild build -project SceneViewDemo.xcodeproj -scheme SceneViewDemo \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/dd CODE_SIGNING_ALLOWED=NO
APP=/tmp/dd/Build/Products/Debug-iphonesimulator/SceneView.app

# Once per class: "iPhone 17 Pro Max" → iphone-6.9/, "iPad Pro 13-inch (M4)" → ipad-13/
# Substitute whichever iPad generation your Xcode actually installed — see the
# table above, M4 and M5 both emit 2064 × 2752. The #3384 set is M4.
UDID=$(xcrun simctl list devices available -j | python3 -c \
  'import json,sys;print([d["udid"] for r in json.load(sys.stdin)["devices"].values() for d in r if d["name"]=="iPad Pro 13-inch (M4)"][0])')
xcrun simctl shutdown "$UDID" 2>/dev/null; xcrun simctl erase "$UDID"; xcrun simctl boot "$UDID"
xcrun simctl ui "$UDID" appearance dark
# Keep the plain "9:41" — the ISO form moves the clock off 09:41 and does not
# pin the iPad date anyway (#3004).
xcrun simctl status_bar "$UDID" override --time "9:41" \
  --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl install "$UDID" "$APP"
sleep 90   # first-boot system banners ("Ready for Apple Intelligence") post about a minute in

RAW=../../tools/store-screenshots/raw/ios
for slot in 03-dynamic-sky 04-materials 05-swiftui; do
  xcrun simctl terminate "$UDID" io.github.sceneview.demo 2>/dev/null
  xcrun simctl launch "$UDID" io.github.sceneview.demo -demo "${slot#*-}" -qa_mode 1
  sleep 28   # model load + settle
  xcrun simctl io "$UDID" screenshot "$RAW/ipad-13-$slot.png"
done

# Slot 1 is not a -demo launch, so -qa_mode 0 is mandatory (the chip persists):
xcrun simctl launch "$UDID" io.github.sceneview.demo \
  -open_file "$(xcrun simctl get_app_container "$UDID" io.github.sceneview.demo data)/…/printed-icosahedron.3mf" \
  -qa_mode 0
# Slot 3 is the Showcase home: launch with no argument at all.
```

Then composite — the committed PNGs are the compositor's output, not these
captures:

```bash
python3 tools/store-screenshots/compose.py --manifest tools/store-screenshots/slots-ios.json
```

`slots-ios.json` names every output path, caption, crop and zoom. Change the
manifest, never a PNG.

### System banners

`simctl` exposes no way to silence notifications, and waiting them out is not
reliable either: a freshly-erased device posts "Ready for Apple Intelligence"
about a minute into the session — i.e. possibly *during* a capture — which is
exactly how that card once landed in an iPad frame. The removed script detected
it by re-shooting each frame and hashing the top band; by hand, the check is
the same one it could never replace: **open all twelve PNGs before committing
them.** #917 shipped a set that passed every mechanical check and was still
wrong (Android captures letterboxed onto an iPad canvas, blank AR scenes), and
#2896 nearly shipped a "park diorama" that was actually a piano.

## Publishing these to the App Store

The upload path exists (#2612 Phase B) — no fastlane involved:

```bash
# What differs between this directory and the live listing (read-only):
python3 .claude/scripts/store-sync/asc_listing.py --dry-run

# Push these screenshots to the EDITABLE App Store version:
python3 .claude/scripts/store-sync/asc_listing.py --apply-screenshots
```

From CI: run the **`Sync App Store screenshots`** workflow
(`.github/workflows/app-store-screenshots.yml`) with `confirm=true`.

Three things worth knowing before you run it:

- It targets the **editable** version and never creates one. With no editable
  version it skips (loudly) rather than inventing somewhere to write.
- It **replaces** a display type's whole set (delete-then-upload) when anything
  differs. Live order is *expected* to follow this directory's filename order —
  Apple does not promise that a set preserves creation order, so the script
  probes the live order after uploading and warns if it diverges. Untouched
  display types are left alone.
- It is deliberately **not** part of `app-store.yml`. A dispatch there also
  runs `deploy-ios`/`deploy-macos`, which archive and upload a TestFlight
  build — refreshing screenshots must not do that.

Screenshots persist from one App Store version to the next, so this is
listing maintenance rather than a per-release step; nothing runs it on a tag.
