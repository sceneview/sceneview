package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.google.android.filament.ColorGrading
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.utils.KTX1Loader
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.DEFAULT_IBL_INTENSITY
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ArDebugStats
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugAnchor
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayIntro
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.DebugView
import io.github.sceneview.demo.theme.SceneViewTokens.Glass
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.node.MeshNode
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.utils.readBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The Rerun demo's in-app 3D debug view (#3950): what ARCore understood of the room — the
 * camera's path, the live frustum, the feature-point map, the planes and the anchors — drawn by
 * a second SceneView on the demo's Filament engine, from a free third-person camera. No computer
 * needed; streaming to the Rerun viewer on a computer stays the "pro" path, untouched.
 *
 * Everything here is Android glue. The data (ArDebugTrace), the geometry (ArDebugGeometry) and
 * the camera (ArDebugOrbitCamera) are pure Kotlin in demos/internal, unit-tested on the JVM.
 */

// ─── Recording ───────────────────────────────────────────────────────────────────────────────

/**
 * Copies what ARCore reports each frame into the view's [ArDebugTrace]: the display-oriented
 * camera pose every frame, the feature points at [POINTS_INTERVAL_NS], the planes at
 * [PLANES_INTERVAL_NS], and every anchor once. Main thread, from `onSessionUpdated`.
 *
 * Nothing is recorded while tracking is lost: a pose ARCore does not vouch for would draw a
 * trail jumping across the room.
 */
internal class ArDebugRecorder {
    private var lastPointsNanos = Long.MIN_VALUE
    private var lastPlanesNanos = Long.MIN_VALUE
    private val planeIds = HashMap<Plane, Int>()
    private val livePlanes = HashSet<Plane>()
    private val anchorIds = HashMap<Anchor, Int>()
    private var recordedFor: ArDebugTrace? = null

    fun record(trace: ArDebugTrace, session: Session, frame: Frame, anchors: List<Anchor>) {
        if (trace !== recordedFor) reset(trace)
        val nanos = frame.timestamp
        if (nanos <= 0L || frame.camera.trackingState != TrackingState.TRACKING) return

        trace.addPose(nanos, frame.camera.displayOrientedPose.toDebugPose())

        if (nanos - lastPointsNanos >= POINTS_INTERVAL_NS) {
            lastPointsNanos = nanos
            runCatching {
                frame.acquirePointCloud().use { cloud ->
                    val buffer = cloud.points
                    val count = buffer.remaining() / 4
                    if (count > 0) {
                        val xyz = FloatArray(count * 3)
                        val confidence = FloatArray(count)
                        for (i in 0 until count) {
                            xyz[i * 3] = buffer.get(i * 4)
                            xyz[i * 3 + 1] = buffer.get(i * 4 + 1)
                            xyz[i * 3 + 2] = buffer.get(i * 4 + 2)
                            confidence[i] = buffer.get(i * 4 + 3)
                        }
                        trace.addPoints(nanos, xyz, confidence)
                    }
                }
            }
        }

        if (nanos - lastPlanesNanos >= PLANES_INTERVAL_NS) {
            lastPlanesNanos = nanos
            recordPlanes(trace, nanos, session)
        }

        for (anchor in anchors) {
            if (anchor in anchorIds || anchor.trackingState != TrackingState.TRACKING) continue
            val id = anchorIds.size + 1
            anchorIds[anchor] = id
            trace.addAnchor(nanos, id, anchor.pose.toDebugPose())
        }
    }

    @Suppress("LoopWithTooManyJumpStatements") // guard clauses read better than nested ifs here
    private fun recordPlanes(trace: ArDebugTrace, nanos: Long, session: Session) {
        val seen = HashSet<Plane>()
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val local = plane.polygon
            val count = local.remaining() / 2
            if (count < 3) continue
            val centre = plane.centerPose
            val world = FloatArray(count * 3)
            val point = FloatArray(3)
            for (i in 0 until count) {
                point[0] = local.get(i * 2)
                point[1] = 0f
                point[2] = local.get(i * 2 + 1)
                val w = centre.transformPoint(point)
                world[i * 3] = w[0]
                world[i * 3 + 1] = w[1]
                world[i * 3 + 2] = w[2]
            }
            val id = planeIds.getOrPut(plane) { planeIds.size + 1 }
            trace.addPlane(nanos, id, plane.type.toDebugKind(), world)
            seen += plane
        }
        // A plane merged into another, or dropped by ARCore, leaves the view with it.
        for (gone in livePlanes - seen) {
            planeIds[gone]?.let { trace.addPlane(nanos, it, gone.type.toDebugKind(), FloatArray(0)) }
        }
        livePlanes.clear()
        livePlanes += seen
    }

    private fun reset(trace: ArDebugTrace) {
        recordedFor = trace
        lastPointsNanos = Long.MIN_VALUE
        lastPlanesNanos = Long.MIN_VALUE
        planeIds.clear()
        livePlanes.clear()
        anchorIds.clear()
    }

    private companion object {
        const val POINTS_INTERVAL_NS = 200_000_000L // 5 Hz: ARCore refreshes its cloud about that often
        const val PLANES_INTERVAL_NS = 500_000_000L // 2 Hz: plane polygons grow slowly
    }
}

