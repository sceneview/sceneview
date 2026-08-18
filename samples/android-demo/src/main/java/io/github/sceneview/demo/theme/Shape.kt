package io.github.sceneview.demo.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * SceneView M3 Expressive Shape System
 *
 * Material shape roles mapped to the radius tokens defined in [SceneViewTokens].
 * The role names and usage descriptions below are Compose-specific mappings:
 * - ExtraSmall (8dp): utility elements, chips
 * - Small (12dp): code blocks, inputs, badges, tooltips
 * - Medium (16dp): buttons, medium cards, dialogs
 * - Large (24dp): section cards, bottom sheets
 * - ExtraLarge (28dp): prominent cards, showcase items, hero panels
 */
val SceneViewShapes = Shapes(
    extraSmall = RoundedCornerShape(SceneViewTokens.Radius.xs),
    small = RoundedCornerShape(SceneViewTokens.Radius.sm),
    medium = RoundedCornerShape(SceneViewTokens.Radius.md),
    large = RoundedCornerShape(SceneViewTokens.Radius.lg),
    extraLarge = RoundedCornerShape(SceneViewTokens.Radius.xl),
)
