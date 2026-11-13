# Android demo navigation audit — batch 1

Worktree: `demo-nav-sections`, branch `feat/demo-nav-sections`. All changes are uncommitted. No Git commit, push or merge; neither audit nor `local.properties` was edited.

## Result and removals

- `ui/RootScreen.kt`: home is the sole root screen. Removed `RootTab`, bottom navigation, tab transitions, duplicate gallery state and AR-session chrome. Selected section and search survive navigation/restoration. About is opened by the home information button, with the unread dot.
- `ui/ArViewTab.kt`: deleted in full, including the AR Experiences launcher, early camera access button, duplicate catalogue, embedded placement session and launcher-only ARCore availability memory.
- `ui/AboutSheet.kt`: extracted the useful About content into an M3 modal sheet: support, documentation, source, other apps, feedback, privacy policy, licences/attributions, usage-statistics and notification settings, and one What's new entry. Removed Playground, the separate release-notes link and the HD-pack row. Notifications remain opt-in through this setting.
- `ui/home/HomeScreen.kt`, `HomeSections.kt`, `HomeListRow.kt`: six M3 secondary scrollable tabs and ordinary M3 list rows. Search is a top-bar icon and spans every section. Removed featured carousel, section chips, duplicate featured/new rows, home gallery link, glass header, image tint/dissolve rows and overlay copy. Deleted `HomeFeatured.kt`, `HomeTopSections.kt`, `HomeSectionJump.kt`, `HomeRowStyle.kt` and `HomeAmbient.kt`.
- `HomeHeroScene.kt` and `HomeHeroFlight.kt` are unchanged. The hero retains its 320 dp phone / 400 dp expanded band, stage dimensions, camera padding, scroll/parallax behaviour, motion, quality tiers and fallback. Its engine stays outside the lazy list. Removed the surrounding painted sky, text scrims and fades; deleted their unused theme tokens.
- `ui/viewer/ModelPickerSheet.kt`: Browse online models now opens the existing gallery from the picker. HD scenes/download management is offered here alongside the existing HD model choices. No audit-9 TODO is needed.
- `MainActivity.kt`: hosts the gallery as a page over the viewer and routes legacy root links to home; removed automatic push prompting. `telemetry/PushPrompt.kt`, `PushPromptPolicy.kt`, their stored counters, events and tests were removed. Usage-statistics consent is unchanged.
- `ListedDemos.kt`: removed the catalogue exclusions. All 44 registered demos now have a home row; ML labels and XR are in Tools. XR continues to display its reference preview on a phone. No demo implementation or registered id was deleted or merged.
- `whatsnew/WhatsNewUi.kt`: removed the obsolete home card and its previews. Both release-note detail views are reached through the single About entry; existing read/acknowledgement behaviour is retained.
- Deleted launcher/chip/carousel/fused-row tests and unused strings. Added section reachability, navigation/restoration/search and legacy-link coverage. Updated analytics and registry tests. Added `changelog.d/audit-demo-nav-sections.md`.

## Final section → demo ids, in display order

| Section | Ordered ids |
|---|---|
| Models | `model-viewer`, `animation-physics`, `camera-path`, `camera-gestures`, `picking-collision` |
| AR | `ar-placement`, `ar-face`, `ar-geospatial-anchors`, `ar-image`, `ar-measure`, `ar-body-tracker`, `ar-orbital`, `ar-depth-occlusion`, `ar-people-occlusion`, `point-and-ask`, `ar-depth-of-field` |
| Scan | `ar-rerun`, `splat-preview`, `ar-splat-room` |
| Materials | `matter`, `materials`, `two-d-in-three-d`, `lighting`, `contact-shadow-preview`, `geometry`, `custom-geometry`, `lines-paths`, `spatial-audio` |
| Play | `cosmos`, `rolling-balls`, `ar-pool`, `finger-drawing` |
| Tools | `ar-surfaces`, `ar-plane-node`, `ar-point-cloud`, `ar-raw-depth-point-cloud`, `ar-depth-visualization`, `ar-image-stabilization`, `ar-scene-semantics`, `debug-overlay`, `model-thumbnails`, `ar-cloud-anchor`, `ar-ml-object-label`, `ar-xr` |

Audit names resolve as follows: Animation → `animation-physics`; Photographic 3D → `splat-preview`; Compose in 3D → `two-d-in-three-d`; Pool → `ar-pool`; Thumbnails → `model-thumbnails`. Shapes expands to the three individual geometry demos; Occlusion to depth and people; AR diagnostics to the seven individual diagnostic demos. Contact shadows remains immediately after Lighting.

The audit's named order is preserved. No usage counts were invented: it does not provide per-demo ranks inside the future diagnostic/Shapes/Occlusion groups. Diagnostic demos follow the audit's surfaces/points/depth/stabilization/semantics grouping.

## Demos not named as standalone entries in the audit table

- Models: `camera-path`, `camera-gestures`, `picking-collision`, appended in the audit's stated 9 / 7 / 4 opens order.
- AR: `ar-depth-of-field`, appended.
- Scan: `ar-splat-room` (Room model), appended pending its later merger with 3D Scan.
- Materials: `contact-shadow-preview`, kept beside Lighting pending their later merger.
- Play: `finger-drawing`, appended.
- Tools: `ar-ml-object-label`, `ar-xr`, appended. Previously excluded from the phone catalogue; now directly reachable as requested.

