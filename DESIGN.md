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
- **Dynamic shapes:** Corners vary by component role — small utility elements use `radius-xs` (8px), prominent cards use `radius-lg` (28px), pills use `radius-full`.

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

### Text

| Token | Light | Dark | Usage |
|---|---|---|---|
| `on-surface` | #1a1a2e | #f3f4f6 | Primary text |
| `on-surface-dim` | #3d4654 | #9ca3af | Secondary text |
| `on-surface-faint` | #5c6370 | #6b7280 | Tertiary text, captions |

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
- Radius: `radius-lg` (28px)
- Padding: `space-xl` (32px), `space-lg` on mobile
- Hover: lift -3px, shadow `shadow-lg`, border `primary-subtle`

### Code Blocks
- Background: `code-bg`
- Text: `code-text`, font `font-mono`
- Radius: `radius-sm`
- Padding: `space-md`
- Border: 1px solid rgba(255,255,255,0.05)
- Font size: `text-xs` (0.85rem)

### Tabs
- Padding: 10px 20px
- Background: `surface-dim`
- Radius: `radius-sm`
- Active: bg `surface-container`, text `primary`, `weight-semibold`

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