private fun Pose.toDebugPose() = DebugPose(tx(), ty(), tz(), qx(), qy(), qz(), qw())

private fun Plane.Type.toDebugKind() = when (this) {
    Plane.Type.HORIZONTAL_UPWARD_FACING -> DebugPlaneKind.Floor
    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> DebugPlaneKind.Ceiling
    Plane.Type.VERTICAL -> DebugPlaneKind.Wall
}

// ─── Rendering ───────────────────────────────────────────────────────────────────────────────

/**
 * One layer of the view: a triangle mesh re-uploaded whenever its content changes, with the
 * PointCloudNode buffer discipline — fresh direct buffers per upload (Filament copies them
 * asynchronously), power-of-two growth, rebind before freeing the old buffers.
 */
internal class DebugLayerNode(
    engine: Engine,
    material: MaterialInstance,
    priority: Int,
    /** Carries [DebugMesh.uvs] as UV0 — the textured layers of the replay. */
    private val textured: Boolean = false,
) : MeshNode(
    engine = engine,
    primitiveType = PrimitiveType.TRIANGLES,
    vertexBuffer = createVertexBuffer(engine, INITIAL_CAPACITY, textured),
    indexBuffer = createIndexBuffer(engine, INITIAL_CAPACITY),
    // The session is room-sized but unbounded; a generous fixed box keeps culling cheap and
    // never clips a layer, where a per-upload AABB would cost a pass over every vertex.
    boundingBox = com.google.android.filament.Box(
        0f, 0f, 0f,
        WORLD_HALF_EXTENT_M, WORLD_HALF_EXTENT_M, WORLD_HALF_EXTENT_M,
    ),
    materialInstance = material,
    builder = {
        priority(priority)
        castShadows(false)
        receiveShadows(false)
    },
) {
    private var ownedVertexBuffer: VertexBuffer = vertexBuffer
    private var ownedIndexBuffer: IndexBuffer = indexBuffer
    private var vertexCapacity = INITIAL_CAPACITY
    private var indexCapacity = INITIAL_CAPACITY

    init {
        // The buffers are uninitialised until the first upload: draw one degenerate triangle.
        upload(null)
    }

    /** Replaces the layer's triangles with [mesh]'s; `null` or empty draws nothing. */
    fun upload(mesh: DebugMesh?) {
        val empty = mesh == null || mesh.isEmpty
        val vertexCount = if (empty) 1 else mesh!!.vertexCount
        val indexCount = if (empty) 3 else mesh!!.indexCount

        val newVertexBuffer =
            if (vertexCount > vertexCapacity) {
                createVertexBuffer(engine, nextPowerOfTwo(vertexCount), textured)
            } else {
                null
            }
        val newIndexBuffer =
            if (indexCount > indexCapacity) createIndexBuffer(engine, nextPowerOfTwo(indexCount)) else null
        val vertexTarget = newVertexBuffer ?: ownedVertexBuffer
        val indexTarget = newIndexBuffer ?: ownedIndexBuffer
        try {
            val vertexBytes = ByteBuffer.allocateDirect(vertexCount * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            if (empty) vertexBytes.asFloatBuffer().put(floatArrayOf(0f, 0f, 0f))
            else vertexBytes.asFloatBuffer().put(mesh!!.positions, 0, vertexCount * 3)
            vertexTarget.setBufferAt(engine, 0, vertexBytes, 0, vertexCount * 3 * Float.SIZE_BYTES)
            if (textured) {
                val uvBytes = ByteBuffer.allocateDirect(vertexCount * 2 * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                if (empty) uvBytes.asFloatBuffer().put(floatArrayOf(0f, 0f))
                else uvBytes.asFloatBuffer().put(mesh!!.uvs, 0, vertexCount * 2)
                vertexTarget.setBufferAt(engine, 1, uvBytes, 0, vertexCount * 2 * Float.SIZE_BYTES)
            }

            val indexBytes = ByteBuffer.allocateDirect(indexCount * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            if (empty) indexBytes.asIntBuffer().put(intArrayOf(0, 0, 0))
            else indexBytes.asIntBuffer().put(mesh!!.indices, 0, indexCount)
            indexTarget.setBuffer(engine, indexBytes, 0, indexCount * Int.SIZE_BYTES)

            renderableManager.setGeometryAt(
                renderableInstance, 0, PrimitiveType.TRIANGLES, vertexTarget, indexTarget, 0, indexCount,
            )
        } catch (t: Throwable) {
            newVertexBuffer?.let { engine.safeDestroyVertexBuffer(it) }
            newIndexBuffer?.let { engine.safeDestroyIndexBuffer(it) }
            throw t
        }
        if (newVertexBuffer != null) {
            engine.safeDestroyVertexBuffer(ownedVertexBuffer)
            ownedVertexBuffer = newVertexBuffer
            vertexCapacity = newVertexBuffer.vertexCount
        }
        if (newIndexBuffer != null) {
            engine.safeDestroyIndexBuffer(ownedIndexBuffer)
            ownedIndexBuffer = newIndexBuffer
            indexCapacity = newIndexBuffer.indexCount
        }
    }

    override fun destroy() {
        if (isDestroyed) return
        // The renderable goes before the buffers it references.
        super.destroy()
        engine.safeDestroyVertexBuffer(ownedVertexBuffer)
        engine.safeDestroyIndexBuffer(ownedIndexBuffer)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val WORLD_HALF_EXTENT_M = 500f

        fun nextPowerOfTwo(value: Int): Int = Integer.highestOneBit((value - 1).coerceAtLeast(1)) shl 1

        fun createVertexBuffer(engine: Engine, count: Int, textured: Boolean): VertexBuffer = VertexBuffer.Builder()
            .bufferCount(if (textured) 2 else 1)
            .vertexCount(count)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
            .apply { if (textured) attribute(VertexAttribute.UV0, 1, AttributeType.FLOAT2) }
            .build(engine)

        fun createIndexBuffer(engine: Engine, count: Int): IndexBuffer = IndexBuffer.Builder()
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .indexCount(count)
            .build(engine)
    }
}

/** Colour, glow and draw priority of each layer — all from [SceneViewTokens.DebugView]. */
private class LayerPaint(val color: Color, val glow: Float = 1f, val priority: Int = 4)

private fun paintOf(layer: DebugLayer): LayerPaint = when (layer) {
    // Priority 1, not 0: the replay's photo floor (priority 0) goes under the grid.
    DebugLayer.GridMinor -> LayerPaint(DebugView.gridMinor, priority = 1)
    DebugLayer.GridMajor -> LayerPaint(DebugView.gridMajor, priority = 1)
    DebugLayer.AxisX -> LayerPaint(DebugView.axisX)
    DebugLayer.AxisY -> LayerPaint(DebugView.axisY)
    DebugLayer.AxisZ -> LayerPaint(DebugView.axisZ)
    DebugLayer.PlaneFloor -> LayerPaint(DebugView.floorFill, priority = 1)
    DebugLayer.PlaneWall -> LayerPaint(DebugView.wallFill, priority = 1)
    DebugLayer.PlaneOther -> LayerPaint(DebugView.otherFill, priority = 1)
    DebugLayer.OutlineFloor -> LayerPaint(DebugView.floorOutline, priority = 2)
    DebugLayer.OutlineWall -> LayerPaint(DebugView.wallOutline, priority = 2)
    DebugLayer.OutlineOther -> LayerPaint(DebugView.otherOutline, priority = 2)
    DebugLayer.MapPoints -> LayerPaint(DebugView.mapPoint, priority = 3)
    DebugLayer.LivePoints -> LayerPaint(DebugView.livePoint, glow = DebugView.livePointGlow, priority = 6)
    DebugLayer.TrailHead -> LayerPaint(DebugView.trailNew, glow = DebugView.trailHeadGlow)
    DebugLayer.Keyframes -> LayerPaint(DebugView.keyframe, priority = 3)
    DebugLayer.Frustum -> LayerPaint(DebugView.frustum, glow = DebugView.frustumGlow)
    DebugLayer.FrustumFace -> LayerPaint(DebugView.frustumFace, priority = 5)
    DebugLayer.Anchors -> LayerPaint(DebugView.anchor, glow = DebugView.anchorGlow)
    else -> {
        // Trail0 … Trail7: the brand ramp, oldest to newest, through tint-soft.
        val f = DebugLayer.trailSteps.indexOf(layer).toFloat() / (DebugLayer.trailSteps.size - 1)
        val color = if (f < 0.5f) lerp(DebugView.trailOld, DebugView.trailMid, f * 2f)
        else lerp(DebugView.trailMid, DebugView.trailNew, (f - 0.5f) * 2f)
        LayerPaint(color)
    }
}

private fun MaterialLoader.createLayerMaterial(paint: LayerPaint): MaterialInstance =
    createUnlitColorInstance(paint.color).apply {
        // The opaque unlit material culls back faces; ribbons and fans are seen from both sides.
        if (paint.color.alpha >= 1f) setCullingMode(Material.CullingMode.NONE)
        if (paint.glow != 1f) {
            // Past 1.0 in linear light, so the bloom pass (threshold 1.0) lifts it.
            val linear = colorOf(paint.color).toLinearSpace()
            setParameter(
                "color", Colors.RgbaType.LINEAR,
                linear.x * paint.glow, linear.y * paint.glow, linear.z * paint.glow, linear.w,
            )
        }
    }

/** The parts rebuilt independently, each when its own inputs change. */
private enum class Part(val layers: List<DebugLayer>, val group: DebugGroup) {
    Stage(
        listOf(DebugLayer.GridMinor, DebugLayer.GridMajor, DebugLayer.AxisX, DebugLayer.AxisY, DebugLayer.AxisZ),
        DebugGroup.Stage,
    ),
    Planes(DebugLayer.entries.filter { it.group == DebugGroup.Planes }, DebugGroup.Planes),
    Map(listOf(DebugLayer.MapPoints), DebugGroup.Points),
    Live(listOf(DebugLayer.LivePoints), DebugGroup.Points),
    Trail(DebugLayer.trailSteps + DebugLayer.TrailHead, DebugGroup.Trail),
    Camera(listOf(DebugLayer.Keyframes, DebugLayer.Frustum, DebugLayer.FrustumFace), DebugGroup.Trail),
    Anchors(listOf(DebugLayer.Anchors), DebugGroup.Anchors),
}

/**
 * Keeps the layer nodes in step with an [ArDebugFrame]: each [Part] is rebuilt only when its
 * key — the inputs its geometry depends on — changes, so a steady view uploads nothing.
 */
private class ArDebugLayers(engine: Engine, materials: Map<DebugLayer, MaterialInstance>) {
    val nodes: Map<DebugLayer, DebugLayerNode> = DebugLayer.entries.associateWith { layer ->
        DebugLayerNode(engine, materials.getValue(layer), paintOf(layer).priority)
    }
    private val meshes = DebugLayer.entries.associateWith { DebugMesh() }
    private val keys = HashMap<Part, Any?>()
    private val out: (DebugLayer) -> DebugMesh = { meshes.getValue(it) }

    @Suppress("LoopWithTooManyJumpStatements") // hidden and unchanged parts skip early
    fun sync(
        frame: ArDebugFrame,
        style: ArDebugStyle,
        pointStyle: ArDebugStyle,
        stageBounds: FloatArray,
        floorY: Float,
        visible: (DebugGroup) -> Boolean,
        replay: ReplayLayers? = null,
    ) {
        for (part in Part.entries) {
            val style = if (part == Part.Map || part == Part.Live) pointStyle else style
            // The replay draws its map points in their photo colours, on its own layer.
            val shown = visible(part.group) && !(part == Part.Map && replay != null)
            part.layers.forEach { nodes.getValue(it).isVisible = shown }
            if (!shown) {
                keys.remove(part) // rebuilt when shown again
                continue
            }
            val key = keyOf(part, frame, style, stageBounds, floorY)
            if (keys.containsKey(part) && keys[part] == key) continue
            keys[part] = key
            part.layers.forEach { meshes.getValue(it).clear() }
            when (part) {
                Part.Stage -> ArDebugGeometry.buildStage(stageBounds, floorY, style, out)
                Part.Planes -> ArDebugGeometry.buildPlanes(frame.planes, style, out) { replay?.isTextured(it) == true }
                Part.Map -> ArDebugGeometry.buildMapPoints(frame.mapPoints, style, out(DebugLayer.MapPoints))
                Part.Live -> ArDebugGeometry.buildLivePoints(frame.livePoints, style, out(DebugLayer.LivePoints))
                Part.Trail -> ArDebugGeometry.buildTrail(frame.trail, style, out)
                Part.Camera -> if (replay == null) {
                    ArDebugGeometry.buildCamera(frame, style, out)
                } else {
                    ArDebugGeometry.buildCamera(
                        frame, style, out,
                        lens = replay.lens,
                        depth = ReplayGeometry.FRUSTUM_DEPTH,
                        keyframeDepth = ReplayGeometry.KEYFRAME_DEPTH,
                        face = false,
                    )
                }
                Part.Anchors -> ArDebugGeometry.buildAnchors(frame.anchors, style, out(DebugLayer.Anchors))
            }
            part.layers.forEach { nodes.getValue(it).upload(meshes.getValue(it)) }
        }
    }

    /** Forget every key: the next [sync] rebuilds everything (a new trace). */
    fun invalidate() = keys.clear()

    private fun keyOf(
        part: Part,
        frame: ArDebugFrame,
        style: ArDebugStyle,
        stageBounds: FloatArray,
        floorY: Float,
    ): Any =
        when (part) {
            Part.Stage -> listOf(stageBounds.toList(), floorY, style)
            Part.Planes -> listOf(frame.planes.map { System.identityHashCode(it) }, style)
            Part.Map -> listOf(frame.mapPointCount, style)
            Part.Live -> listOf(frame.liveKey, frame.livePoints.size, style)
            Part.Trail -> listOf(frame.trailLength, style)
            Part.Camera -> listOf(frame.camera, frame.keyframes.size, style)
            Part.Anchors -> listOf(frame.anchors, style)
        }
}

/** Grid extent for [frame]: the content bounds snapped to the grid, so it never crawls. */
private fun stageBoundsOf(frame: ArDebugFrame): FloatArray {
    val bounds = ArDebugGeometry.contentBounds(frame) ?: floatArrayOf(-1f, 0f, -2f, 1f, 0f, 0.5f)
    val cell = ArDebugGeometry.GRID_CELL_M
    return floatArrayOf(
        floor(bounds[0] / cell) * cell, 0f, floor(bounds[2] / cell) * cell,
        ceil(bounds[3] / cell) * cell, 0f, ceil(bounds[5] / cell) * cell,
    )
}

/**
 * The 3D debug view: a second SceneView on the demo's [engine], drawing [session] from [orbit].
 *
 * [compact] is the picture-in-picture: a lower frame rate, and no touch (the card over it takes
 * the tap that opens the full view).
 */
@Composable
internal fun ArDebugSceneView(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    replay: RerunReplayMedia? = null,
) {
    val context = LocalContext.current

    // Created before the SceneView so they are released after it (Compose forgets in reverse).
    val materials = remember(materialLoader) {
        DebugLayer.entries.associateWith { materialLoader.createLayerMaterial(paintOf(it)) }
    }
    DisposableEffect(materials) {
        onDispose { materials.values.forEach { materialLoader.destroyMaterialInstance(it) } }
    }
    // Linear tone mapping: the unlit layers show their token colours exactly, and the stage
    // skybox is exactly `Stage.background`. Values past 1.0 still bloom (bloom runs before it).
    val colorGrading = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    }
    DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
    val environment = rememberEnvironment(engine) {
        val stage = colorOf(SceneViewTokens.Stage.background).toLinearSpace()
        Environment(
            // The placed models are lit; the debug layers are unlit and ignore it.
            indirectLight = KTX1Loader.createIndirectLight(
                engine,
                context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
            ).indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
            skybox = Skybox.Builder().color(stage.x, stage.y, stage.z, 1f).build(engine),
        )
    }
    val view = rememberView(engine)
    val renderer = rememberRenderer(engine)

    val layers = remember(engine, materials) { ArDebugLayers(engine, materials) }
    // The replay's textured layers: created before the SceneView, released after its nodes.
    val replayLayers = remember(engine, materialLoader, replay) {
        replay?.let { ReplayLayers(engine, materialLoader, it) }
    }
    DisposableEffect(replayLayers) { onDispose { replayLayers?.destroy() } }
    var anchors by remember { mutableStateOf(emptyList<DebugAnchor>()) }
    val clock = remember { FrameClock() }

    // The stage colour behind the view: a TextureView stays transparent until its first frame,
    // which would show the AR camera through the "3D view" for as long as the engine takes.
    Box(modifier.background(SceneViewTokens.Stage.background)) {
        SceneView(
            modifier = Modifier.matchParentSize(),
            // A TextureView composes with the chrome and the AR SurfaceView under it.
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            view = view,
            renderer = renderer,
            isOpaque = true,
            frameRatePolicy = FrameRatePolicy.Continuous(maxFps = if (compact) PIP_FPS else null),
            autoCenterContent = false,
            environment = environment,
            cameraManipulator = orbit,
            onFrame = { frameTimeNanos ->
                val dt = clock.tick(frameTimeNanos)
                if (!clock.configured) {
                    clock.configured = true
                    view.configureForDebug(colorGrading)
                }
                session.tick(dt)

                val trace = session.trace
                if (trace !== clock.trace) {
                    clock.trace = trace
                    clock.frame = null
                    layers.invalidate()
                }
                val time = session.time
                val frame = clock.frame?.takeIf {
                    trace.version == clock.version && time == it.time ||
                        frameTimeNanos - clock.frameAtNanos < FRAME_INTERVAL_NS
                } ?: trace.frameAt(time).also {
                    clock.frame = it
                    clock.version = trace.version
                    clock.frameAtNanos = frameTimeNanos
                }

                if (session.fpsMeter.tick(frameTimeNanos)) session.fps = session.fpsMeter.fps

                // A recording is framed whole from its first frame — the camera and the grid hold
                // still while it plays — where a live session is framed as it grows.
                val whole = if (replay != null) {
                    clock.wholeFrame?.takeIf { clock.wholeFor === trace }
                        ?: trace.frameAt(trace.duration).also {
                            clock.wholeFrame = it
                            clock.wholeFor = trace
                        }
                } else {
                    frame
                }
                val bounds = ArDebugGeometry.contentBounds(whole)
                val home = ArDebugFraming.home(
                    bounds, orbit.home.azimuthDegrees, orbit.verticalFovDegrees, orbit.aspect,
                    elevationDegrees = orbit.homeElevation,
                )
                if (orbit.following) orbit.home = home
                if (!orbit.hasFramedContent && bounds != null) {
                    orbit.hasFramedContent = true
                    val intro = replay != null && orbit.drift
                    if (intro) orbit.playIntro(ReplayIntro.startFor(home)) else orbit.snapTo(home)
                }

                val style = ArDebugStyle.forOrbit(orbit.pose.distance, orbit.verticalFovDegrees, orbit.viewportHeight)
                val floorY = (ArDebugGeometry.floorHeight(whole) * 100f).roundToInt() / 100f
                // The picture-in-picture packs the room into a few hundred pixels: full-size points
                // would read as noise there, so they shrink while lines keep their weight.
                val pointStyle = if (compact) ArDebugStyle(style.metresPerPixel * PIP_POINT_SCALE) else style
                layers.sync(frame, style, pointStyle, stageBoundsOf(whole), floorY, session::isVisible, replayLayers)
                replayLayers?.sync(
                    frame, pointStyle, floorY,
                    ReplayVisibility(
                        planes = session.isVisible(DebugGroup.Planes),
                        points = session.isVisible(DebugGroup.Points),
                        anchors = session.isVisible(DebugGroup.Anchors),
                        trail = session.isVisible(DebugGroup.Trail),
                    ),
                )

                if (frame.anchors != anchors) anchors = frame.anchors
                if (frameTimeNanos - clock.statsAtNanos >= STATS_INTERVAL_NS) {
                    clock.statsAtNanos = frameTimeNanos
                    session.stats = ArDebugStats.of(frame, trace.duration)
                }
            },
        ) {
            // The layer nodes hang off one plain node, and are destroyed with it.
            Node(
                apply = {
                    layers.nodes.values.forEach { addChildNode(it) }
                    replayLayers?.nodes?.forEach { addChildNode(it) }
                },
            )
            if (session.isVisible(DebugGroup.Anchors)) {
                anchors.forEach { anchor ->
                    // One instance per anchor: a Filament model instance can only hang off one node.
                    key(anchor.id) {
                        val dog = rememberModelInstance(modelLoader, "models/shiba.glb")
                        Node(
                            position = Position(anchor.pose.x, anchor.pose.y, anchor.pose.z),
                            apply = {
                                quaternion = Quaternion(anchor.pose.qx, anchor.pose.qy, anchor.pose.qz, anchor.pose.qw)
                            },
                        ) {
                            dog?.let { ModelNode(modelInstance = it, scaleToUnits = ANCHOR_MODEL_SIZE_M) }
                        }
                    }
                }
            }
        }
    }
}

/** Per-view frame bookkeeping, deliberately not Compose state: it changes every frame. */
private class FrameClock {
    var lastNanos = 0L
    var configured = false
    var trace: ArDebugTrace? = null
    var frame: ArDebugFrame? = null
    var version = -1
    var frameAtNanos = 0L
    var statsAtNanos = 0L
    var wholeFrame: ArDebugFrame? = null
    var wholeFor: ArDebugTrace? = null

    fun tick(nanos: Long): Float {
        val dt = if (lastNanos == 0L) 0f else ((nanos - lastNanos) / 1e9f)
        lastNanos = nanos
        return dt
    }
}

/**
 * After the RenderQuality preset: bloom on bright values only (threshold 1.0 — just the layers
 * with a glow), 4× MSAA for the thin lines, no SSAO or shadows (nothing here is lit but the
 * placed models, and there is no ground to receive a shadow).
 */
private fun com.google.android.filament.View.configureForDebug(colorGrading: ColorGrading) {
    this.colorGrading = colorGrading
    bloomOptions = bloomOptions.apply {
        enabled = true
        threshold = true
        strength = BLOOM_STRENGTH
        levels = 7
        lensFlare = false
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = true
        sampleCount = 4
    }
    ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = false }
    setShadowingEnabled(false)
}

private const val PIP_FPS = 30
private const val PIP_POINT_SCALE = 0.55f
private const val FRAME_INTERVAL_NS = 50_000_000L // rebuild the frame at most at 20 Hz
private const val STATS_INTERVAL_NS = 250_000_000L
private const val BLOOM_STRENGTH = 0.28f
private const val ANCHOR_MODEL_SIZE_M = 0.3f

// ─── Chrome ──────────────────────────────────────────────────────────────────────────────────

/**
 * The picture-in-picture over the camera: the live 3D view in a portrait glass card. A tap
 * opens the full view; the view itself takes no touch here, so the tap is never an orbit.
 */
@Composable
internal fun ArDebugPip(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    replay: RerunReplayMedia? = null,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Box(
        modifier = modifier
            .size(DebugView.pipWidth, DebugView.pipHeight)
            .shadow(elevation = SceneViewTokens.Elevation.md, shape = shape, clip = false)
            .clip(shape)
            .background(SceneViewTokens.Stage.background)
            .testTag(AR_DEBUG_PIP_TAG),
    ) {
        ArDebugSceneView(
            session = session,
            orbit = orbit,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            modifier = Modifier.fillMaxSize(),
            compact = true,
            replay = replay,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .overMediaEdge(shape)
                .clickable(role = Role.Button, onClick = onExpand)
                .semantics { contentDescription = "Open the 3D view" },
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Space.sm)
                .background(ArOverlay.scrimDark, CircleShape)
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("3D", style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrim))
            Spacer(Modifier.width(Space.xs))
            Icon(
                Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = ArOverlay.onScrim,
                modifier = Modifier.size(Space.md - Space.xs / 2),
            )
        }
    }
}

