# SceneView Branding Assets

> **Source of truth:** [`DESIGN.md`](../DESIGN.md). Colours, type, shape and the
> Forbidden list are defined there and nowhere else — this file only lists the assets.

## Logo

A flat isometric cube: three faces filled with solid tones of the M3 primary palette
generated from `#005BC1`, no gradient, no glow, no shadow. See `DESIGN.md` § *Logo*.

`logo.svg` is the shared construction on a 108-unit canvas: three inset, separated
faces, without viewport brackets. `logo-dark.svg` changes only the right face to the
dark `primary` role. The Android foreground and monochrome mask use these exact paths;
their furthest vertex is 30.4 units from the centre, inside the 33-unit safe radius.

`app-icon-1024.svg` and `npm-icon.svg` enlarge that construction 1.2× about `(54,54)`
on a full-square `surface` background. Store and Apple icons have no baked corner mask.
The compact favicon uses the same separated faces in monochrome, with a `20 20 68 68`
viewBox. Android legacy round icons are separate circular exports.

The launcher vector is `samples/android-demo/src/main/res/drawable/ic_launcher_foreground.xml`.

## Identity exports

- `exports/logo/`: transparent light and dark masters at 128, 256, 512 and 1024 px.
- `exports/app-icon/`: full-square RGBA icons at 192, 256, 512 and 1024 px.
- `exports/npm/`: 128 and 256 px; `exports/favicon/`: 16, 32, 48, 192 and 512 px,
  a 16/32/48 px ICO, and a 180 px touch icon.
- Android and Android TV: 108 dp foreground/background layers, separate monochrome
  layers for both v33 icon names, day/night role resources, and 48/72/96/144/192 px
  legacy icons. The two Play exports are opaque 512 px RGBA PNGs with sRGB metadata.
- `samples/ios-demo/SceneViewDemo/Assets.xcassets/AppIcon.appiconset/`: all listed
  iOS/macOS sizes, plus iOS dark (transparent background) and tinted (grayscale)
  1024 px sources. The appearance dictionaries match the Xcode 27.2 template.
  macOS retains its listed size slots; clear and macOS appearance treatments are
  supplied by the system, without unsupported asset-catalog keys.
- `identity-contact-sheet.png`: raster review at native sizes, simulated adaptive
  masks and wallpaper tints, and an old/new comparison. It is not a device capture.

The plain web identity copies are `website-static/favicon.svg`, `favicon.ico`,
`favicon-192.png`, `apple-touch-icon.svg`, `apple-touch-icon.png`, and the `i-logo`
symbols in `website-static/assets/icons.svg` and `website-static/index.html`.
Docs use `docs/docs/assets/images/logo-sceneview.svg`, `favicon.svg` (PWA) and
`favicon.png` (browser, selected by `docs/mkdocs.yml`). The web demo already points
to the root favicon; its inline header symbol remains for the demo-code batch.

Composed artwork still containing the former mark is outside this identity export:
`feature-graphic.svg`, `exports/store/`, `exports/social/`, `social-preview.html`,
`website-static/og-image.{svg,png}`, the docs hero/badge illustrations, and the Android
TV banner. Store screenshots, thumbnails and demo code are unchanged.

Export specifications: [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive),
[Google Play icon specification](https://developer.android.com/distribute/google-play/resources/icon-design-specifications),
[Apple app-icon HIG](https://developer.apple.com/design/human-interface-guidelines/app-icons),
and [Xcode asset-catalog appearances](https://developer.apple.com/documentation/xcode/configuring-your-app-icon).

## Colours and type

Not repeated here, so they cannot drift: use the M3 role table in `DESIGN.md` § *Colour*
(light and dark) and the system font (`DESIGN.md` § *Typography*). No custom web font.

## Store Assets Status

### Google Play Store
- [x] Feature graphic (1024x500) — `branding/feature-graphic.svg` (gradient, to flatten)
- [x] Icon — adaptive icon with the cube (`samples/android-demo/.../ic_launcher_foreground.xml`)
- [ ] Screenshots (phone, tablet, TV) — demo app running on emulator
- [x] Short description: "3D & AR SDK for Android — Jetpack Compose, Filament, ARCore"
- [x] Full description with features list

### Apple App Store
- [x] App icon (1024x1024) — `branding/app-icon-1024.svg` (flat M3 palette)
- [ ] Screenshots (iPhone, iPad) — iOS demo app running on simulator (manual)
- [ ] App preview video (optional)
- [x] Description matching Play Store

### npm
- [x] Package icon — `branding/npm-icon.svg` (256x256, flat M3 palette)

### Website
- [x] Favicon — `website-static/favicon.svg` (compact monochrome, light/dark)
- [x] OG image (1200x630) — `website-static/og-image.svg` (gradient, to flatten)
- [x] Apple touch icon (180x180) — `website-static/apple-touch-icon.svg` (flat, full square)
