package io.github.sceneview.demo.theme

import androidx.compose.animation.core.CubicBezierEasing
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
        val heroStageHeight = 360.dp
        const val mediaAspect = 1.25f
    }
}
