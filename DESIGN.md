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
| `hero-subtitle` | rgba(255,255,255,0.80) | rgba(255,255,255,0.80) | Hero subtitle, max width 260dp |
| `hero-pill-bg` | #ffffff | #ffffff | Hero CTA pill (44dp, `radius-full`) |
| `hero-pill-text` | #1a1a2e | #1a1a2e | Hero CTA label |
| `header-overlay` | `surface` at 100 % | `surface` at 100 % | Sticky home header over the scrolling grid |
| `outline-subtle` | #ebedf0 | #46516a | 1dp card and header hairline (see Borders) |

Catalogue **section headers** (the full-span label above each group of demo cards)
use `on-surface` at `titleMedium` / `weight-semibold` — no colour of their own, because
a header that tints itself competes with the cards it introduces. Geometry:

| Token | Value | Usage |
|---|---|---|
| `section-header-top-gap` | 32px (`space-xl`) | Above a section header |
| `section-header-bottom-gap` | 16px (`space-md`) | Header to its first card row |

A header is drawn only when more than one section is visible: with a single category
filtered, the chip already names it.

### Demo App About (Android)

The About tab carries **exactly one emphasised surface**: the support card. Everything
else — the identity block, the groups of rows — sits at `surface` or `surface-container`
so the eye lands on the one thing the screen is for.

| Token | Value | Usage |
|---|---|---|
| `about-mark` | 80dp, `radius-xl` | Identity mark — the launcher icon (`ic_sceneview_hero`), never a Material glyph |
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
| `success` | #16a34a | Positive states, checkmarks |
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

One spring and one fade for the chrome; one shared-axis spec for screen changes and
one fly-in for a 3D subject's arrival. In AR, the coaching glyph and a placed object's
entrance (the `motion-coach-*` and `motion-placement-*` tokens below — shipped by the SDK,
so every AR app gets them). Nothing else animates.

| Token | Value | Usage |
|---|---|---|
| `motion-spring` | `spring(dampingRatio = 0.85, stiffness = 450)` | Press scale (0.97–0.98), sheet open/close, dock show/hide, panel expand |
| `motion-fade` | `tween(300ms, FastOutSlowIn)` | Every opacity change — chrome toggle, menus, loading-cover crossfade |
| `motion-screen` | `tween(350ms, ease-expressive)` | Screen transitions — Material shared-axis X, both screens travelling ⅙ of the viewport while they cross-fade |
| `motion-entrance` | `tween(700ms, ease-expressive)` | The camera fly-in when a 3D scene's subject arrives — once per screen, cancelled by the first touch |
| `motion-coach-sweep` | 1600ms per sweep, sine, ±18dp travel and ±10° roll | The phone of the AR coaching glyph sweeping over the surface it is looking for. Half speed while tracking is limited |
| `motion-coach-resolve` | `tween(450ms, ease-expressive)` | The "surface found" beat — the target fills with `primary` and a cube lands on it, held 150ms, then the glyph leaves |
| `motion-placement-entrance` | `tween(260ms)`, scale 0.55 → 1, cubic ease-out | A placed AR object growing into place about its contact point. Reversed over 300ms (`motion-fade`) when tracking is lost — opaque glTF materials cannot fade, so they shrink |

**Reduced motion.** When the system animator scale is 0 (Android) or Reduce Motion is on
(iOS), the coaching glyph is drawn as its settled frame — no sweep, no spin — and only the
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

- **No blur on Android.** A `SurfaceView` cannot be sampled by a Compose render
  effect, so glass over the scene is fill + border only. Do not emulate blur.
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
| `dock-height` | 64dp |
| `dock-radius` | `radius-full` |
| `dock-item` | 48dp minimum touch target; icon over caption |
| `dock-icon` | 22dp |
| `dock-caption` | `type-caption`, 2dp under the icon, one line, never truncated |
| `dock-items` | at most 4 items + 1 optional accent (primary-tinted) item |
| `dock-accent` | 40dp filled disc, 48dp touch target, 12dp from the dock edge on every side |