## Deep links and analytics

- Every registered `sceneview://demo/<id>` continues through the existing resolver; retired-id aliases and in-demo mode parameters remain intact.
- `sceneview://ar`, `sceneview://ar-view`, `sceneview://ar_view`, `sceneview://tab/ar_view`, `sceneview://tabs/ar-view`, `sceneview://demo/ar-view` and `sceneview://home?tab=ar` select AR on home. The AR aliases are accepted in the tab/home forms too.
- `sceneview://about`, `sceneview://tab/about`, `sceneview://tabs/about`, `sceneview://demo/about`, `sceneview://home?tab=about` open the About sheet over home.
- Manifest intent filters accept these legacy hosts. Unknown hosts, schemes and malformed root paths are rejected.
- `sample_open` remains in place. The existing consent-aware GA4 helper receives `home_section_selected` with `section` equal to one of `Models`, `AR`, `Scan`, `Materials`, `Play`, `Tools`. No user input is included in this new event.

## Validation

- Latest APK: `:samples:android-demo:compileDebugKotlin :samples:android-demo:assembleDebug` completed with **`BUILD SUCCESSFUL in 1m 20s`**; 119 actionable tasks, 8 executed, 3 from cache, 108 up-to-date. Log: `/tmp/demo-nav-gallery-build.log`.
- Final full unit-test / compile / lint run is in progress; results will be filled before handoff.
- Registry cross-check: **44 registered ids, 44 section entries, zero missing ids, zero unknown ids, zero duplicates**.
- `git diff --check`: clean.

Build commands use `ANDROID_HOME=$HOME/Library/Android/sdk`, `GRADLE_USER_HOME=$HOME/.gradle-codex` and `./gradlew --max-workers=4`. Tests also use `JAVA_TOOL_OPTIONS=-Duser.home=/tmp/demo-nav-jvm`; an existing Maven repository link avoids redownloading Android test jars. This fixes Robolectric's attempt to write its lock outside the sandbox. The shell HOME, `local.properties` and repository build configuration are unchanged.

### Captures and observed navigation

Inspected by the delegated visual QA agent on **Pixel_7a / emulator-5554 only**, in `/tmp/demo-nav-sections-qa/`:

| Capture | Verified behaviour |
|---|---|
| `final-chrome-idle-dark.png` | AR selected, live hero, complete chrome, no bottom navigation or hero overlay copy |
| `final-home-light.png` | Light Scan home using the existing debug hero-still fallback |
| `final-section-after-scroll-dark.png` | Changing section after scrolling shows its first demo |
| `final-about-dark.png`, `final-about-light.png` | About in both themes |
| `final-search-dark.png` | Search finds a demo across sections; closing restores the selected section |
| `final-picker-light.png` | Model Viewer picker contains the online gallery entry and HD scenes |

Also exercised: Place in AR opens its chooser directly and Back restores AR; Support, Credits and What's new open from About and return correctly; legacy AR/About links and the existing Model Viewer link work. No notification permission prompt appeared. Notifications settings cannot be exercised in this debug build without Firebase configuration.

The gallery opens from the picker; Back returns to Model Viewer with the picker closed. The first gallery capture (`final-gallery-light.png`) exposed a missing root surface in the new route. **Fixed** by restoring an opaque Material `surface`, formerly supplied by RootScreen's Scaffold. The latest APK above includes that fix. **Its new capture is blocked**: the emulator subsequently disconnected, then both restart paths failed before Android started (headless: SIGILL in `init_cache_info`; windowed: Qt reports missing `neon`). AVD userdata was preserved. Logs: `gallery-fix-emulator.log`, `gallery-fix-emulator-verbose.log`, `gallery-fix-emulator-windowed.log` in the capture directory. The old gallery capture is evidence of the finding, not approval of the corrected screen. Finished online gallery content was not verified; its capture still showed network-loading placeholders.

Initial emulator startup under concurrent heavy Gradle work produced one startup ANR and transient partial home frames. Quiet-load checks produced stable home captures and no further ANR; initial live-scene loading remains slow on this emulator. No causal claim or hardware performance claim is made.

QA required trimming regenerable guest caches and removing the confirmed stale SceneView instrumentation APK (`io.github.sceneview.test`, 52 MiB). The guest storage reserve was temporarily reduced for installation, then both settings were restored to their original absent values. Other apps/data were preserved; only this task's QA app was reinstalled. The emulator lease is released.

## Uncertainty / deliberate scope limits

- **Night circuit has no demo id in this checkout.** It exists only as the home hero (`models/night_circuit.glb` / `HomeHeroScene`). The hero remains; no new demo or duplicate Model Viewer row was invented to represent it.
- **`my-demo` is only a fragment README example**, not a registered demo. The current registry contains 44 entries, versus the audit's approximate count of 43.
- Registry categories remain unchanged because AR camera gates, analytics and gallery accents consume them. The new home section mapping is separate so moving Pool, Geospatial or diagnostics cannot change permission behaviour.
- No Pavilion scene, demo mergers, new thumbnails or demo-scaffold redesign in this batch.
