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
 */
object DemoCategoryAccent {

    /** Accent used for a category this palette does not know — `gradient-hero`'s end stop. */
    val Fallback: Color = Color(0xFF6446CD)

    private val light: Map<String, Color> = mapOf(
        "3D Basics" to Color(0xFF005BC1),
        "Lighting & Environment" to Color(0xFF1457C3),
        "Content" to Color(0xFF2853C6),
        "Interaction" to Color(0xFF3C4EC8),
        "Advanced" to Color(0xFF504ACB),
        "Augmented Reality" to Color(0xFF6446CD),
    )

    private val dark: Map<String, Color> = mapOf(
        "3D Basics" to Color(0xFFA4C1FF),
        "Lighting & Environment" to Color(0xFFADBCFF),
        "Content" to Color(0xFFB6B7FF),
        "Interaction" to Color(0xFFC0B2FF),
        "Advanced" to Color(0xFFC9ADFF),
        "Augmented Reality" to Color(0xFFD2A8FF),
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
