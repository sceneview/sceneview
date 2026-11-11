package io.github.sceneview.demo.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * DESIGN.md's canonical Fidelity tertiary roles, scoped to Matter's scene materials
 * until the app's retired purple palette is migrated. These existing M3 roles have
 * identical light/dark values; this is not a named oak colour or a new semantic role.
 */
internal fun ColorScheme.matterMaterialColors(): ColorScheme = copy(
    tertiaryContainer = Color(0xFFA33F00),
    onTertiaryContainer = Color(0xFFFFCFBB),
)