/**
 * The layer toggles of the full view — the Rerun viewer's entity list, as a 2×2 grid of equal
 * pills: a colour dot (the layer's own colour), a name, and what it holds. A grid rather than a
 * row: four labelled counts do not fit a phone's width, and a scrolling row hid the last one.
 */
@Composable
internal fun ArDebugLegend(session: ArDebugSession, modifier: Modifier = Modifier) {
    val stats = session.stats
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md)
            .widthIn(max = ArOverlay.maxWidth)
            .testTag(AR_DEBUG_LEGEND_TAG),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val path = ArDebugFormat.distance(stats.pathMetres)
            LegendChip("Path", path, DebugView.trailNew, session, DebugGroup.Trail, Modifier.weight(1f))
            val points = ArDebugFormat.count(stats.mapPoints)
            LegendChip("Points", points, DebugView.livePoint, session, DebugGroup.Points, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val planes = ArDebugFormat.count(stats.planes)
            LegendChip("Planes", planes, DebugView.floorOutline, session, DebugGroup.Planes, Modifier.weight(1f))
            val anchors = ArDebugFormat.count(stats.anchors)
            LegendChip("Anchors", anchors, DebugView.anchor, session, DebugGroup.Anchors, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegendChip(
    label: String,
    value: String,
    dot: Color,
    session: ArDebugSession,
    group: DebugGroup,
    modifier: Modifier = Modifier,
) {
    val on = session.isVisible(group)
    val shape = CircleShape
    Row(
        modifier = modifier
            .height(Glass.pillHeight)
            .clip(shape)
            .background(if (on) ArOverlay.scrimDark else Glass.surface, shape)
            .overMediaEdge(shape)
            .clickable(role = Role.Switch) { session.toggle(group) }
            .semantics { stateDescription = if (on) "Shown" else "Hidden" }
            .padding(horizontal = Glass.pillPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Space.sm + Space.xs / 2)
                .background(if (on) dot else ArOverlay.meterTrack, CircleShape),
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            label,
            style = SceneViewTokens.Type.caption.copy(color = if (on) ArOverlay.onScrim else ArOverlay.onScrimMuted),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.xs + Space.xs / 2))
        Text(
            value,
            style = SceneViewTokens.Type.caption.copy(
                color = ArOverlay.onScrimMuted,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
        )
    }
}

/**
 * The timeline of the full view: play/pause, the time, a scrubber over the whole session, its
 * length, and the Live chip. One caption under it says how to move the camera — the gestures
 * are not discoverable otherwise.
 */
@Composable
internal fun ArDebugTimelineCard(session: ArDebugSession) {
    val stats = session.stats
    val duration = stats.duration
    val time = if (session.live) duration else session.cursor
    OverlayCard(testTag = AR_DEBUG_TIMELINE_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = session::togglePlay,
                enabled = duration > 0f,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget),
            ) {
                val playing = session.playing && !session.live
                Icon(
                    if (playing || session.live) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing || session.live) "Pause" else "Play",
                    tint = ArOverlay.onScrim,
                )
            }
            Text(ArDebugFormat.clock(time), style = ClockStyle)
            Slider(
                value = if (duration > 0f) (time / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { session.scrubTo(it * duration) },
                enabled = duration > 0f,
                colors = SliderDefaults.colors(
                    thumbColor = ArOverlay.onScrim,
                    activeTrackColor = ArOverlay.accentProgress,
                    inactiveTrackColor = ArOverlay.meterTrack,
                    disabledThumbColor = ArOverlay.onScrimMuted,
                    disabledInactiveTrackColor = ArOverlay.meterTrack,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.sm)
                    .semantics { contentDescription = "Session timeline" },
            )
            Text(ArDebugFormat.clock(duration), style = ClockStyle)
            Spacer(Modifier.width(Space.sm))
            LiveChip(live = session.live, onClick = session::goLive)
        }
        Text(
            text = if (duration > 0f || stats.tracking) {
                "Drag to orbit · pinch to zoom · double-tap to recenter"
            } else {
                "Nothing mapped yet — move the phone slowly around the room."
            },
            style = OnScrimCaption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ClockStyle @Composable get() =
    SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrimMuted, fontFamily = FontFamily.Monospace)

@Composable
private fun LiveChip(live: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (live) ArOverlay.accentSuccess.copy(alpha = LIVE_FILL_ALPHA) else ArOverlay.meterTrack,
                shape,
            )
            .clickable(enabled = !live, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.sm + Space.xs / 2, vertical = Space.xs + Space.xs / 2)
            .testTag(AR_DEBUG_LIVE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = if (live) ArOverlay.accentSuccess else ArOverlay.onScrimMuted
        Box(Modifier.size(Space.sm).background(dot, CircleShape))
        Spacer(Modifier.width(Space.xs + Space.xs / 2))
        val label = if (live) ArOverlay.onScrim else ArOverlay.onScrimMuted
        Text("Live", style = SceneViewTokens.Type.caption.copy(color = label))
    }
}

private const val LIVE_FILL_ALPHA = 0.22f

internal const val AR_DEBUG_PIP_TAG = "ar_debug_pip"
internal const val AR_DEBUG_LEGEND_TAG = "ar_debug_legend"
internal const val AR_DEBUG_TIMELINE_TAG = "ar_debug_timeline"
internal const val AR_DEBUG_LIVE_TAG = "ar_debug_live"
