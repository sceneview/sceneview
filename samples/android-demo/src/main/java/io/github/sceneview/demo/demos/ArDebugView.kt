package io.github.sceneview.demo.demos

import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.PlaneWallSource
import io.github.sceneview.demo.demos.internal.livePlanesOf
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.OrbitLimits
import io.github.sceneview.demo.demos.internal.DebugAnchor
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.OneUploadInFlight
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.IntervalGate
import io.github.sceneview.demo.demos.internal.OrbitPose
import io.github.sceneview.demo.demos.internal.CameraRig
import io.github.sceneview.demo.demos.internal.PlaneLayering
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayIntro
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.scanSummary
import io.github.sceneview.demo.demos.internal.ScanPlanes
import io.github.sceneview.demo.demos.internal.ScanFollow
import io.github.sceneview.demo.demos.internal.ScanLens
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.DebugView
import io.github.sceneview.demo.theme.SceneViewTokens.Glass
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.math.toTransform
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.MeshNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyEnvironment
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.utils.readBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
 * trail jumping across the room. A room scan (Record mode, [ScanCapture]) rides on the same
 * pass: it adds the points' colours and the photos.
 */
internal class ArDebugRecorder {
    // Gates, not `Long.MIN_VALUE` timestamps: `now - Long.MIN_VALUE` overflows negative, and a
    // real-device scan recorded no point and no plane at all (#4095).
    private val pointsGate = IntervalGate(POINTS_INTERVAL_NS)
    private val planesGate = IntervalGate(PLANES_INTERVAL_NS)
    private val planeIds = HashMap<Plane, Int>()
    private val livePlanes = HashSet<Plane>()
    private val anchorIds = HashMap<Anchor, Int>()
    private var recordedFor: ArDebugTrace? = null

    /**
     * Records [frame] into [trace]. With a [scan] recording into that same trace, the new feature
     * points also take their colour from the frame's camera image, and the frame gives the scan a
     * photo when the camera has moved enough since the last one.
     */
    fun record(
        trace: ArDebugTrace,
        session: Session,
        frame: Frame,
        anchors: List<Anchor>,
        scan: ScanCapture? = null,
    ) {
        if (trace !== recordedFor) reset(trace)
        val nanos = frame.timestamp
        if (nanos <= 0L || frame.camera.trackingState != TrackingState.TRACKING) return

        val display = frame.camera.displayOrientedPose.toDebugPose()
        trace.addPose(nanos, display)

        val capture = scan?.takeIf { it.trace === trace }
        capture?.recordDepthStats(nanos)
        val pointsDue = pointsGate.isDue(nanos)
        val photoDue = capture?.wantsPhoto(nanos, display) == true
        // A new raw-depth image (Rerun v2 dense map), when the scan has depth and is free to fuse.
        val depth = capture?.acquireDepth(frame)
        // One camera image per frame at most, shared by the colours, the photo and the depth.
        val needsImage = pointsDue || photoDue || depth != null
        val image = if (needsImage) capture?.acquire(frame) else null
        try {
            if (pointsDue) {
                pointsGate.mark(nanos)
                recordPoints(trace, nanos, frame, capture, image)
            }
            if (capture != null && photoDue && image != null) capture.takePhoto(nanos, image, display)
            if (capture != null && depth != null) capture.fuseDepth(depth, image)
        } finally {
            image?.close()
            depth?.close()
        }

        if (planesGate.isDue(nanos)) {
            planesGate.mark(nanos)
            recordPlanes(trace, nanos, session)
        }

        for (anchor in anchors) {
            if (anchor in anchorIds || anchor.trackingState != TrackingState.TRACKING) continue
            val id = anchorIds.size + 1
            anchorIds[anchor] = id
            trace.addAnchor(nanos, id, anchor.pose.toDebugPose())
        }
    }

