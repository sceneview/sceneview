# SceneView Branding Assets

> **Source of truth:** [`DESIGN.md`](../DESIGN.md). Colours, type, shape and the
> Forbidden list are defined there and nowhere else — this file only lists the assets.

## Logo

A flat isometric cube: three faces filled with solid tones of the M3 primary palette
generated from `#005BC1`, no gradient, no glow, no shadow. See `DESIGN.md` § *Logo*.

The current SVG and vector files below still draw the cube with gradients and are due to
be flattened (follow-up, tracked in `DESIGN.md` § *Logo*). Do not redraw them by hand
from this file.

The launcher vector is `samples/android-demo/src/main/res/drawable/ic_launcher_foreground.xml`.

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
- [x] App icon (1024x1024) — `branding/app-icon-1024.svg` (gradient, to flatten)
- [ ] Screenshots (iPhone, iPad) — iOS demo app running on simulator (manual)
- [ ] App preview video (optional)
- [x] Description matching Play Store

### npm
- [x] Package icon — `branding/npm-icon.svg` (256x256, for sceneview-mcp and sceneview.js; gradient, to flatten)

### Website
- [x] Favicon — `website-static/favicon.svg` (gradient, to flatten)
- [x] OG image (1200x630) — `website-static/og-image.svg` (gradient, to flatten)
- [x] Apple touch icon (180x180) — `website-static/apple-touch-icon.svg` (gradient, to flatten)
