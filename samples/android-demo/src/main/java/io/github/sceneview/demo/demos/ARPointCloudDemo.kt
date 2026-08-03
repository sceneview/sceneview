package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import java.util.Locale

/**
 * AR demo — renders ARCore's live tracking feature points as an in-scene point cloud using
 * [io.github.sceneview.ar.node.PointCloudNode].
 *
 * Unlike [ARRawDepthPointCloudDemo] — which samples the raw *depth image* and draws a
 * screen-space Compose `Canvas` overlay — this demo consumes
 * [com.google.ar.core.Frame.acquirePointCloud] (the sparse, **world-space** feature points
 * ARCore uses for motion tracking) and renders them as a real Filament `POINTS` primitive in
 * the 3D scene. The result is the SceneView equivalent of AR Foundation's `ARPointCloudManager`.
 *
 * A Compose [Slider] drives the confidence cut-off: at `0` every detected feature point is
 * rendered (a denser but noisier cloud); raising it keeps only ARCore's most confident points.
 *
 * The live point count is surfaced in the scaffold header so the user gets honest feedback on
 * tracking quality — more points generally means a richer, better-tracked scene.
 *
 * Closes [#1773](https://github.com/sceneview/sceneview/issues/1773).
 */
@Composable
fun ARPointCloudDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Confidence cut-off in [0, 1]. Default mirrors PointCloudNode.DEFAULT_CONFIDENCE_THRESHOLD
    // (0.2) — permissive enough that motion-stereo's noisy first frames still surface points.
    var confidenceThreshold by remember { mutableFloatStateOf(0.2f) }
    var pointCount by remember { mutableIntStateOf(0) }

    // A flat cyan unlit dot — lighting-independent so the points read clearly against any
    // camera feed. Owned by this composable: destroyed on dispose to avoid leaking the
    // MaterialInstance into Filament's tables (#1123).
    val pointMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(SceneViewColors.Accent)
    }
    DisposableEffect(pointMaterial) {
        onDispose { materialLoader.destroyMaterialInstance(pointMaterial) }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_point_cloud_title),
        onBack = onBack,
        peekHeader = if (pointCount > 0) {
            stringResource(R.string.demo_ar_point_cloud_count, pointCount)
        } else {
            stringResource(R.string.demo_ar_point_cloud_move_hint)
        },
        controls = {
            Text(
                text = "ARCore's sparse feature points, rendered as a world-space point cloud " +
                    "(PointCloudNode). Move your device to detect more points.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Confidence filter",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "%.2f".format(Locale.US, confidenceThreshold),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(
                    value = confidenceThreshold,
                    onValueChange = { confidenceThreshold = it },
                    valueRange = 0f..1f,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
            ) {
                val pointCloud = rememberPointCloud(
                    confidenceThreshold = confidenceThreshold,
                    materialInstance = pointMaterial,
                    onPointCloudUpdated = { pointCount = it },
                )
                PointCloudNode(node = pointCloud)
            }
        }
    }
}
