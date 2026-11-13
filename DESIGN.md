# SceneView Design

The theme and its tokens for every SceneView surface: the website (`website-static/`), the
docs (`docs/`), the Android demo (`samples/android-demo`), the iOS demo (`samples/ios-demo`),
the other samples, store listings and videos.

---

## Colour

### One source colour, generated roles

Every colour role is generated from **one source colour, `#005BC1`**, scheme **Fidelity**,
contrast level 0, with Google's
[`@material/material-color-utilities`](https://www.npmjs.com/package/@material/material-color-utilities)
(v0.4.0, `SchemeFidelity` + `MaterialDynamicColors`).

Fidelity keeps the brand blue: `primary-container` is the source `#005BC1` itself in both
themes, and `primary` is its AA tone `#004494` (light) / `#ADC6FF` (dark). Every `on-*` pair
below reaches at least 4.5:1 on its matching ground (WCAG 2.2 formula on the hex values; the
tightest is `on-primary-container` on `primary-container`, 4.55:1). Other combinations are not
covered by that figure.

| Role | Light | Dark |
|---|---|---|
| `primary` | `#004494` | `#ADC6FF` |
| `on-primary` | `#FFFFFF` | `#002E69` |
| `primary-container` | `#005BC1` | `#005BC1` |
| `on-primary-container` | `#C9D9FF` | `#C9D9FF` |
| `secondary` | `#4B5E86` | `#B3C6F4` |
| `on-secondary` | `#FFFFFF` | `#1B3055` |
| `secondary-container` | `#BBCFFD` | `#33466D` |
| `on-secondary-container` | `#44587F` | `#A1B5E2` |
| `tertiary` | `#7C2F00` | `#FFB595` |
| `on-tertiary` | `#FFFFFF` | `#571E00` |
| `tertiary-container` | `#A33F00` | `#A33F00` |
| `on-tertiary-container` | `#FFCFBB` | `#FFCFBB` |
| `error` | `#BA1A1A` | `#FFB4AB` |
| `on-error` | `#FFFFFF` | `#690005` |
| `error-container` | `#FFDAD6` | `#93000A` |
| `on-error-container` | `#93000A` | `#FFDAD6` |
| `surface-dim` | `#D8D9E2` | `#11131A` |
| `surface` | `#F9F9FF` | `#11131A` |
| `surface-bright` | `#F9F9FF` | `#373940` |
| `surface-container-lowest` | `#FFFFFF` | `#0C0E14` |
| `surface-container-low` | `#F2F3FC` | `#191B22` |
| `surface-container` | `#ECEDF6` | `#1D2026` |
| `surface-container-high` | `#E7E7F1` | `#272A31` |
| `surface-container-highest` | `#E1E2EB` | `#32353C` |
| `on-surface` | `#191B22` | `#E1E2EB` |
| `on-surface-variant` | `#424753` | `#C2C6D5` |
| `outline` | `#727784` | `#8C909E` |
| `outline-variant` | `#C2C6D5` | `#424753` |
| `inverse-surface` | `#2E3037` | `#E1E2EB` |
| `inverse-on-surface` | `#EFF0F9` | `#2E3037` |
| `inverse-primary` | `#ADC6FF` | `#005BC0` |
| `scrim` | `#000000` | `#000000` |
| `shadow` | `#000000` | `#000000` |
| `surface-variant` | `#DEE2F1` | `#424753` |
| `surface-tint` | `#005BC0` | `#ADC6FF` |
| `primary-fixed` / `primary-fixed-dim` | `#D8E2FF` / `#ADC6FF` | same |
| `on-primary-fixed` / `on-primary-fixed-variant` | `#001A41` / `#004493` | same |
| `secondary-fixed` / `secondary-fixed-dim` | `#D8E2FF` / `#B3C6F4` | same |
| `on-secondary-fixed` / `on-secondary-fixed-variant` | `#021A3F` / `#33466D` | same |
| `tertiary-fixed` / `tertiary-fixed-dim` | `#FFDBCC` / `#FFB595` | same |
| `on-tertiary-fixed` / `on-tertiary-fixed-variant` | `#351000` / `#7C2E00` | same |

A Compose `lightColorScheme` / `darkColorScheme` call that omits a role fills it with the M3
baseline purple, so every role, the `*-fixed` ones included, is set explicitly.

**Semantic colours** (M3 custom colours, same utility, `customColor` unblended, so green stays
green and orange stays orange):

| Role | Light | Dark |
|---|---|---|
| `success` / `on-success` | `#006E2A` / `#FFFFFF` | `#73DC82` / `#003912` |
| `success-container` / `on-success-container` | `#8FFA9B` / `#002108` | `#00531E` / `#8FFA9B` |
| `warning` / `on-warning` | `#954A00` / `#FFFFFF` | `#FFB785` / `#502500` |
| `warning-container` / `on-warning-container` | `#FFDCC6` / `#301400` | `#713700` / `#FFDCC6` |

### Where the values live

| Surface | File | Format |
|---|---|---|
| Web | `website-static/assets/tokens.css` | CSS custom properties named after the official M3 web tokens, `--md-sys-color-<role>`; light on `:root`, dark under `prefers-color-scheme: dark` and `[data-theme="dark"]`. Font stacks: `--md-ref-typeface-plain`, `--md-ref-typeface-code` |
| Docs (MkDocs Material) | `docs/docs/stylesheets/extra.css` | The same values, mapped onto MkDocs Material's palette variables |
| Android (Compose) | `samples/android-demo/src/main/java/io/github/sceneview/demo/theme/Color.kt` (values) and `theme/Theme.kt` (schemes) | `lightColorScheme(…)` / `darkColorScheme(…)` with every role above, read through `MaterialTheme.colorScheme.<role>`; `dynamicColor` defaults to `false` |
| Android 3D scene | `samples/android-demo/src/main/java/io/github/sceneview/demo/SceneViewColors.kt` | Colours of materials inside a scene (floor, grid, props): scene content, not UI tokens |
| Apple (SwiftUI) | `samples/ios-demo/SceneViewDemo/Theme.swift` | System colours plus one `AccentColor` asset: `primary` light `#004494`, dark `#ADC6FF`. SwiftUI's default white label on `#ADC6FF` is 1.7:1; `on-primary` (`#FFFFFF` light, `#002E69` dark) is the matching label colour |

---

## Typography

M3 type scale and the closest HIG text style:

| M3 role | Size / line height (sp; web: px at 16 px root) | Weight | Closest HIG style |
|---|---|---|---|
| Display Large | 57 / 64 | 400 | — (web hero only) |
| Display Medium | 45 / 52 | 400 | — |
| Display Small | 36 / 44 | 400 | Large Title (34) |
| Headline Large | 32 / 40 | 400 | — |
| Headline Medium | 28 / 36 | 400 | Title 1 (28) |
| Headline Small | 24 / 32 | 400 | Title 2 (22) |
| Title Large | 22 / 28 | 400 | Title 3 (20) |
| Title Medium | 16 / 24 | 500 | Headline (17, semibold) |
| Title Small | 14 / 20 | 500 | Subheadline (15, semibold) |
| Body Large | 16 / 24 | 400 | Body (17) |
| Body Medium | 14 / 20 | 400 | Subheadline (15) |
| Body Small | 12 / 16 | 400 | Footnote (13) |
| Label Large | 14 / 20 | 500 | Callout (16) |
| Label Medium | 12 / 16 | 500 | Caption 1 (12) |
| Label Small | 11 / 16 | 500 | Caption 2 (11) |

---

## Elevation

M3 tonal elevation levels:

| Level | Role | Typical component |
|---|---|---|
| 0 | `surface` | Page, screen, top app bar at rest |
| 1 | `surface-container-low` | Bottom sheet, elevated content area |
| 2 | `surface-container` | Navigation bar / rail, menu, scrolled top app bar |
| 3 | `surface-container-high` | Dialog, search bar, a card inside a sheet |
| 4 | `surface-container-highest` | Filled card, filled text field, tile on a card |

---

## Shape and layout

M3 default corners:

|---|---|
| Card | 12 dp (M3 medium) |
| Bottom sheet (top corners), dialog | 28 dp (M3 extra-large) |
| Buttons, FAB-like round controls, search bar | Full (pill) |
| Chip, menu, small tile | 8 dp (M3 small) |
| Text field (filled, top corners) | 4 dp (M3 extra-small) |

- M3 window classes: compact < 600 dp, medium 600–839, expanded 840–1199, large ≥ 1200.
  Default margins: 16 dp in compact, 24 dp from medium up.
- M3 web motion tokens: standard / emphasized `cubic-bezier(0.2, 0, 0, 1)`, emphasized
  decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)`, emphasized accelerate
  `cubic-bezier(0.3, 0, 0.8, 0.15)`.

---

## Accessibility reference

- WCAG 2.2 AA: 4.5:1 for text, 3:1 for large text, icons and the boundary of a control
  (1.4.3, 1.4.11); text resizable to 200 % (1.4.4); information not by colour alone (1.4.1);
  visible focus (2.4.7); an accessible name for every control (4.1.2).
- Minimum touch targets: 48 × 48 dp on Android (Material), 44 × 44 pt on Apple (HIG).
- Reduced-motion settings: `prefers-reduced-motion` on the web, animator duration scale 0 on
  Android, Reduce Motion on Apple.
- AR coaching: `ARCoachingOverlayView` on Apple, the SDK's `ARCoachingOverlay` on Android.

---

## 3D assets

- Demo and website models are registered in `assets/catalog.json` (source, author, licence);
  `CREDITS.md` is generated from it. New assets go through `tools/fetch-assets.sh --register`
  and the `assets-v1` release (see `CLAUDE.md`).
- Store listings, videos and the website are promotional use: an NC (non-commercial) licence
  does not cover it, and an ND licence does not allow re-lighting or re-texturing.
- Demo previews: `preview_<demo_id>_light.webp` and `preview_<demo_id>_dark.webp` in
  `samples/android-demo/src/main/res/drawable-nodpi/`, 800 × 640 (5 : 4).

---

## Logo files

The mark is the isometric cube. Files: `branding/logo.svg`, `branding/logo-dark.svg`,
`branding/app-icon-1024.svg`, `branding/npm-icon.svg`, `branding/feature-graphic.svg`,
`website-static/favicon.svg`, `website-static/apple-touch-icon.svg`,
`website-static/og-image.svg`, the Android launcher
(`samples/android-demo/src/main/res/drawable/ic_launcher_foreground.xml`,
`ic_sceneview_mark.xml`, `ic_sceneview_hero.xml`) and the TV banner
(`samples/android-tv-demo/src/main/res/drawable/tv_banner.xml`).
