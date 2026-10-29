package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.demos.internal.FeaturePointAccumulator
import io.github.sceneview.demo.demos.internal.PointCloudFeedback
import io.github.sceneview.demo.demos.internal.PointCloudSprites
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import com.google.android.filament.Box as FilamentBox
import io.github.sceneview.node.MeshNode as MeshNodeImpl

/**
 * AR demo — builds a persistent, world-space point cloud out of ARCore's tracking feature points
 * and draws it as on-screen-sized dots over the camera.
 *
 * Unlike [ARRawDepthPointCloudDemo] — which samples the raw *depth image* — this demo consumes
 * [com.google.ar.core.Frame.acquirePointCloud]: the sparse feature points ARCore uses for motion
 * tracking. That call only returns **this frame's** points (a few tens), so drawing just those
 * looked empty — "Tracking 35 points" over an almost blank camera, as 1-pixel `POINTS` (#4624).
 * The demo now accumulates them across frames by ARCore point id ([FeaturePointAccumulator]:
 * latest position wins, capped buffer, the point seen longest ago is evicted first), so the room
 * fills in as the user moves. Each point is a camera-facing sprite sized in screen pixels
 * ([PointCloudSprites]), amber while the camera sees it right now, then settling into the brand
 * ramp by confidence.
 *
 * The status pill counts the accumulated cloud and says "move slowly to build the cloud" while
 * it is still sparse; the Settings sheet trades the old raw confidence slider for one plain
 * "Show only confident points" switch.
 *
 * **Tracking feedback (#3270).** A lost-tracking session adds no point, with no on-screen
 * explanation, so the demo surfaces the same tracking-failure banner as its
 * [ARRawDepthPointCloudDemo] / `ARSceneSemanticsDemo` siblings, plus a "still scanning" hint if
 * tracking is fine but the cloud has stayed empty for a couple of seconds.
 *
 * Closes [#1773](https://github.com/sceneview/sceneview/issues/1773),
 * [#3270](https://github.com/sceneview/sceneview/issues/3270).
 */
