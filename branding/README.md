# SceneView Branding Assets

> **Design system source of truth:** [`DESIGN.md`](../DESIGN.md) (Google Stitch format)
> All colors, typography, spacing, and component patterns are defined there.

## Logo
The SceneView logo is a **3D isometric cube** in blue gradient.
- Top face: White (#FFFFFF)
- Right face: Light blue (#64B5F6)
- Left face: Dark blue (#1565C0)
- Background: Blue (#1565C0 or #2196F3)

The vector source is in `samples/android-demo/src/main/res/drawable/ic_launcher_foreground.xml`.

## Brand Colors

See `DESIGN.md` for the full palette (light + dark mode). Key values:

| Name | Hex | Usage |
|---|---|---|
| Primary | #005bc1 | Website primary, links, accents |
| Primary (dark) | #a4c1ff | Dark mode primary |
| Tertiary | #6446cd | Gradients, accents |
| Tertiary (dark) | #d2a8ff | Dark mode tertiary |
| Brand Dark Blue | #1565C0 | Logo, gradients |
| Brand Light Blue | #64B5F6 | Logo, highlights |
| Surface Dark | #0D1117 | Dark mode background |
| Surface Variant | #161B22 | Cards, elevated surfaces |

## Typography
- **Primary:** Inter (web), system default (mobile)
- **Code:** JetBrains Mono

See `DESIGN.md` for full font scale, weights, and letter spacing.

## Store Assets Status

### Google Play Store
- [x] Feature graphic (1024x500) — `branding/feature-graphic.svg`
- [x] Icon — adaptive icon with blue cube (`samples/android-demo/.../ic_launcher_foreground.xml`)
- [ ] Screenshots (phone, tablet, TV) — demo app running on emulator
- [x] Short description: "3D & AR SDK for Android — Jetpack Compose, Filament, ARCore"
- [x] Full description with features list

### Apple App Store
- [x] App icon (1024x1024) — `branding/app-icon-1024.svg`
- [ ] Screenshots (iPhone, iPad) — iOS demo app running on simulator (manual)
- [ ] App preview video (optional)
- [x] Description matching Play Store

### npm
- [x] Package icon — `branding/npm-icon.svg` (256x256, for sceneview-mcp and sceneview.js)

### Website
- [x] Favicon (blue cube) — `website-static/favicon.svg` (`DESIGN.md` #005bc1 palette)
- [x] OG image (1200x630) — `website-static/og-image.svg`
- [x] Apple touch icon (180x180) — `website-static/apple-touch-icon.svg`
