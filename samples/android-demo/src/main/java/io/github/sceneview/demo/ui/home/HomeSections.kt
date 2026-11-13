package io.github.sceneview.demo.ui.home

import androidx.annotation.StringRes
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.R

/** Home's audit order, independent of the registry categories used by AR gates and analytics. */
enum class HomeSection(@StringRes val titleRes: Int, val demoIds: List<String>) {
    Models(R.string.home_section_models, listOf(
        "model-viewer", "animation-physics",
        "camera-path", "camera-gestures", "picking-collision",
    )),
    AR(R.string.home_section_ar, listOf(
        "ar-placement", "ar-face", "ar-geospatial-anchors", "ar-image", "ar-measure",
        "ar-body-tracker", "ar-orbital", "ar-depth-occlusion", "ar-people-occlusion", "point-and-ask",
        "ar-depth-of-field",
    )),
    Scan(R.string.home_section_scan, listOf("ar-rerun", "splat-preview", "ar-splat-room")),
    Materials(R.string.home_section_materials, listOf(
        "matter", "materials", "two-d-in-three-d", "lighting", "contact-shadow-preview",
        "geometry", "custom-geometry", "lines-paths", "spatial-audio",
    )),
    Play(R.string.home_section_play, listOf("cosmos", "rolling-balls", "ar-pool", "finger-drawing")),
    Tools(R.string.home_section_tools, listOf(
        "ar-surfaces", "ar-plane-node", "ar-point-cloud", "ar-raw-depth-point-cloud",
        "ar-depth-visualization", "ar-image-stabilization", "ar-scene-semantics",
        "debug-overlay", "model-thumbnails", "ar-cloud-anchor", "ar-ml-object-label", "ar-xr",
    ));

    fun demos(registry: List<DemoEntry>): List<DemoEntry> {
        val byId = registry.associateBy { it.id }
        return demoIds.mapNotNull(byId::get)
    }
}
