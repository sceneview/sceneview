package io.github.sceneview.sample.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The one place a sample category's accent colour is defined.
 *
 * Both palettes are sampled along the `gradient-hero` tokens in `DESIGN.md` — light runs
 * `#005bc1 → #6446cd`, dark runs `#a4c1ff → #d2a8ff` — so every category accent stays inside
 * the brand's blue-to-violet span instead of introducing hues the design system does not
 * have. The reference apps the demo UI is anchored on (Sketchfab, Polycam, Reality Composer)
 * differentiate list categories by position and label, not by hue family; the ramp keeps a
 * subtle per-category shift without turning the list into a rainbow.
 *
 * Before this existed the palette was copied into three screens, one of them carrying a
 * "keep these in sync by hand" comment and one of them having no dark variant at all — so in
 * dark mode that screen rendered the light palette, the exact case the dark palette exists to
 * avoid. A hand-synced constant is a coherence bug waiting for someone to notice it on a
 * device; `check-demo-design-system.sh` now fails the build on a fourth copy.
 *
 * The keys are the `DemoCategory` constants in the Android demo's `DemoRegistry.kt`
 * — nine since the #2239 catalogue regroup, re-sampled evenly along the same two
 * gradient endpoints rather than nudged by hand, so the ramp stayed a ramp when it
 * gained three stops. An unknown key falls back to [Fallback] rather than throwing;
 * `DemoDesignSystemTest` asserts the two maps stay key-identical and that the
 * accents remain distinct within a scheme.
 */
object DemoCategoryAccent {

    /** Accent used for a category this palette does not know — `gradient-hero`'s end stop. */
    val Fallback: Color = Color(0xFF6446CD)

    private val light: Map<String, Color> = mapOf(
        "Viewer" to Color(0xFF005BC1),
        "Geometry & Materials" to Color(0xFF0C58C2),
        "Rendering" to Color(0xFF1956C4),
        "Interaction" to Color(0xFF2653C6),
        "AR Placement" to Color(0xFF3250C7),
        "AR Tracking" to Color(0xFF3E4EC8),
        "AR Understanding" to Color(0xFF4B4BCA),
        "AR Anchors" to Color(0xFF5849CC),
        "Platform" to Color(0xFF6446CD),
    )

    private val dark: Map<String, Color> = mapOf(
        "Viewer" to Color(0xFFA4C1FF),
        "Geometry & Materials" to Color(0xFFAABEFF),
        "Rendering" to Color(0xFFB0BBFF),
        "Interaction" to Color(0xFFB5B8FF),
        "AR Placement" to Color(0xFFBBB4FF),
        "AR Tracking" to Color(0xFFC1B1FF),
        "AR Understanding" to Color(0xFFC6AEFF),
        "AR Anchors" to Color(0xFFCCABFF),
        "Platform" to Color(0xFFD2A8FF),
    )

    /** Every category this palette covers, in design-system order. */
    val categories: List<String> get() = light.keys.toList()

    /**
     * Accent for [category] in the requested scheme, falling back to [Fallback] for an
     * unknown category.
     *
     * The dark map is asserted to cover exactly the same keys as the light one by
     * `DemoCategoryAccentTest`, so a category added to one and forgotten in the other is a
     * test failure rather than a screen that silently loses its dark palette.
     */
    operator fun get(category: String, isDark: Boolean): Color =
        (if (isDark) dark else light)[category] ?: Fallback

    internal fun lightKeys(): Set<String> = light.keys

    internal fun darkKeys(): Set<String> = dark.keys
}

/**
 * Accent for [category], resolved against the current system colour scheme.
 *
 * Use this from a composable that has no `dark` flag of its own; pass
 * `DemoCategoryAccent[category, dark]` where the screen already threads one.
 */
@Composable
@ReadOnlyComposable
fun demoCategoryAccent(category: String): Color =
    DemoCategoryAccent[category, isSystemInDarkTheme()]
