// Split out of NodeEditingOverlay.kt so each file's single top-level class matches its name
// (detekt MatchingDeclarationName) — same package, so imports are unaffected.
package io.github.sceneview.gesture

import androidx.compose.ui.graphics.Color

/**
 * Colors used by [NodeEditingOverlay].
 *
 * Defaults follow the SceneView AR-surface rules: the ground behind the overlay is an
 * arbitrary camera frame (or 3D scene), not an app surface, so the badge scrim and
 * accents keep their dark-scheme values in both themes.
 */
data class NodeEditingOverlayColors(
    /** Rotation ring / arc and active-gesture accents. */
    val accent: Color = Color(0xFFA4C1FF),
    /** Selection ring (gesture-idle). */
    val selection: Color = Color.White,
    /** Badge pill background — near-opaque dark scrim over the camera feed. */
    val badgeBackground: Color = Color(0xE6000000),
    /** Badge pill hairline. */
    val badgeBorder: Color = Color(0x29FFFFFF),
    /** Badge text. */
    val badgeText: Color = Color.White,
    /** Border/text tint while the pinch is pressing against `editableScaleRange`. */
    val limit: Color = Color(0xFFFFB4AB),
    /** Soft contact shadow under the node while dragging. */
    val shadow: Color = Color(0x80000000),
)
