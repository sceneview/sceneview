# SceneView Design System

> Agent-friendly design system (Google Stitch DESIGN.md format).
> Source of truth for all UI across website, apps, docs, and store assets.

---

## Brand Identity

- **Name:** SceneView
- **Tagline:** 3D and AR for every platform
- **Logo:** Isometric cube, blue gradient
- **Voice:** Technical but approachable. Developer-first, AI-optimized.

---

## Design Philosophy

### Material 3 Expressive

Material 3 Expressive principles guide all interactive surfaces and motion:

- **Bold colors:** Use primary and gradient tokens with high saturation for hero elements; avoid washed-out or neutral-only palettes.
- **Variable typography:** Scale headings expressively with `clamp()` — hero text should feel large and confident; body text stays readable and compact.
- **Spring animations:** Interactive elements (buttons, cards, nav items) use spring-based easing (`ease-spring`) for physical, bouncy feedback.
- **Dynamic shapes:** Corners vary by component role — small utility elements use `radius-xs` (8px), prominent cards use `radius-xl` (28px), pills use `radius-full`.

### Liquid Glass Accents

Glassmorphism adds depth and layering to surfaces that float over content (nav, modals, cards on hero backgrounds):

- **Mechanism:** `backdrop-filter: blur()` + semi-transparent background + subtle border
- **Restraint:** Apply only to overlapping or floating surfaces — not every card. Overuse flattens the effect.
- **Dark mode:** Reduce opacity further in dark mode; glass should whisper, not shout.

### Professional Developer SDK Aesthetic

- **Clarity over decoration:** Every design choice must serve legibility of code, APIs, and documentation.
- **AI-optimized:** Consistent tokens and patterns so AI agents can generate correct UI on the first try.
- **Neutral confidence:** The palette is blue-dominant and professional — no playful pastels or consumer-app softness.

---

## Colors

### Primary

| Token | Light | Dark | Usage |
|---|---|---|---|
| `primary` | #005bc1 | #a4c1ff | Links, buttons, accents |
| `primary-hover` | #0050aa | #b8d0ff | Interactive hover states |
| `primary-light` | rgba(0,91,193,0.08) | rgba(164,193,255,0.10) | Subtle backgrounds |
| `primary-subtle` | rgba(0,91,193,0.12) | rgba(164,193,255,0.15) | Borders, overlays |

### Brand Gradient

| Token | Value | Usage |
|---|---|---|
| `gradient-hero` | `linear-gradient(135deg, #005bc1 0%, #6446cd 100%)` | Hero headings (light) |
| `gradient-hero-dark` | `linear-gradient(135deg, #a4c1ff 0%, #d2a8ff 100%)` | Hero headings (dark) |
| `gradient-hero-alt` | `linear-gradient(135deg, #0d419d 0%, #5a32a3 100%)` | Secondary hero treatment |

### Surfaces

| Token | Light | Dark | Usage |
|---|---|---|---|
| `surface` | #ffffff | #0D1117 | Page background |
| `surface-dim` | #f1f3f5 | #0B0E14 | Recessed ground. In dark this sits *below* the page; see the note under this table |
| `surface-container-low` | #ffffff | #1B212D | Low-emphasis container |
| `surface-container` | #ffffff | #232A39 | Cards, bottom sheets, dialogs |
| `surface-container-high` | #f1f3f5 | #2C3546 | Tiles, chips, thumbnails — a container on a container |
| `surface-container-highest` | #e9ecef | #354056 | Fields inside a sheet |
| `stage-scrim-start` | transparent | transparent | Spatial Gallery media scrim start |
| `stage-scrim-end` | rgba(0,0,0,0.90) | rgba(0,0,0,0.90) | Spatial Gallery media scrim end |
| `glass-surface` | rgba(255,255,255,0.72) | rgba(255,255,255,0.05) | Floating Spatial Gallery controls |
| `glass-border` | 1px rgba(255,255,255,0.08) | 1px rgba(255,255,255,0.08) | Floating control outline |
| `stage-background` | #0B0F16 | #0B0F16 | **Full-screen** 3D stage clear colour — the 3D fills the screen with no page around it, so the value is identical in both themes |
| `stage-background-embedded` | #0B0F16 | #22293E | A 3D stage **embedded in a card** (home hero, card thumbnails). In dark it takes the elevated container value so the card keeps a visible background against the page; #0B0F16 there sits at 1.01:1 on `surface` and the card disappears |
| `stage-lighting-floor` | #2A3346 | #2A3346 | Floor of the Lighting demo stage (Android `LightingStage.FLOOR_COLOR`, iOS `Stage.lightingFloor`): a blue-grey slate dark enough for a contact shadow, light enough to catch the key light. Fixed in both themes, like the stage |
| `stage-tray-maple-early` | #E6CDA3 | #E6CDA3 | Pale earlywood of the Rolling Balls playing field (Android `TrayStage.MAPLE_EARLY`, procedural `tray_wood` grain): the lit ground every ball reads on. Fixed in both themes — the board is an object on the stage |
| `stage-tray-maple-late` | #C39A63 | #C39A63 | Growth rings of the maple field (Android `TrayStage.MAPLE_LATE`). Fixed in both themes |
| `stage-tray-walnut-early` | #6E452B | #6E452B | Walnut frame of the Rolling Balls board, between its rings (Android `TrayStage.WALNUT_EARLY`). Fixed in both themes |
| `stage-tray-walnut-late` | #2E1B12 | #2E1B12 | Rings of the walnut frame and body (Android `TrayStage.WALNUT_LATE`): the dark edge that frames the pale field. Fixed in both themes |
| `stage-tray-steel` | #D7DCE3 | #D7DCE3 | Chrome ball of the Rolling Balls demo (Android `TrayStage.STEEL_COLOR`, metallic, roughness 0.06): near-neutral so it mirrors the studio. Fixed in both themes |
| `stage-tray-glass` | #BFE6EA | #BFE6EA | Glass marble of the Rolling Balls demo (Android `TrayStage.GLASS_COLOR`, full transmission, IOR 1.5): the aqua tint the light picks up through it, and its chip swatch. Fixed in both themes |
| `stage-tray-rubber-1`…`-5` | #F2654B · #2E86F0 · #F5B029 · #2FBF8F · #9B5DE5 | same | Rubber balls of the Rolling Balls demo, in turn (Android `TrayStage.RUBBER_COLORS`, clear-coated): coral, azure, amber, mint, orchid — each holds on the maple and on the walnut. Fixed in both themes |
| `stage-pip-floor` | #E2E6EB | #161B22 | Floor of the iOS Secondary Camera (PiP) stage (iOS `Stage.pipFloor`; dark is Android's `StageSky.floor`). Light is a step under `surface-container-highest` so the ground reads against the backdrop (1.13:1 on `stage-pip-backdrop`, enough for a fill; the grid carries the edge) |
| `stage-pip-grid` | #7A8494 | #5C6780 | Grid lines on `stage-pip-floor` (iOS `Stage.pipGrid`): 3.0:1 on the floor in both themes, where Android's `outline` / `outline-subtle` measured 1.18:1 / 2.18:1 |
| `stage-pip-backdrop` | #F1F3F5 | #0B0F16 | Flat backdrop of the Secondary Camera stage (iOS `Stage.pipBackdrop`, = `surface-container-high` light / `stage-background` dark). The floor texture fades into exactly this colour at its rim, so no camera angle shows the floor's edge |
| `ar-scrim` | rgba(0,0,0,0.94) | rgba(0,0,0,0.88) | AR coaching overlay ground, over the camera feed |
| `ar-scrim-border` | 1px rgba(255,255,255,0.16) | 1px rgba(255,255,255,0.10) | AR coaching overlay hairline |

**The dark ramp is solved for ratio, not picked by eye.** Contrast ratio compresses at
the dark end, where `(Y+0.05)` is dominated by the constant: against a `#0D1117` page a
container needs `L* ≥ 15` before it reaches even **1.25:1**, the point where a fill starts
to read as a distinct surface. The dark column above was previously three tones — the two
`surface-container` rows were within 0.9 L\* of each other and of `surface-dim` — so every
card, sheet and chip was drawn the same colour as its background. Reported as *"you cannot
see the background of elements at all, unlike light, which makes it confusing."*

Nesting in these products is two deep at most (page → card/sheet → tile/field), so the
ramp targets that depth rather than stacking 1.25:1 at every step, which would end pale
grey and off-brand. The two depth-2 pairs that still land at 1.17:1 — a tile inside a
sheet, a dialog over a sheet — carry an `outline-subtle` hairline instead of more tone.

`surface-dim` is *not* the role for "a tile that must stand out": at the dark end there is
no room below the page, and elevation reads as lighter. Use a `surface-container-*` role.