The dock replaces FABs and top app bars in demo screens; its Controls item opens the
settings sheet. Show/hide uses `motion-spring`; tap on the scene toggles the chrome
with `motion-fade`.

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

- **A material is not a colour — it needs a floor and a ceiling.** `.ultraThinMaterial`
  is a blur of what is behind it. Over dark media it resolves to nearly black (hence
  the 8 % floor); over a bright studio backdrop the dark-scheme material resolves to
  the backdrop itself — measured **1.01:1**, a pill with no edge. The ceiling is the
  dark-scheme counterpart of the floor, and with the 24 % border the better of
  fill-vs-ground and border-vs-ground never drops under **1.43:1** on dark, mid and
  bright grounds (border 3.12:1 on the dark stage).
- **The sheet is a themed surface, not glass.** `surface-container`, the app's
  light/dark colours, `outline-subtle` hairline. (Android's settings sheet is
  `glass-sheet` since #3827 — the translucency iOS gets from its sheet material,
  Android has to get from opacity; see the Android scaffold section.) The stage and its chrome are media and
  stay dark in both schemes; the sheet is the only part of a demo that follows the theme.
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

### AR Coaching Glyph

The animated onboarding shown **centred** over the camera while an AR session starts,
searches or loses tracking — Apple's `ARCoachingOverlayView` on iOS, its visual twin
`ARCoachingOverlay` in `arsceneview` on Android. It shows the gesture instead of
describing it.

- Ground: a 96dp `ar-scrim` disc with the `ar-scrim-border` hairline and `shadow-lg`; an
  optional one-word caption pill underneath in the same ground (`on-ar-scrim`,
  `type-caption`), 8dp gap. The full sentence is the accessible name, announced politely.
- One glyph per cue, strokes in `on-ar-scrim`, accents in the dark-scheme `primary` and
  `warning`, like the pill:

| Cue | Glyph | Caption |
|---|---|---|
| Initializing (after 500ms) | Phone with an orbiting `primary` dot | — |
| Scan (floor) | Phone sweeping over a dashed diamond (`motion-coach-sweep`) | Scan |
| Scan (wall) | Phone sweeping in front of a dashed upright rectangle | Scan |
| Surface found | Target fills with `primary`, a cube lands on it (`motion-coach-resolve`) | — |
| Tracking limited | The scan glyph at 60%, half speed, a `warning` pause badge | Paused |
| Relocalizing | The scan glyph with a rotating `warning` circular arrow | Look back |

- **Hide the chrome while it shows** (Apple HIG): status pills and hints step aside while
  the glyph is up and come back when it leaves. Action cards never do — the glyph is
  silent whenever a card explains the state.
- Copy never says "ARKit", "ARCore", "tracking" or "plane": *Scan*, *Paused*, *Look back*.

### AR Overlay Card

The surface for what an AR demo needs the user to **see** rather than read — a code to
share, an input to fill, a meter to watch, or an explanation of why the screen cannot
work. It stacks directly under the coaching overlay in the same bottom band, so the two
must read as one language: same `ar-scrim` ground, same `ar-scrim-border` hairline, same
`radius-lg`, same 480px max width, `shadow-lg`. Padding `space-md`, children spaced
`space-sm`.

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
- **Meters** use three segments at `radius-xs`, `space-sm` tall, `space-xs` apart. Lit
  segments take the coaching overlay's accent for the state they represent (`warning`
  while the user must keep moving, `primary` once the value is acceptable); the unlit
  track is the same `Button glass` fill.
- **An explanation card carries no action when there is none.** A configuration a user
  cannot change from the phone gets a title and one sentence — a button that would do
  nothing is worse than a plain explanation (`ARCoreAvailabilityOverlay`, #3374).
- **One card at a time, and never doubled with a pill saying the same thing.** When a card
  explains the state, the coaching overlay stays silent.

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
