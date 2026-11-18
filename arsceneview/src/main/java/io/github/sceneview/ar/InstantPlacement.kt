package io.github.sceneview.ar

import androidx.compose.runtime.staticCompositionLocalOf
import com.google.ar.core.Anchor
import com.google.ar.core.InstantPlacementPoint
import io.github.sceneview.ar.node.InstantPlacementPose

/** Retains the hit information an Anchor alone cannot expose. Scoped to one placed item. */
internal class InstantPlacement(
    val anchor: Anchor,
    val point: InstantPlacementPoint,
    heldAnchor: Anchor,
) {
    val refinement = InstantPlacementPose(heldAnchor::getPose, heldAnchor::detach)
}

internal val LocalInstantPlacement = staticCompositionLocalOf<InstantPlacement?> { null }