@Composable
fun ARPointCloudDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    var arSessionUnavailable by remember { mutableStateOf(false) }
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    var confidentOnly by rememberSaveable { mutableStateOf(false) }
    val minConfidence = if (confidentOnly) CONFIDENT_ONLY_THRESHOLD else ALL_POINTS_THRESHOLD
    var pointCount by remember { mutableIntStateOf(0) }

    var isTracking by remember { mutableStateOf(false) }
    // QA camera backdrop (#3308): the emulator has no camera HAL, so a blurred room photo
    // stands in for the camera feed until a real frame arrives.
    var cameraReady by remember { mutableStateOf(false) }
    val cameraStream = rememberARCameraStream(materialLoader)
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // "Stuck at zero points" chip state (#3270) — same shape as ARRawDepthPointCloudDemo's.
    var zeroPointsSince by remember { mutableStateOf<Long?>(SystemClock.elapsedRealtime()) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            now = SystemClock.elapsedRealtime()
        }
    }
    val zeroPointsStuck = PointCloudFeedback.zeroPointsStuck(
        isTracking = isTracking,
        pointCount = pointCount,
        zeroPointsSinceMs = zeroPointsSince,
        nowMs = now,
    )

    // The accumulated cloud and the per-frame scratch it is fed from. Main thread only: both
    // are touched from the AR frame callback.
    val cloud = remember { FeaturePointAccumulator() }
    val frameScratch = remember { FrameScratch() }
    val renderer = remember { PointCloudRenderer(cloud.capacity) }
    val density = LocalDensity.current.density
    var viewHeightPx by remember { mutableIntStateOf(0) }
    // Saturated ends (`ar-mesh-ground` → `ar-points-settled`, #4623): the brand purple and
    // the pale #a4c1ff it ran to blended into a white wall and read as grey dust.
    val palette = remember {
        PointCloudSprites.Palette(
            low = SceneViewTokens.ArOverlay.meshGround.linearRgb(),
            high = SceneViewTokens.ArOverlay.pointsSettled.linearRgb(),
            fresh = SceneViewTokens.ArOverlay.accentGuidance.linearRgb(),
        )
    }

    // Camera-facing, alpha-blended dots: the Cosmos demo's dust sprite (one quad per point,
    // corners pushed along the camera axes in the vertex stage). `POINTS` would be 1 px wide.
    val dotMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial(DOT_MATERIAL)
    }
    val dotInstance = remember(materialLoader, dotMaterial) {
        dotMaterial?.let { materialLoader.createInstance(it).apply { setParameter("opacity", 1f) } }
    }
    DisposableEffect(dotInstance) {
        onDispose { dotInstance?.let { materialLoader.destroyMaterialInstance(it) } }
    }

    val startOver = {
        cloud.clear()
        // Hide the old dots now: the next rebuild waits for tracking, which may be lost.
        renderer.node?.isVisible = false
        renderer.lastUploadMs = 0L
        pointCount = 0
        zeroPointsSince = SystemClock.elapsedRealtime()
    }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = !arSessionUnavailable,
        title = stringResource(R.string.demo_ar_point_cloud_title),
        onBack = onBack,
        peekHeader = when (PointCloudFeedback.status(pointCount)) {
            PointCloudFeedback.Status.Empty -> stringResource(R.string.demo_ar_point_cloud_move_hint)
            PointCloudFeedback.Status.Sparse ->
                pluralStringResource(R.plurals.demo_ar_point_cloud_count_sparse, pointCount, pointCount)
            PointCloudFeedback.Status.Built ->
                pluralStringResource(R.plurals.demo_ar_point_cloud_count, pointCount, pointCount)
        },
        dock = listOf(
            DockItem(
                icon = Icons.Default.Refresh,
                label = stringResource(R.string.demo_ar_point_cloud_start_over),
                onClick = startOver,
                enabled = pointCount > 0,
            ),
        ),
        controls = {
            Text(
                text = stringResource(R.string.demo_ar_point_cloud_about),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(SceneViewTokens.Space.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = confidentOnly, onValueChange = { confidentOnly = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = SceneViewTokens.Space.md)) {
                    Text(
                        text = stringResource(R.string.demo_ar_point_cloud_confident_only),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.demo_ar_point_cloud_confident_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = confidentOnly, onCheckedChange = null)
            }
            // Developer-only debug toggle — lets QA force-emit each TrackingFailureReason so
            // the banner below can be validated without staging a real failure (#1881).
            ForceTrackingFailureMenu()
        },
        bottomOverlay = {
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = (!isTracking && trackingFailureReason != null) ||
                    ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val (statusText, statusTone) = when (effectiveReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT ->
                        "Not enough light" to DemoStatusTone.Guidance
                    TrackingFailureReason.EXCESSIVE_MOTION ->
                        "Moving too fast" to DemoStatusTone.Guidance
                    TrackingFailureReason.INSUFFICIENT_FEATURES ->
                        "Not enough detail — point at a textured surface" to
                            DemoStatusTone.Guidance
                    TrackingFailureReason.CAMERA_UNAVAILABLE ->
                        "Camera unavailable" to DemoStatusTone.Blocked
                    TrackingFailureReason.BAD_STATE ->
                        "AR session error" to DemoStatusTone.Blocked
                    else -> stringResource(R.string.ar_status_scanning) to
                        DemoStatusTone.Progress
                }
                DemoStatusBanner(text = statusText, tone = statusTone)
            }
            AnimatedVisibility(
                visible = zeroPointsStuck && trackingFailureReason == null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                DemoStatusBanner(
                    text = stringResource(R.string.demo_ar_point_cloud_move_hint_short),
                    tone = DemoStatusTone.Guidance,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewHeightPx = it.height }) {
            if (qaBackdrop) QaCameraBackdrop(seed = "point-cloud")
            ARSceneView(
                lifecycle = rememberHostLifecycle(),
                onSessionFailure = { arSessionFailed = true },
                onARCoreAvailability = { arSessionUnavailable = it != null },
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                playbackDataset = arPlaybackDataset,
                onSessionUpdated = { _: Session, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (isTracking) {
                        val nowMs = SystemClock.elapsedRealtime()
                        frameScratch.ingest(frame, cloud, nowMs)
                        if (nowMs - renderer.lastUploadMs >= REBUILD_INTERVAL_MS && viewHeightPx > 0) {
                            val pose = frame.camera.pose
                            val zAxis = pose.zAxis
                            renderer.rebuild(
                                engine = engine,
                                cloud = cloud,
                                minConfidence = minConfidence,
                                nowMs = nowMs,
                                camera = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
                                cameraForward = floatArrayOf(-zAxis[0], -zAxis[1], -zAxis[2]),
                                focalPx = renderer.focalPx(frame, viewHeightPx),
                                radiusPx = DOT_RADIUS_DP * density,
                                palette = palette,
                            )
                            val count = cloud.count(minConfidence)
                            pointCount = count
                            zeroPointsSince = if (count > 0) null else zeroPointsSince ?: nowMs
                        }
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            ) {
                // Buffers remembered inside the scene content, before the node that draws them,
                // so Compose forgets (and frees) them after that node is destroyed.
                val buffers = remember(engine) { renderer.createBuffers(engine) }
                DisposableEffect(buffers) {
                    onDispose { renderer.destroyBuffers(engine) }
                }
                dotInstance?.let { material ->
                    MeshNode(
                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer = buffers.vertexBuffer,
                        indexBuffer = buffers.indexBuffer,
                        boundingBox = WORLD_BOX,
                        materialInstance = material,
                        apply = {
                            isShadowCaster = false
                            isShadowReceiver = false
                            isVisible = false
                            renderer.node = this
                        },
                    )
                }
            }
        }
    }
}

/** Copies one frame's ARCore point cloud into reusable arrays and feeds the accumulator. */
private class FrameScratch {
    private var ids = IntArray(INITIAL_SCRATCH_POINTS)
    private var xyzc = FloatArray(INITIAL_SCRATCH_POINTS * 4)

    fun ingest(frame: Frame, cloud: FeaturePointAccumulator, nowMs: Long) {
        // acquirePointCloud throws while ARCore has nothing for this frame (deadline, not yet
        // available); a frame without points simply adds nothing.
        runCatching {
            frame.acquirePointCloud().use { pointCloud ->
                val points = pointCloud.points
                val pointIds = pointCloud.ids
                val n = minOf(points.remaining() / 4, pointIds.remaining())
                if (n > 0) {
                    if (ids.size < n) {
                        ids = IntArray(n)
                        xyzc = FloatArray(n * 4)
                    }
                    pointIds.get(ids, 0, n)
                    points.get(xyzc, 0, n * 4)
                    cloud.ingest(ids, xyzc, n, nowMs)
                }
            }
        }
    }
}

private class PointCloudBuffers(val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer)

/**
 * Owns the sprite buffers (sized once for the accumulator's capacity) and re-uploads the
 * vertices at [REBUILD_INTERVAL_MS]. Main thread only — every call is a Filament JNI call.
 */
private class PointCloudRenderer(private val maxPoints: Int) {
    var node: MeshNodeImpl? = null
    var lastUploadMs: Long = 0L
    private var buffers: PointCloudBuffers? = null
    private val vertices =
        FloatArray(maxPoints * PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE)
    private val projection = FloatArray(16)

    fun createBuffers(engine: Engine): PointCloudBuffers {
        val strideBytes = PointCloudSprites.STRIDE * Float.SIZE_BYTES
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(maxPoints * PointCloudSprites.VERTICES_PER_POINT)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, strideBytes)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 12, strideBytes)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 28, strideBytes)
            .build(engine)
        // Zeroed vertices (radius 0) until the first upload.
        val zero = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        vertexBuffer.setBufferAt(engine, 0, zero)
        val indices = PointCloudSprites.indices(maxPoints)
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        val indexBytes = ByteBuffer.allocateDirect(indices.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        indexBytes.asIntBuffer().put(indices)
        indexBuffer.setBuffer(engine, indexBytes)
        return PointCloudBuffers(vertexBuffer, indexBuffer).also { buffers = it }
    }

    fun destroyBuffers(engine: Engine) {
        buffers?.let {
            engine.safeDestroyVertexBuffer(it.vertexBuffer)
            engine.safeDestroyIndexBuffer(it.indexBuffer)
        }
        buffers = null
        node = null
    }

    /** Vertical focal length in pixels of the AR camera for a viewport [viewHeightPx] tall. */
    fun focalPx(frame: Frame, viewHeightPx: Int): Float {
        frame.camera.getProjectionMatrix(projection, 0, NEAR_M, FAR_M)
        return projection[5] * viewHeightPx / 2f
    }

    @Suppress("LongParameterList")
    fun rebuild(
        engine: Engine,
        cloud: FeaturePointAccumulator,
        minConfidence: Float,
        nowMs: Long,
        camera: FloatArray,
        cameraForward: FloatArray,
        focalPx: Float,
        radiusPx: Float,
        palette: PointCloudSprites.Palette,
    ) {
        val node = node ?: return
        val buffers = buffers ?: return
        lastUploadMs = nowMs
        if (focalPx <= 0f) return
        val count = PointCloudSprites.write(
            cloud = cloud,
            minConfidence = minConfidence,
            nowMs = nowMs,
            camX = camera[0],
            camY = camera[1],
            camZ = camera[2],
            camForwardX = cameraForward[0],
            camForwardY = cameraForward[1],
            camForwardZ = cameraForward[2],
            focalPx = focalPx,
            radiusPx = radiusPx,
            palette = palette,
            out = vertices,
        )
        node.isVisible = count > 0
        if (count == 0) return
        // Fresh direct buffer per upload: Filament copies asynchronously, so a reused buffer
        // could be overwritten mid-copy (the PointCloudNode / DepthMeshNode #1841 rule).
        val floats = count * PointCloudSprites.VERTICES_PER_POINT * PointCloudSprites.STRIDE
        val bytes = ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        bytes.asFloatBuffer().put(vertices, 0, floats)
        buffers.vertexBuffer.setBufferAt(engine, 0, bytes, 0, floats * Float.SIZE_BYTES)
        engine.renderableManager.setGeometryAt(
            node.renderableInstance,
            0,
            RenderableManager.PrimitiveType.TRIANGLES,
            buffers.vertexBuffer,
            buffers.indexBuffer,
            0,
            count * PointCloudSprites.INDICES_PER_POINT,
        )
    }
}