    /** ARCore's feature points, coloured from [image] when a [scan] records. */
    private fun recordPoints(trace: ArDebugTrace, nanos: Long, frame: Frame, scan: ScanCapture?, image: ScanImage?) {
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
                    val colors = if (scan != null && image != null) scan.colors(image, xyz) else null
                    trace.addPoints(nanos, xyz, confidence, colors)
                }
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements") // guard clauses read better than nested ifs here
    private fun recordPlanes(trace: ArDebugTrace, nanos: Long, session: Session) {
        val seen = HashSet<Plane>()
        val merged = HashSet<Plane>()
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.subsumedBy != null) merged += plane
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
        // A plane merged into another, or dropped by ARCore, leaves the view with it — but not
        // every plane at once: that is a tracking reset, and the room keeps its surfaces.
        for (gone in ScanPlanes.removed(livePlanes, seen, merged)) {
            planeIds[gone]?.let { trace.addPlane(nanos, it, gone.type.toDebugKind(), FloatArray(0)) }
        }
        livePlanes.clear()
        livePlanes += seen
    }

    private fun reset(trace: ArDebugTrace) {
        recordedFor = trace
        pointsGate.reset()
        planesGate.reset()
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
    /**
     * Also carries CUSTOM0, each corner minus its surfel's centre, given to [upload]: the dense
     * layer drawn through `scan_surfel.mat`, which clamps a surfel's size on screen (#4742).
     */
    private val surfels: Boolean = false,
) : MeshNode(
    engine = engine,
    primitiveType = PrimitiveType.TRIANGLES,
    vertexBuffer = createVertexBuffer(engine, INITIAL_CAPACITY, textured, surfels),
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
    private var uploadedIndices = 3
    private var shownIndices = 3

    /** What [showIndices] last asked for, re-applied when a deferred upload lands; `-1`: all. */
    private var wantedIndices = -1

    /**
     * One upload in flight at a time (#4750): an [upload] asked for while Filament still holds the
     * previous one's buffers is copied into [pendingMesh] / [pendingOffsets] and goes out from the
     * release callback. However long the renderer stops consuming, this layer pins one upload.
     */
    private val flight = OneUploadInFlight()
    private val pendingMesh = DebugMesh(0)
    private var pendingEmpty = true
    private var pendingOffsets: FloatArray? = null

    /** The array [pendingOffsets] is copied into, kept so each deferred upload does not allocate one. */
    private var offsetSpare: FloatArray? = null

    /**
     * CUSTOM0 staging for a [surfels] layer, reused across uploads: a live room is megabytes of
     * offsets per snapshot. Filament reads it asynchronously; [flight] guarantees the previous
     * upload let go of it before it is refilled.
     */
    private var offsetScratch: ByteBuffer? = null

    init {
        // The buffers are uninitialised until the first upload: draw one degenerate triangle.
        // Not tracked: it is one upload, and the first real one should not wait a frame on it.
        write(null, null, tracked = false)
    }

    /**
     * Replaces the layer's triangles with [mesh]'s; `null` or empty draws nothing. A [surfels]
     * layer takes [offsets] too, xyz per vertex (`DenseSurfels.cornerOffsets`); none draws every
     * surfel at its world size.
     *
     * While the previous upload is still in Filament's hands, [mesh] and [offsets] are copied and
     * uploaded once it lets go — the caller may reuse both at once. Only the latest such call wins.
     */
    fun upload(mesh: DebugMesh?, offsets: FloatArray? = null) {
        wantedIndices = -1
        if (flight.request()) {
            write(mesh, offsets, tracked = true)
            return
        }
        val empty = mesh == null || mesh.isEmpty
        pendingEmpty = empty
        if (empty) {
            pendingMesh.clear()
            pendingOffsets = null
        } else {
            pendingMesh.set(mesh!!)
            pendingOffsets = offsets?.let { source ->
                val size = minOf(source.size, mesh.vertexCount * 3)
                // Reuse the held copy: a surfel layer's offsets run to megabytes per frame.
                val target = offsetSpare?.takeIf { it.size >= size } ?: FloatArray(size)
                offsetSpare = target
                source.copyInto(target, 0, 0, size)
                target
            }
        }
    }

    /** Filament let go of one buffer of upload [id]: on the main thread, via [mainHandler]. */
    private fun onReleased(id: Int) {
        if (!flight.release(id) || isDestroyed) return
        val offsets = pendingOffsets
        pendingOffsets = null
        write(pendingMesh.takeUnless { pendingEmpty }, offsets, tracked = true)
        if (wantedIndices >= 0) applyShownIndices()
        // Left from a callback, not from a frame of the scene: ask for the frame that shows it.
        requestRender()
    }

    private fun write(mesh: DebugMesh?, offsets: FloatArray?, tracked: Boolean) {
        val empty = mesh == null || mesh.isEmpty
        val vertexCount = if (empty) 1 else mesh!!.vertexCount
        val indexCount = if (empty) 3 else mesh!!.indexCount

        val newVertexBuffer =
            if (vertexCount > vertexCapacity) {
                createVertexBuffer(engine, nextPowerOfTwo(vertexCount), textured, surfels)
            } else {
                null
            }
        val newIndexBuffer =
            if (indexCount > indexCapacity) createIndexBuffer(engine, nextPowerOfTwo(indexCount)) else null
        val vertexTarget = newVertexBuffer ?: ownedVertexBuffer
        val indexTarget = newIndexBuffer ?: ownedIndexBuffer
        // Every buffer handed over below reports back once: the gate reopens on the last one.
        val id = if (tracked) flight.begin(1 + (if (textured) 1 else 0) + (if (surfels) 1 else 0) + 1) else 0
        val handler = if (tracked) mainHandler else null
        val released = if (tracked) Runnable { onReleased(id) } else null
        try {
            val vertexBytes = ByteBuffer.allocateDirect(vertexCount * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            if (empty) vertexBytes.asFloatBuffer().put(floatArrayOf(0f, 0f, 0f))
            else vertexBytes.asFloatBuffer().put(mesh!!.positions, 0, vertexCount * 3)
            vertexTarget.setBufferAt(engine, 0, vertexBytes, 0, vertexCount * 3 * Float.SIZE_BYTES, handler, released)
            if (textured) {
                val uvBytes = ByteBuffer.allocateDirect(vertexCount * 2 * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                if (empty) uvBytes.asFloatBuffer().put(floatArrayOf(0f, 0f))
                else uvBytes.asFloatBuffer().put(mesh!!.uvs, 0, vertexCount * 2)
                vertexTarget.setBufferAt(engine, 1, uvBytes, 0, vertexCount * 2 * Float.SIZE_BYTES, handler, released)
            }
            if (surfels) {
                val bytes = vertexCount * 3 * Float.SIZE_BYTES
                val offsetBytes = if (tracked) {
                    if ((offsetScratch?.capacity() ?: 0) < bytes) {
                        offsetScratch = ByteBuffer.allocateDirect(nextPowerOfTwo(bytes)).order(ByteOrder.nativeOrder())
                    }
                    offsetScratch!!.apply { clear() }
                } else {
                    ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                }
                if (!empty && offsets != null && offsets.size >= vertexCount * 3) {
                    offsetBytes.asFloatBuffer().put(offsets, 0, vertexCount * 3)
                }
                vertexTarget.setBufferAt(engine, 2, offsetBytes, 0, bytes, handler, released)
            }

            val indexBytes = ByteBuffer.allocateDirect(indexCount * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            if (empty) indexBytes.asIntBuffer().put(intArrayOf(0, 0, 0))
            else indexBytes.asIntBuffer().put(mesh!!.indices, 0, indexCount)
            indexTarget.setBuffer(engine, indexBytes, 0, indexCount * Int.SIZE_BYTES, handler, released)

            renderableManager.setGeometryAt(
                renderableInstance, 0, PrimitiveType.TRIANGLES, vertexTarget, indexTarget, 0, indexCount,
            )
            uploadedIndices = indexCount
            shownIndices = indexCount
        } catch (t: Throwable) {
            if (tracked) {
                flight.abort()
                // Filament may still read the scratch handed over before the throw: never refill it.
                offsetScratch = null
            }
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

    /**
     * Draws only the first [count] indices of the last [upload] — a prefix of its triangles, with
     * no re-upload: how the replay reveals a dense cloud as it grew. Clamped to what was uploaded;
     * asked again of an upload still waiting on the previous one when that one goes out.
     */
    fun showIndices(count: Int) {
        wantedIndices = count
        applyShownIndices()
    }

    private fun applyShownIndices() {
        val shown = (wantedIndices - wantedIndices % 3).coerceIn(3, uploadedIndices)
        if (shown == shownIndices) return
        shownIndices = shown
        renderableManager.setGeometryAt(
            renderableInstance, 0, PrimitiveType.TRIANGLES, ownedVertexBuffer, ownedIndexBuffer, 0, shown,
        )
    }

    override fun destroy() {
        if (isDestroyed) return
        flight.clear()
        // The renderable goes before the buffers it references.
        super.destroy()
        engine.safeDestroyVertexBuffer(ownedVertexBuffer)
        engine.safeDestroyIndexBuffer(ownedIndexBuffer)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val WORLD_HALF_EXTENT_M = 500f

        /** Where Filament's buffer-released callbacks run: the next upload is a Filament call. */
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun nextPowerOfTwo(value: Int): Int = Integer.highestOneBit((value - 1).coerceAtLeast(1)) shl 1

        fun createVertexBuffer(engine: Engine, count: Int, textured: Boolean, surfels: Boolean): VertexBuffer {
            require(!surfels || textured) { "a surfel layer is textured" }
            return VertexBuffer.Builder()
                .bufferCount(if (surfels) 3 else if (textured) 2 else 1)
                .vertexCount(count)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
                .apply { if (textured) attribute(VertexAttribute.UV0, 1, AttributeType.FLOAT2) }
                .apply { if (surfels) attribute(VertexAttribute.CUSTOM0, 2, AttributeType.FLOAT3) }
                .build(engine)
        }

        fun createIndexBuffer(engine: Engine, count: Int): IndexBuffer = IndexBuffer.Builder()
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .indexCount(count)
            .build(engine)
    }
}

/** Colour, glow and draw priority of each layer — all from a [SceneViewTokens.DebugView] palette. */
internal class LayerPaint(val color: Color, val glow: Float = 1f, val priority: Int = 4)

internal fun paintOf(layer: DebugLayer, palette: DebugPalette): LayerPaint = when (layer) {
    // Priority 1, not 0: the replay's photo floor (priority 0) goes under the grid.
    DebugLayer.GridMinor -> LayerPaint(palette.gridMinor, priority = 1)
    DebugLayer.GridMajor -> LayerPaint(palette.gridMajor, priority = 1)
    DebugLayer.AxisX -> LayerPaint(palette.axisX)
    DebugLayer.AxisY -> LayerPaint(palette.axisY)
    DebugLayer.AxisZ -> LayerPaint(palette.axisZ)
    DebugLayer.PlaneFloor -> LayerPaint(palette.floorFill, priority = 1)
    DebugLayer.PlaneWall -> LayerPaint(palette.wallFill, priority = 1)
    DebugLayer.PlaneOther -> LayerPaint(palette.otherFill, priority = 1)
    DebugLayer.OutlineFloor -> LayerPaint(palette.floorOutline, priority = 2)
    DebugLayer.OutlineWall -> LayerPaint(palette.wallOutline, priority = 2)
    DebugLayer.OutlineOther -> LayerPaint(palette.otherOutline, priority = 2)
    DebugLayer.MapPoints -> LayerPaint(palette.mapPoint, priority = 3)
    DebugLayer.LivePoints -> LayerPaint(palette.livePoint, glow = palette.livePointGlow, priority = 6)
    DebugLayer.TrailHead -> LayerPaint(palette.trailNew, glow = palette.trailHeadGlow)
    DebugLayer.Keyframes -> LayerPaint(palette.keyframe, priority = 3)
    DebugLayer.Frustum -> LayerPaint(palette.frustum, glow = palette.frustumGlow)
    DebugLayer.FrustumFace -> LayerPaint(palette.frustumFace, priority = 5)
    DebugLayer.Anchors -> LayerPaint(palette.anchor, glow = palette.anchorGlow)
    else -> {
        // Trail0 … Trail7: one blue, faint to strong, oldest to newest.
        val f = DebugLayer.trailSteps.indexOf(layer).toFloat() / (DebugLayer.trailSteps.size - 1)
        LayerPaint(trailColorAt(palette, f))
    }
}

/**
 * The camera path's colour at [age] along it, `0` the oldest and `1` now: one hue, the palette's
 * camera blue, from faint to strong (#4597) — the path and the frusta it leaves read as the same
 * thing, and the ramp needs no legend beyond "stronger is later".
 */
internal fun trailColorAt(palette: DebugPalette, age: Float): Color {
    val f = age.coerceIn(0f, 1f)
    return if (f < 0.5f) lerp(palette.trailOld, palette.trailMid, f * 2f)
    else lerp(palette.trailMid, palette.trailNew, (f - 0.5f) * 2f)
}

private fun MaterialLoader.createLayerMaterial(paint: LayerPaint): MaterialInstance =
    createUnlitColorInstance(paint.color).apply {
        // The opaque unlit material culls back faces; ribbons and fans are seen from both sides.
        if (paint.color.alpha >= 1f) setCullingMode(Material.CullingMode.NONE)
        paint(paint)
    }

/** [paint]'s colour and glow on a layer's material, in place. */
private fun MaterialInstance.paint(paint: LayerPaint) {
    if (paint.glow == 1f) {
        setColor(paint.color)
    } else {
        // Past 1.0 in linear light, so the bloom pass (threshold 1.0) lifts it.
        val linear = colorOf(paint.color).toLinearSpace()
        setParameter(
            "color", Colors.RgbaType.LINEAR,
            linear.x * paint.glow, linear.y * paint.glow, linear.z * paint.glow, linear.w,
        )
    }
}

/**
 * The flat layers' material instances, one per [DebugLayer], for as long as the view lives.
 *
 * A theme switch recolours them in place ([paint]). Building a second set and destroying the
 * first, as a `remember` keyed on the palette did, freed instances the scene's nodes still drew:
 * the scene is a composition of its own, which had not let go of them yet (#4330). A layer is
 * opaque or translucent in every palette alike, so the material it was created on always fits.
 */
private class LayerMaterials(private val materialLoader: MaterialLoader, private var palette: DebugPalette) {
    /** `replay_grid.mat`: the grid's flat colour, fading out round the room (#4040). */
    private val gridMaterial = materialLoader.createMaterial(GRID_MATERIAL)

    val instances: Map<DebugLayer, MaterialInstance> = DebugLayer.entries.associateWith { layer ->
        if (layer in GRID_LAYERS) {
            materialLoader.createInstance(gridMaterial).apply { paint(paintOf(layer, palette)) }
        } else {
            materialLoader.createLayerMaterial(paintOf(layer, palette))
        }
    }

    /** Takes [palette]'s colours; nothing is done while it is the one already shown. */
    fun paint(palette: DebugPalette) {
        if (palette === this.palette) return
        this.palette = palette
        instances.forEach { (layer, instance) -> instance.paint(paintOf(layer, palette)) }
    }

    /** On the main thread, after the nodes that draw them left the scene. */
    fun destroy() {
        instances.values.forEach { materialLoader.destroyMaterialInstance(it) }
        // After its instances: a view opened again loads its own.
        materialLoader.destroyMaterial(gridMaterial)
    }

    companion object {
        const val GRID_MATERIAL = "materials/replay_grid.filamat"
        val GRID_LAYERS = setOf(DebugLayer.GridMinor, DebugLayer.GridMajor)
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
    Camera(listOf(DebugLayer.Keyframes, DebugLayer.Frustum, DebugLayer.FrustumFace), DebugGroup.Cameras),
    Anchors(listOf(DebugLayer.Anchors), DebugGroup.Anchors),
}

/**
 * Keeps the layer nodes in step with an [ArDebugFrame]: each [Part] is rebuilt only when its
 * key — the inputs its geometry depends on — changes, so a steady view uploads nothing.
 */
private class ArDebugLayers(engine: Engine, private val materials: Map<DebugLayer, MaterialInstance>) {
    val nodes: Map<DebugLayer, DebugLayerNode> = DebugLayer.entries.associateWith { layer ->
        // Draw priorities do not depend on the palette.
        DebugLayerNode(engine, materials.getValue(layer), paintOf(layer, DebugView.Dark).priority)
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
                Part.Stage -> {
                    ArDebugGeometry.buildStage(stageBounds, floorY, style, out)
                    val fade = ArDebugGeometry.gridFade(stageBounds)
                    LayerMaterials.GRID_LAYERS.forEach {
                        materials.getValue(it).setParameter("fade", fade.centreX, fade.centreZ, fade.start, fade.end)
                    }
                }
                // Beside the replay's photos, at their depths: see [PlaneLayering].
                Part.Planes -> ArDebugGeometry.buildPlanes(
                    frame.planes, style, out,
                    layering = replay?.let { PlaneLayering.of(frame, floorY) },
                ) { replay?.isTextured(it) == true }
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
            Part.Planes -> listOf(frame.planes.map { System.identityHashCode(it) }, style, floorY)
            Part.Map -> listOf(frame.mapPointCount, style)
            Part.Live -> listOf(frame.liveKey, frame.livePoints.size, style)
            Part.Trail -> listOf(frame.trailLength, style)
            Part.Camera -> listOf(frame.camera, frame.keyframes.size, style)
            Part.Anchors -> listOf(frame.anchors, style)
        }
}

/** Grid extent for the framed [subject] bounds, snapped to the grid so it never crawls. */
private fun stageBoundsOf(subject: FloatArray?): FloatArray {
    val bounds = subject ?: floatArrayOf(-1f, 0f, -2f, 1f, 0f, 0.5f)
    val cell = ArDebugGeometry.GRID_CELL_M
    return floatArrayOf(
        floor(bounds[0] / cell) * cell, 0f, floor(bounds[2] / cell) * cell,
        ceil(bounds[3] / cell) * cell, 0f, ceil(bounds[5] / cell) * cell,
    )
}

/**
 * The environment of a 3D view on the themed stage: the neutral light the placed models are lit by
 * (the debug layers are unlit and ignore it) and a skybox in the stage's [ground] — cleared to
 * nothing when [transparent], for a view drawn over the camera (#4628).
 *
 * Built once for the view. A theme switch repaints the skybox in place: building another
 * environment for it destroyed the skybox and the light of a scene still on screen (#4330), and
 * left a cubemap of the light on the GPU each time — [Engine.safeDestroyEnvironment] frees the
 * light, not the texture it was built from. That texture is freed here, with the view, a few
 * frames after the light that reads it ([EngineDestroyQueue]).
 */
@Composable
internal fun rememberStageEnvironment(engine: Engine, ground: Color, transparent: Boolean = false): Environment {
    val context = LocalContext.current
    // The light is keyed with the environment that owns it: one never outlives the other.
    val light = remember(engine) {
        KTX1Loader.createIndirectLight(engine, context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"))
    }
    // Before the environment's own effect, so Compose runs it after: the light first, then its cubemap.
    DisposableEffect(light) {
        onDispose { light.cubemap?.let { EngineDestroyQueue.of(engine).enqueueTexture(it) } }
    }
    val environment = remember(light) {
        val stage = colorOf(ground).toLinearSpace()
        Environment(
            indirectLight = light.indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
            skybox = Skybox.Builder().color(stage.x, stage.y, stage.z, skyboxAlpha(transparent)).build(engine),
        )
    }
    DisposableEffect(environment) { onDispose { engine.safeDestroyEnvironment(environment) } }
    SideEffect {
        val stage = colorOf(ground).toLinearSpace()
        environment.skybox?.setColor(stage.x, stage.y, stage.z, skyboxAlpha(transparent))
    }
    return environment
}

/** A transparent view's skybox clears to nothing, so what is under the view shows through it. */
private fun skyboxAlpha(transparent: Boolean): Float = if (transparent) 0f else 1f

/**
 * The 3D debug view: a second SceneView on the demo's [engine], drawing [session] from [orbit].
 *
 * [compact] is the picture-in-picture: a lower frame rate, and no touch (the card over it takes
 * the tap that opens the full view).
 *
 * [onShown] fires once, when the session's content has been on screen for a few rendered frames
 * — textures uploaded, nothing half-drawn — so a caller can hold its cover and chrome until then.
 *
 * [transparent] draws no ground: the layers over whatever is under the view (3D Scan's live view
 * draws them over the dimmed camera, #4628). [visible] picks the layers drawn, the session's own
 * choice by default. [follow], when set, is where following mode heads for each frame instead of
 * framing the content's bounds — `null` keeps the last home. [observe] hears each frame's
 * [ArDebugFrame] after it is drawn and changes nothing of the view: 3D Scan's coverage map
 * reads the cloud and the trail off it (#4631).
 *
 * [selection] keeps the object tapped in a [replay] while this view is away, and [cornerCard] is
 * the card the caller hangs in its top end corner, which a tapped object is kept clear of (#4505).
 *
 * [measuresRoom] off — an object's scan (#4683) — the replay neither draws nor names a room's
 * dimensions, nor frames room for them.
 *
 * [walls], 3D Scan's live walls (#4540), drawn by a [ScanWallLayer] once the caller's [observe]
 * has fed them the frame, while [drawWalls]; hidden, they still give the room its wall count.
 *
 * [maxFps] caps the full view's cadence (the picture-in-picture has its own): 3D Scan's live view
 * draws from the phone's eye, which ARCore moves at the camera's rate, not the display's (#4743).
 */
@Composable
@Suppress("LongParameterList") // the demo's shared engine, handed down once, and one flag per reading
internal fun ArDebugSceneView(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    replay: RerunReplayMedia? = null,
    measuresRoom: Boolean = true,
    onShown: (() -> Unit)? = null,
    transparent: Boolean = false,
    visible: (DebugGroup) -> Boolean = session::isVisible,
    follow: ((ArDebugFrame) -> OrbitPose?)? = null,
    observe: ((ArDebugFrame) -> Unit)? = null,
    selection: ScanSelection? = null,
    cornerCard: DpSize = DpSize.Zero,
    walls: ScanWallsState? = null,
    drawWalls: Boolean = true,
    maxFps: Int? = null,
    /**
     * `false` for a second view of a session another view already drives (the live 3D map): it
     * draws, but neither advances the session's clock nor writes its figures.
     */
    drivesSession: Boolean = true,
    /** More of the scene, in the session's world space: the live 3D map's floor. */
    content: (@Composable SceneScope.() -> Unit)? = null,
) {
    val shown by rememberUpdatedState(onShown)
    val wallsDrawn by rememberUpdatedState(drawWalls)
    val observer by rememberUpdatedState(observe)
    // The ground and palette of the stage this view is drawn on (#4080): the dark stage under
    // media chrome, the light one on a themed stage in light theme.
    val chrome = LocalStageChrome.current
    val palette = chrome.debug

    // Created before the SceneView so they are released after it (Compose forgets in reverse).
    // Not keyed on the palette: what a theme switch changes is repainted in place below (#4330).
    val materials = remember(materialLoader) { LayerMaterials(materialLoader, palette) }
    DisposableEffect(materials) { onDispose { materials.destroy() } }
    // Linear tone mapping: the unlit layers show their token colours exactly, and the stage
    // skybox is exactly the stage's ground. Values past 1.0 still bloom (bloom runs before it).
    val colorGrading = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    }
    DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
    val environment = rememberStageEnvironment(engine, chrome.ground, transparent)
    val view = rememberView(engine)
    val renderer = rememberRenderer(engine)
    // Held here so the phone's own lens can replace the orbit's while 3D Scan follows (#4716).
    val cameraNode = rememberCameraNode(engine)

    val layers = remember(engine, materials) { ArDebugLayers(engine, materials.instances) }
    // The replay's textured layers: created before the SceneView, released after its nodes.
    val measureInk = palette.floorOutline.toArgb()
    val measureHalo = chrome.ground.toArgb()
    val replayLayers = remember(engine, materialLoader, replay) {
        replay?.let { ReplayLayers(engine, materialLoader, it, measureInk, measureHalo) }
    }
    DisposableEffect(replayLayers) { onDispose { replayLayers?.destroy() } }
    // 3D Scan's live walls (#4540): created before the SceneView, released after its nodes.
    val wallLayer = remember(engine, materialLoader, walls) { walls?.let { ScanWallLayer(engine, materialLoader) } }
    DisposableEffect(wallLayer) { onDispose { wallLayer?.destroy() } }
    // The theme's colours, on the layers the scene already draws.
    SideEffect {
        materials.paint(palette)
        replayLayers?.setMeasureColors(measureInk, measureHalo)
    }
    // A tap on an object of the scan boxes it (#4485); the picture-in-picture takes no touch.
    val objectPick =
        rememberScanObjectPick(replay.takeUnless { compact || !drivesSession }, session, orbit, engine, materialLoader, selection)
    var anchors by remember { mutableStateOf(emptyList<DebugAnchor>()) }
    val clock = remember { FrameClock() }

    // The stage colour behind the view: a TextureView stays transparent until its first frame,
    // which would show the AR camera through the "3D view" for as long as the engine takes.
    Box(if (transparent) modifier else modifier.background(chrome.ground)) {
        SceneView(
            modifier = Modifier.matchParentSize(),
            // A TextureView composes with the chrome and the AR SurfaceView under it.
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            view = view,
            renderer = renderer,
            cameraNode = cameraNode,
            isOpaque = !transparent,
            frameRatePolicy = FrameRatePolicy.Continuous(maxFps = if (compact) PIP_FPS else maxFps),
            autoCenterContent = false,
            environment = environment,
            cameraManipulator = orbit,
            onGestureListener = rememberOnGestureListener(
                onDown = { _, _ -> objectPick?.down() },
                onSingleTapConfirmed = { event, _ -> objectPick?.tap(event.x, event.y) },
            ),
            onFrame = { frameTimeNanos ->
                val dt = clock.tick(frameTimeNanos)
                if (!clock.configured) {
                    clock.configured = true
                    view.configureForDebug(colorGrading)
                    // Bloom spreads past the layers' alpha: over the camera it would haze it.
                    if (transparent) view.bloomOptions = view.bloomOptions.apply { enabled = false }
                }
                if (drivesSession) session.tick(dt)
                // From the phone's eye, through the phone's lens (#4716): ARCore's projection for
                // the AR view under this one, same size, so a point lands on its camera pixel. The
                // orbit's lens comes back with the orbit, after a drag or a pinch, or eased back
                // over the hand-off off the phone's eye as a save starts (#4719).
                if (orbit.firstPerson != null && orbit.customProjection(clock.projection)) {
                    clock.lensApplied = true
                    cameraNode.setCustomProjection(
                        clock.projection.toTransform(),
                        ScanLens.NEAR_M.toDouble(),
                        ScanLens.FAR_M.toDouble(),
                    )
                } else if (clock.lensApplied) {
                    clock.lensApplied = false
                    cameraNode.updateProjection()
                }

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

                if (drivesSession && session.fpsMeter.tick(frameTimeNanos)) session.fps = session.fpsMeter.fps

                // A recording is framed whole from its first frame — the camera and the grid hold
                // still while it plays — where a live session, or a scan still recording, is framed
                // as it grows.
                val whole = if (replay != null && !replay.growing) {
                    clock.wholeFrame?.takeIf { clock.wholeFor === trace }
                        ?: trace.frameAt(trace.duration).also {
                            clock.wholeFrame = it
                            clock.wholeFor = trace
                        }
                } else {
                    frame
                }
                val finished = replay != null && !replay.growing
                val floorY = stageFloorY(whole)
                // A finished recording is measured once, clouds included, and seen from the side
                // it was scanned from; a session still growing is framed on what it has so far.
                val subject = if (finished) {
                    clock.subject?.takeIf { clock.subjectFor === whole }
                        ?: SceneSubject.of(whole, replay?.dense?.cloud?.positions, floorY).also {
                            clock.subject = it
                            clock.subjectFor = whole
                        }
                } else {
                    SceneSubject(ArDebugGeometry.contentBounds(whole), room = replayLayers?.measure)
                }
                val bounds = subject.bounds
                // The room's dimensions are written outside its walls: where they are drawn, the
                // framing leaves them their room, so no figure is cut by the screen's edge.
                val measured = subject.room.takeIf { !compact && measuresRoom }
                orbit.roomYawDegrees = subject.roomYawDegrees
                val firstContent = !orbit.hasFramedContent && bounds != null
                if (firstContent) {
                    subject.frontAzimuth?.let { orbit.frontAzimuth = it }
                    // Turns the automatic framing to the room's good side before it is first shown.
                    orbit.recenter()
                }
                val home = ArDebugFraming.homeWithMeasure(
                    bounds, measured, floorY, orbit.viewportHeight,
                    orbit.home.azimuthDegrees, orbit.verticalFovDegrees, orbit.aspect,
                    elevationDegrees = orbit.homeElevation,
                    band = orbit.band,
                    fill = if (finished) 1f else ArDebugFraming.GROWING_FILL,
                )
                // Full screen (#4558): how much larger the room is drawn once the chrome is gone —
                // the fit for the whole view against the fit for the chrome's band.
                orbit.focusBand?.let { focusBand ->
                    val homeFull = ArDebugFraming.homeWithMeasure(
                        bounds, measured, floorY, orbit.viewportHeight,
                        orbit.home.azimuthDegrees, orbit.verticalFovDegrees, orbit.aspect,
                        elevationDegrees = orbit.homeElevation,
                        band = focusBand,
                        fill = if (finished) 1f else ArDebugFraming.GROWING_FILL,
                    )
                    if (homeFull.distance > 0f && home.distance > 0f) {
                        orbit.focusScale = (home.distance / homeFull.distance).coerceIn(1f, MAX_FOCUS_SCALE)
                    }
                }
                val followed = follow?.invoke(frame)
                // Following, the view frames the room — moved by what a picked box needs to be seen.
                if (orbit.following) {
                    if (follow == null) {
                        orbit.home = objectPick?.framed(home) ?: home
                    } else if (followed != null) {
                        orbit.followTo(followed)
                    }
                }
                // A followed view stands inside the room it scans, so the room's walls cannot hold
                // its gestures: it keeps the empty-session reach (#4628).
                orbit.limits = if (follow == null) ArDebugFraming.limits(bounds, home, floorY) else OrbitLimits.NONE
                if (follow != null) {
                    // A drag orbits the cloud, not the phone behind it (#4628).
                    if (clock.pivotFor !== frame) {
                        clock.pivotFor = frame
                        clock.pivot = ScanFollow.cloudCentre(frame.mapPoints) ?: bounds?.let {
                            Position((it[0] + it[3]) / 2f, (it[1] + it[4]) / 2f, (it[2] + it[5]) / 2f)
                        }
                    }
                    orbit.pivot = clock.pivot
                    if (!orbit.hasFramedContent && followed != null) {
                        orbit.hasFramedContent = true
                        orbit.snapTo(followed)
                    }
                } else if (firstContent) {
                    orbit.hasFramedContent = true
                    if (finished && orbit.drift) {
                        orbit.playIntro(ReplayIntro.startFor(home), held = true)
                    } else {
                        orbit.snapTo(home)
                    }
                }

                val style = ArDebugStyle.forOrbit(orbit.pose.distance, orbit.verticalFovDegrees, orbit.viewportHeight)
                // The picture-in-picture packs the room into a few hundred pixels: full-size points
                // would read as noise there, so they shrink while lines keep their weight.
                val pointStyle = if (compact) ArDebugStyle(style.metresPerPixel * PIP_POINT_SCALE) else style
                layers.sync(frame, style, pointStyle, stageBoundsOf(bounds), floorY, visible, replayLayers)
                replayLayers?.sync(
                    frame, pointStyle, floorY,
                    ReplayVisibility(
                        planes = visible(DebugGroup.Planes),
                        points = visible(DebugGroup.Points),
                        anchors = visible(DebugGroup.Anchors),
                        trail = visible(DebugGroup.Trail),
                        cameras = visible(DebugGroup.Cameras),
                        measure = !compact && measuresRoom,
                        measureOnTop = orbit.overhead,
                    ),
                    eye = CameraRig.eye(orbit.pose).let { Vec3(it.x, it.y, it.z) },
                )
                objectPick?.sync(style)

                observer?.invoke(frame)
                if (walls != null) wallLayer?.sync(walls, dt, shown = wallsDrawn)
                if (frame.anchors != anchors) anchors = frame.anchors
                if (drivesSession && frameTimeNanos - clock.statsAtNanos >= STATS_INTERVAL_NS) {
                    clock.statsAtNanos = frameTimeNanos
                    // A replay with a dense cloud counts its surfels, as the sessions list does.
                    val points = replay?.pointCountAt(frame.time) ?: frame.mapPointCount
                    session.count(frame, points)
                }
                // A replay names the room it found, as a floor plan would: the one its dimensions
                // draw ([ReplayLayers.measure]), so the two never disagree — and on the frame the
                // floor redraws them, rather than a stats tick later.
                if (replayLayers != null && drivesSession) {
                    // A scan still growing names no room before two walls are seen (#4540).
                    val room = replayLayers.measure?.takeIf { measuresRoom }
                        ?.takeIf {
                            // The walls the HUD counts when it counts them: one figure on screen.
                            replay?.growing != true ||
                                (walls?.wallCount ?: PlaneWallSource.wallCount(livePlanesOf(frame), floorY)) >=
                                ArDebugSession.MIN_ROOM_WALLS
                        }
                        ?.scanSummary(Locale.getDefault())
                    if (session.stats.room != room) session.stats = session.stats.copy(room = room)
                }
                // onFrame only fires for a frame that reached the surface (#3444): counting them is
                // counting what the user has actually seen.
                if (bounds != null && !clock.shown) {
                    clock.contentFrames++
                    if (clock.contentFrames >= SHOWN_AFTER_FRAMES) {
                        clock.shown = true
                        orbit.releaseIntro()
                        shown?.invoke()
                    }
                }
            },
        ) {
            // Each set of layer nodes hangs off one plain node, and is destroyed with it. Keyed on
            // the set: were it ever replaced while the view stays, its nodes leave the scene before
            // its material instances are destroyed, and the next set's nodes are attached.
            key(layers) { Node(apply = { layers.nodes.values.forEach { addChildNode(it) } }) }
            key(replayLayers) { Node(apply = { replayLayers?.nodes?.forEach { addChildNode(it) } }) }
            key(wallLayer) { Node(apply = { wallLayer?.nodes?.forEach { addChildNode(it) } }) }
            // The picked object's tint and box are rebuilt with the replay, so they get the same
            // treatment.
            key(objectPick) { Node(apply = { objectPick?.nodes?.forEach { addChildNode(it) } }) }
            content?.invoke(this)
            if (visible(DebugGroup.Anchors)) {
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
        ScanObjectOverlay(objectPick, Modifier.matchParentSize(), cornerCard)
    }
}

/**
 * What the camera frames of a session: its [bounds], the side it is best seen from
 * ([frontAzimuth], `null` while the path names none), the heading of its walls
 * ([roomYawDegrees], `null` without any) and the [room] its dimensions are written round
 * (`null` when it holds none).
 */
internal class SceneSubject(
    val bounds: FloatArray?,
    val frontAzimuth: Float? = null,
    val roomYawDegrees: Float? = null,
    val room: RoomMeasure? = null,
) {
    companion object {
        /**
         * The subject of the finished recording [whole], with its [dense] cloud (flat xyz) and
         * its floor at [floorY].
         */
        fun of(whole: ArDebugFrame, dense: FloatArray?, floorY: Float): SceneSubject {
            val bounds = ArDebugGeometry.subjectBounds(whole, dense)
            return SceneSubject(
                bounds = bounds,
                frontAzimuth = ArDebugFraming.frontAzimuth(bounds, whole.trail),
                roomYawDegrees = ArDebugGeometry.roomYawDegrees(whole.planes),
                room = RoomMeasure.of(whole.planes, floorY),
            )
        }
    }
}

/** Per-view frame bookkeeping, deliberately not Compose state: it changes every frame. */
private class FrameClock {
    var lastNanos = 0L
    var lensApplied = false
    val projection = FloatArray(ScanLens.MATRIX_SIZE)
    var configured = false
    var trace: ArDebugTrace? = null
    var frame: ArDebugFrame? = null
    var version = -1
    var frameAtNanos = 0L
    var statsAtNanos = 0L
    var wholeFrame: ArDebugFrame? = null
    var wholeFor: ArDebugTrace? = null
    var subject: SceneSubject? = null
    var subjectFor: ArDebugFrame? = null
    var pivotFor: ArDebugFrame? = null
    var pivot: Position? = null
    var contentFrames = 0
    var shown = false

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
internal fun com.google.android.filament.View.configureForDebug(colorGrading: ColorGrading) {
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
internal const val STATS_INTERVAL_NS = 250_000_000L

/** The floor the stage stands on, to the centimetre. [whole] is the frame the scene is framed on. */
internal fun stageFloorY(whole: ArDebugFrame): Float = (ArDebugGeometry.floorHeight(whole) * 100f).roundToInt() / 100f
private const val BLOOM_STRENGTH = 0.28f
private const val ANCHOR_MODEL_SIZE_M = 0.3f

/** Rendered frames of content before [ArDebugSceneView]'s onShown: the textures are up by then. */
private const val SHOWN_AFTER_FRAMES = 3

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
    val chrome = LocalStageChrome.current
    Box(
        modifier = modifier
            .size(DebugView.pipWidth, DebugView.pipHeight)
            .shadow(elevation = SceneViewTokens.Elevation.md, shape = shape, clip = false)
            .clip(shape)
            .background(chrome.ground)
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
                .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
                .clickable(role = Role.Button, onClick = onExpand)
                .semantics { contentDescription = "Open the 3D view" },
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Space.sm)
                .background(chrome.card, CircleShape)
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("3D", style = SceneViewTokens.Type.caption.copy(color = chrome.onCard))
            Spacer(Modifier.width(Space.xs))
            Icon(
                Icons.Rounded.OpenInFull,
                contentDescription = null,
                tint = chrome.onCard,
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
    val palette = LocalStageChrome.current.debug
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
            // The live view keeps one Path chip: its frusta go with it.
            LegendChip(
                "Path", path, palette.trailNew, session, DebugGroup.Trail, Modifier.weight(1f),
                linked = DebugGroup.Cameras,
            )
            val points = ArDebugFormat.count(stats.mapPoints)
            LegendChip("Points", points, palette.livePoint, session, DebugGroup.Points, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val planes = ArDebugFormat.count(stats.planes)
            LegendChip("Planes", planes, palette.floorOutline, session, DebugGroup.Planes, Modifier.weight(1f))
            val anchors = ArDebugFormat.count(stats.anchors)
            LegendChip("Anchors", anchors, palette.anchor, session, DebugGroup.Anchors, Modifier.weight(1f))
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
    linked: DebugGroup? = null,
) {
    val on = session.isVisible(group)
    val shape = CircleShape
    Row(
        modifier = modifier
            .height(Glass.pillHeight)
            .clip(shape)
            .background(if (on) ArOverlay.scrimDark else Glass.surface, shape)
            .overMediaEdge(shape)
            .clickable(role = Role.Switch) {
                session.toggle(group)
                linked?.let { if (session.isVisible(it) != session.isVisible(group)) session.toggle(it) }
            }
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

internal const val AR_DEBUG_LEGEND_TAG = "ar_debug_legend"
internal const val AR_DEBUG_TIMELINE_TAG = "ar_debug_timeline"
internal const val AR_DEBUG_LIVE_TAG = "ar_debug_live"

internal const val AR_DEBUG_PIP_TAG = "ar_debug_pip"

/** The most full screen enlarges the room: past it, a thin band's fit would throw the room at the lens. */
private const val MAX_FOCUS_SCALE = 2.5f
