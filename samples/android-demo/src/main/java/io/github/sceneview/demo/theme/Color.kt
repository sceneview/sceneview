package io.github.sceneview.demo.theme

import androidx.compose.ui.graphics.Color

/**
 * SceneView M3 Expressive Color System
 *
 * Generated from the SceneView design system (see DESIGN.md) with source color #005bc1.
 * Aligned with website tokens (styles.css) for brand consistency.
 *
 * Light: primary #005bc1, tertiary #6446cd
 * Dark: primary #a4c1ff (GitHub-dark inspired), tertiary #d2a8ff
 */

// ===== Light Scheme =====
val md_theme_light_primary = Color(0xFF005BC1)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFD6E3FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001B3E)
val md_theme_light_inversePrimary = Color(0xFFA4C1FF)

val md_theme_light_secondary = Color(0xFF555F71)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFD9E3F8)
val md_theme_light_onSecondaryContainer = Color(0xFF121C2B)

val md_theme_light_tertiary = Color(0xFF6446CD)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFE8DEFF)
val md_theme_light_onTertiaryContainer = Color(0xFF21005E)

val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_surface = Color(0xFFFFFFFF)
val md_theme_light_onSurface = Color(0xFF1A1A2E)
val md_theme_light_surfaceVariant = Color(0xFFF1F3F5)
val md_theme_light_onSurfaceVariant = Color(0xFF3D4654)
val md_theme_light_surfaceDim = Color(0xFFF1F3F5)
val md_theme_light_surfaceBright = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainer = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerHigh = Color(0xFFF1F3F5)
// The one light value that moves (#F1F3F5 -> #E9ECEF), and it moves *up* in separation:
// `surfaceContainerHigh` and `surfaceContainerHighest` were the same tone, so a field
// inside a sheet had nothing to sit on here either. 6.7 dL* from the page instead of 4.3.
val md_theme_light_surfaceContainerHighest = Color(0xFFE9ECEF)

val md_theme_light_outline = Color(0xFFD6DAE0)
val md_theme_light_outlineVariant = Color(0xFFEBEDF0)
val md_theme_light_inverseSurface = Color(0xFF2F3036)
val md_theme_light_inverseOnSurface = Color(0xFFF0F0F7)

// ===== Dark Scheme (GitHub-dark inspired) =====
val md_theme_dark_primary = Color(0xFFA4C1FF)
val md_theme_dark_onPrimary = Color(0xFF002F64)
val md_theme_dark_primaryContainer = Color(0xFF00448D)
val md_theme_dark_onPrimaryContainer = Color(0xFFD6E3FF)
val md_theme_dark_inversePrimary = Color(0xFF005BC1)

val md_theme_dark_secondary = Color(0xFFBDC7DC)
val md_theme_dark_onSecondary = Color(0xFF273141)
val md_theme_dark_secondaryContainer = Color(0xFF3D4758)
val md_theme_dark_onSecondaryContainer = Color(0xFFD9E3F8)

val md_theme_dark_tertiary = Color(0xFFD2A8FF)
val md_theme_dark_onTertiary = Color(0xFF37139B)
val md_theme_dark_tertiaryContainer = Color(0xFF4D2CB4)
val md_theme_dark_onTertiaryContainer = Color(0xFFE8DEFF)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

