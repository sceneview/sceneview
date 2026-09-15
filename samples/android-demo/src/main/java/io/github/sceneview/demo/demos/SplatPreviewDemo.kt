package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real phone capture, bundled as the very file a scanning app exports: three raccoons on a
 * tree stump, filmed by walking around it, reconstructed as 233 808 coloured points.
 *
 * Source: the `racoonfamily.spz` sample published with Niantic's SPZ format (MIT). The bundled
 * copy is cropped to the subject by `tools/crop-spz.py` — 932 560 splats / 24 MB down to a
 * bundleable 3.1 MB — with every kept splat byte-identical to the capture.
 */
private const val SPLAT_ASSET = "splats/raccoon_family.spz"

/**
 * **Open a 3D scan** — the demo answers one question a developer actually has: *what does a
 * capture made with Scaniverse / Polycam / Luma look like in my app, and what does it cost?*
 *
 * Everything on screen comes from the file: the point count, its size on disk, how long it took
 * to decode. The single control is the one that matters in production — how many of those points
 * you draw ([io.github.sceneview.node.SplatNode.splatCount]), which is how a large capture stays
 * smooth on a cheaper phone without re-exporting anything.
 *
 * Rendering is [io.github.sceneview.node.SplatNode] (#2646): hardware-instanced camera-facing
 * gaussian discs, re-sorted back-to-front on a background thread whenever the camera moves, so
 * the translucent points composite correctly from every angle.
 */