Light keeps every value it had except `surface-container-highest`, which gains a step so a
field inside a sheet separates there too.

### Demo App Home (Android)

Tokens the demo app's home screen uses that are not Material roles. Chips follow
the surface ramp above, not the M3 tonal ramp.

| Token | Light | Dark | Usage |
|---|---|---|---|
| `chip-bg` | #f1f3f5 (`surface-container-high`) | #2C3546 | Unselected category chip |
| `chip-text` | #3d4654 (`on-surface-dim`) | #a4abb7 | Unselected chip label |
| `chip-selected-bg` | #1a1a2e (`on-surface`) | #f3f4f6 | Selected category chip |
| `chip-selected-text` | #ffffff (`surface`) | #0D1117 | Selected chip label |
| `hero-title` | #ffffff | #ffffff | Hero headline — the hero is an image card that stays dark in both themes |
| `hero-sky-top` | #0B0F16 | #0B0F16 | Home stage sky, top stop — the dusk gradient Compose paints behind the transparent live flight (#3948); one gradient in both themes, the hero stays dark |
| `hero-sky-dusk` | #3B1D46 | #3B1D46 | Home stage sky, mid stop |
| `hero-sky-horizon` | #E2734F | #E2734F | Home stage sky, horizon stop — also the flight's fog colour, so the far ridges dissolve into it |
| `hero-sky-ground` | #2A1220 | #2A1220 | Home stage sky, below the horizon, under the terrain |
| `hero-subtitle` | rgba(255,255,255,0.80) | rgba(255,255,255,0.80) | Hero subtitle, max width 260dp |
| `hero-pill-bg` | #ffffff | #ffffff | Hero CTA pill (44dp, `radius-full`) |
| `hero-pill-text` | #1a1a2e | #1a1a2e | Hero CTA label |
| `header-glass` | `surface` at 72 % over the list under it blurred `header-glass-blur` (24dp) | `surface` at 78 %, same blur | Sticky home header once the list scrolls under it (API 31+). The blurred copy is drawn over a `surface` ground, so no sharp title shows through: the rows read as colour moving behind frosted glass, never as an overlap. `on-surface` holds 8.7:1 (light) / 8.2:1 (dark) against the worst ground, `on-surface-variant` 4.9:1 / 4.2:1 (icons, 3:1 bar). The stage is a `TextureView`, so its frame is in the recorded copy too. iOS: the same `surface` veil over native Liquid Glass (`glassEffect(.regular)`) on iOS 26+, over `.ultraThinMaterial` below — bare glass let the row titles read through |
| `header-overlay` | `surface` at 100 % | `surface` at 100 % | The same header below API 31 (no `RenderEffect`). Tried at the `glass-sheet` opacity: without a backdrop blur the card titles scrolling under the wordmark stay legible and read as an overlap bug, so without blur it stays opaque |
| `card-glass` | `surface-container` (#ffffff) at 80 % over the card's own picture blurred 28dp — at 72 % a dark picture turned it a muddy grey | `surface-container` at 90 % (the `glass-sheet` value), same blur | Caption of a home card. Below API 31 (no `RenderEffect`) there is no blurred copy and the fill takes `glass-sheet` (88 % / 90 %) |
| `outline-subtle` | #ebedf0 | #46516a | 1dp card and header hairline (see Borders) |

**Under the 3D header, the Home is a list of pictures.** The live header is the one
showpiece; below it every demo is a card led by its own capture, and the capture is the
card: no frame, no inset, no radius of its own. One vertical scroll, no carousel: the
"Featured" banners, the "Browse online models" row, the category chips, then one group per
category.

**Above the chips, a demo is pushed once.** The header pager, the "Featured" banners and
the "What's new" row's picture are read in that order, and each only shows what the ones
before it do not; a group left with nothing of its own is not drawn. The pager stays at two
pages (Models, Cosmos) so the banners keep the other three featured demos: the banner is the
card the Home is liked for. Under "All", a demo shown as a banner is not listed again in its
category; a chip or a search is a query and lists every match. The "What's new" row
opens the "What's new" sheet over the whole catalogue. It never selects the filter chip:
a tap on a home row must not make the catalogue look smaller than it is.

**A chip filter always shows its way out.** The selected chip is its own off switch (a
second tap goes back to "All"), Back clears the filter before it leaves the app, and a
filtered list ends on a "Show all N samples" text button that names the size of the
whole catalogue.

A card's ground is **ambient**: the colour of its own picture, taken down to a fixed
luminance (`home-row-ambient`), so in dark every card is a deep version of its scene and
in light a pale one, and the picture dissolves into it with no line between image and text.
Two shapes, one recipe:

- A **Featured banner** is the picture full width at `home-banner-aspect`, dissolving
  downward from `home-banner-dissolve`, the caption pulled up `home-banner-caption-overlap`
  into the fade.
- A **catalogue row** is the picture over the leading `home-row-media-fraction` of the card,
  full height, dissolving sideways from `home-row-dissolve`; the text starts at
  `home-row-text-start`, where the picture is down to a tenth of its opacity.

The title is `type-card` (17, semibold, `on-surface`), the subtitle `type-caption` regular
(`on-surface-variant`), the "New" / "Updated" / status chips on their own line above the title (`home-badge`: 3dp vertical padding, 12dp icon, accent text on `surface-container` at 92%, measured 6.3:1 light / 7.3:1 dark; the line collapses to zero height when no chip draws, so plain rows keep their rhythm). "New" covers a demo whose `addedIn` is within the last two minors of the build, "Updated" one whose `updatedIn` is, and both expire on their own; a "What's new" row under the hero and a "What's new" filter chip appear only while at least one demo carries a marker. Each card is
its own `home-row-radius` tile, `home-row-gap` from the next. No shadow, no outline, in
either theme — the ambient tone carries it. A demo without a capture shows its glyph at
`home-row-glyph` in the category accent, over the accent's own ambient tint washed with
the accent at 18 %. The press is the platform ripple, no scale. Titles and subtitles are
never truncated; the card grows.

| Token | Value | Usage |
|---|---|---|
| `home-row-ambient` | Chroma-weighted mean of the picture (weight 0.1 + chroma, 1/16 downsample), saturation x1.2 capped at 0.5, then lightness solved to relative luminance 0.035 (dark) / 0.84 (light) | A card's ground. On it: `on-surface` 11.2:1 / 14.5:1, `on-surface-variant` 5.3:1 / 8.1:1, whatever the picture (unit-tested on both platforms) |
| `home-row-height` | 116dp minimum | A catalogue row; it grows with the text |
| `home-row-media-fraction` | 0.5 | Width of the picture in a catalogue row, full height, edge to edge |
| `home-row-dissolve` | 0.42 | Where the picture starts dissolving towards the text, as a fraction of its width; cosine ease to 0 at its edge |
| `home-row-text-start` | 0.44 of the card | Leading edge of a row's text column; 16dp to the trailing edge, 14dp above and below, 4dp title to subtitle |
| `home-banner-aspect` | 2 : 1 | A Featured banner's picture, full card width |
| `home-banner-dissolve` | 0.55 | Where a banner's picture starts dissolving towards its caption, as a fraction of its height |
| `home-banner-caption-overlap` | 28dp | How far the caption is pulled up into the banner's fade; 16dp across |
| `home-row-radius` | 20dp | Every card's corners; the picture follows them, it has none of its own |
| `home-row-gap` | 10dp | Between two cards |
| `home-row-glyph` | 40dp | Glyph of a demo without a capture, and of "Browse online models" |
| `home-row-min-width` | 340dp | From two of these across the content width, the list lays out in columns (a tablet) |
| `home-group-gap` | 16dp (`space-md`) | Between two groups with no header between them ("Featured" and "Browse online") |

Tried and dropped (2026-09-30): the 2dp-seam grey block with a 120x96 inset picture — a
cropped window in a grey slab, the picture framed rather than shown; every card a banner —
the catalogue became a two-screen scroll per category; every card a sideways row — the
Featured demos lost the width their scenes are composed for.

Catalogue **section headers** (the full-span label above each group of rows) use
`on-surface` at `titleMedium` / `weight-semibold` — no colour of their own, because a
header that tints itself competes with the rows it introduces. Geometry:

| Token | Value | Usage |
|---|---|---|
| `section-header-top-gap` | 24px (`space-lg`); 8px (`space-sm`) for the first one, right under the chips | Above a section header |
| `section-header-bottom-gap` | 12px | Header to its group |

A header is drawn only when more than one section is visible: with a single category
filtered, the chip already names it.

**The picture card** (`DemoMediaCard`) is no longer on the Home; the Explore tab keeps it.
A card is a square picture whose lower edge *melts* into a frosted caption: the caption's
ground is a blurred copy of the same picture under `card-glass`, faded in over
`card-glass-melt`, so there is no line between image and text. No white box, no border in
light (`shadow-sm` lifts it), the 1 dp `outline-subtle` in dark.

| Token | Value | Usage |
|---|---|---|
| `card-media-aspect` | 1 : 1 | Card picture |
| `card-glass-blur` | 28dp | Blur of the picture copy under a card caption |
| `card-glass-melt` | 28dp | Band over which the sharp picture fades into the glass (the fade spans twice this, centred on the caption's top) |

Contrast of `card-glass`, composited over the worst uniform ground: light, over black —
`on-surface` 10.6:1, `on-surface-variant` 5.9:1; dark, over white — 9.6:1 and 4.56:1. Dark
is at 90 % and not lower because light pictures do reach dark mode: at 85 % the Animation
card's light-grey stage took its caption to 4.4:1.

iOS draws the same list in SwiftUI (`HomeListRow.swift`, `ShowcaseTab.swift`, `home-row-*`
tokens in `Theme.swift`): the same groups in the same order, the same cards from the same
captures (the twelve shared imagesets, `tools/demo-previews/README.md`), the same ambient
arithmetic (`HomeAmbient`, unit-tested with Android's cases), one column on an iPhone and
340 pt columns on an iPad. The press is `on-surface` at 10 % over the card, a list cell's
highlight, no scale. Card text follows Dynamic Type up to `accessibility2`. The Explore tab's
"Try a demo" row keeps the picture card (`DemoMediaCard.swift`).

### Demo App About (Android)

The About tab carries **exactly one emphasised surface**: the support card. Everything
else — the identity block, the groups of rows — sits at `surface` or `surface-container`
so the eye lands on the one thing the screen is for.

| Token | Value | Usage |
|---|---|---|
| `about-mark` | 80dp / 80pt, `radius-xl` | Identity mark — a 3D cube with two orbit rings on iOS. Android uses `ic_sceneview_hero`; iOS keeps the `about_mark` image set as its placeholder until the 3D mark is ready. Never use a Material glyph or an SF Symbol |
| `about-stage-height` | 176pt | Height of the iOS 3D mark band (`About.stageHeight`) |
| `about-stage-shadow-size` | 132 × 18pt | Soft contact-shadow ellipse under the floating iOS mark |
| `mark-color` | Body `#3D7FD9`, lid `#BDD3FF`, orbit `#A4C1FF` | iOS `MarkColor` palette for the 3D cube, inset, rings and satellites |
| `mark-shadow` | `#0B1B3A` | Light-mode core colour of the contact shadow; it fades to transparent at the rim |
| `about-row-icon` | 20dp | Leading glyph of an action row |
| `about-row-affordance` | 16dp open-in-new / 20dp chevron | Trailing glyph — leaves the app, or stays in it. Two sizes because the chevron is the thinner drawing: matched boxes read as two icon sets. |
| `about-row-divider-inset` | 48dp | Hairline start inset, so it begins under the label |
| `about-group-radius` | 16px (`radius-md`) | The `surface-container` card wrapping one group of rows |
| `about-support-radius` | 24px (`radius-lg`) | The support card — `secondary-container`, the only tinted surface of the screen |

- **The identity block is not a card.** A slab there is a second emphasised surface
  competing with the support card below it, which is exactly what the pre-#3564 screen
  did with a 110dp gradient tile.
- **The mark is the launcher icon**, cut from `ic_launcher_foreground` and kept
  theme-independent — it is the product's identity, the same picture in light and dark,
  and its contrast is self-contained (light cube on `#0D2137`).
- **Support is stated once and never pushed.** One card, on the About tab, above the
  fold. No dialog, no launch prompt, no badge, no amounts, no tiers, no urgency copy.

### Text

| Token | Light | Dark | Usage |
|---|---|---|---|
| `on-surface` | #1a1a2e | #f3f4f6 | Primary text |
| `on-surface-dim` | #3d4654 | #a4abb7 | Secondary text. Lifted with the surface ramp: #9ca3af was 7.45:1 on the dark page but only 3.79:1 on the new lightest container, i.e. it would have failed 4.5:1 exactly where the ramp fix made surfaces lighter |
| `on-surface-faint` | #5c6370 | #6b7280 | Tertiary text, captions |
| `on-ar-scrim` | #ffffff | #ffffff | AR coaching overlay text — white in both themes, the ground is the camera |
| `on-ar-scrim-dim` | rgba(255,255,255,0.72) | rgba(255,255,255,0.72) | AR coaching overlay secondary text |

### Borders

| Token | Light | Dark | Usage |
|---|---|---|---|
| `outline` | #d6dae0 | #8b95a6 | Default borders, and the boundary that identifies a control (WCAG 1.4.11). #2a3346 was 1.50:1 on the dark page — an unfocused search field was invisible until focused |
| `outline-subtle` | #ebedf0 | #46516a | Light dividers, and the hairline that separates a container from the container behind it where tone alone cannot |

### Status

| Token | Value | Usage |
|---|---|---|
| `success` | #16a34a | Positive states, checkmarks — as a fill, dot or icon. It is 3.3:1 on white, so it is never light-mode text |
| `success-text` | #166534 light · #56d364 dark | `success` as text ("Stable", "Fully supported"), including on its own 8–12% tinted chip: >= 5.6:1 on every light surface. The website's `--color-status-stable` |
| `warning` | #f59e0b | Caution states |
| `danger` | #ea4335 | Error states, destructive |
| `info` | #ea580c | Informational highlights |

### Partner Colors

| Token | Value | Usage |
|---|---|---|
| `claude-orange` | #d97757 | Claude/Anthropic branded elements |
| `claude-gradient` | `linear-gradient(135deg, #d97757 0%, #c4622e 100%)` | Claude CTA buttons |
| `discord-purple` | #5865f2 | Discord community links |

### Code Syntax

| Token | Value | Element |
|---|---|---|
| `syntax-keyword` | #cba6f7 | Keywords (val, fun, import) |
| `syntax-function` | #89b4fa | Function names, methods |
| `syntax-string` | #a6e3a1 | String literals |
| `syntax-number` | #fab387 | Numeric values |
| `syntax-comment` | #6c7086 | Comments |
| `code-bg` | #1e1e2e | Code block background (light) |
| `code-bg-dark` | #0d1117 | Code block background (dark) |
| `code-text` | #cdd6f4 | Code text (light) |
| `code-text-dark` | #c9d1d9 | Code text (dark) |

---

## Typography

### Font Families

| Token | Value | Usage |
|---|---|---|
| `font-body` | 'Inter', system-ui, -apple-system, sans-serif | All body text |
| `font-mono` | 'JetBrains Mono', ui-monospace, 'Cascadia Code', 'Fira Code', monospace | Code, terminal |

### Font Scale (responsive)

| Token | Size | Usage |
|---|---|---|
| `text-hero` | clamp(2.5rem, 6vw, 4rem) | Hero title |
| `text-section` | clamp(1.75rem, 4vw, 2.5rem) | Section headings |
| `text-subtitle` | clamp(1rem, 2vw, 1.125rem) | Section subtitles |
| `text-card-title` | 1.125rem | Card titles |
| `text-body` | 1rem (16px) | Body text |
| `text-small` | 0.9rem | Secondary text, labels |
| `text-xs` | 0.85rem | Captions, badges |

### App Type Scale (Android demo)

The only five text styles the demo app's own chrome uses. `-0.02em` tracking on
display/title; line height 1.2 on display/title, 1.35 on body.

| Token | Size / Weight | Usage |
|---|---|---|
| `type-display` | 32sp / 700 | Hero headline |
| `type-title` | 22sp / 600 | Screen and sheet titles |
| `type-card` | 17sp / 600 | Card titles |
| `type-body` | 15sp / 400 | Body copy, descriptions |
| `type-caption` | 13sp / 500 | Chips, captions, dock labels |

### Font Weights

| Token | Value | Usage |
|---|---|---|
| `weight-regular` | 400 | Body text |
| `weight-medium` | 500 | Emphasized body |
| `weight-semibold` | 600 | Subheadings, buttons |
| `weight-bold` | 700 | Section headings |
| `weight-extrabold` | 800 | Hero title |

### Letter Spacing

| Token | Value | Usage |
|---|---|---|
| `tracking-tight` | -0.03em | Headlines |
| `tracking-normal` | 0 | Body text |
| `tracking-wide` | 0.05em | Uppercase labels |

---

## Spacing

Base unit: **8px**

| Token | Value | Usage |
|---|---|---|
| `space-xs` | 4px | Tight gaps, icon padding |
| `space-sm` | 8px | Compact gaps |
| `space-md` | 16px | Default gaps, card padding |
| `space-lg` | 24px | Section gaps, card padding (mobile) |
| `space-xl` | 32px | Card padding (desktop) |
| `space-2xl` | 48px | Large section gaps |
| `space-3xl` | 64px | Section separators |
| `space-4xl` | 96px | Section top/bottom padding |

### Spatial Gallery Media

| Token | Value | Usage |
|---|---|---|
| `hero-stage-height` | 360px | Online-gallery hero stage height |
| `media-aspect` | 1.25 | Online-gallery model-card media aspect ratio |

---

## Border Radius

M3 Expressive shape scale — corner radius communicates component weight and prominence.

| Token | Value | M3 Scale | Usage |
|---|---|---|---|
| `radius-xs` | 8px | XS | Small elements, icon containers, chips |
| `radius-sm` | 12px | S | Code blocks, inputs, badges, tooltips |
| `radius-md` | 16px | M | Buttons, medium cards, dialogs |
| `radius-lg` | 24px | L | Section cards, bottom sheets |
| `radius-xl` | 28px | XL | Prominent cards, showcase items, hero panels |
| `radius-full` | 9999px | Full | Pills, avatars, FAB, fully rounded elements |

---

## Shadows

### Light Mode

| Token | Value | Usage |
|---|---|---|
| `shadow-sm` | 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06) | Subtle lift |
| `shadow-md` | 0 4px 12px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06) | Cards, dropdowns |
| `shadow-lg` | 0 12px 40px rgba(0,0,0,0.12), 0 4px 12px rgba(0,0,0,0.06) | Modals, prominent |
| `shadow-primary` | 0 2px 8px rgba(26,115,232,0.3) | Primary button |
| `shadow-primary-hover` | 0 4px 16px rgba(26,115,232,0.4) | Primary button hover |

### Dark Mode

| Token | Value |
|---|---|
| `shadow-sm` | 0 1px 3px rgba(0,0,0,0.3) |
| `shadow-md` | 0 4px 12px rgba(0,0,0,0.4) |
| `shadow-lg` | 0 12px 40px rgba(0,0,0,0.5) |

---

## Motion

### Easing

| Token | Value | Usage |
|---|---|---|
| `ease-spring` | cubic-bezier(0.34, 1.56, 0.64, 1) | Bouncy interactions — buttons, cards, spring hover |
| `ease-expressive` | cubic-bezier(0.2, 0, 0, 1) | Smooth transitions — page changes, reveals, drawers |

### Duration

| Token | Value | Usage |
|---|---|---|
| `duration-short` | 200ms | Hover, focus, micro-interactions |
| `duration-medium` | 350ms | Card reveals, tab switches |
| `duration-long` | 700ms | Scroll reveal, page transitions |

### Patterns

- **Spring hover:** `translateY(-4px)` with `ease-spring` easing and shadow increase — feels physically responsive
- **Standard hover lift:** `translateY(-2px)` with `ease-expressive` — subtler, for secondary elements
- **Scroll reveal:** `translateY(24px) opacity(0)` to `translateY(0) opacity(1)` over `duration-long` with `ease-expressive`
- **Button press:** `scale(0.97)` on active/mousedown with `ease-spring`, releases back with overshoot
- **Reduced motion:** Respect `prefers-reduced-motion: reduce` — disable `translateY` and `scale`, keep opacity fades

### App Motion (Android demo)

One spring and one fade for the chrome; one shared-axis spec for screen changes, one
fly-in for a 3D subject's arrival, one short handover from the loading cover to the first
rendered frame, and one breathing ellipsis for a step in flight. In AR,
the coaching card and a placed object's entrance (the `motion-coach-*` and
`motion-placement-*` tokens below — shipped by the SDK, so every AR app gets them).
Nothing else animates.

