package io.github.sceneview.demo

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanoramaPhotosphere
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Roofing
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Runtime status of a demo as observed on the audited device matrix
 * (Pixel emulators + Pixel 9 hardware in `.claude/scripts/qa-android-demos.sh`).
 * Drives the badge rendered on the demo card in [DemoListScreen] so users
 * see honest expectations rather than a uniformly green list that lies.
 */
enum class DemoStatus {
    /** Verified working on the audit device matrix. */
    Working,

    /** Known visual or interaction regression — surfaced with a yellow badge. */
    KnownIssue,

    /** Compiles but not yet wired up to a real implementation. */
    ComingSoon,
}

/**
 * One entry in the curated demo list shown on the Samples tab.
 *
 * String content is referenced through Android resources to keep literals
 * out of code and defined in a single place. The sample app is English-only
 * by design — see #1294. Closes #1099 / #955.
 *
 * @param id          Stable identifier used by the deep-link router
 *                    (`sceneview://demo/<id>`) and as a Compose key.
 * @param titleRes    Short headline shown on the card — resolved with
 *                    [androidx.compose.ui.res.stringResource] at the call site.
 * @param subtitleRes One-line description rendered under [titleRes].
 * @param category    Stable category key. Used as a map key when grouping
 *                    demos and to look up the per-category accent colour —
 *                    see [categoryDisplayNameRes] for the display header label.
 * @param icon        Material icon used to give the card visual identity
 *                    in the absence of a captured 3D thumbnail.
 * @param status      See [DemoStatus]. Defaults to [DemoStatus.Working].
 */
data class DemoEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val category: String,
    val icon: ImageVector,
    val status: DemoStatus = DemoStatus.Working,
)

/**
 * Stable category keys — they are map keys and registry filters, never
 * shown to the user. Use [categoryDisplayNameRes] to obtain the display
 * header label.
 */
object DemoCategory {
    const val BASICS_3D = "3D Basics"
    const val LIGHTING_ENVIRONMENT = "Lighting & Environment"
    const val CONTENT = "Content"
    const val INTERACTION = "Interaction"
    const val ADVANCED = "Advanced"
    const val AUGMENTED_REALITY = "Augmented Reality"
}

/** Ordered list of category keys — controls display order in the list. */
val DEMO_CATEGORIES = listOf(
    DemoCategory.BASICS_3D,
    DemoCategory.LIGHTING_ENVIRONMENT,
    DemoCategory.CONTENT,
    DemoCategory.INTERACTION,
    DemoCategory.ADVANCED,
    DemoCategory.AUGMENTED_REALITY,
)

/**
 * Maps a stable category key to its display-name resource ID.
 * Unknown keys fall back to [R.string.category_3d] (safe default — never
 * surfaces a raw key like "3D Basics" to the user).
 */
@StringRes
fun categoryDisplayNameRes(category: String): Int = when (category) {
    DemoCategory.BASICS_3D -> R.string.category_3d_basics
    DemoCategory.LIGHTING_ENVIRONMENT -> R.string.category_lighting_environment
    DemoCategory.CONTENT -> R.string.category_content
    DemoCategory.INTERACTION -> R.string.category_interaction
    DemoCategory.ADVANCED -> R.string.category_advanced
    DemoCategory.AUGMENTED_REALITY -> R.string.category_augmented_reality
    else -> R.string.category_3d_basics
}

