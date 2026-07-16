package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.core.splat.SplatParser
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled synthetic 3D Gaussian Splatting scene — a dense rainbow sphere shell (8 000 splats). */
private const val SPLAT_ASSET = "splats/rainbow_sphere.ply"

/**
 * Renders a **3D Gaussian Splatting** scene with [io.github.sceneview.node.SplatNode] (#2646) —
 * the radiance-field capture format produced by Scaniverse, Polycam, Luma and every INRIA-style
 * trainer.
 *
 * The bundled asset ([SPLAT_ASSET]) is a synthetic rainbow sphere: ~8 000 overlapping, translucent
 * gaussians decoded by the shared `SplatParser` (P1a) and rendered as hardware-instanced,
 * camera-facing quads (P1b). The overlapping translucent shell is deliberately a stress case for
 * the CPU painter's sort — drag to orbit and the node re-sorts the splats back-to-front so the
 * far side composites correctly behind the near side.
 *
 * Controls:
 * - **Orbit** — drag to rotate the camera; `cameraPositionProvider` feeds the live camera world
 *   position to the node so it re-sorts on motion.
 * - **Visible splats** — a reveal slider driving `SplatNode.splatCount` (LOD / reveal hook).
 */
@Composable
fun SplatPreviewDemo(onBack: () -> Unit) {
    // Inspection mode (AS @Preview pane, Roborazzi): bypass the Filament-backed body BEFORE any
    // rememberEngine() call — LayoutLib does not ship the native .so files. See GeometryDemo.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Gaussian Splatting", onBack = onBack)
        return
    }

    val context = LocalContext.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState()

    // Decode the bundled .ply off the main thread (pure CPU, no Filament calls). Null while
    // loading — the SplatNode is only declared once the cloud is ready, mirroring the SDK's
    // "returns null while loading, always handle the null case" resource-loading contract.
    var splatCloud by remember { mutableStateOf<SplatCloud?>(null) }
    LaunchedEffect(Unit) {
        val bytes = withContext(Dispatchers.IO) {
            context.assets.open(SPLAT_ASSET).use { it.readBytes() }
        }
        splatCloud = withContext(Dispatchers.Default) { SplatParser.fromPly(bytes) }
    }

    // Live camera world position, refreshed each frame and handed to the node's painter's sort.
    var cameraPosition by remember { mutableStateOf(HOME_CAMERA_POSITION) }
    // Reveal slider — number of splats drawn (0..count). Defaults to the whole cloud.
    var visibleSplats by remember { mutableIntStateOf(0) }
    val totalSplats = splatCloud?.count ?: 0

    // Snap the reveal slider to the full cloud as soon as it loads (it starts at 0 before we
    // know the count, so the scene is not momentarily empty once the splats are ready).
    LaunchedEffect(totalSplats) {
        if (totalSplats > 0) visibleSplats = totalSplats
    }

    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = HOME_CAMERA_POSITION,
        targetPosition = Position(0f),
    )

    DemoScaffold(
        title = stringResource(R.string.demo_splat_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            SplatPreviewControls(
                visibleSplats = visibleSplats,
                totalSplats = totalSplats,
                onVisibleSplatsChange = { visibleSplats = it },
            )
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = cameraManipulator,
            // Chain the first-frame signal with a per-frame camera-position read so the node's
            // painter's sort tracks the orbit.
            onFrame = { frameTimeNanos ->
                firstFrame.onFrame(frameTimeNanos)
                cameraPosition = cameraNode.worldPosition
            },
        ) {
            splatCloud?.let { cloud ->
                SplatNode(
                    splatCloud = cloud,
                    cameraPositionProvider = { cameraPosition },
                    splatCount = visibleSplats,
                )
            }
        }
    }
}

/**
 * Stateless controls panel for [SplatPreviewDemo] — a reveal slider over the splat count. Kept
 * separate so a Roborazzi snapshot test can capture it in pure JVM (no Filament). See GeometryDemo.
 */
@Composable
internal fun SplatPreviewControls(
    visibleSplats: Int,
    totalSplats: Int,
    onVisibleSplatsChange: (Int) -> Unit,
) {
    Text(
        text = "Visible splats: $visibleSplats / $totalSplats",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = visibleSplats.toFloat(),
        onValueChange = { onVisibleSplatsChange(it.toInt()) },
        valueRange = 0f..totalSplats.coerceAtLeast(1).toFloat(),
        enabled = totalSplats > 0,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Drag the scene to orbit — the splats re-sort back-to-front so the translucent " +
            "shell composites correctly from every angle.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Orbit home: pulled back on +Z so the 0.5 m-radius sphere frames comfortably in portrait. */
private val HOME_CAMERA_POSITION = Position(x = 0f, y = 0.1f, z = 1.6f)

// ── Android Studio @Preview support ────────────────────────────────────────────

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun SplatPreviewDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        SplatPreviewDemo(onBack = {})
    }
}

@Preview(name = "Demo (dark)", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SplatPreviewDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        SplatPreviewDemo(onBack = {})
    }
}

@Preview(name = "Controls only", showBackground = true)
@Composable
private fun SplatPreviewControlsPreview() {
    SceneViewDemoTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
        ) {
            SplatPreviewControls(visibleSplats = 6000, totalSplats = 8000, onVisibleSplatsChange = {})
        }
    }
}