| Token | Value | Usage |
|---|---|---|
| `motion-spring` | `spring(dampingRatio = 0.85, stiffness = 450)` | Press scale (0.97–0.98), sheet open/close, dock show/hide, panel expand |
| `motion-fade` | `tween(300ms, FastOutSlowIn)` | Every opacity change — chrome toggle, menus |
| `motion-handover` | `tween(150ms, FastOutSlowIn)` | The loading cover giving way to the first rendered frame — the scene is already there, so the veil leaves fast |
| `motion-screen` | `tween(350ms, ease-expressive)` | Screen transitions — Material shared-axis X, both screens travelling ⅙ of the viewport while they cross-fade |
| `motion-entrance` | `tween(700ms, ease-expressive)` | The camera fly-in when a 3D scene's subject arrives — once per screen, cancelled by the first touch |
| `motion-coach-sweep` | 2800ms per sweep, eased both ways, across 22–78 % of the illustration | The phone of the AR coaching card sweeping over the surface it is looking for, lighting feature points behind it. Half speed and 60 % opacity while tracking is limited |
| `motion-coach-resolve` | `spring(dampingRatio = 0.6, stiffness = 400)`, scale 0.6 → 1 | The "surface found" beat — the card leaves and a pill with a `primary` check springs in below centre, held 1s |
| `motion-placement-entrance` | `tween(260ms)`, scale 0.55 → 1, cubic ease-out | A placed AR object growing into place about its contact point. Reversed over 300ms (`motion-fade`) when tracking is lost — opaque glTF materials cannot fade, so they shrink |
| `motion-narration` | `1200ms` linear loop, opacity only (0.25 → 1) | The trailing ellipsis of a loading line (`NarrationText`): the light runs across the three dots. The line names the step the code is really in — "Searching Sketchfab…", "Downloading *name* (3.2 MB)…", "Decoding the model…" — never a timed script. One loader per screen — the M3 Expressive `LoadingIndicator`, or a `CircularWavyProgressIndicator` ring once the byte count is known. Static `…` under reduced motion |

