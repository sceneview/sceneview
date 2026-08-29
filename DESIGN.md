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
| `surface-dim` | #f1f3f5 | #161B22 | Secondary background, cards |
| `surface-container` | #ffffff | #161c2c | Elevated surfaces |
| `stage-scrim-start` | transparent | transparent | Spatial Gallery media scrim start |
| `stage-scrim-end` | rgba(0,0,0,0.90) | rgba(0,0,0,0.90) | Spatial Gallery media scrim end |
| `glass-surface` | rgba(255,255,255,0.72) | rgba(255,255,255,0.05) | Floating Spatial Gallery controls |
| `glass-border` | 1px rgba(255,255,255,0.08) | 1px rgba(255,255,255,0.08) | Floating control outline |
| `stage-background` | #0B0F16 | #0B0F16 | Model-viewer stage clear colour and hero placeholder field — identical in both themes |
| `ar-scrim` | rgba(0,0,0,0.94) | rgba(0,0,0,0.88) | AR coaching overlay ground, over the camera feed |
| `ar-scrim-border` | 1px rgba(255,255,255,0.16) | 1px rgba(255,255,255,0.10) | AR coaching overlay hairline |

### Demo App Home (Android)

Tokens the demo app's home screen uses that are not Material roles. Chips follow
the surface ramp above, not the M3 tonal ramp.

| Token | Light | Dark | Usage |
|---|---|---|---|
| `chip-bg` | #f1f3f5 (`surface-dim`) | #161B22 | Unselected category chip |
| `chip-text` | #3d4654 (`on-surface-dim`) | #9ca3af | Unselected chip label |
| `chip-selected-bg` | #1a1a2e (`on-surface`) | #f3f4f6 | Selected category chip |
| `chip-selected-text` | #ffffff (`surface`) | #0D1117 | Selected chip label |
| `hero-title` | #ffffff | #ffffff | Hero headline — the hero is an image card that stays dark in both themes |
| `hero-subtitle` | rgba(255,255,255,0.80) | rgba(255,255,255,0.80) | Hero subtitle, max width 260dp |
| `hero-pill-bg` | #ffffff | #ffffff | Hero CTA pill (44dp, `radius-full`) |
| `hero-pill-text` | #1a1a2e | #1a1a2e | Hero CTA label |
| `header-overlay` | `surface` at 100 % | `surface` at 100 % | Sticky home header over the scrolling grid |
| `outline-subtle` | #ebedf0 | #1f2937 | 1dp card and header hairline (see Borders) |

### Text

| Token | Light | Dark | Usage |
|---|---|---|---|
| `on-surface` | #1a1a2e | #f3f4f6 | Primary text |
| `on-surface-dim` | #3d4654 | #9ca3af | Secondary text |
| `on-surface-faint` | #5c6370 | #6b7280 | Tertiary text, captions |
| `on-ar-scrim` | #ffffff | #ffffff | AR coaching overlay text — white in both themes, the ground is the camera |
| `on-ar-scrim-dim` | rgba(255,255,255,0.72) | rgba(255,255,255,0.72) | AR coaching overlay secondary text |

### Borders

| Token | Light | Dark | Usage |
|---|---|---|---|
| `outline` | #d6dae0 | #2a3346 | Default borders |
| `outline-subtle` | #ebedf0 | #1f2937 | Light dividers |

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

One spring, one fade — nothing else animates in the demo chrome.

| Token | Value | Usage |
|---|---|---|
| `motion-spring` | `spring(dampingRatio = 0.85, stiffness = 450)` | Press scale (0.97–0.98), sheet open/close, dock show/hide |
| `motion-fade` | `tween(300ms, FastOutSlowIn)` | Every opacity change — chrome toggle, menus, preview crossfade |

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
| `glass-surface` (over media) | rgba(255,255,255,0.08) | Back button, identity pill, dock |
| `glass-border` | 1px rgba(255,255,255,0.08) | Outline of every glass element |
| `on-glass` | #ffffff | Icons and labels on glass |
| `on-glass-muted` | rgba(255,255,255,0.72) | Secondary label on glass |
| `chrome-scrim` | rgba(0,0,0,0.60) → transparent | Ground under the chrome bands |
| `glass-icon-button` | 44dp visual, 48dp touch target | Back |
| `glass-pill` | 36dp high, 14dp horizontal padding | Identity pill |

- **No blur on Android.** A `SurfaceView` cannot be sampled by a Compose render
  effect, so glass over the scene is fill + border only. Do not emulate blur.
- **The chrome bands sit on `chrome-scrim`.** White on media reads only when the
  media is dark, and a demo scene can be any brightness — a near-white studio
  erases an 8 % white fill and white glyphs alike. The top band (160dp) and the
  bottom band (220dp minimum) each carry a vertical scrim, flat for the 55 %
  nearest the screen edge and fading to transparent, so the chrome never depends
  on what the scene happens to render behind it. The top scrim fades with the
  chrome; the bottom one grows to the measured overlay band and outlives the
  fade, because a status pill or legend stays on screen after a scene tap has
  hidden the dock.
- **There is no overflow menu.** Reset, Send feedback and QA mode live in the
  settings sheet the dock's Controls item opens — one settings surface, not two.

### Floating Dock (Android demo)

| Token | Value |
|---|---|
| `dock-height` | 64dp |
| `dock-radius` | `radius-full` |
| `dock-item` | 48dp square touch target |
| `dock-icon` | 22dp |
| `dock-items` | at most 4 items + 1 optional accent (primary-tinted) item |

The dock replaces FABs and top app bars in demo screens; its Controls item opens the
settings sheet. Show/hide uses `motion-spring`; tap on the scene toggles the chrome
with `motion-fade`.

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
