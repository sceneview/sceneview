package io.github.sceneview.sample.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The one place a sample category's accent colour is defined.
 *
 * The light palette mirrors the v4.1.0 SceneView design system (see `DESIGN.md`); the dark
 * palette desaturates each hue and lifts its lightness so tinted gradients and icon tints do
 * not burn at >9:1 contrast against an M3 dark `surfaceContainer`.
 *
 * Before this existed the palette was copied into three screens, one of them carrying a
 * "keep these in sync by hand" comment and one of them having no dark variant at all — so in
 * dark mode that screen rendered the light palette, the exact case the dark palette exists to
 * avoid. A hand-synced constant is a coherence bug waiting for someone to notice it on a
 * device; `check-demo-design-system.sh` now fails the build on a fourth copy.
 */
object DemoCategoryAccent {

    /** Accent used for a category this palette does not know. */
    val Fallback: Color = Color(0xFF6446CD)

    private val light: Map<String, Color> = mapOf(
        "3D Basics" to Color(0xFF6446CD),
        "Lighting & Environment" to Color(0xFFE6A23C),
        "Content" to Color(0xFF42A5F5),
        "Interaction" to Color(0xFFEC407A),
        "Advanced" to Color(0xFF26A69A),
        "Augmented Reality" to Color(0xFF66BB6A),
    )

    private val dark: Map<String, Color> = mapOf(
        "3D Basics" to Color(0xFFB39DDB),
        "Lighting & Environment" to Color(0xFFFFCC80),
        "Content" to Color(0xFF90CAF9),
        "Interaction" to Color(0xFFF48FB1),
        "Advanced" to Color(0xFF80CBC4),
        "Augmented Reality" to Color(0xFFA5D6A7),
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