**Reduced motion.** When the system animator scale is 0 (Android) or Reduce Motion is on
(iOS), the coaching card's illustration is drawn as its settled frame — no sweep, no spin — and only the
fades remain, as on the web (`prefers-reduced-motion`).

---

## Liquid Glass

Glassmorphism layer system for surfaces that float over content. Apply with restraint.

### Light Mode

| Component | Background | Backdrop Filter | Border |
|---|---|---|---|
| **Nav glass** | rgba(255,255,255,0.72) | blur(20px) | 1px solid rgba(255,255,255,0.08) |
| **Card glass** | rgba(255,255,255,0.60) | blur(16px) | 1px solid rgba(255,255,255,0.06) |
| **Button glass** | rgba(255,255,255,0.08) | blur(12px) | none |

### Dark Mode

| Component | Background | Backdrop Filter | Border |
|---|---|---|---|
| **Nav glass** | rgba(255,255,255,0.05) | blur(20px) | 1px solid rgba(255,255,255,0.08) |
| **Card glass** | rgba(255,255,255,0.03) | blur(16px) | 1px solid rgba(255,255,255,0.06) |
| **Button glass** | rgba(255,255,255,0.08) | blur(12px) | none |

### Usage Rules

- Nav glass replaces the solid `surface` background when the nav scrolls over hero/image content.
- Card glass applies to cards placed directly on gradient hero sections or image backgrounds — not on flat `surface-dim`.
- Button glass is for secondary ghost-style CTAs on dark/image backgrounds only.
- Always add `will-change: backdrop-filter` for performance on animated glass elements.
- Fallback for browsers without `backdrop-filter` support: use the solid `surface` or `surface-container` token.

### Glass Chrome over Media (Android demo)

The demo chrome floats over a live Filament / ARCore viewport, which is media, not a
themed surface — so it is theme-independent and uses the "Button glass" row.

| Token | Value | Usage |
|---|---|---|
| `glass-surface` (over media) | rgba(255,255,255,0.14) | Back button, identity pill, dock — **0.14, not 0.08, wherever there is no backdrop blur**; see below |
| `over-media-edge` | 1px rgba(255,255,255,0.36) **+** 1px rgba(0,0,0,0.75) outside it | The edge of every element that floats over media — dock, pills, cards, chips. Drawn **outside** the fill; replaces `glass-border` |
| `on-glass` | #ffffff | Icons and labels on glass |
| `on-glass-muted` | rgba(255,255,255,0.72) | Secondary label on glass |
| `chrome-scrim` | rgba(0,0,0,0.60) → transparent | Ground under the chrome bands |
| `glass-icon-button` | 44dp visual, 48dp touch target | Back |
| `glass-pill` | 36dp high, 14dp horizontal padding | Identity pill |
| `mode-pill-container` | #1A1F28, opaque | A segmented mode switch over the scene (Cosmos "Starlight \| Spacetime"), in the `bottomOverlay` band |
| `mode-pill-outline` | 1dp #D1D2D4, opaque | Its edge: the container clears 3:1 on grounds of L ≥ 0.14, the outline on L ≤ 0.18 — every ground is covered |
| `mode-pill-selected` / `on-mode-pill-selected` | #FFFFFF / #0B0F16 | The checked segment and its label (19.2:1) |
| `on-mode-pill` | #FFFFFF | Unchecked labels (16.5:1 on the container) |
| `mode-pill-segment-min-width` | 72dp | A segment's minimum width, so a short label ("ML") keeps a target as wide as the 48dp row is tall |