val ALL_DEMOS = listOf(
    // 3D Basics
    DemoEntry("model-viewer", R.string.demo_model_viewer, R.string.demo_model_viewer_subtitle, DemoCategory.BASICS_3D, Icons.Filled.ViewInAr),
    DemoEntry("geometry", R.string.demo_geometry_title, R.string.demo_geometry_subtitle, DemoCategory.BASICS_3D, Icons.Filled.Category),
    DemoEntry("animation", R.string.demo_animation_title, R.string.demo_animation_subtitle, DemoCategory.BASICS_3D, Icons.Filled.RotateRight),
    DemoEntry("multi-model", R.string.demo_multi_model_title, R.string.demo_multi_model_subtitle, DemoCategory.BASICS_3D, Icons.Filled.Layers),
    DemoEntry("scene-gallery", R.string.demo_scene_gallery_title, R.string.demo_scene_gallery_subtitle, DemoCategory.BASICS_3D, Icons.Filled.Collections),
    // Lighting & Environment
    DemoEntry("lighting", R.string.demo_lighting_title, R.string.demo_lighting_subtitle, DemoCategory.LIGHTING_ENVIRONMENT, Icons.Filled.Lightbulb),
    DemoEntry("movable-light", R.string.demo_movable_light_title, R.string.demo_movable_light_subtitle, DemoCategory.LIGHTING_ENVIRONMENT, Icons.Filled.AutoFixHigh),
    DemoEntry("dynamic-sky", R.string.demo_dynamic_sky, R.string.demo_dynamic_sky_subtitle, DemoCategory.LIGHTING_ENVIRONMENT, Icons.Filled.WbSunny),
    DemoEntry("fog", R.string.demo_fog, R.string.demo_fog_subtitle, DemoCategory.LIGHTING_ENVIRONMENT, Icons.Filled.Cloud),
    DemoEntry("environment", R.string.demo_environment_title, R.string.demo_environment_subtitle, DemoCategory.LIGHTING_ENVIRONMENT, Icons.Filled.PanoramaPhotosphere),
    // Content
    DemoEntry("text", R.string.demo_text_title, R.string.demo_text_subtitle, DemoCategory.CONTENT, Icons.Filled.FormatShapes),
    DemoEntry("lines-paths", R.string.demo_lines_paths_title, R.string.demo_lines_paths_subtitle, DemoCategory.CONTENT, Icons.Filled.Timeline),
    DemoEntry("image", R.string.demo_image_title, R.string.demo_image_subtitle, DemoCategory.CONTENT, Icons.Filled.Image),
    DemoEntry("billboard", R.string.demo_billboard_title, R.string.demo_billboard_subtitle, DemoCategory.CONTENT, Icons.Filled.Visibility),
    DemoEntry("video", R.string.demo_video_title, R.string.demo_video_subtitle, DemoCategory.CONTENT, Icons.Filled.VideoLibrary),
    // Interaction
    DemoEntry("camera-controls", R.string.demo_camera_controls_title, R.string.demo_camera_controls_subtitle, DemoCategory.INTERACTION, Icons.Filled.PhotoCamera),
    DemoEntry("gesture-editing", R.string.demo_gesture_editing_title, R.string.demo_gesture_editing_subtitle, DemoCategory.INTERACTION, Icons.Filled.OpenWith),
    DemoEntry("collision", R.string.demo_collision_title, R.string.demo_collision_subtitle, DemoCategory.INTERACTION, Icons.Filled.CenterFocusStrong),
    DemoEntry("view-node", R.string.demo_view_node_title, R.string.demo_view_node_subtitle, DemoCategory.INTERACTION, Icons.Filled.Dashboard),
    // Advanced
    DemoEntry("materials", R.string.demo_materials_title, R.string.demo_materials_subtitle, DemoCategory.ADVANCED, Icons.Filled.Palette),
    DemoEntry("physics", R.string.demo_physics_title, R.string.demo_physics_subtitle, DemoCategory.ADVANCED, Icons.Filled.ScatterPlot),
    DemoEntry("double-pendulum", R.string.demo_double_pendulum_title, R.string.demo_double_pendulum_subtitle, DemoCategory.ADVANCED, Icons.Filled.Vibration),
    DemoEntry("post-processing", R.string.demo_post_processing_title, R.string.demo_post_processing_subtitle, DemoCategory.ADVANCED, Icons.Filled.Tune),
    DemoEntry("custom-mesh", R.string.demo_custom_mesh_title, R.string.demo_custom_mesh_subtitle, DemoCategory.ADVANCED, Icons.Filled.Hexagon),
    DemoEntry("texture-streaming", R.string.demo_texture_streaming_title, R.string.demo_texture_streaming_subtitle, DemoCategory.ADVANCED, Icons.Filled.Texture),
    DemoEntry("shape", R.string.demo_shape_title, R.string.demo_shape_subtitle, DemoCategory.ADVANCED, Icons.Filled.Pentagon),
    DemoEntry("reflection-probes", R.string.demo_reflection_probes_title, R.string.demo_reflection_probes_subtitle, DemoCategory.ADVANCED, Icons.Filled.BlurOn),
    DemoEntry("secondary-camera", R.string.demo_secondary_camera_title, R.string.demo_secondary_camera_subtitle, DemoCategory.ADVANCED, Icons.Filled.PictureInPicture),
    DemoEntry("debug-overlay", R.string.demo_debug_overlay_title, R.string.demo_debug_overlay_subtitle, DemoCategory.ADVANCED, Icons.Filled.Speed),
    // Augmented Reality
    DemoEntry("ar-placement", R.string.demo_ar_placement_title, R.string.demo_ar_placement_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.TouchApp),
    DemoEntry("ar-image", R.string.demo_ar_image_title, R.string.demo_ar_image_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Image),
    DemoEntry("ar-face", R.string.demo_ar_face_title, R.string.demo_ar_face_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Face),
    DemoEntry("ar-cloud-anchor", R.string.demo_ar_cloud_anchor_title, R.string.demo_ar_cloud_anchor_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.CloudCircle, DemoStatus.KnownIssue),
    DemoEntry("ar-streetscape", R.string.demo_ar_streetscape_title, R.string.demo_ar_streetscape_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.LocationCity, DemoStatus.KnownIssue),
    DemoEntry("ar-pose", R.string.demo_ar_pose_title, R.string.demo_ar_pose_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.MyLocation),
    DemoEntry("ar-rerun", R.string.demo_ar_rerun_title, R.string.demo_ar_rerun_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.BugReport),
    DemoEntry("ar-record-playback", R.string.demo_ar_record_playback_title, R.string.demo_ar_record_playback_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Replay),
    DemoEntry("ar-depth-occlusion", R.string.demo_ar_depth_occlusion_title, R.string.demo_ar_depth_occlusion_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.FilterCenterFocus),
    DemoEntry("ar-instant-placement", R.string.demo_ar_instant_placement_title, R.string.demo_ar_instant_placement_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Bolt),
    DemoEntry("ar-terrain", R.string.demo_ar_terrain_title, R.string.demo_ar_terrain_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Landscape, DemoStatus.KnownIssue),
    DemoEntry("ar-rooftop", R.string.demo_ar_rooftop_title, R.string.demo_ar_rooftop_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Roofing, DemoStatus.KnownIssue),
    DemoEntry("ar-image-stabilization", R.string.demo_ar_image_stabilization_title, R.string.demo_ar_image_stabilization_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Texture),
    DemoEntry("ar-orbital", R.string.demo_ar_orbital_title, R.string.demo_ar_orbital_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Public),
    DemoEntry("ar-depth-visualization", R.string.demo_ar_depth_visualization_title, R.string.demo_ar_depth_visualization_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.Palette),
    DemoEntry("ar-raw-depth-point-cloud", R.string.demo_ar_raw_depth_cloud_title, R.string.demo_ar_raw_depth_cloud_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.ScatterPlot),
    DemoEntry("ar-depth-collider", R.string.demo_ar_depth_collider_title, R.string.demo_ar_depth_collider_subtitle, DemoCategory.AUGMENTED_REALITY, Icons.Filled.ScatterPlot, DemoStatus.KnownIssue),
)
