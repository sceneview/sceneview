package io.github.sceneview.demo.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Compose translation of the non-colour, non-typography tokens in `DESIGN.md`.
 *
 * Names mirror the token names in `DESIGN.md` one-for-one — `Space.md` is `space-md` —
 * so a reader (human or AI) can move between the spec and the code without a lookup
 * table. Material 3 slot names stay on the Material side of the boundary: `Shape.kt`
 * writes `extraSmall = RoundedCornerShape(SceneViewTokens.Radius.xs)`, which reads as
 * the translation it is.
 *
 * An immutable object fits these application-wide constants better than a
 * CompositionLocal: none of the source tokens varies with theme or composition.
 */
@Immutable
object SceneViewTokens {
    /** Model-viewer stage, deliberately identical in light and dark themes. */
    object Stage {
        val background = Color(0xFF0B0F16)
    }
    /** `DESIGN.md` — Spatial Gallery overlay colours. */
    object SpatialGalleryColor {
        val stageScrimStart = Color.Transparent
        val stageScrimEnd = Color(0xE6000000)
        val glassSurfaceLight = Color(0xB8FFFFFF)
        val glassSurfaceDark = Color(0x0DFFFFFF)
        val glassBorderLight = Color(0x14FFFFFF)
        val glassBorderDark = Color(0x14FFFFFF)
        val glassBorderWidth = 1.dp
    }

    /**
     * `DESIGN.md` — Liquid Glass, the "button glass" row, as the demo chrome uses it.
     *
     * Theme-independent on purpose: the chrome floats over a live Filament/ARCore
     * viewport, which is media, not a themed surface. White on media reads in both
     * themes, and the fill is the same 8 % in light and dark per the spec. There is
     * no blur: a `SurfaceView` cannot be sampled by a Compose render effect.
     */
    object Glass {
        /** `glass-surface` over media — white at 8 %. */
        val surface = Color(0x14FFFFFF)
        /** `glass-border` — white at 8 %, 1 dp. */
        val border = Color(0x14FFFFFF)
        val borderWidth = 1.dp
        /** Foreground on glass over media: always white. */
        val onGlass = Color.White
        /** Secondary foreground on glass — white at 72 %. */
        val onGlassMuted = Color(0xB8FFFFFF)
        /** `GlassIconButton` visual diameter. The touch target is [Layout.touchTarget]. */
        val iconButtonSize = 44.dp
        /** `GlassPill` height. */
        val pillHeight = 36.dp
        /** `GlassPill` horizontal content padding. */
        val pillPaddingHorizontal = 14.dp
    }

    /**
     * Home / app-wide type scale — the only five text styles the demo app's own
     * chrome uses (design spec §2). `-0.02em` on display/title; line height 1.2
     * on display/title, 1.35 on body.
     *
     * Tokens: `type-display 32sp/700`, `type-title 22sp/600`, `type-card 17sp/600`,
     * `type-body 15sp/400`, `type-caption 13sp/500`.
     */
    object Type {
        val display = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
        val title = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
        val card = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        val body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    }

    /**
     * Home screen colours that are NOT Material roles (design spec §2).
     *
     * The hero is an image card that stays dark in both themes, so its text and
     * pill are fixed: `hero-title #FFFFFF`, `hero-subtitle rgba(255,255,255,0.80)`,
     * `hero-pill-bg #FFFFFF`, `hero-pill-text #1A1A2E`. Filter chips follow
     * `DESIGN.md` surfaces rather than the M3 ramp, which is why they carry
     * explicit light/dark pairs: unselected `chip-bg` = `surface-dim`
     * (#F1F3F5 / #161B22) with `chip-text` = `on-surface-dim` (#3D4654 / #9CA3AF);
     * selected `chip-selected-bg` = `on-surface` (#1A1A2E / #F3F4F6) with
     * `chip-selected-text` = `surface` (#FFFFFF / #0D1117). `header-overlay` is
     * `surface` at 94 %.
     */
    object HomeColor {
        val heroTitle = Color(0xFFFFFFFF)
        val heroSubtitle = Color(0xCCFFFFFF)
        val heroPillBackground = Color(0xFFFFFFFF)
        val heroPillText = Color(0xFF1A1A2E)
        /** Hero placeholder / stage field, `#0B0F16` — matches the viewer stage clear colour. */
        val heroField = Color(0xFF0B0F16)

        val chipBackgroundLight = Color(0xFFF1F3F5)
        val chipBackgroundDark = Color(0xFF161B22)
        val chipTextLight = Color(0xFF3D4654)
        val chipTextDark = Color(0xFF9CA3AF)
        val chipSelectedBackgroundLight = Color(0xFF1A1A2E)
        val chipSelectedBackgroundDark = Color(0xFFF3F4F6)
        val chipSelectedTextLight = Color(0xFFFFFFFF)
        val chipSelectedTextDark = Color(0xFF0D1117)

        /** `outline-subtle` — #EBEDF0 / #1F2937, the 1 dp card + header hairline. */
        val outlineSubtleLight = Color(0xFFEBEDF0)
        val outlineSubtleDark = Color(0xFF1F2937)

        const val headerOverlayAlpha = 1f
    }