- **No blur on Android.** A `SurfaceView` cannot be sampled by a Compose render
  effect, so glass over the scene is fill + border only. Do not emulate blur.
  (The Home header is not over a `SurfaceView`: what scrolls under it is Compose and a
  `TextureView`, which a `GraphicsLayer` can record, so it takes a real blur — `header-glass`.)
- **Which is why the fill is 0.14, not 0.08.** 8 % white is a value borrowed from
  surfaces that back it with a real backdrop blur, where the blur separates the
  panel from the media by *structure* and the fill was never doing the work alone.
  Without blur it has to, and at 8 % it cannot: measured over the `#0B0F16` stage
  that is **1.20:1**, and 1.14:1 over a 60 %-scrimmed camera feed. 0.14 clears
  1.25:1 on both grounds with margin (1.47:1 and 1.35:1) while still reading as
  glass rather than a solid sheet. Web and iOS keep 0.08 wherever they have a
  genuine `backdrop-filter` — see *Demo Scaffold (iOS demo)*.
- **The edge is two bands, and it is drawn outside.** 1.25:1 is a *fill* bar; the
  line that identifies a control is WCAG 1.4.11's **3:1**. The old 1px 0.24 white
  border failed it for a reason no opacity could fix: `Modifier.border` strokes
  *inside* the bounds, over the panel's own 14 % white fill — white on that fill is
  **1.03:1**, invisible by construction, on every ground and in both themes. Over
  media, the ground is not ours to choose (a white wall, a night room), so no single
  colour passes either: 36 % white is 1.4:1 on `#F5F5F5`, 75 % black is 1.5:1 on
  `#050505`. `over-media-edge` therefore pairs them — white ring straddling the
  boundary, black halo 1px further out, both on the media — so the room can only
  lose to one band at a time. What carries the boundary changes with the room, and
  that is the point — on a bright ground the halo separates from the media (9.9:1 on
  `#F5F5F5`), on a dark one the two bands separate from each other (3.27:1 on
  `#050505`, where the halo against the media is only 1.02:1). Computed by sRGB
  source-over compositing of the token alphas over five camera grounds, worst
  adjacency per ground:

  | ground | ring \| halo | halo \| media | ring \| fill |
  |---|---|---|---|
  | `#F5F5F5` white wall | 10.20:1 | 9.89:1 | 1.03:1 |
  | `#CFC8BD` pale carpet | 9.32:1 | 7.68:1 | 1.18:1 |
  | `#8A6F55` wood floor | 6.90:1 | 3.60:1 | 1.69:1 |
  | `#1E1B18` dim room | 3.93:1 | 1.18:1 | 2.90:1 |
  | `#050505` night | 3.27:1 | 1.02:1 | 3.23:1 |
  | `#000000` black, the floor | **3.14:1** | 1.00:1 | 3.27:1 |

  The worst ground is pure black, and there the boundary still reads from both
  sides: **3.14:1** outward against the media, **3.27:1** inward against the panel's
  own fill. Verified on device, not only on paper — captured on the emulator over the
  black AR backdrop, the bands render rgb(92) over the media and rgb(115) over the
  fill, which is 36 % white composited over each, to the unit. The single-band border
  this replaces reached 1.01–1.28:1 on the same grounds.

  Bright grounds are still the untested half: no emulator here starts an ARCore
  session, so the white-wall column is arithmetic, not a photograph.
- **The chrome bands sit on `chrome-scrim`.** White on media reads only when the
  media is dark, and a demo scene can be any brightness — a near-white studio
  erases an 8 % white fill and white glyphs alike. The top band (160dp) and the
  bottom band (220dp minimum) each carry a vertical scrim, flat for the 55 %
  nearest the screen edge and fading to transparent, so the chrome never depends
  on what the scene happens to render behind it. Both are drawn under the
  overlay slots, so they tint the scene and never a demo's own overlay card. The
  top scrim fades with the chrome; the bottom one grows to the measured overlay
  band and outlives the fade, because a status pill or legend stays on screen
  after a scene tap has hidden the dock.

  **A phone held sideways (compact height) has no bands, only grounds.** 160dp
  is more than a third of a 411dp window and 220dp more than half: together they
  leave a strip of scene. Each scrim keeps to what it grounds. The top one is
  whole behind the upper half of the status bar, where the clock and the battery
  are drawn with nothing of their own, and eases out over `space-2xl` past the
  bar — smoothstep, not a straight ramp, because a ramp that ended at the bar
  drew a line across the picture. The bottom one is whole under the system bar
  and the lower half of the dock, gone `space-lg` above the dock, and leaves
  with the chrome. Everything glass that floats over the scene — the back button
  and the title pill, a status pill, a demo's own pills — then carries
  `chrome-scrim` itself, under its glass and cut to its own outline
  (`LocalGlassGround`): same stack (scene, scrim, glass, glyph), same contrast,
  on the chip instead of across the picture.
- **There is no overflow menu.** Reset, Send feedback and QA mode live in the
  settings sheet the dock's Controls item opens — one settings surface, not two.
