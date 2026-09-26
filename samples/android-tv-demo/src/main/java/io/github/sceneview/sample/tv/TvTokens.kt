package io.github.sceneview.sample.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The `DESIGN.md` tokens the TV viewer uses, named one-for-one after the spec (`Space.xl2` is
 * `space-2xl`) so the code and the spec read the same.
 *
 * Everything the TV viewer draws floats over the 3D stage, which is media rather than a themed
 * surface — so, like the phone demo's glass chrome, these values are theme-independent: the
 * stage is `stage-background` in light and dark, and white on a dark scrim reads in both. Accents
 * are the dark-scheme values for the same reason the AR coaching overlay uses them: they are read
 * on a dark ground whatever the system theme.
 */
@Immutable
internal object TvTokens {

    /** `stage-background` — the full-screen 3D stage, identical in both themes. */
    object Stage {
        val background = Color(0xFF0B0F16)
    }

    /** `DESIGN.md` — Glass Chrome over Media (Android demo). */
    object Glass {
        /** `glass-surface` over media — white at 14 % (no blur on Android). */
        val surface = Color(0x24FFFFFF)
        /** `over-media-edge`, inner band — white at 36 %. */
        val edgeRing = Color(0x5CFFFFFF)
        /** `on-glass`. */
        val onGlass = Color(0xFFFFFFFF)
        /** `on-glass-muted` — white at 72 %. */
        val onGlassMuted = Color(0xB8FFFFFF)
        /** `chrome-scrim` — the ground under the top and bottom chrome bands. */
        val scrim = Color(0x99000000)
        /** `chrome-scrim` top band height. */
        val topBand = 160.dp
        /** `chrome-scrim` bottom band minimum height. */
        val bottomBand = 220.dp
    }

    /** `primary` / `on-primary`, dark-scheme values — the focus accent. */
    object Accent {
        val primary = Color(0xFFA4C1FF)
        val onPrimary = Color(0xFF002F65)
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

    /** `radius-*` — M3 Expressive shape scale. */
    object Radius {
        val xs = 8.dp
        val sm = 12.dp
        val md = 16.dp
        val lg = 24.dp
    }

    /** App Type Scale (Android demo): `-0.02em` tracking on display/title. */
    object Type {
        val display = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, letterSpacing = (-0.02).em)
        val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, letterSpacing = (-0.02).em)
        val card = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
        val caption = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    }

    /** `motion-fade` — every opacity change. */
    val fade = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

    /**
     * TV overscan-safe margins: Android TV asks for 48 dp at the sides and 27 dp at the top and
     * bottom of a 960 × 540 dp screen. `space-2xl` and `space-xl` are the nearest tokens that
     * clear both.
     */
    object Overscan {
        val horizontal = Space.xl2
        val vertical = Space.xl
    }
}