// ## The dark ramp is a ramp, not three tones (#3681)
//
// Reported as "you cannot see the background of elements at all, unlike light, which
// makes it confusing". That is literally true: the dark surfaces held **three** distinct
// tones — `surface` L* 5.0, `surfaceVariant`/`surfaceDim`/`surfaceContainerLow` 9.6, and
// everything from `surfaceContainer` up to `surfaceContainerHighest` *plus*
// `surfaceBright` on a single 10.4. Every container/background pair therefore landed
// between 1.07:1 and 1.13:1 — a card, a sheet and a chip were each drawn *the same
// colour as the thing behind them*.
//
// `surfaceDim` was also inverted: 9.6 against a `surface` of 5.0, i.e. the "dim" role was
// the brighter one, which no M3 component can use as intended.
//
// ### Why the ramp had to move a long way, not a little
//
// Contrast ratio compresses badly at the dark end — (Y+0.05) is dominated by the 0.05.
// Against this #0D1117 page (Y=0.0055) a container needs Y>=0.0194, i.e. L*>=15, before
// it reaches even 1.25:1. The old `surfaceContainer` sat at L* 10.4 and could never have
// got there by nudging. The tones below are solved *for the ratio*, not picked by eye,
// along the existing blue-slate hue line so the GitHub-dark cast survives:
//
// | role                    | before  | after   | vs page |
// |-------------------------|---------|---------|---------|
// | surfaceDim              | #161B22 | #0B0E14 | 1.02:1  |
// | surface                 | #0D1117 | #0D1117 |    —    |
// | surfaceContainerLowest  | #0D1117 | #12171F | 1.05:1  |
// | surfaceContainerLow     | #161B22 | #1B212D | 1.17:1  |
// | surfaceContainer        | #161C2C | #232A39 | 1.32:1  |
// | surfaceContainerHigh    | #161C2C | #2C3546 | 1.54:1  |
// | surfaceContainerHighest | #161C2C | #354056 | 1.82:1  |
//
// Nesting in this app is two deep at most (page -> card/sheet -> tile/field), so the ramp
// is targeted for that depth rather than stacked 1.25:1 five times, which would have
// ended up pale grey. The two depth-2 pairs that still land at 1.17:1 — a tile inside a
// sheet, a dialog over a sheet — carry an `outlineVariant` hairline instead; tone alone
// cannot do it without pushing the whole scheme lighter than the brand allows.
//
// ### What this costs
//
// `onSurfaceVariant` had to move with it. #9CA3AF was fine on a near-black page (7.45:1)
// but only 3.79:1 on the new brightest container — placeholder and supporting text would
// have failed 4.5:1 exactly where the fix made the surface lighter. #A4ABB7 is the
// smallest lift that clears it (4.50:1 there, 8.19:1 on the page). `DESIGN.md`'s dark
// `on-surface-dim` moves with it in the same change.
//
// Light keeps every value it had except `surfaceContainerHighest`, which gains a step
// (#F1F3F5 -> #E9ECEF) so a field inside a sheet separates there too — strictly more
// separation than before, never less.
val md_theme_dark_surface = Color(0xFF0D1117)
val md_theme_dark_onSurface = Color(0xFFF3F4F6)
val md_theme_dark_surfaceVariant = Color(0xFF232A39)
val md_theme_dark_onSurfaceVariant = Color(0xFFA4ABB7)
// M3's own dark baseline sets `surfaceDim` equal to `surface`: at the dark end there is
// no room below the page, so nothing "recesses" — elevation reads as *lighter*, and a
// component that has to stand out belongs on a container role, not on `surfaceDim`.
// Sitting it just under the page fixes the inversion without inviting the old misuse.
val md_theme_dark_surfaceDim = Color(0xFF0B0E14)
val md_theme_dark_surfaceBright = Color(0xFF354056)
val md_theme_dark_surfaceContainerLowest = Color(0xFF12171F)
val md_theme_dark_surfaceContainerLow = Color(0xFF1B212D)
val md_theme_dark_surfaceContainer = Color(0xFF232A39)
val md_theme_dark_surfaceContainerHigh = Color(0xFF2C3546)
val md_theme_dark_surfaceContainerHighest = Color(0xFF354056)

// `outline` carries WCAG 1.4.11: it is the boundary that identifies a control, and the
// home search field's unfocused border is drawn with it. At #2A3346 it was 1.50:1
// against the page — the field was a rumour until you focused it. #8B95A6 is 6.26:1 on
// the page and never drops below 3.44:1 on any tone of the ramp above, while keeping the
// blue-slate cast.
//
// `outlineVariant` is not decoration here: it is what separates a tile from the sheet
// behind it and a dialog from the sheet behind it, the two pairs tone alone cannot carry.
// #46516A gives those hairlines 1.81:1 on `surfaceContainer` and 1.55:1 on
// `surfaceContainerHigh` — visible at 1dp, still quiet.
val md_theme_dark_outline = Color(0xFF8B95A6)
val md_theme_dark_outlineVariant = Color(0xFF46516A)
val md_theme_dark_inverseSurface = Color(0xFFE2E2E9)
val md_theme_dark_inverseOnSurface = Color(0xFF2F3036)

// ===== Brand Colors =====
/** SceneView primary blue — light mode */
val SceneViewBlue = md_theme_light_primary

/** SceneView primary blue — dark mode */
val SceneViewBlueDark = md_theme_dark_primary

// ===== Status Colors =====
val StatusStable = Color(0xFF16A34A)
val StatusBeta = Color(0xFF2563EB)
val StatusAlpha = Color(0xFF7C3AED)
val StatusSoon = Color(0xFF6B7280)