- **A sheet you tweak the scene through is glass, low and non-modal (#3827).** The
  settings sheet exists to be watched through: drag a slider, look at what it did.
  - `sheet-peek`: it rests at **36 % of the window** (or hugs its controls when they
    are shorter), so the upper two thirds — where every demo frames its hero — stay
    visible. Dragging up reveals the rest, stopping `space-2xl` under the status bar.
    A `ModalBottomSheet` cannot do this: its partial detent is fixed at half the
    screen. The settings sheet is a standard sheet (`BottomSheetScaffold`).
  - **No scrim**, and the scene above the sheet stays touchable — iOS
    `presentationBackgroundInteraction(.enabled)`. The sheet carries its own close
    button, since there is nothing to tap outside it.
  - `glass-sheet`: `surface-container` at **88 % (light) / 90 % (dark)**, no tonal
    tint, no shadow. Android has no blur, so the opacity is solved for text over the
    three grounds a demo can put behind it (stage, mid-grey scene, white AR wall):
    `on-surface` ≥ 9.5:1 and `on-surface-variant` ≥ 4.5:1 in both themes. Dark is
    more opaque because its worst ground is the white wall. Light started at 78 %,
    which passed on contrast, but a lit model read through the chips as a second
    sharp image; 88 % leaves the scene as a silhouette.
  - **The dock fades out while a glass sheet is open.** Seen through the glass it
    read as a row of live buttons that were not there.
  - The Model Viewer's Lighting sheet uses the same glass fill and no scrim.
    Browsing sheets (model picker, credits, what's new) stay opaque and modal — you
    read those, you do not watch something change behind them.

### Floating Dock (Android demo)

| Token | Value |
|---|---|
| `dock-height` | 64dp minimum; grows with the system font scale so labels are never clipped |
| `dock-radius` | `radius-full` |
| `dock-item` | 48dp minimum touch target; icon over caption |
| `dock-icon` | 22dp |
| `dock-caption` | `type-caption`, 2dp under the icon, one line, never truncated |
| `dock-items` | at most 4 items + 1 optional accent (primary-tinted) item |
| `dock-accent` | 40dp filled disc, 48dp touch target, 12dp from the dock edge on every side; `#A4C1FF` fill with `#002F64` glyph (dark-scheme `primary` / `onPrimary`) in **both** themes — over media, never `colorScheme` |
| `dock-selected` | A selected toggle item sits on a `radius-md` pill filled `#A4C1FF`, icon + caption `#002F64` (dark-scheme `primary` / `onPrimary`, both themes) — 7.3:1 on any scene |

The dock replaces FABs and top app bars in demo screens; its Controls item opens the
settings sheet. Show/hide uses `motion-spring`; tap on the scene toggles the chrome
with `motion-fade`. A demo's own overlays stay through that tap — a status pill or a
capture button is not chrome — unless everything it lays over the scene *is* chrome (the
Room Scan replay's timeline): it opts in with `overlaysFollowChrome`, and the tap gives
the scene the whole window (#4379).

- **A thin bar goes with the dock (#4379).** The bottom overlay slot ends 24dp above the
  dock, so a card reads as stacked over it. A one-row bar that belongs *with* the dock —
  a timeline, a scrubber — sinks by `dockGap - space-sm` and rests `space-sm` above it.
- **A mode that is a developer tool stays off the screen (#4397).** The mode pill is for
  two experiences of equal weight (Cosmos "Starlight | Spacetime"). When the second mode
  is a tool the QA harness opens — Room Scan's "Session MP4" — the host sets
  `showSwitch = false`: no pill over the scene, no row in the settings sheet, and the
  mode opens by deep link only (`?tab=<key>`).

- **Every dock item is labelled.** An icon-only dock makes the user decode glyphs, and
  two actions in the same row can legitimately want the same picture — the viewer had
  an outlined cube for "Models" beside a filled cube for "View in AR". The caption is
  one word (`Models`, `Lighting`, `Animate`, `Recenter`, `Settings`); if an action
  needs more than one word to be understood, the wrong action is in the dock. Icon and
  caption share a colour, so a selected toggle reads as one unit.
- **The accent is the exception.** It is a filled, primary-tinted disc (`dock-accent`,
  40dp visual in a 48dp touch target) and stays icon-only — a caption would not fit
  `dock-height`, and its treatment already sets it apart from the labelled items the way
  a FAB is set apart from a navigation bar. At 40dp it sits 12dp from the dock edge on
  every side, the same air the first labelled item has at the leading end; a 48dp disc
  ended 8dp from the rounded cap and read as touching it (#3835).
- **Everything in a pill is centred on the pill.** A glass surface that is raised to the
  48dp touch target centres its 36dp content; it never pins it to the top.
- **Actions under a centred toast or card are centred on it** — never start-aligned
  under centred text (`SceneActionBar`).
- **The caption is not the accessible name.** The content description stays the full
  phrase ("Demo settings"); only the visible caption is shortened ("Settings").

### Demo Scaffold (iOS demo)

`DemoScaffold` is the one SwiftUI shell every iOS demo screen stands in: the scene
full-bleed, the chrome above it, one settings sheet. A demo passes its `SceneView` and,
at most, one accessory and its controls — it never places chrome, reads a safe area or
presents a sheet itself. Same tokens as the two Android sections above; what differs is
listed here.

| Token | Value | Usage |
|---|---|---|
| `glass-surface` (iOS) | rgba(255,255,255,0.08) under `.ultraThinMaterial` | Floor — what the blur cannot fall under over dark media |
| `glass-ceiling` (iOS, dark scheme) | rgba(42,43,44,0.60) over the material | Ceiling — what the blur cannot rise above over bright media |
| `glass-border` | 1pt rgba(255,255,255,0.24) | iOS keeps a hairline border: `.ultraThinMaterial` is a real blur, so the border is read against a panel the blur has already separated from the media. Android has no blur and replaced this token with `over-media-edge` (above) |
| `dock-caption` (iOS) | `caption2` / 500 | Five captioned items + the accent fit 402pt; at larger Dynamic Type sizes the dock falls back to icons, the accessibility label stays |

**Bottom of the screen, in points** (measured on the 402 × 874pt iPhone 17, 34pt home
indicator; every value follows the safe area, none is a constant offset from the edge):

| Element | Value |
|---|---|
| Dock bottom edge → screen bottom | `max(16, safe-area + 8)` = **42pt** (16pt with a home button) |
| Dock height | 64pt |
| Accessory (option strip / hint) → dock | 12pt |
| Option strip height | 48pt (44pt segments) |
| Horizontal margin, every chrome block | 16pt |
| Back button → safe-area top | 8pt |
| Settings sheet, resting | hugs the measured controls + 24pt inset top and bottom, capped at half the screen; never a fraction that can cut a control |
| Settings sheet, last control → sheet edge | 24pt + the bottom safe area (68pt visual on iPhone 17) |
| Shared rows (Reset · Send feedback · QA mode) | below the fold, `safe-area + 8pt` past the resting edge; scroll or expand to reach them |

- **iOS 26+: native `glassEffect`; below: the material stack.** On iOS 26 and later every
  chrome surface over the stage (back button, identity pill, dock, option strip, hint) is
  the system's Liquid Glass — `.regular`, `.interactive()` on controls — and the dock
  cluster is one `GlassEffectContainer`, so the accessory and the dock morph into each
  other. The dock accent is `.glassProminent` tinted `primary`. Below 26 the floor /
  material / ceiling / border stack below still applies. Content cards inside a page
  (About, Credits) keep the stack on every version, and AR chrome keeps its `ar-scrim`
  ground. Android keeps its own glass fill — an accepted divergence.
- **A material is not a colour — it needs a floor and a ceiling.** `.ultraThinMaterial`
  is a blur of what is behind it. Over dark media it resolves to nearly black (hence
  the 8 % floor); over a bright studio backdrop the dark-scheme material resolves to
  the backdrop itself — measured **1.01:1**, a pill with no edge. The ceiling is the
  dark-scheme counterpart of the floor, and with the 24 % border the better of
  fill-vs-ground and border-vs-ground never drops under **1.43:1** on dark, mid and
  bright grounds (border 3.12:1 on the dark stage).
- **The sheet follows the theme — iOS 26+: native glass on the partial detents; below:
  a themed surface.** On iOS 26 and later the sheet takes no background of its own, so
  the resting detent is the system's glass sheet and the scene stays visible behind the
  controls; the system turns it opaque at `.large`. Below 26 it is `surface-container`,
  the app's light/dark colours, `outline-subtle` hairline. (Android's settings sheet is
  `glass-sheet` since #3827 — the translucency iOS gets from its sheet material,
  Android has to get from opacity; see the Android scaffold section.) The stage and its
  chrome are media and stay dark in both schemes; the sheet is the only part of a demo
  that follows the theme.
- **Motion.** Stage fades in (`motion-fade`, 300 ms); chrome rises 12pt (top) / 24pt
  (bottom) on `motion-spring` — measured 333 ms; an option change moves the selection
  capsule on the same spring. Under Reduce Motion the travel is dropped and the opacity
  fade stays.
- **VoiceOver order** is back, title, scene, accessory, dock — Settings last.

---

## Breakpoints

| Token | Value | Description |
|---|---|---|
| `bp-desktop` | > 1024px | Full layout, 3-column grids |
| `bp-tablet` | <= 1024px | 2-column grids |
| `bp-mobile` | <= 768px | Hamburger nav, single column |
| `bp-small` | <= 600px | Full-width buttons, stacked |
| `bp-xs` | <= 480px | Reduced padding, compact text |

---

## Layout

| Token | Value | Usage |
|---|---|---|
| `container-max` | 1200px | Content max width |
| `container-padding` | 24px (desktop), 16px (mobile) | Horizontal page padding |
| `nav-height` | 64px | Fixed navigation height |

---

## Components

### Navigation
- Height: `nav-height` (64px)
- Background: `surface` at 88% opacity + `backdrop-filter: blur(16px)`
- Border bottom: 1px solid `outline`
- Position: fixed, z-index: 100

### Buttons
- **Primary:** bg `primary`, text white, radius `radius-md`, shadow `shadow-primary`
- **Outline:** border 1.5px `outline`, transparent bg
- **Ghost:** no border, transparent bg
- **Padding:** 12px 24px (default), 14px 28px (large)
- **Font:** `weight-semibold`, `text-small`
- **Hover:** lift -1px, shadow increase, bg darken

### Cards
- Background: `surface-container`
- Border: 1px solid `outline`
- Radius: `radius-lg` (24px)
- Padding: `space-xl` (32px), `space-lg` on mobile
- Hover: lift -3px, shadow `shadow-lg`, border `primary-subtle`

### Code Blocks
- Background: `code-bg`
- Text: `code-text`, font `font-mono`
- Radius: `radius-sm`
- Padding: `space-md`
- Border: 1px solid rgba(255,255,255,0.05)
- Font size: `text-xs` (0.85rem)

### AR Coaching Overlay

The one instruction surface shown over a live camera feed (`DemoStatusBanner` on Android).

- Ground: `ar-scrim` — a near-opaque dark pill, **not** a brand-coloured one. The
  background it must beat is an arbitrary camera frame, not an app surface, so the
  ground does not flip with the theme; only its opacity does (light mode is used
  outdoors more often, so it is a touch more opaque).
- Text: `on-ar-scrim`, `text-body` at `weight-medium`, max 3 lines, one short sentence.
- Border: `ar-scrim-border`, shadow `shadow-lg` — separates the pill from a busy frame.
- Radius: `radius-lg`; padding 16px horizontal, 12px vertical; max width 480px.
- Leading indicator, 20px, one per severity:

| Severity | Indicator | Accent |
|---|---|---|
| Progress | Indeterminate spinner | `primary` (dark value #a4c1ff) |
| Guidance | Gesture / move-device icon | `warning` |
| Blocked | Error icon | #ffb4ab (dark-scheme error) |

- Accents are the **dark-scheme** values in both themes: they are read on `ar-scrim`.
- Motion: enters with fade + 8px rise (`duration-medium`, `ease-expressive`), leaves
  with fade + fall (`duration-short`). Nothing to say → nothing on screen.

### AR Coaching Card

The animated onboarding shown over the camera while an AR session starts, searches or
loses tracking — Apple's `ARCoachingOverlayView` on iOS, `ARCoachingOverlay` in
`arsceneview` on Android (#4038). It shows the gesture **and** says what to do: an
illustration over a two-line instruction, in one card.

- Ground: an `ar-scrim` card, `ar-scrim-border` hairline, `radius-lg`, 20dp padding,
  max width 320dp (480dp in the landscape row layout). It sits at **45 %** of the safe
  band (bias −0.1), above centre, so the thumb zone and the bottom chrome stay clear.
  The band is the `safeDrawing` area, minus the host's `contentPadding` (the demo passes
  its dock), minus a 16dp gutter.
- Illustration, 200 × 120dp (160 × 96dp from `fontScale` 1.5): strokes in `on-ar-scrim`,
  accents in the dark-scheme `primary` and `warning`.
- Text: headline 18sp semibold `on-ar-scrim`, max 2 lines; detail 15sp `on-ar-scrim-dim`,
  max 2 lines. The block reserves two lines of each, so the card never jumps.
- Layout: column; a row (illustration left, text right) when the band is under 420dp tall.

| Cue | Illustration | Headline · detail |
|---|---|---|
| Initializing (after 500ms) | The phone rises into view, lens up | Getting ready · Hold your phone up and look around |
| Scan (floor) | Phone sweeping over a perspective floor; feature points light up behind it | Move your phone slowly · Point it at the floor or a table |
| Scan (wall) | The same sweep over an upright wall | Point at a wall · Move your phone slowly across it |
| Scan, 8s without a surface | unchanged | detail becomes *Try a brighter spot with more texture* |
| Surface found | Card leaves; a pill with a `primary` check springs in below centre for 1s | Surface found / Wall found |
| Tracking limited | The scan at 60 %, half speed | Keep looking around · Move slowly, in a well-lit spot |
| Relocalizing | Phone with chevrons pointing back | Point back at your object · Look where you placed it |

- **Reason chip.** When ARCore names a reason during start-up or a loss, a `warning` chip
  (near-black #101014 text, 13sp semibold; 8.8:1) straddles the card's top edge, clear of the phone — *Too dark*, *Too
  fast*, *Low detail* — and the headline becomes its fix (*Move to a brighter spot*,
  *Move your phone more slowly*, *Aim at something with more texture*). It waits for a
  reason held 700ms and stays at least 1.5s. Never during a plain scan.
- **Motion.** Card in: fade + 8dp rise + scale from 0.96 (`duration-medium`,
  `ease-expressive`); out: fade + scale to 0.96 (`duration-short`). Text changes
  fade through (90ms out, 210ms in, 4dp rise). The sweep loops every 2.8s. A cue holds at
  least 1.5s; a loss must last 600ms before the card comes back, a recovery 500ms before it
  leaves. With animations off (any of the three system scales at 0) only fades remain and
  the illustration rests mid-sweep.
- **Accessibility.** The card is one node: headline and detail as its description,
  announced politely (not while initializing). The found pill is announced the same way.
- **Hide the chrome while it shows** (Apple HIG): status pills and hints step aside while
  the card is up (`ArGuidanceState.isCoaching`) — the card names the reason itself, so no
  exception for low light. Action cards never step aside: the card is silent whenever one
  explains the state.
- Copy never says "ARKit", "ARCore", "tracking" or "plane".

### AR Overlay Card

The surface for what an AR demo needs the user to **see** rather than read — a code to
share, an input to fill, a meter to watch, or an explanation of why the screen cannot
work. It stacks directly under the coaching overlay in the same bottom band, so the two
must read as one language: same `ar-scrim` ground, same `ar-scrim-border` hairline, same
`radius-lg`, same `card-max-width` (480dp: iPad, landscape), and no elevation shadow: under a translucent scrim a
shadow shows through as a darker inner rectangle in dark mode. Padding `space-md`,
children spaced `space-sm`.

- **Theme-independent, like the coaching overlay, and for the same reason.** The ground is
  a camera frame. A demo card that used `surface` at 90% opacity — as the Cloud Anchors
  screen did before #3421 — is a near-white slab over the room in light mode and a
  near-black one in dark, neither contrasted against anything on purpose.
- Title `type-card` in `on-ar-scrim`; body and captions `type-body` / `type-caption` in
  `on-ar-scrim-dim`.
- **Codes are `font-mono` and shortened**, never wrapped prose. Show `first6…last4`; the
  full value goes to the clipboard, the share sheet and the accessible name.
- **Text input** is unstyled (`BasicTextField`), never a Material text field: every
  Material field colour is a theme role, which is the wrong ground here. Field fill is the
  `Button glass` white-at-8% used for every "present but empty" element over media.
- **Meters** use three segments at `radius-xs`, `meter-height` (`space-sm`) tall,
  `space-xs` apart. Lit segments take the coaching overlay's accent for the state they
  represent (`warning` while the user must keep moving, `primary` once the value is
  acceptable); the unlit track is the same `Button glass` fill. A **download** meter
  uses ten segments, lit in `accent-progress`, one per 10 % (ML depth model).
- **An explanation card carries no action when there is none.** A configuration a user
  cannot change from the phone gets a title and one sentence — a button that would do
  nothing is worse than a plain explanation (`ARCoreAvailabilityOverlay`, #3374).
- **One card at a time, and never doubled with a pill saying the same thing.** When a card
  explains the state, the coaching overlay stays silent.
- **Recording is `danger` red, and only recording.** The live dot of a capture card and
  the shutter disc use `danger` (#ea4335) — the camera-app convention — so a red dot over
  the camera always means "this is being recorded", never an error (#3831).

### AR Debug View (Android demo)

The in-app 3D view (#3950) of the **Room Scan** demo (`ar-rerun`; named for what the user
does, not for the tool, since #4306): what ARCore understood of the room, drawn by a
second `SceneView` from a free third-person camera. The layout borrows from three
references: **Polycam** (live camera with a small 3D preview, one tap to the full 3D
inspection), the **Rerun viewer** (a dark spatial view with entity toggles over a
timeline), and **Reality Composer** (planes and anchors drawn over the camera itself —
the camera mode keeps `ARSceneView`'s own plane renderer).

- **The scene first (#4379).** The room or the camera keeps the screen — 70 % of the
  window clear of chrome at rest in the replay. What is read once is a row of the settings
  sheet, never a card over the scene: the layers and their figures, the room's size,
  Points | Surface, a scan's counts and the computer stream's status. There is no
  picture-in-picture and no corner card: the other view is one dock cell away.
- **Two modes, one dock toggle.** *Camera*: the AR camera, bare. *3D view*: the debug view
  full screen, entity toggles on top, the timeline card at the bottom. Recenter is the
  dock's third item.
- **The replay follows the theme; the camera does not (#4080).** Over the live camera the
  3D view is drawn on `Stage.background` with the AR overlay chrome, in both themes. The
  replay and the landing are views the app draws itself, so they take the **themed stage**
  (below): in dark theme the same `Stage.background` and media chrome, in light theme a
  `surface-dim` ground under `glass-sheet` chrome with `on-surface` text.
- **Themed stage** (`DemoScaffold(themedStage = true)`, `StageChrome`, read through
  `LocalStageChrome`; `themedStageChrome()` picks by the surface's luminance). *Media*: the
  theme-independent glass of every demo. *Light*: ground `surface-dim`; glass and cards
  `surface-container` at 88 %; text `on-surface` (13:1), secondary `on-surface-dim`
  (8.9:1); edge `on-surface` at 12 %, no halo; the chrome bands wash towards the ground
  instead of black; the one filled accent is the light scheme's `primary`; the status bar
  keeps dark icons. The Room Scan and Camera & Gestures demos opt in — every other stage is
  media.
- **Stage sky** (`StageSky`, #4089): the backdrop of a themed stage whose subjects stand on
  an open floor that the camera orbits. A flat skybox in the stage ground (the zenith) under
  Filament height fog in `surface-container` (the horizon): the fog covers the far floor and
  the sky just above the horizon, so floor, horizon glow and sky are one gradient at every
  camera elevation — no hard horizon, no empty clear colour. The floor plane is
  `surface-container-highest` in light, the `surface-dim` grounding plane in dark. Use it
  instead of a Compose gradient behind a transparent scene whenever the camera can tilt.
- **Colour carries meaning, and only existing palette values carry it**
  (`SceneViewTokens.DebugView`, one palette per ground). *Dark*: the trail runs the brand
  ramp from `accent-deep` (oldest) to `tint-light` (now); the live frustum is `tint-light`;
  what the camera sees *this second* is `warning`; everything seen so far is dim white;
  anchors are `success`; planes are a translucent fill with a solid outline, blue on floors
  and tables, lilac on walls; the origin gizmo follows X red / Y green / Z blue. *Light*: the
  same meanings in the light scheme's values — the trail runs `tint-soft` → `tertiary` →
  `primary`, the frustum and floors take `primary`, walls `tertiary`, history points are
  `on-surface` at 55 %, the grid `on-surface` at 8 % / 16 %.
- **Only the present glows.** On the dark ground the trail head, the live frustum, the live
  points and the anchors are pushed past 1.0 in linear light so bloom lifts them; history
  stays flat. The eye lands on "now" without a legend. On the light ground nothing glows —
  bloom only brightens, and a haze on a light stage reads as a smudge — so saturation
  carries the present.
- **Photos are sharp.** The keyframes projected onto planes and shown in the filmstrip are
  full-resolution, mipmapped, trilinear with 8× anisotropy, so a floor seen at a grazing
  angle keeps its boards legible.
- **Never a blank first frame.** The replay's shaders are compiled by one off-screen draw
  while the landing is read, so opening a replay reveals it in a fraction of a second; the
  loading line covers whatever is left.
- **Screen-constant sizes.** Point, line and tube widths are specified in pixels and turned
  into metres from the orbit distance (quantised, so a pinch does not rebuild every frame):
  a room seen from 8 m and a table seen from 50 cm both read.
- **The camera frames itself until touched.** It eases to a three-quarter view of the
  room as it grows; the first drag hands it to the user (drag orbits, two fingers pan,
  pinch zooms); double-tap or Recenter hands it back.
- **The room is fitted into the clear band (#4306).** The subject is the box of everything
  drawn — path, planes, anchors and both point clouds, trimmed of stray points — and it is
  fitted, corner by corner, into the **clear band** of the stage: the part the header above
  and the timeline bar below leave free. The camera stands as far back as the fit asks
  (tested up to a 20 × 9 m box, some 60 m away); the box is what ARCore reported, which can
  over-read the room itself (#4328). The replay **measures** that band on screen
  (`OrbitBand.between`), so it holds on any phone or font scale, and keeps the last band
  it measured while a tap has the chrome hidden — the room does not jump; a view with
  nothing to measure takes the band of its orientation. The camera is lowered, never
  tilted, to centre the room in it. Left alone the view sways
  ±14° about the side the room was scanned from — never a turntable, which ends behind a
  wall. The *Map* is squared with the walls by the nearest quarter-turn. A gesture stops
  at the room's edge: the eye stays outside the cloud and above the floor, a pinch out
  stops at 1.6× the framed distance, a pan keeps its pivot in the room. Every automatic
  move is eased and capped at 150°/s, by the shortest way round; the opening is one short
  crane-in from the same side.
- **One 3D view, two readings: Points | Surface (#4306).** A scan that can be meshed heads
  its settings sheet with a full-width two-way switch (#4379 — it headed a card over the
  stage). *Surface* draws the room's mesh in the same view, under the same camera, in place
  of its points and planes — never a second screen. Under the switch, one line says what
  the surface is doing (building, then "Surface preview" and its triangle count), and a
  `.glb` share button stands beside it once built; a recording with nothing to mesh has no
  switch. The surface is a preview — a short scan gives a coarse, patchy mesh.
- **The room in front, the tracking aids behind it (#4306).** The glows are held low
  (trail head 2.0, live points and frustum 1.4), the history frusta are `primary` at 25 %
  on dark and 30 % on light, the live points are 3 px. What the scan *produced* — the
  photographed planes, the coloured cloud, the mesh — keeps its full strength.
- **A phone on its side (#4306, #4379).** Under 500 dp of height the window has no row to
  give above or under the room — provided it is wide enough for a 280 dp side card to
  leave a stage beside it (`OrbitBand.halfWidthBeside`; a short *and* narrow window stays
  stacked). The replay's timeline bar stands in the top corner, under the header's line,
  280 dp wide; the room stays centred and is fitted clear of the bar, under the status bar
  and `Space.lg` above the dock — its dimensions are written under its floor
  (`OrbitBand.betweenSides`). A scan in progress puts its 3D card on one side and its line
  on the other, the same 280 dp, and the shutter keeps the middle.
- **Replay timeline: one bar, one touch target tall (#4379).** Play/pause, a filmstrip of
  the recorded frames, the clock ("0:07 / 0:24"), on one `radius-lg` glass row `space-sm`
  above the dock, as wide as an AR overlay card (480 dp at most). The strip is the
  scrubber: the part still to come is dimmed, a playhead marks now; dragging pauses. A tap
  on the stage hides it with the header and the dock, and another brings them back.
- **Live 3D view timeline**: play/pause, the time, a scrubber, the length, and a *Live*
  chip in `success` while the view follows the session. Scrubbing pauses; *Live* jumps back.
- **A scan in progress says one line (#4379).** The red dot, "Scanning", the scan's tier
  and the clock in `type-title`, white on the dark scrim; a second line only at the photo
  limit. The only red is `danger` (the dot and the shutter). The counts (points, surfaces,
  photos) are rows of the settings sheet — nobody reads them while walking a room. The 3D
  card under the line is the same view the replay opens on, growing as the phone moves,
  framed to the scan with no intro. While recording the dock is empty: nothing may leave a
  scan half-taken. The privacy line ("Everything stays on your phone.") ends the idle copy.
- **One flow, like a capture app (Polycam, Scaniverse, Reality Composer), laid out as the
  iOS demo's (#4068).** The demo opens on a scrolling page on the themed stage's ground, max width
  560 dp, clear of the header and the settings button: `type-display` "Scan a room in 3D"
  over one muted line; the one primary action, "Record your room", a 96 dp `radius-lg` card
  in `accent-progress` with the camera in a 56 dp well, "Everything stays on your phone."
  under it and a chevron; then a row of two glass actions, "Watch a sample session"
  (weighted) and "Open file". "Your sessions" (`type-card`, "On this phone" on the right)
  lists glass `radius-md` cards: the first photo (64 dp, `radius-sm`), the title, "date ·
  source", then "path · points · photos · duration", and a ⋮ menu with "Share scan file" and
  "Delete" — Delete in the theme's `error` colour, confirmed by a dialog whose confirm is
  `error` too. No session yet is a dashed outline with what goes there. A file that does
  not open says why in a card tinted `danger` at 24 %; a file being read shows "Opening
  file…". Record starts by itself once ARCore has found the room; Stop saves the scan on
  the phone and opens it in the **same** replay as the sample, whole and paused on its
  last frame. The page follows the theme, like the replay it opens (#4080); the iOS demo
  is still pinned dark (#3907). Streaming to a computer is an
  advanced option in the sheet, under its own heading; its status card shows only once
  connected.

### Tabs
- Padding: 10px 20px
- Background: `surface-dim`
- Radius: `radius-sm`
- Active: bg `surface-container`, text `primary`, `weight-semibold`

---

## Preview Image Art Direction

Every demo ships a preview pair in `samples/android-demo/src/main/res/drawable-nodpi/`:
`preview_<demo_id>_light.webp` and `preview_<demo_id>_dark.webp`, 800×640 (5:4, the
`media-aspect` of the home cards).

- **Camera:** 3/4 view, ~20° elevation, subject fills ~70% of the frame.
- **Light:** soft key + rim; no harsh shadows.
- **Field:** neutral, #EEF0F3 light / #0E1218 dark. No gradients, no props.
- **AR demos:** keep a real camera photo as the background — never a synthetic room.
- **Never:** text, UI, device frames, watermarks.
- **Source:** generated with Gemini image-to-image from real captures of the demo —
  the model shown must be the model the demo loads. Never invent a model.

---

## Platform Mapping

| Platform | Framework | How to apply |
|---|---|---|
| **Website** | HTML/CSS | CSS custom properties from this file |
| **Android Demo** | Jetpack Compose | Material 3 theme with these tokens |
| **iOS Demo** | SwiftUI | Asset catalog + Color extensions |
| **Docs** | MkDocs Material | CSS overrides in stylesheets/ |
| **Play Store** | Store listing | Screenshots using these colors/typo |
| **App Store** | Store listing | Screenshots using these colors/typo |

---

## Usage with AI Agents

This file is optimized for consumption by AI coding agents (Claude Code, Cursor, Gemini CLI).

**To generate UI matching SceneView's design:**
1. Read this `DESIGN.md` for tokens and patterns
2. Use CSS custom properties (never hardcode values)
3. Support both light and dark modes
4. Follow the component patterns above
5. Use responsive typography with `clamp()`

**For marketing surfaces only** (store screenshots, website hero shots): you may import
this file into a design tool (Stitch, Figma, …) to keep branding consistent. Do **not**
generate the demo app's own Compose/SwiftUI screens this way — that chrome is
reference-driven native, per the "Design System" rule in `CLAUDE.md`.