private fun Color.linearRgb(): FloatArray = floatArrayOf(
    PointCloudSprites.srgbToLinear(red),
    PointCloudSprites.srgbToLinear(green),
    PointCloudSprites.srgbToLinear(blue),
)

/**
 * "All points": drops only ARCore's noisiest motion-stereo guesses. The library
 * `PointCloudNode` default is 0.2; the accumulated cloud can be a little more generous because
 * ARCore keeps refining a point's confidence as the camera keeps seeing it.
 */
private const val ALL_POINTS_THRESHOLD = 0.1f

/** "Show only confident points": the points ARCore has triangulated well. */
private const val CONFIDENT_ONLY_THRESHOLD = 0.4f

/** On-screen radius of a fully confident dot, in dp (the soft core reads at about half). */
private const val DOT_RADIUS_DP = 10f

/** Re-upload cadence of the sprite buffer — the cloud grows smoothly at 10 Hz. */
private const val REBUILD_INTERVAL_MS = 100L

private const val INITIAL_SCRATCH_POINTS = 256
private const val NEAR_M = 0.1f
private const val FAR_M = 100f
private const val DOT_MATERIAL = "materials/cosmos_dust.filamat"

/** Room-scale bounds so the cloud is never frustum-culled as a whole. */
private val WORLD_BOX = FilamentBox(0f, 0f, 0f, 100f, 100f, 100f)
