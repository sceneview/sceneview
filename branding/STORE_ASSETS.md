# SceneView Store & Platform Assets

Brand color: **#1A73E8** (light) / **#8AB4F8** (dark)
Background: **#0D2137** (dark navy)
Font: Inter (Material Design 3 Expressive)

## Source Files

| File | Purpose | Format |
|---|---|---|
| `branding/logo.svg` | Primary logo (light mode) | SVG 512x512 |
| `branding/logo-dark.svg` | Logo for dark backgrounds | SVG 512x512 |
| `website-static/favicon.svg` | Website favicon | SVG 32x32 |
| `samples/android-demo/.../ic_launcher_foreground.xml` | Android adaptive icon foreground | Vector drawable |
| `samples/android-demo/.../ic_launcher_background.xml` | Android adaptive icon background color | Color resource |

---

## Google Play Store

Assets live in `samples/android-demo/distribution/play-store/en-GB/graphics/` and are synced by `play-store.yml` on every release.

| Asset | Size | Status | Notes |
|---|---|---|---|
| App icon (hi-res) | 512x512 PNG | **DONE** | `icon-512.png` — SceneView cube on navy |
| Feature graphic | 1024x500 PNG | **DONE** | `feature-graphic.png` — cube logo, "SceneView" tagline |
| Phone screenshots | 1080x2304 PNG | **DONE** | 4 screenshots: 3D viewer, AR, Sketchfab gallery, samples |
| 7" tablet screenshots | 2560x1440 PNG | **DONE** | 6 Chromebook/tablet landscape screenshots (#2092) |
| 10" tablet screenshots | 2560x1440 PNG | **DONE** | 6 Chromebook/tablet landscape screenshots (#2092) |
| TV banner | 1280x720 PNG | NEEDED | For Android TV demo app |
| TV screenshots | 1920x1080 PNG | NEEDED | At least 3. D-pad navigation, model showcase, rotation |
| Short description | max 80 chars | **DONE** | "3D & AR for Jetpack Compose — load models, place objects in AR" |
| Full description | max 4000 chars | **DONE** | Synced via `play-store.yml` from `distribution/play-store/en-GB/` |

### Screenshot content suggestions

1. **Hero shot** — DamagedHelmet model on gradient background, "SceneView" overlay
2. **AR placement** — Virtual furniture on real floor with plane indicators
3. **Material 3 UI** — Full app showing tabs, cards, Material You theming
4. **Multi-model** — Grid of different GLB models loaded simultaneously
5. **Code + Result** — Split showing Compose code on left, rendered scene on right
6. **Lighting** — Same model under different IBL environments
7. **Animation** — Animated character model mid-animation
8. **Touch interaction** — Pinch-to-zoom, rotate gesture visualization

---

## Apple App Store

| Asset | Size | Status | Notes |
|---|---|---|---|
| App icon | 1024x1024 PNG | **DONE** | Set in Xcode asset catalog (`AppIcon`) |
| 6.9" iPhone screenshots | 1284x2778 PNG | **DONE** | 4 screenshots uploaded to v4.15.1 draft (#917, #2121) |
| 6.1" iPhone screenshots | 1179x2556 PNG | OPTIONAL | 6.9" shots used as fallback in ASC |
| iPad Pro 13" screenshots | 2048x2732 PNG | **DONE** | 4 screenshots uploaded to v4.15.1 draft (#917, #2121) |
| iPad Pro 11" screenshots | 1668x2388 PNG | OPTIONAL | Optional but recommended |
| App preview video | 1920x1080 MOV | OPTIONAL | 15-30s showing AR placement |

### App Store notes

- iOS icon must be exactly 1024x1024 with NO alpha channel
- Round corners are applied by the OS, do NOT round manually
- Export `logo.svg` at 1024x1024 with `#0D2137` background, no transparency
- Screenshots should show SwiftUI + RealityKit features

---

## Website

| Asset | Size | Status | Notes |
|---|---|---|---|
| favicon.svg | 32x32 SVG | DONE | `website-static/favicon.svg` |
| favicon.ico | 16x16 + 32x32 ICO | NEEDED | Generate from favicon.svg |
| apple-touch-icon.png | 180x180 PNG | NEEDED | Export from logo.svg, add 20px padding, blue bg |
| og-image.png | 1200x630 PNG | NEEDED | Open Graph — blue gradient bg, logo, "SceneView" text, tagline |
| og-image-docs.png | 1200x630 PNG | OPTIONAL | For docs pages — include "Documentation" subtitle |
| twitter-card.png | 1200x600 PNG | OPTIONAL | Same as og-image, 2:1 ratio preferred by Twitter |

### Website integration

```html
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<meta property="og:image" content="https://sceneview.github.io/og-image.png">
```

---

## GitHub

| Asset | Size | Status | Notes |
|---|---|---|---|
| Social preview | 1280x640 PNG | NEEDED | Repo Settings > Social preview. Blue gradient bg, logo, "SceneView" text, platform badges |
| README badge/logo | ~200px wide SVG | DONE | Use inline version of `logo.svg` |
| Sponsor banner | 1280x640 PNG | OPTIONAL | For GitHub Sponsors page |

---

## npm / Package Registries

| Asset | Size | Status | Notes |
|---|---|---|---|
| npm avatar | 256x256 PNG | OPTIONAL | For @sceneview org on npm |
| pub.dev icon | 256x256 PNG | OPTIONAL | For Flutter plugin |

---

## Design Guidelines

### Logo usage

- **Minimum size**: 24x24px (use favicon variant below this)
- **Clear space**: Minimum 25% of logo width on all sides
- **On light backgrounds**: Use `logo.svg` (blue brackets, blue cube faces)
- **On dark backgrounds**: Use `logo-dark.svg` (light blue brackets, brighter cube)
- **Monochrome**: White version on colored backgrounds is acceptable

### Color palette

| Name | Hex | Usage |
|---|---|---|
| Primary Blue | `#1A73E8` | Brackets, buttons, links (light mode) |
| Primary Blue Dark | `#8AB4F8` | Brackets, buttons, links (dark mode) |
| Deep Blue | `#0D47A1` | Cube shadow face |
| Navy Background | `#0D2137` | Icon background, dark surfaces |
| Light Blue | `#42A5F5` | Cube top face gradient end |
| Highlight Blue | `#A8CBFA` | Cube top face highlight |
| Surface Light | `#F8FAFF` | Light mode background |
| Surface Dark | `#0F1A2E` | Dark mode background |

### Typography

- **Headings**: Inter Bold / Semi-Bold
- **Body**: Inter Regular
- **Code**: JetBrains Mono

### Export instructions

To generate raster assets from SVG sources:

```bash
# Install rsvg-convert (macOS)
brew install librsvg

# App icon 512x512 (Play Store)
rsvg-convert -w 512 -h 512 -b '#0D2137' branding/logo.svg -o branding/icon-512.png

# App icon 1024x1024 (App Store)
rsvg-convert -w 1024 -h 1024 -b '#0D2137' branding/logo.svg -o branding/icon-1024.png

# OG image — requires compositing (use Figma or ImageMagick)
# favicon.ico — use https://realfavicongenerator.net/ with favicon.svg

# Apple touch icon 180x180
rsvg-convert -w 180 -h 180 -b '#0D2137' branding/logo.svg -o branding/apple-touch-icon.png
```

---

## Figma / Design File

Consider creating a Figma file with:
- Logo variants (color, mono, inverted)
- App icon on adaptive icon grid
- Screenshot templates with device frames
- Feature graphic template
- Social preview template
- Color palette + typography tokens
