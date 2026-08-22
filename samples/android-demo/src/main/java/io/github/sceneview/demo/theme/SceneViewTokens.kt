package io.github.sceneview.demo.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

    /** `DESIGN.md` — Motion durations (`duration-*`). */
    object Duration {
        const val shortMillis = 200
        const val mediumMillis = 350
        const val longMillis = 700
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
        val heroStageHeight = 360.dp
        const val mediaAspect = 1.25f
    }
}
