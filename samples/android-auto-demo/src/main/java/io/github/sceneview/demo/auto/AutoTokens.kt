package io.github.sceneview.demo.auto

import androidx.compose.animation.core.FastOutSlowInEasing
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
 * The `DESIGN.md` tokens the garage uses, named one-for-one after the spec (`Space.xl2` is
 * `space-2xl`). Every colour and dimension of the chrome is read from here.
 *
 * The chrome floats over the 3D stage, which is media rather than a themed surface, so — like
 * the phone demo's glass chrome and the TV viewer — these values are the same in light and dark:
 * the stage is `stage-background` in both, and white on glass over a dark scrim reads in both.
 * The accent is the dark-scheme `primary` for the reason the AR coaching overlay uses it: it is
 * always read on a dark ground.
 */
@Immutable
internal object AutoTokens {

    /** `stage-background` — the cover the scene is revealed from, identical in both themes. */
    object Stage {
        val background = Color(0xFF0B0F16)
    }

    /** `DESIGN.md` — Glass Chrome over Media. */
    object Glass {
        /** `glass-surface` over media — white at 14 % (no blur over a `SurfaceView`). */
        val surface = Color(0x24FFFFFF)
        /** `over-media-edge`, inner band — white at 36 %, straddling the boundary. */
        val edgeRing = Color(0x5CFFFFFF)
        /** `over-media-edge`, outer band — black at 75 %, one band further out. */
        val edgeHalo = Color(0xBF000000)
        /** `over-media-edge` band width. */
        val edgeWidth = 1.dp
        /** `on-glass`. */
        val onGlass = Color(0xFFFFFFFF)
        /** `on-glass-muted` — white at 72 %. */
        val onGlassMuted = Color(0xB8FFFFFF)
        /** `chrome-scrim` — the ground under the chrome bands. */
        val scrim = Color(0x99000000)
    }

    /** `primary` / `on-primary`, dark-scheme values. */
    object Accent {
        val primary = Color(0xFFA4C1FF)
    }

    /** `space-*` — 8 dp base unit. */
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
        val xl2 = 48.dp
    }

    /** `radius-*`. */
    object Radius {
        val lg = 24.dp
    }

    /** `DESIGN.md` — Car Chrome (Android Auto demo). */
    object Car {
        /** `car-touch-target` — the height of anything tappable at arm's length. */
        val touchTarget = 76.dp
        /** `car-control-max-width` — a control never stretches into a banner on a wide screen. */
        val controlMaxWidth = 360.dp
        /** `car-pip` — one step of a control's position indicator. */
        val pip = Space.sm
    }

    /** App Type Scale (Android demo): `-0.02em` tracking on display/title. */
    object Type {
        val display = TextStyle(
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 38.sp,
            letterSpacing = (-0.02).em,
        )
        val card = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        val caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
        /** `type-caption` with `tracking-wide`, for an uppercase label. */
        val label = caption.copy(letterSpacing = 0.05.em)
    }

    /** `motion-spring` — press scale. */
    val spring = spring<Float>(dampingRatio = 0.85f, stiffness = 450f)

    /** `motion-spring` press scale. */
    const val PRESSED_SCALE = 0.97f

    /** `motion-fade` — every opacity change. */
    val fade = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

    /** `motion-handover` — the loading cover giving way to the first rendered frame. */
    val handover = tween<Float>(durationMillis = 150, easing = FastOutSlowInEasing)
}
