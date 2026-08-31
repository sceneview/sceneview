# iOS App Store screenshots

Fresh, correctly-sized App Store Connect screenshots for the SceneView demo
app — real iOS-simulator captures of rendered 3D content.

> ✅ **The committed set is current as of 2026-08-29 (#3384).** The four
> captured frames were re-captured from `main` at `710bb13dd`; #2844 later added
> a fifth and sixth file — `00-ar.png` in each class, a **generated** AR visual,
> not a capture (see "The `00-ar.png` slot" below). The previous set dated
> from 2026-08-04 (`a1dcba562`) and had gone stale in two visible ways: the
> demo-app glass chrome redesign (#3308) replaced the UI drawn over every
> frame, and #3315 stripped the white display plinth the hovercar was standing
> on. The subjects themselves did not change — the old set already showed the
> hovercar in slot 1 and the Damaged Helmet in slot 2. What the app renders
> today is what this directory holds.
>
> Slot 1 keeps its old subject only because two `qa_mode` overrides put it
> back: #3382 re-selects the hovercar (the redesign's first-run default is the
> helmet, which would have made slot 1 a duplicate of slot 2), and this refresh
> adds the stage under it — the capture pass draws a `studio_warm` skybox
> instead of the interactive default's undrawn one, which had put the store
> frame back on black (#2896, regressed by #3308).
>
> ✅ **The #2897 caveat is cleared.** The linear-multiplier fix is in the build
> these frames came from. As predicted by the measurement that caveat carried
> (viewport mean luma 192.3 → 191.2, vehicle region 151.6 → 147.4), its visible
> change was nil. Only the IBL contribution moved; the skybox is drawn directly
> and the direct lights were untouched.
>
> ✅ **`dynamic-sky` now shows the same subject Android does (#3003).** It used
> to build a stylised skyline out of five `systemGray` cubes, so the shot was
> grey blocks on a plinth against a photo HDR — the demo working as written, but
> demonstrating a time-of-day sun with an object that has almost nothing to
> show: a matte grey box reads the same at noon and at dusk apart from its
> shadow. It now loads `khronos_damaged_helmet`, the subject Android's Lighting
> Lab puts under this same demo id, whose metal and rough-dielectric regions
> render the environment change directly in their reflections.
>
> The ground plane went with the cubes. It existed so the auto-framing pass —
> which fits the *union* bounding sphere — would not pull back to contain an
> oversized slab (#2896); with a single hero subject it earned nothing and cost
> twice, leaving the helmet at about a sixth of the frame height and visibly
> intersecting it. Android's shot of this subject has no plane either.
>
> ⚠️ **The iPad frames carry their capture date, and `simctl` cannot pin it
> (#3004).** `simctl status_bar override --time "9:41"` fixes the clock, but
> iPadOS draws the date beside it: the committed frames read `09:41 Sat 29 Aug`.
> The documented escape hatch does not work — measured on the iPad Pro 13-inch
> (M4) simulator, iOS 26.3: `--time` rejects every ISO form except the
> milliseconds one (`2007-01-09T09:41:00.000Z`), and that form sets **only the
> clock**, converted to a local time (the status bar read `10:41 Sat 22 Aug`
> — the override had moved the clock off 09:41 and left the real date). `simctl status_bar list` confirms it: one
> `Time:` field, no date. There is no `simctl` clock setter either. So keep the
> plain `"9:41"` form, and read the iPad class as **reproducible within a
> capture day, not across days** — the iPhone class has no date and stays
> byte-reproducible.
>
> Nothing enforces any of this — the upload is manual and `asc_listing.py`
> compares checksums, not pixels — so it is a note, not a gate. Look at the
> mosaic before you upload.

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

## The `00-ar.png` slot is generated, not captured (#2844)

The listing text sells AR and no image showed any. `00-ar.png` in both classes
is an **AI-generated marketing visual** (Gemini `gemini-3.1-flash-image`,
image-to-image from the committed hero-model reference
`tools/demo-previews/refs/hero.webp`, dark variant, centre-cropped to each
class's exact pixel spec): the sci-fi helmet anchored in a real photographed
room, per DESIGN.md's "Preview Image Art Direction" (real camera background,
no text/UI/device frame/people). It is **not** a simulator capture — the
simulator has no camera and cannot run an AR session — and no procedure in
this README reproduces it. The `00-` prefix is load-bearing: the upload
scripts order slots lexicographically by filename, so it takes slot 1 on the
live listing without renaming the captured frames. Replace it with a real
device capture whenever an authorized device session produces a better one.

## Demos captured (#2854, #2896)

Android's set v2 order, minus `multi-model` (see below). Both ids are
standalone demos that render rich 3D content with **no network** — deliberately
not empty or loading AR scenes:

1. `01-model-viewer` — bundled hero model (cyberpunk hovercar) staged in
   `studio_warm` with its skybox drawn, frozen on a three-quarter hero pose.
   **Neither the model nor the stage is what the demo shows interactively**, and
   both overrides are load-bearing:
   - The hovercar is **not** the demo's interactive default model. Under
     `qa_mode` the demo picks `storeHeroAssetName` (`ModelViewerDemo.swift`)
     before the first load (#3382); a frame showing the Khronos helmet here
     means that selection regressed, and that is exactly what the set shipped
     with before #3384.
   - The stage is **not** the interactive default either. Interactively this
     demo opens on `studio` with `showSkybox = false`, so nothing is drawn
     behind the model and the viewport shows it over the clear colour — fine
     for a viewer you are about to orbit, wrong for a store frame: the
     hovercar's dark bodywork then reads as a grey silhouette on near-black,
     the exact "dim, dark-on-black" capture #2896 was filed about. Under
     `qa_mode` the demo therefore also picks `storeHeroEnvironmentName`
     (`studio_warm` — a real photo studio: seamless cyclorama, softboxes) and
     turns the skybox **on**. A slot-1 frame with a black background means that
     override regressed. The pre-redesign code carried the same decision as a
     `heroEnvironment` constant; #3308 dropped it, which is how the dark frames
     came back.
   - Expect a soft dark vertical block against the right edge: it is the studio
     flag in the `studio_warm` HDRI, i.e. the corner of the cyclorama, not a
     rendering artefact. It is small on `iphone-6.9` and takes roughly the
     top-right fifth of the wider `ipad-13` frame.
2. `02-dynamic-sky` — the `khronos_damaged_helmet` hero under a live HDRI sky,
   its metal and rough-dielectric regions carrying the time-of-day light. Same
   subject Android's Lighting Lab shows for this id (#3003); it was a five-cube
   skyline until then

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

Three further ids that used to be in this set were retired from Android's for
defects that are platform-independent, so do not reach for them here either.
`materials` opened on a streamed subject and drew an orbiting HDRI skybox, so
neither the subject nor the backdrop was reproducible; the demo side is fixed
(#2874) and the id is eligible again, but do not re-add it without capturing it
and looking at the frame against the other slots first. `double-pendulum`
renders as a tiny linkage in a mostly-black frame.

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
status-bar crop.

⚠️ Dark appearance styles the **system chrome only** — it does not decide how
dark a frame is. Each scene's look comes from the HDRI it draws: since #3384
both slots are light (a `studio_warm` cyclorama, a daylit sky), so do not read
"dark appearance" as "dark frame". This has been wrong in both directions
before — the paragraph claimed both frames were light while `model-viewer` was
in fact rendering on black, then claimed they sat at opposite ends the same day
the stage was fixed. Re-check it against the committed PNGs rather than
trusting it.

The status-bar glyphs are **not** the same colour in the two frames — dark in
`01-model-viewer`, light in `02-dynamic-sky`, on both classes. iOS picks for
legibility against what is behind them; both are legible, and neither is a
capture bug to "fix".

One more thing the mosaic shows: the two demos carry different app chrome.
`model-viewer` has a five-item glass dock along the bottom (place, environment,
model, animation, settings); `dynamic-sky` has a single round glass control.
`model-viewer` also resolves a demo title, so it draws a "Model Viewer" pill in
the identity row, where `dynamic-sky` has none. That is the demos' own shape,
not a capture artefact, but it is visible when the two sit side by side on the
listing.

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

for slot in 01-model-viewer 02-dynamic-sky; do
  xcrun simctl terminate "$UDID" io.github.sceneview.demo 2>/dev/null
  xcrun simctl launch "$UDID" io.github.sceneview.demo -demo "${slot#*-}" -qa_mode 1
  sleep 28   # model load + settle
  xcrun simctl io "$UDID" screenshot "appstore-screenshots/ipad-13/$slot.png"
done
```

### System banners

`simctl` exposes no way to silence notifications, and waiting them out is not
reliable either: a freshly-erased device posts "Ready for Apple Intelligence"
about a minute into the session — i.e. possibly *during* a capture — which is
exactly how that card once landed in an iPad frame. The removed script detected
it by re-shooting each frame and hashing the top band; by hand, the check is
the same one it could never replace: **open all four PNGs before committing
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
