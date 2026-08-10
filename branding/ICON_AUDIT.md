# SceneView Icon & Branding Audit

Audited: 2026-03-26 · **Refreshed: 2026-07-18 (#2773)**

## Brand Assets (Source of Truth)

| File | Description |
|---|---|
| `branding/logo.svg` | Light mode logo — 512x512, blue cube + viewport brackets |
| `branding/logo-dark.svg` | Dark mode logo — brighter blues for dark backgrounds |
| `branding/app-icon-1024.svg` | **Store app icon** — cube on a blue→violet diagonal gradient |
| `website-static/favicon.svg` | Favicon — 32x32, compact isometric cube + brackets |

### Brand Colors (current — SceneView design system palette)

> **2026-07-18:** the palette below supersedes the older values this doc
> originally listed (`#1A73E8` primary etc.). The whole codebase now uses
> `#005BC1` — see `samples/android-demo/src/main/res/values/colors.xml`
> (`md_theme_primary #005BC1`) and `samples/ios-demo/.../Theme.swift`.

- Primary: `#005BC1`
- Tertiary (accent 2): `#6446CD`
- Light (dark-mode primary): `#A4C1FF`
- Dark-mode tertiary: `#D2A8FF`
- Dark background: `#0D2137`
- Cube faces: `#005BC1` (left/shadow), `#3D7FD9` (right/lit), `#A4C1FF` (top/light)

---

## Platform Status

### Android Demo (`samples/android-demo/`)
- **Status: COMPLETE**
- Adaptive icon foreground: `ic_launcher_foreground.xml` — blue cube with viewport brackets
- v26 background: `#0D2137` (dark navy)
- v33 background: `#1A73E8` (brand primary) + monochrome support
- Bitmap fallbacks: all densities (mdpi through xxxhdpi)
- Play Store icon: `ic_launcher-playstore.png` (512x512)

### Android TV Demo (`samples/android-tv-demo/`)
- **Status: COMPLETE** (fixed in this audit)
- Adaptive icon foreground: identical to android-demo
- v26 background: `#0D2137` (dark navy)
- v33 monochrome: added (was missing)
- Note: no bitmap fallback PNGs (OK for TV, adaptive icon covers modern devices)

### iOS Demo (`samples/ios-demo/`)
- **Status: COMPLETE** (resolved since the 2026-03-26 audit)
- `AppIcon.appiconset/AppIcon.png` (1024×1024) is present and committed; it is
  pixel-identical to `branding/app-icon-1024.svg` (blue→violet gradient, cube,
  viewport brackets). `AppIcon_512x512.png` matches the Play Store `icon-512.png`.
- AccentColor: `#005BC1` (light) / `#A4C1FF` (dark) — matches the [`DESIGN.md`](../DESIGN.md) palette.
- **Store icons are consistent iOS ↔ Android** (both the gradient icon).

### Website (`website-static/`)
- **Status: COMPLETE**
- `favicon.svg`: blue cube with viewport brackets, brand colors
- Referenced in `index.html` as `<link rel="icon" type="image/svg+xml" href="/favicon.svg">`
- og:image points to favicon.svg — works but a 1200x630 PNG social preview would be better

### GitHub Social Preview
- **Status: TEMPLATE READY**
- `branding/social-preview.html` — 1280x640 template with dark mode logo
- **ACTION REQUIRED**: Screenshot this HTML at 1280x640, upload as repository social preview in GitHub Settings > General > Social preview

### npm Package (`mcp/`)
- **Status: NO ICON FIELD**
- npm doesn't have an icon field in package.json
- The npm registry page will show the GitHub social preview if set
- No action needed beyond setting the GitHub social preview

### Chrome Extension / sceneview-tools
- **Status: NOT IN REPO**
- No extension icons found in the repository
- If the Chrome extension is published separately, it needs 16/48/128px PNGs
- Generate from `branding/logo.svg` with `#0D2137` background

---

## Store-icon vs on-device-icon note (#2773)

The **store** icons (iOS `AppIcon_1024`, Play `icon-512`) both use the
blue→violet gradient from `branding/app-icon-1024.svg` and are consistent.
The **on-device Android** adaptive icon deliberately uses a *flat* background
(`#0D2137` navy on API 26, `#005BC1` on API 33 — `ic_launcher_background.xml`)
rather than the gradient, because adaptive-icon backgrounds are masked/animated
by the launcher and a flat fill survives that cleanly. The glyph (iso cube + 4
viewport brackets) is identical everywhere. **Open decision:** whether to bring
the gradient into the adaptive icon or keep the flat on-device fill.

## Manual Actions Required

1. **GitHub Social Preview**: Open `branding/social-preview.html` in browser, screenshot at 1280x640, upload to GitHub repo settings
2. **Chrome Extension Icons** (if needed): Generate 16x16, 48x48, 128x128 PNGs from logo.svg
