package io.github.sceneview.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import io.github.sceneview.demo.demos.ARCloudAnchorDemo
import io.github.sceneview.demo.demos.ARDepthOcclusionDemo
import io.github.sceneview.demo.demos.ARFaceDemo
import io.github.sceneview.demo.demos.ARImageDemo
import io.github.sceneview.demo.demos.ARImageStabilizationDemo
import io.github.sceneview.demo.demos.ARInstantPlacementDemo
import io.github.sceneview.demo.demos.ARPlacementDemo
import io.github.sceneview.demo.demos.ARPoseDemo
import io.github.sceneview.demo.demos.ARRecordPlaybackDemo
import io.github.sceneview.demo.demos.ARRerunDemo
import io.github.sceneview.demo.demos.ARRooftopAnchorDemo
import io.github.sceneview.demo.demos.ARStreetscapeDemo
import io.github.sceneview.demo.demos.ARTerrainAnchorDemo
import io.github.sceneview.demo.demos.AnimationDemo
import io.github.sceneview.demo.demos.CameraControlsDemo
import io.github.sceneview.demo.demos.CustomGeometryDemo
import io.github.sceneview.demo.demos.DebugOverlayDemo
import io.github.sceneview.demo.demos.DynamicSkyDemo
import io.github.sceneview.demo.demos.EnvironmentDemo
import io.github.sceneview.demo.demos.FogDemo
import io.github.sceneview.demo.demos.GeometryDemo
import io.github.sceneview.demo.demos.GestureEditingDemo
import io.github.sceneview.demo.demos.LightingDemo
import io.github.sceneview.demo.demos.LinesPathsDemo
import io.github.sceneview.demo.demos.ModelViewerDemo
import io.github.sceneview.demo.demos.MultiModelDemo
import io.github.sceneview.demo.demos.PhysicsDemo
import io.github.sceneview.demo.demos.PickingAndCollisionDemo
import io.github.sceneview.demo.demos.PostProcessingDemo
import io.github.sceneview.demo.demos.ReflectionProbesDemo
import io.github.sceneview.demo.demos.SecondaryCameraDemo
import io.github.sceneview.demo.demos.TwoDInThreeDDemo
import io.github.sceneview.demo.theme.SceneViewDemoTheme

/**
 * Debug-only test harness activity.
 *
 * Launches a **single demo composable directly**, bypassing the home list + navigation stack.
 * Built only in debug builds so the shipped Play Store release APK isn't bloated with test
 * entry points.
 *
 * ### Why this exists
 *
 * The demo list uses a Compose `LazyColumn` whose scroll position is only partially visible
 * to `UiScrollable.scrollTextIntoView()` — items far down the list (e.g. `Physics`,
 * `Custom Mesh` in the ADVANCED section) stay out of the view tree until the LazyColumn
 * finalises recomposition after each swipe. This races with UiAutomator's "I'm done scrolling"
 * heuristic and manifests as `scrollTextIntoView` returning `false` even when enough scroll
 * budget remains.
 *
 * Rather than fight the scroll-until-visible loop, instrumentation tests can launch this
 * activity with an intent extra identifying the demo:
 *
 * ```kotlin
 * val intent = Intent(context, DemoHostActivity::class.java)
 *     .putExtra(EXTRA_DEMO_ID, "physics")
 *     .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
 * context.startActivity(intent)
 * ```
 *
 * The activity renders the demo composable directly, with `finish()` wired to the back button.
 * `DemoInteractionTest` uses this for every interaction test — fast, deterministic, scales
 * to all 30 demos without any scroll-tuning.
 *
 * @see DemoEntry.id in [ALL_DEMOS] for the full list of valid ids.
 */
class DemoHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEMO_ID = "demo_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val demoId = intent.getStringExtra(EXTRA_DEMO_ID)
            ?: error("DemoHostActivity launched without $EXTRA_DEMO_ID extra")

        setContent {
            SceneViewDemoTheme {
                DemoById(demoId)
            }
        }
    }

    @Composable
    private fun DemoById(id: String) {
        val back: () -> Unit = { finish() }
        when (id) {
            // 3D Basics
            "model-viewer" -> ModelViewerDemo(onBack = back)
            "geometry" -> GeometryDemo(onBack = back)
            "animation" -> AnimationDemo(onBack = back)
            "multi-model" -> MultiModelDemo(onBack = back)
            // Lighting & Environment
            "lighting" -> LightingDemo(onBack = back)
            "dynamic-sky" -> DynamicSkyDemo(onBack = back)
            "fog" -> FogDemo(onBack = back)
            "environment" -> EnvironmentDemo(onBack = back)
            // Content
            // #2239 Batch 1 — `text`, `image`, `video`, `billboard` consolidated into `two-d-in-three-d`.
            "two-d-in-three-d", "text", "image", "video", "billboard" -> TwoDInThreeDDemo(onBack = back)
            "lines-paths" -> LinesPathsDemo(onBack = back)
            // Interaction
            "camera-controls" -> CameraControlsDemo(onBack = back)
            "gesture-editing" -> GestureEditingDemo(onBack = back)
            // #2239 Batch 1 — `collision` and `view-node` consolidated into `picking-collision`.
            "picking-collision", "collision", "view-node" -> PickingAndCollisionDemo(onBack = back)
            // Advanced
            "physics" -> PhysicsDemo(onBack = back)
            "post-processing" -> PostProcessingDemo(onBack = back)
            // #2239 Batch 1 — `custom-mesh` and `shape` consolidated into `custom-geometry`.
            "custom-geometry", "custom-mesh", "shape" -> CustomGeometryDemo(onBack = back)
            "reflection-probes" -> ReflectionProbesDemo(onBack = back)
            "secondary-camera" -> SecondaryCameraDemo(onBack = back)
            "debug-overlay" -> DebugOverlayDemo(onBack = back)
            // Augmented Reality
            "ar-placement" -> ARPlacementDemo(onBack = back)
            "ar-image" -> ARImageDemo(onBack = back)
            "ar-face" -> ARFaceDemo(onBack = back)
            "ar-cloud-anchor" -> ARCloudAnchorDemo(onBack = back)
            "ar-streetscape" -> ARStreetscapeDemo(onBack = back)
            "ar-pose" -> ARPoseDemo(onBack = back)
            "ar-rerun" -> ARRerunDemo(onBack = back)
            // 6 AR demos shipped after the previous DemoHostActivity revision (#888):
            "ar-record-playback" -> ARRecordPlaybackDemo(onBack = back)
            "ar-depth-occlusion" -> ARDepthOcclusionDemo(onBack = back)
            "ar-instant-placement" -> ARInstantPlacementDemo(onBack = back)
            "ar-terrain" -> ARTerrainAnchorDemo(onBack = back)
            "ar-rooftop" -> ARRooftopAnchorDemo(onBack = back)
            "ar-image-stabilization" -> ARImageStabilizationDemo(onBack = back)
            else -> error("Unknown demo id '$id'")
        }
    }
}
