# SceneView Design

The one design reference for every SceneView surface: the website (`website-static/`), the
docs (`docs/`), the Android demo (`samples/android-demo`), the iOS demo (`samples/ios-demo`),
the other samples, store listings and videos.

**The controls follow the platform; the 3D is the show.** Buttons, lists, bars and sheets follow
the platform's published guidelines with one source colour. The screen itself should make
people say "wow": a live, full-bleed 3D scene, real light, rich imagery, motion. The guidelines
govern the controls; they are never a reason to flatten the scene or shrink it to a list.

| Surface | Follow | Source |
|---|---|---|
| Android, Android TV, Android Auto, web, docs | Material Design 3 | [m3.material.io](https://m3.material.io) |
| iOS, iPadOS, macOS, visionOS | Apple Human Interface Guidelines | [developer.apple.com/design/human-interface-guidelines](https://developer.apple.com/design/human-interface-guidelines) |
| Charts | M3 / HIG "Charts", then IBM Carbon "Data visualization" | [carbondesignsystem.com/data-visualization](https://carbondesignsystem.com/data-visualization/) |
| Contrast, targets, motion | WCAG 2.2 AA | [w3.org/TR/WCAG22](https://www.w3.org/TR/WCAG22/) |

When this file and the platform guideline disagree, the platform guideline wins and this file
gets fixed.

---

## Forbidden

These are the patterns that make a screen look generated. None of them ships, on any surface.
Platform materials are welcome: Liquid Glass and system materials on Apple, M3 Expressive
shapes and motion on Android.

- **Decorative gradients on controls**: gradient buttons, gradient text, gradient icons,
  glowing borders and halos (the generic "AI app" look Thomas rejected on 2026-11-04). A scrim
  or fade that lets a scene or a photo flow into the page is not decoration and is allowed.
- **Fonts other than the system font** for UI: no Google Fonts display faces (Bricolage
  Grotesque, Fraunces, Instrument Serif, Space Grotesk, DM Serif, Playfair, Plus Jakarta Sans),
  and no Inter, Geist or JetBrains Mono as a deliberate choice. No serif for big numbers.
- **All-caps monospace "eyebrows"** or any letter-spaced label above a heading.
- **Decoration that pretends to be structure**: tinted callout boxes for every remark, coloured
  accent bars on the left edge of cards, pills that do nothing (a chip filters, selects or
  inputs, per M3 "Chips"), emoji as section markers, decorative 01 / 02 / 03 numbering.
- **Shadowed cards everywhere.** Elevation is a surface tone (see *Elevation*).
- **Hover lifts, bouncy springs, scale-on-press.** Feedback is the platform's state layer,
  ripple or highlight.
- **Invented components** where the platform has one: list, top app bar, navigation bar /
  rail / tab bar, segmented button, FAB, bottom sheet, dialog, snackbar, search bar, menu,
  chip, progress indicator. Use the platform component, styled only by the theme.
- **Dynamic colour** (wallpaper-based) on Android: every surface uses the one scheme below so
  the site, the docs and both apps match.

---

## Colour

### One source colour, generated roles

Every colour is an M3 colour role generated from **one source colour, `#005BC1`**, scheme
**Fidelity**, contrast level 0, with Google's
[`@material/material-color-utilities`](https://www.npmjs.com/package/@material/material-color-utilities)
(v0.4.0, `SchemeFidelity` + `MaterialDynamicColors`). Never pick a value by eye, never
hardcode one outside the theme files, never add a role. If a role is missing for a need, the
need is wrong or the component is wrong.

Fidelity keeps the brand blue: `primary-container` is the source `#005BC1` itself in both
themes, and `primary` is its AA tone `#004494` (light) / `#ADC6FF` (dark) so text and
icons on it reach AA. Every `on-*` pair below reaches at least 4.5:1 (WCAG 2.2 formula on the
hex values; the tightest is `on-primary-container` on `primary-container`, 4.55:1).

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

Every role is set explicitly, the `*-fixed` ones included: a Compose `lightColorScheme` /
`darkColorScheme` call that omits a role fills it with the M3 baseline purple.

**Semantic colours** (M3 custom colours, same utility, `customColor` unblended, so green stays
green and orange stays orange). Use them for state only, never for decoration.

| Role | Light | Dark |
|---|---|---|
| `success` / `on-success` | `#006E2A` / `#FFFFFF` | `#73DC82` / `#003912` |
| `success-container` / `on-success-container` | `#8FFA9B` / `#002108` | `#00531E` / `#8FFA9B` |
| `warning` / `on-warning` | `#954A00` / `#FFFFFF` | `#FFB785` / `#502500` |
| `warning-container` / `on-warning-container` | `#FFDCC6` / `#301400` | `#713700` / `#FFDCC6` |

Rules:

- **Light and dark, always.** Dark starts from the dark `surface` above (`#11131A`): never pure
  black, never `#0D1117`, never an inversion of light. Design and capture dark first.
- **Text is an `on-*` role on its matching ground** (`on-surface` on any `surface*`,
  `on-primary-container` on `primary-container`…). The generated pairs meet WCAG 2.2 AA;
  mixing pairs voids that guarantee.
- **One emphasis per region**: one filled button, one tinted container. `primary` marks the
  action, not the decoration.
- **Third-party marks** (Claude, GitHub, Discord, Apple, Google Play…) use the owner's official
  logo file unmodified. Their brand colours never become a button or a background.

### Where the values live

**Target, not the current state.** The files below still carry the retired palette; each one
is migrated to this table in its own PR (follow-ups). Until then the table above wins over
whatever a file contains.

| Surface | File | Target |
|---|---|---|
| Web | `website-static/assets/tokens.css` | CSS custom properties named after the official M3 web tokens, `--md-sys-color-<role>` (e.g. `--md-sys-color-surface-container`), light on `:root`, dark under `prefers-color-scheme: dark` and `[data-theme="dark"]` |
| Docs (MkDocs Material) | `docs/docs/stylesheets/extra.css` | The same values, mapped onto MkDocs Material's palette variables |
| Android (Compose) | `samples/android-demo/src/main/java/io/github/sceneview/demo/theme/Color.kt` (values) and `theme/Theme.kt` (schemes) — not `SceneViewColors.kt`, which is the 3D scene palette | `lightColorScheme(…)` / `darkColorScheme(…)` with every role above, read through `MaterialTheme.colorScheme.<role>`; `dynamicColor = false` |
| Apple (SwiftUI) | `samples/ios-demo/SceneViewDemo/Theme.swift` | System colours only — `.primary`, `.secondary`, `Color(.systemBackground)`, `Color(.secondarySystemBackground)`, `Color(.separator)`, `.red` / `.green` / `.orange` for state — plus one `AccentColor` asset: `primary` light `#004494`, dark `#ADC6FF`. A filled button (`.borderedProminent`) sets its label to `on-primary` (`#FFFFFF` light, `#002E69` dark) with `.foregroundStyle`: SwiftUI's default white label on `#ADC6FF` is 1.7:1 |

---

## Typography

**The system font, and nothing else.**

- Web: `font-family: system-ui, -apple-system, Roboto, "Segoe UI", sans-serif;` — Roboto on
  Android, SF on Apple, Segoe on Windows. No web-font download for UI text.
- Code only (code blocks, inline code, a value the user copies):
  `font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;`.
- Compose: `MaterialTheme.typography.<role>`, default M3 type scale, no custom `FontFamily`.
- SwiftUI: text styles (`.largeTitle` … `.caption2`) with Dynamic Type. No fixed point sizes.

Hierarchy comes from the platform's scale, never from another font, tracking or all-caps.

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

The number that matters on a screen is Display Small; its trend or unit sits next to it in
Label Medium. Body copy stays at Body Large / Body Medium with a line length under ~70
characters.

---

## Elevation

**Elevation is a surface tone, not a shadow.** A container sits one tone above what is behind
it (M3 "Elevation", tonal surfaces):

| Level | Role | Typical component |
|---|---|---|
| 0 | `surface` | Page, screen, top app bar at rest |
| 1 | `surface-container-low` | Bottom sheet, elevated content area |
| 2 | `surface-container` | Navigation bar / rail, menu, scrolled top app bar |
| 3 | `surface-container-high` | Dialog, search bar, a card inside a sheet |
| 4 | `surface-container-highest` | Filled card, filled text field, tile on a card |

A shadow appears only where the platform component draws its own (a menu, a FAB, an item
being dragged). Where two tones meet with too little contrast, add an `outline-variant`
hairline, not a shadow. Cards are M3 *filled* or *outlined* cards.

On Apple: grouped lists and `Form`, `Color(.secondarySystemBackground)` for a container, the
system's own sheet and popover backgrounds.

---

## Shape, space, touch

M3 "Shape" and "Layout". Use the component's default shape; these are the defaults to know:

| Element | Corner |
|---|---|
| Card | 12 dp (M3 medium) |
| Bottom sheet (top corners), dialog | 28 dp (M3 extra-large) |
| Buttons, FAB-like round controls, search bar | Full (pill) |
| Chip, menu, small tile | 8 dp (M3 small) |
| Text field (filled, top corners) | 4 dp (M3 extra-small) |

- **4 dp grid.** Every spacing is a multiple of 4 (4, 8, 12, 16, 24, 32, 48…).
- **Margins**: 16 dp in compact width (< 600 dp), 24 dp from medium width (600 dp) up. Web
  follows the same M3 window classes: compact < 600 px, medium 600–839, expanded 840–1199,
  large ≥ 1200; reading column ≤ 840 px.
- **Touch targets**: 48 × 48 dp on Android and the web, 44 × 44 pt on Apple, whatever the
  visible size. Android TV and Auto follow their own M3 / Car guidance (larger targets, D-pad
  focus).
- **Primary actions at the bottom**, in thumb reach, on phones.
- **Edge to edge**: content runs under the system bars; insets come from the platform
  (`WindowInsets` / `safeAreaInset`), never a hardcoded offset.

---

## Motion

Use the platform's motion, not ours (M3 "Motion", HIG "Motion").

- Compose: `MaterialTheme(motionScheme = MotionScheme.standard())` and the components'
  built-in transitions, never a hand-tuned `spring(…)` or `tween(…)` for chrome; screen
  changes are M3 transitions (shared axis, container transform, fade through); predictive back
  enabled, every pop transition seekable by the gesture.
- SwiftUI: the default animations and system transitions (`.smooth`, navigation push, sheet
  presentation).
- Web: M3 easing tokens — standard / emphasized `cubic-bezier(0.2, 0, 0, 1)`, emphasized
  decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)`, emphasized accelerate
  `cubic-bezier(0.3, 0, 0.8, 0.15)`; durations 100–200 ms for small changes, 300–500 ms for a
  screen.
- No bouncy overshoot, no hover lift, no scroll-reveal choreography. Opacity and position
  only; a 3D camera move eases in and out and is cancelled by the first touch.
- **Reduced motion**: `prefers-reduced-motion`, Android animator scale 0, iOS Reduce Motion —
  keep the fades, drop the travel and loops.

---

## Accessibility

- WCAG 2.2 AA: 4.5:1 for text, 3:1 for large text, icons and the boundary of a control
  (1.4.11). The role pairs above meet it; anything composited over a 3D scene or a camera must
  be measured on the worst ground it can meet.
- Font scale up to 200 % on Android and the web, Dynamic Type up to the accessibility sizes on
  Apple. Text never truncates: the layout grows.
- Every control has an accessible name; a visible caption may be shorter than the name.
- Visible focus for keyboard and D-pad.
- Never colour alone: a state also has a word or an icon.

---

## Over the 3D scene and the camera

A 3D view or a camera feed is media, not a themed surface. Chrome over it follows the same
rules as everywhere else, with these additions:

- **Controls over the scene are platform components with opaque fills from the current
  theme**: icon buttons (M3 filled tonal icon button), a navigation bar, a bottom sheet, a
  snackbar (`inverse-surface` / `inverse-on-surface`) for one-line transient guidance, a
  filled card (`surface-container-high`) for anything with actions. Opaque means the contrast
  holds over a white wall and a dark room alike.
- **Text that must sit directly on the picture** (a full-screen caption, the system bars over
  a bright scene) gets a **flat** band of `scrim` at 60 % behind it, edge to edge, hard-edged, with text
  in white `#FFFFFF` in both themes, or a soft fade when contrast still holds.
- **A sheet you tweak the scene through** is a standard (non-modal) bottom sheet resting low
  enough to keep the subject in view; browsing sheets (pickers, credits) are modal.
- **The 3D stage background** is a scheme surface (`surface` or `surface-container-low`) or a
  real lit environment (an HDR, a studio floor, fog) rendered by the engine. Light falloff the
  renderer computes is a scene; a fade that blends the scene into the page is allowed.
  Colours of materials inside a scene (a floor, a ball, a grid) are scene content, live in code
  next to the scene, and are not UI tokens.
- **AR guidance**: on Apple use `ARCoachingOverlayView`; on Android, the SDK's
  `ARCoachingOverlay`. User copy never says "ARCore", "ARKit", "tracking" or "plane" — it says
  what to do ("Move your phone slowly", "Point it at the floor").
- **Recording** is the platform's red record control (`error` on Android, `.red` on Apple), and
  red over a camera means recording, nothing else.
- **A loader names the step the code is really in** ("Downloading Robot (3.2 MB)…",
  "Decoding the model…"), never a timed script; one progress indicator per screen (M3 progress
  indicator, `ProgressView`).

---

## Product rules that stay

- **Support is asked, never pushed.** One row or card on About; no launch prompt, no badge, no
  urgency copy. On Apple, tips go through In-App Purchase and unlock nothing.
- **Other apps are listed, not advertised**: plain list rows with the app's real icon, name
  and one line; no price, no install button.
- **Titles and labels are never truncated**, at any font scale.
- **A demo screen can give the scene the whole screen**; one control in the same corner brings
  the chrome back, and system Back does too.

---

## 3D models shown

What a capture, store listing, video, website hero or demo shows:

- **Never** the Khronos glTF sample models (ToyCar, Fox, DamagedHelmet, Duck, Avocado,
  BoomBox, FlightHelmet…) and never a low-poly or dated asset. Unit tests may keep them.
- **Premium, detailed PBR models**, creatures and characters first, then products. Sources:
  Sketchfab (downloadable staff picks), Fab, Poly Haven (CC0).
- **Every licence allows promotional use** — CC0, CC BY, or a licence that says so. No NC
  (non-commercial), no ND where we re-light or re-texture. Source, author and licence go in
  `assets/catalog.json` (`CREDITS.md` is generated from it) and on screen where the model is
  credited.
- New assets follow `tools/fetch-assets.sh --register` and the `assets-v1` release (see
  `CLAUDE.md`).

### Preview images

Each demo ships `preview_<demo_id>_light.webp` and `preview_<demo_id>_dark.webp` in
`samples/android-demo/src/main/res/drawable-nodpi/`, 800 × 640 (5 : 4).

- Three-quarter view, ~20° elevation, subject ~70 % of the frame, soft key + rim light.
- Field: flat `surface-container-low` of the theme (`#F2F3FC` / `#191B22`). No gradient, no
  vignette, no props.
- AR demos keep a real camera photo as the background, never a synthetic room.
- Never text, UI, device frames or watermarks.
- Generated with Gemini image-to-image from real captures of the demo: the model shown is the
  model the demo loads. Never invent one.

---

## Logo

- The mark is the **isometric cube**. It is drawn **flat**: each face one solid colour from
  the primary tonal palette (`primary-container`, `primary`, `on-primary-container` in light;
  their dark values on dark grounds). No gradient, no glow, no inner shadow.
- On a coloured ground, a single-colour version (`on-primary` on `primary`) is acceptable.
- Clear space: 25 % of the mark's width on every side; minimum size 24 px (use the favicon
  below that).
- Files: `branding/logo.svg`, `branding/logo-dark.svg`, `branding/app-icon-1024.svg`,
  `branding/npm-icon.svg`, `branding/feature-graphic.svg`, `website-static/favicon.svg`,
  `website-static/apple-touch-icon.svg`, `website-static/og-image.svg`, the Android launcher
  (`samples/android-demo/src/main/res/drawable/ic_launcher_foreground.xml`,
  `ic_sceneview_mark.xml`, `ic_sceneview_hero.xml`) and the TV banner
  (`samples/android-tv-demo/src/main/res/drawable/tv_banner.xml`).
- **Follow-up**: the SVG and vector files above still carry gradients and the old palette.
  They are to be redrawn flat in a dedicated PR; until then they are the known exception, not a
  reference.

---

## The review test

Run on the **capture**, not on the code, in light **and** dark, before anything is shown or
merged:

1. Put the capture next to the visual target and the best previous version of the same screen
   (for the home: 4.68). Ask: *is this more striking than before, and did it lose anything that
   worked?* If it lost something, redo it. Native screens are a reference for controls and
   legibility only, never for the scene.
2. Read the **Forbidden** list against the capture.
3. Check the model on screen against *3D models shown*.
4. Measure contrast on any text over a scene or a camera.

---

## For AI agents

1. Use the platform component; style it only through the theme.
2. Read colours from roles (`MaterialTheme.colorScheme.*`, `--md-sys-color-*`, system colours
   on Apple). Never write a hex literal outside the theme files.
3. Use the system font and the platform type scale.
4. Ship light and dark together.
5. Re-read **Forbidden** before you finish.

The cinematic home that shipped up to 4.68 (full-bleed 3D hero under the app bar, its veil,
the featured carousel, the large illustrated cards) is the reference Thomas validated ("bluffé",
2026-09-27). Do not remove what made it striking; the custom type and radius scales stay retired.

Known follow-ups, each its own PR: the theme files under *Where the values live*; the logo
files under *Logo*; the MCP widgets and artifacts (`mcp/src/widgets.ts`, `mcp/src/artifact.ts`:
gradients and `#0d1117`); `branding/social-preview.html`; the Khronos BoomBox model in the
`CinematicHero` recipe of `llms.txt`.
