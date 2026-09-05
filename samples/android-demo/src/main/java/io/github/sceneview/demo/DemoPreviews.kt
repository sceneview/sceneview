package io.github.sceneview.demo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource

/**
 * Captured preview images for the home grid, keyed by [DemoEntry.id].
 *
 * The map is empty until the preview-image pipeline (design spec §5) lands the
 * rendered `res/drawable-nodpi/preview_<id_with_underscores>_light.webp` /
 * `_dark.webp` pairs — 800×640 (5:4), q80. That pipeline fills [previews] in
 * the same PR it adds the drawables; nothing else writes here. Until then every
 * card falls back to its [DemoEntry.icon] tile (see `DemoMediaCard`), which is
 * why [resourceFor] returns `null` rather than a placeholder drawable: a missing
 * capture must read as "no capture yet", not as a fake one.
 */
object DemoPreviews {
    private class PreviewPair(@DrawableRes val light: Int, @DrawableRes val dark: Int)

    /** `id` → light/dark drawable pair. Filled by the image pipeline. */
    private val previews: Map<String, PreviewPair> = mapOf(
        "animation-physics" to PreviewPair(R.drawable.preview_animation_physics_light, R.drawable.preview_animation_physics_dark),
        "ar-body-tracker" to PreviewPair(R.drawable.preview_ar_body_tracker_light, R.drawable.preview_ar_body_tracker_dark),
        "ar-cloud-anchor" to PreviewPair(R.drawable.preview_ar_cloud_anchor_light, R.drawable.preview_ar_cloud_anchor_dark),
        "ar-collaborative" to PreviewPair(R.drawable.preview_ar_collaborative_light, R.drawable.preview_ar_collaborative_dark),
        "ar-depth-collider" to PreviewPair(R.drawable.preview_ar_depth_collider_light, R.drawable.preview_ar_depth_collider_dark),
        "ar-depth-occlusion" to PreviewPair(R.drawable.preview_ar_depth_occlusion_light, R.drawable.preview_ar_depth_occlusion_dark),
        "ar-depth-of-field" to PreviewPair(R.drawable.preview_ar_depth_of_field_light, R.drawable.preview_ar_depth_of_field_dark),
        "ar-depth-visualization" to PreviewPair(R.drawable.preview_ar_depth_visualization_light, R.drawable.preview_ar_depth_visualization_dark),
        "ar-face" to PreviewPair(R.drawable.preview_ar_face_light, R.drawable.preview_ar_face_dark),
        "ar-fog" to PreviewPair(R.drawable.preview_ar_fog_light, R.drawable.preview_ar_fog_dark),
        "ar-geospatial-anchors" to PreviewPair(R.drawable.preview_ar_geospatial_anchors_light, R.drawable.preview_ar_geospatial_anchors_dark),
        "ar-hand-tracking" to PreviewPair(R.drawable.preview_ar_hand_tracking_light, R.drawable.preview_ar_hand_tracking_dark),
        "ar-image" to PreviewPair(R.drawable.preview_ar_image_light, R.drawable.preview_ar_image_dark),
        "ar-image-stabilization" to PreviewPair(R.drawable.preview_ar_image_stabilization_light, R.drawable.preview_ar_image_stabilization_dark),
        "ar-measure" to PreviewPair(R.drawable.preview_ar_measure_light, R.drawable.preview_ar_measure_dark),
        "ar-ml-object-label" to PreviewPair(R.drawable.preview_ar_ml_object_label_light, R.drawable.preview_ar_ml_object_label_dark),
        "ar-orbital" to PreviewPair(R.drawable.preview_ar_orbital_light, R.drawable.preview_ar_orbital_dark),
        "ar-people-occlusion" to PreviewPair(R.drawable.preview_ar_people_occlusion_light, R.drawable.preview_ar_people_occlusion_dark),
        "ar-placement" to PreviewPair(R.drawable.preview_ar_placement_light, R.drawable.preview_ar_placement_dark),
        "ar-plane-node" to PreviewPair(R.drawable.preview_ar_plane_node_light, R.drawable.preview_ar_plane_node_dark),
        "ar-plane-renderer-v2" to PreviewPair(R.drawable.preview_ar_plane_renderer_v2_light, R.drawable.preview_ar_plane_renderer_v2_dark),
        "ar-point-cloud" to PreviewPair(R.drawable.preview_ar_point_cloud_light, R.drawable.preview_ar_point_cloud_dark),
        "ar-pose" to PreviewPair(R.drawable.preview_ar_pose_light, R.drawable.preview_ar_pose_dark),
        "ar-raw-depth-point-cloud" to PreviewPair(R.drawable.preview_ar_raw_depth_point_cloud_light, R.drawable.preview_ar_raw_depth_point_cloud_dark),
        "ar-record-playback" to PreviewPair(R.drawable.preview_ar_record_playback_light, R.drawable.preview_ar_record_playback_dark),
        "ar-rerun" to PreviewPair(R.drawable.preview_ar_rerun_light, R.drawable.preview_ar_rerun_dark),
        "ar-scene-mesh" to PreviewPair(R.drawable.preview_ar_scene_mesh_light, R.drawable.preview_ar_scene_mesh_dark),
        "ar-scene-semantics" to PreviewPair(R.drawable.preview_ar_scene_semantics_light, R.drawable.preview_ar_scene_semantics_dark),
        "ar-xr-face" to PreviewPair(R.drawable.preview_ar_xr_face_light, R.drawable.preview_ar_xr_face_dark),
        "camera-gestures" to PreviewPair(R.drawable.preview_camera_gestures_light, R.drawable.preview_camera_gestures_dark),
        "contact-shadow-preview" to PreviewPair(R.drawable.preview_contact_shadow_preview_light, R.drawable.preview_contact_shadow_preview_dark),
        "custom-geometry" to PreviewPair(R.drawable.preview_custom_geometry_light, R.drawable.preview_custom_geometry_dark),
        "debug-overlay" to PreviewPair(R.drawable.preview_debug_overlay_light, R.drawable.preview_debug_overlay_dark),
        "double-pendulum" to PreviewPair(R.drawable.preview_double_pendulum_light, R.drawable.preview_double_pendulum_dark),
        "geometry" to PreviewPair(R.drawable.preview_geometry_light, R.drawable.preview_geometry_dark),
        "lighting" to PreviewPair(R.drawable.preview_lighting_light, R.drawable.preview_lighting_dark),
        "lighting-lab" to PreviewPair(R.drawable.preview_lighting_lab_light, R.drawable.preview_lighting_lab_dark),
        "lines-paths" to PreviewPair(R.drawable.preview_lines_paths_light, R.drawable.preview_lines_paths_dark),
        "materials" to PreviewPair(R.drawable.preview_materials_light, R.drawable.preview_materials_dark),
        "model-viewer" to PreviewPair(R.drawable.preview_model_viewer_light, R.drawable.preview_model_viewer_dark),
        "picking-collision" to PreviewPair(R.drawable.preview_picking_collision_light, R.drawable.preview_picking_collision_dark),
        "placement-scene" to PreviewPair(R.drawable.preview_placement_scene_light, R.drawable.preview_placement_scene_dark),
        "point-and-ask" to PreviewPair(R.drawable.preview_point_and_ask_light, R.drawable.preview_point_and_ask_dark),
        "secondary-camera" to PreviewPair(R.drawable.preview_secondary_camera_light, R.drawable.preview_secondary_camera_dark),
        "spatial-audio" to PreviewPair(R.drawable.preview_spatial_audio_light, R.drawable.preview_spatial_audio_dark),
        "splat-preview" to PreviewPair(R.drawable.preview_splat_preview_light, R.drawable.preview_splat_preview_dark),
        "two-d-in-three-d" to PreviewPair(R.drawable.preview_two_d_in_three_d_light, R.drawable.preview_two_d_in_three_d_dark),
        "video-recording" to PreviewPair(R.drawable.preview_video_recording_light, R.drawable.preview_video_recording_dark),
        "wall-placement" to PreviewPair(R.drawable.preview_wall_placement_light, R.drawable.preview_wall_placement_dark),
    )

    /** The preview drawable for [id] in the requested scheme, or `null` if none was captured. */
    @DrawableRes
    fun resourceFor(id: String, dark: Boolean = false): Int? =
        previews[id]?.let { if (dark) it.dark else it.light }
}

/**
 * The captured preview for this demo in the current colour scheme, or `null`
 * when the pipeline has not produced one — callers draw the icon tile instead.
 */
@Composable
fun DemoEntry.previewPainter(): Painter? =
    DemoPreviews.resourceFor(id, dark = isSystemInDarkTheme())?.let { painterResource(it) }
