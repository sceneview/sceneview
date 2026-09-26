package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import io.github.sceneview.demo.common.placement.ARFeatureComparison
import io.github.sceneview.demo.common.placement.PlacementFeature

/** One automatic, plane-anchored subject; the comparison never recreates its placement. */
@Composable
fun ARDepthOcclusionDemo(onBack: () -> Unit) {
    ARFeatureComparison(PlacementFeature.DEPTH, onBack)
}