    /** Home screen geometry (design spec §2) — `home-*` tokens. */
    object Home {
        val headerHeight = 56.dp
        val markSize = 24.dp
        val searchFieldHeight = 48.dp
        val contentPadding = 20.dp
        val gridGutter = 12.dp
        val gridMinCell = 156.dp
        val gridMinCellExpanded = 220.dp
        val heroHeight = 320.dp
        val heroHeightExpanded = 400.dp
        val heroPadding = 24.dp
        val heroSubtitleMaxWidth = 260.dp
        val heroPillHeight = 44.dp
        val heroPillPaddingHorizontal = 18.dp
        val heroTopGap = 8.dp
        val chipRowTopGap = 28.dp
        val chipRowHeight = 40.dp
        val chipGap = 8.dp
        val chipPaddingHorizontal = 16.dp
        val gridTopGap = 20.dp
        val gridBottomInset = 32.dp
        val cardRadius = 20.dp
        val cardTextPaddingTop = 12.dp
        val cardTextPaddingHorizontal = 14.dp
        val cardTextPaddingBottom = 14.dp
        val cardOutlineWidth = 1.dp
        val iconTileGlyph = 40.dp
        /** Globe badge on the "Browse online models" collage — `hero-pill` colours. */
        val browseBadgeSize = 32.dp
        val browseBadgeGlyph = 18.dp
        /** Width from which the hero grows and the grid uses [gridMinCellExpanded]. */
        const val expandedWidthDp = 600
        const val heroScrimStart = 0.5f
    }

    /** `DESIGN.md` — Spring motion: `spring(dampingRatio 0.85, stiffness 450)`, one spring for press, sheets, dock. */
    object Spring {
        const val dampingRatio = 0.85f
        const val stiffness = 450f
        const val pressScale = 0.98f
    }

    /**
     * `DESIGN.md` — AR coaching overlay colours (`ar-scrim`, `on-ar-scrim`, …).
     *
     * These do **not** flip with the app theme the way a surface token does: the
     * ground behind an AR overlay is an arbitrary camera frame, so the pill stays
     * a dark scrim with white text in both themes, and only its opacity moves
     * (light mode is used outdoors more often, where the frame is brightest).
     * The accents are the dark-scheme values of the Material roles for the same
     * reason — they are read on black, never on `surface`.
     */
    object ArOverlay {
        val scrimLight = Color(0xF0000000)
        val scrimDark = Color(0xE0000000)
        val onScrim = Color(0xFFFFFFFF)
        val borderLight = Color(0x29FFFFFF)
        val borderDark = Color(0x1AFFFFFF)
        val borderWidth = 1.dp

        /** Transient work in progress — spinner accent. `primary` (dark value). */
        val accentProgress = Color(0xFFA4C1FF)

        /** Waiting on the user to move the phone — `warning`. */
        val accentGuidance = Color(0xFFF59E0B)

        /** Broken until something changes — dark-scheme `error`. */
        val accentBlocked = Color(0xFFFFB4AB)

        /** Widest a coaching pill may grow — a long line stays one readable column. */
        val maxWidth = 480.dp
    }

    /** `DESIGN.md` — Spacing scale (`space-*`). */
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
        val x2l = 48.dp
        val x3l = 64.dp
        val x4l = 96.dp
    }

    /** `DESIGN.md` — Corner radius scale (`radius-*`). */
    object Radius {
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 28.dp
        val full = 9999.dp
    }

    /**
     * `DESIGN.md` — Shadows (`shadow-*`), as Compose elevation. A CSS multi-layer
     * shadow has no exact Compose twin; these are the `shadowElevation` values
     * whose blur radius matches the spec's dominant layer (sm 3px, md 12px, lg 40px).
     */
    object Elevation {
        val sm = 1.dp
        val md = 4.dp
        val lg = 12.dp
    }

    /** `DESIGN.md` — Motion durations (`duration-*`). */
    object Duration {
        const val shortMillis = 200
        const val mediumMillis = 350
        const val longMillis = 700
        /** Design spec §6 — the one fade (`tween(300, FastOutSlowIn)`). */
        const val fadeMillis = 300
    }

    /**
     * Demo-chrome motion (`final-spec.md` §6): one spring, one fade.
     *
     * The spring drives dock show/hide and press scale; the fade drives every
     * opacity change in the chrome (chrome toggle, menu, preview crossfade uses
     * [Duration.mediumMillis]). Nothing else animates.
     */
    object Motion {
        const val springDampingRatio = 0.85f
        const val springStiffness = 450f
        const val fadeMillis = 300
        /** Press scale on dock items and glass buttons. */
        const val pressScale = 0.97f

        fun <T> spring(): SpringSpec<T> = spring(
            dampingRatio = springDampingRatio,
            stiffness = springStiffness,
        )

        fun <T> fade(): TweenSpec<T> = tween(
            durationMillis = fadeMillis,
            easing = FastOutSlowInEasing,
        )
    }

    /** `DESIGN.md` — Easing curves (`ease-*`). */
    object Ease {
        val spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
        val expressive = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }

    /** `DESIGN.md` — Layout constants (`container-padding`, `nav-height`). */
    object Layout {
        val containerPaddingDesktop = 24.dp
        val containerPaddingMobile = 16.dp
        val navigationHeight = 64.dp
        /** Minimum touch target (M3 / WCAG). */
        val touchTarget = 48.dp
        /** Height of the demo dock (`HorizontalFloatingToolbar`). */
        val dockHeight = 64.dp
        /** Dock items are [touchTarget] square; their icons are this size. */
        val dockIconSize = 22.dp
        val viewerEnvironmentTile = 72.dp
        val viewerAnimationButton = 48.dp
        val selectedOutlineWidth = 2.dp
        val heroStageHeight = 360.dp
        const val mediaAspect = 1.25f
    }
}