@Composable
fun SplatPreviewDemo(onBack: () -> Unit) {
    // Inspection mode (AS @Preview pane, Roborazzi): bypass the Filament-backed body BEFORE any
    // rememberEngine() call — LayoutLib does not ship the native .so files. See GeometryDemo.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(
            title = stringResource(R.string.demo_splat_preview_title),
            onBack = onBack,
        )
        return
    }

    val context = LocalContext.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState()

    // Decode the bundled capture off the main thread (pure CPU, no Filament calls). Null while
    // loading — the SplatNode is only declared once the cloud is ready, mirroring the SDK's
    // "returns null while loading, always handle the null case" resource-loading contract.
    var scan by remember { mutableStateOf<LoadedScan?>(null) }
    LaunchedEffect(Unit) {
        val bytes = withContext(Dispatchers.IO) {
            context.assets.open(SPLAT_ASSET).use { it.readBytes() }
        }
        scan = withContext(Dispatchers.Default) {
            val startedAt = System.nanoTime()
            // parse() sniffs the container: the same call opens a .spz or a .ply export.
            val cloud = SplatParser.parse(bytes)
            LoadedScan(
                cloud = cloud,
                framing = scanFraming(cloud),
                fileBytes = bytes.size,
                decodeMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    // Live camera world position, refreshed each frame and handed to the node's painter's sort.
    var cameraPosition by remember { mutableStateOf(Position(z = 2f)) }
    // How many of the captured points are drawn. 0 until the file is open, then the whole scan.
    var drawnPoints by remember { mutableIntStateOf(0) }
    val totalPoints = scan?.cloud?.count ?: 0
    LaunchedEffect(totalPoints) {
        if (totalPoints > 0) drawnPoints = totalPoints
    }

    // The framing is only known once the file is decoded, so the manipulator is re-created when
    // the scan lands — same `remember(key)` pattern the model viewer uses for its park framing.
    val framing = scan?.framing
    val cameraManipulator = remember(framing) {
        createDefaultCameraManipulator(
            eyePosition = framing?.cameraPosition ?: DEFAULT_CAMERA_POSITION,
            targetPosition = framing?.target ?: Position(0f),
        )
    }

    DemoScaffold(
        title = stringResource(R.string.demo_splat_preview_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_splat_preview_loading),
        peekHeader = scan?.let {
            stringResource(
                R.string.demo_splat_preview_peek,
                formatPoints(it.cloud.count),
                formatMegabytes(it.fileBytes),
            )
        },
        onResetSettings = { drawnPoints = totalPoints },
        controls = {
            SplatPreviewControls(
                drawnPoints = drawnPoints,
                totalPoints = totalPoints,
                fileBytes = scan?.fileBytes ?: 0,
                decodeMillis = scan?.decodeMillis ?: 0,
                onDrawnPointsChange = { drawnPoints = it },
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
            scan?.let { loaded ->
                SplatNode(
                    splatCloud = loaded.cloud,
                    cameraPositionProvider = { cameraPosition },
                    splatCount = drawnPoints,
                )
            }
        }
    }
}

/** The decoded capture plus the facts the screen reports about the file it came from. */
private data class LoadedScan(
    val cloud: SplatCloud,
    val framing: ScanFraming,
    val fileBytes: Int,
    val decodeMillis: Long,
)

/**
 * Stateless controls panel for [SplatPreviewDemo]. Kept separate so a Roborazzi snapshot test can
 * capture it in pure JVM (no Filament). See GeometryDemo.
 */
@Composable
internal fun SplatPreviewControls(
    drawnPoints: Int,
    totalPoints: Int,
    fileBytes: Int,
    decodeMillis: Long,
    onDrawnPointsChange: (Int) -> Unit,
) {
    Text(
        text = stringResource(R.string.demo_splat_preview_intro),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))
    LabeledSlider(
        label = stringResource(R.string.demo_splat_preview_points_label),
        value = drawnPoints.toFloat(),
        onValueChange = { onDrawnPointsChange(it.toInt()) },
        valueRange = 0f..totalPoints.coerceAtLeast(1).toFloat(),
        valueText = stringResource(
            R.string.demo_splat_preview_points_value,
            formatPoints(drawnPoints),
            formatPoints(totalPoints),
        ),
        enabled = totalPoints > 0,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.demo_splat_preview_points_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = if (totalPoints > 0) {
            stringResource(
                R.string.demo_splat_preview_file,
                formatMegabytes(fileBytes),
                decodeMillis.toInt(),
            )
        } else {
            stringResource(R.string.demo_splat_preview_file_loading)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `233808` → `233 808`: grouped for readability, with no locale surprise. */
internal fun formatPoints(count: Int): String =
    count.toString().reversed().chunked(3).joinToString(" ").reversed()

/** Bytes → `3.1 MB`, the number a developer compares against their APK budget. */
internal fun formatMegabytes(bytes: Int): String =
    String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)

// ── Orbit home framing ─────────────────────────────────────────────────────────

/** Orbit home and target for a capture, derived from the cloud itself — no hand-picked numbers. */
internal data class ScanFraming(val target: Position, val cameraPosition: Position)

/** Used only while the file is still decoding, so the first frame has a sane camera. */
private val DEFAULT_CAMERA_POSITION = Position(z = 2f)

/**
 * Filament derives its projection from a **35 mm-equivalent focal length against a 24 mm-high
 * sensor** (`CameraNode.focalLength` defaults to 28 mm, applied via `setLensProjection`), so the
 * vertical half-angle is `atan((24 / 2) / focalLength)` and the horizontal one is the vertical
 * scaled by the aspect ratio. In portrait that makes **width the binding constraint**.
 */
private const val SENSOR_HEIGHT_MM = 24.0f
private const val DEFAULT_FOCAL_LENGTH_MM = 28.0f

/** Pixel-class portrait viewport (1080x2400). The SceneView band is shorter, hence wider — using
 *  the full-screen ratio is the conservative choice: any real viewport has more horizontal room. */
private const val PORTRAIT_ASPECT = 9f / 20f

/** Fraction of the frame's half-width the framed radius is allowed to fill — the "small margin". */
private const val FRAME_FILL = 0.95f

/**
 * Fraction of the capture the framing is required to contain. A real capture has no silhouette:
 * it fades out into whatever the phone happened to see — grass, ground, a blurred hedge. Framing
 * on the outermost splat would push the subject into the distance to keep that fringe on screen,
 * so the home shot contains the **median half** of the points and lets the fringe spill off the
 * edges, which is what a photographer would do.
 */
private const val SUBJECT_QUANTILE = 0.5f

/** Elevation of the orbit home above the target (~3.6°), a slightly-above-eye-line look. */
private const val HOME_TILT_RADIANS = 0.06241f

/**
 * Distance at which a sphere of [radius] is fully contained with a [fill] margin, for a
 * vertical-fit perspective camera of [focalLengthMm] at [aspect].
 *
 * The sphere is bounded by the frustum where the view ray is **tangent** to it, so the containing
 * distance is `radius / sin(halfAngle)` — not `radius / tan(halfAngle)`, which frames the flat disc
 * through the centre and still clips a sphere's silhouette. `internal` so the arithmetic is
 * unit-testable without a Filament engine.
 */
internal fun splatFramingDistance(
    radius: Float,
    aspect: Float = PORTRAIT_ASPECT,
    focalLengthMm: Float = DEFAULT_FOCAL_LENGTH_MM,
    fill: Float = FRAME_FILL,
): Float {
    val tanHalfVertical = (SENSOR_HEIGHT_MM / 2f) / focalLengthMm
    // Portrait: aspect < 1, so the horizontal angle is the narrower of the two and binds first.
    val tanHalfHorizontal = aspect * tanHalfVertical
    val sinHalfHorizontal = tanHalfHorizontal / sqrt(1f + tanHalfHorizontal * tanHalfHorizontal)
    return radius / (sinHalfHorizontal * fill)
}

/**
 * Frames [cloud] from its own geometry: the target is the centroid of the points, the distance is
 * [splatFramingDistance] applied to the radius containing [SUBJECT_QUANTILE] of them. Pure math on
 * the decoded arrays — `internal` and Filament-free, so it is unit-tested directly.
 */
internal fun scanFraming(cloud: SplatCloud, aspect: Float = PORTRAIT_ASPECT): ScanFraming {
    val count = cloud.count
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    for (i in 0 until count) {
        sumX += cloud.positions[i * 3]
        sumY += cloud.positions[i * 3 + 1]
        sumZ += cloud.positions[i * 3 + 2]
    }
    val target = Position(
        x = (sumX / count).toFloat(),
        y = (sumY / count).toFloat(),
        z = (sumZ / count).toFloat(),
    )

    val radii = FloatArray(count) { i ->
        val dx = cloud.positions[i * 3] - target.x
        val dy = cloud.positions[i * 3 + 1] - target.y
        val dz = cloud.positions[i * 3 + 2] - target.z
        sqrt(dx * dx + dy * dy + dz * dz)
    }
    radii.sort()
    val subjectRadius = radii[((count - 1) * SUBJECT_QUANTILE).toInt()]

    val distance = splatFramingDistance(radius = subjectRadius, aspect = aspect)
    return ScanFraming(
        target = target,
        cameraPosition = Position(
            x = target.x,
            y = target.y + distance * sin(HOME_TILT_RADIANS),
            z = target.z + distance * cos(HOME_TILT_RADIANS),
        ),
    )
}

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
            SplatPreviewControls(
                drawnPoints = 233_808,
                totalPoints = 233_808,
                fileBytes = 3_305_000,
                decodeMillis = 420,
                onDrawnPointsChange = {},
            )
        }
    }
}
