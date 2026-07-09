package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute.POSITION
import com.google.android.filament.VertexBuffer.VertexAttribute.TANGENTS
import com.google.android.filament.VertexBuffer.VertexAttribute.UV0
import com.google.ar.core.AugmentedFace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.google.ar.core.AugmentedFace.RegionType
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.node.MeshNode

/**
 * AR Augmented Face positioned 3D model node
 *
 * Describes a face detected by ARCore and provides methods to access additional center and face
 * region poses as well as face mesh related data.
 *
 * Augmented Faces supports front-facing (selfie) camera only, and does not support attaching
 * anchors nor raycast hit testing. [Trackable.createAnchor] will result in an
 * `IllegalStateException`.
 *
 * To use Augmented Faces, enable the feature in the session. This can be done at session creation
 * time, or at any time during session runtime:
 *
 * ```
 * Session session = new Session(context, EnumSet.of(Session.Feature.FRONT_CAMERA));
 * Config config = ...
 * config.setAugmentedFaceMode(AugmentedFaceMode.MESH3D);
 * session.configure(config);
 * }
 * ```
 *
 * When Augmented Face mode is enabled, ARCore updates the list of detected faces for each frame.
 * Use [Session.getAllTrackables] and [Trackable.getTrackingState] to get a list of faces that have
 * valid meshes that can be rendered.
 *
 * ```
 * for (AugmentedFace face : session.getAllTrackables(AugmentedFace.class)) {
 *   if (face.getTrackingState() == TrackingState.TRACKING) {
 *     // Render face mesh ...
 *   }
 * }
 * }
 * ```
 *
 * Faces provide static mesh data that does not change during the session, as well as pose and mesh
 * data that is updated each frame:
 *
 * ```
 * // UVs and indices can be cached as they do not change during the session.
 * FloatBuffer uvs = face.getMeshTextureCoordinates();
 * ShortBuffer indices = face.getMeshTriangleIndices();
 *
 * // Center and region poses, mesh vertices, and normals are updated each frame.
 * Pose facePose = face.getCenterPose();
 * FloatBuffer faceVertices = face.getMeshVertices();
 * FloatBuffer faceNormals = face.getMeshNormals();
 * }
 * ```
 */
open class AugmentedFaceNode(
    engine: Engine,
    val augmentedFace: AugmentedFace,
    private val meshMaterialInstance: MaterialInstance? = null,
    /**
     * Whether to compute and upload per-vertex tangent quaternions every frame for the face
     * mesh. Required by PBR (lit) materials — they encode normal + tangent + bitangent into
     * the FLOAT4 TANGENTS attribute.
     *
     * **Set to `false` when the material is unlit** (e.g. `materialLoader.createUnlitColorInstance`)
     * — unlit shaders never sample TANGENTS, so the per-frame Mikkelsen build + JNI roundtrip
     * + buffer upload (~30 Hz of `vertexCount * 16` bytes) is pure waste. Default `true` keeps
     * the lit-PBR behaviour shipping today (#878 audit).
     */
    private val computeTangents: Boolean = true,
    private val builder: RenderableManager.Builder.() -> Unit = {},
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((AugmentedFace) -> Unit)? = null
) : TrackableNode<AugmentedFace>(
    engine = engine,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {

    /**
     * The center of the face, defined to have the origin located behind the nose and between the
     * two cheek bones.
     *
     * Z+ is forward out of the nose, Y+ is upwards, and X+ is towards the left.
     * The units are in meters. When the face trackable state is TRACKING, this pose is synced with
     * the latest frame. When face trackable state is PAUSED, an identity pose will be returned.
     *
     * Use [regionNodes] to retrieve poses of specific regions of the face.
     */
    val centerNode = PoseNode(engine).apply { parent = this@AugmentedFaceNode }

    /**
     * The face mesh node, created lazily once ARCore provides valid mesh buffers.
     *
     * Returns `null` until the first tracking frame with non-empty mesh data.
     * Vertex positions and normals are updated every frame while tracking;
     * UVs and triangle indices are set once (they are static per ARCore docs).
     */
    var meshNode: MeshNode? = null
        private set

    // Reusable tangent-quaternion buffer: 4 floats per vertex, 16 bytes/vertex.
    // Allocated lazily and grown on demand — the face mesh vertex count is stable
    // across frames (ARCore returns a fixed-topology mesh), so this is typically
    // allocated once and reused for the life of the node.
    private var tangentsBuffer: ByteBuffer? = null

    // For unlit materials we upload an identity-quaternion buffer ONCE (mesh creation)
    // and never touch it again. We track its filled vertex count so a recreate-after-
    // destroy or vertex-count change re-initialises lazily without the per-frame
    // (0,0,0,1) write loop the issue (#1758) flagged.
    private var identityTangentsBuffer: ByteBuffer? = null
    private var identityTangentsVertexCount: Int = 0

    /**
     * The region nodes at the tip of the nose, the detected face's left side of the forehead,
     * the detected face's right side of the forehead.
     *
     * Defines face regions to query the pose for. Left and right are defined relative to the person
     * that the mesh belongs to. To retrieve the center pose use [AugmentedFace.getCenterPose].
     */
    val regionNodes = RegionType.values().associateWith {
        PoseNode(engine).apply { parent = this@AugmentedFaceNode }
    }

    // `trackable = augmentedFace` below virtually dispatches the open update() — a subclass
    // override runs BEFORE the subclass's own fields are initialized (#2624, the bug class behind
    // the 4.21.0 ShadowReceiverPlaneNode crash #2621). This flag gates this class's update() tail
    // (the whole mesh build can run during construction when the face is already TRACKING) until
    // construction completes; init then applies the initial state explicitly, so the construction
    // end-state is byte-for-byte unchanged.
    private var constructed = false

    init {
        trackable = augmentedFace
        constructed = true
        // Apply the initial state that the gated update() skipped during the constructor dispatch.
        applyTrackableState()
    }

    override fun update(trackable: AugmentedFace?) {
        super.update(trackable)

        // Bail while the constructor dispatch is in flight (#2624) — init applies the state below.
        if (!constructed) return
        applyTrackableState()
    }

    /**
     * The class-specific trackable refresh — lazy mesh build + per-frame buffer/pose updates —
     * gated behind [constructed] (#2624).
     */
    private fun applyTrackableState() {
        if (augmentedFace.trackingState != TrackingState.TRACKING) return

        // Guard: buffers are not yet populated in the very first TRACKING frame.
        // Building Filament buffers with size 0 triggers a native abort.
        val indices = augmentedFace.meshTriangleIndices
        val vertices = augmentedFace.meshVertices
        val normals = augmentedFace.meshNormals
        // UVs are static per ARCore docs but `SurfaceOrientation` consumes them every
        // frame to recompute tangent quaternions, so they have to be in scope here too.
        val uvs = augmentedFace.meshTextureCoordinates

        if (indices.limit() == 0 || vertices.limit() == 0) return

        val vertexCount = vertices.limit() / 3

        // Compute tangent quaternions from positions + normals + uvs + indices.
        // PBR materials require TANGENTS (FLOAT4 quaternions encoding normal + tangent
        // + bitangent), not raw FLOAT3 normals — uploading normals as if they were
        // quaternions left the mesh with undefined lighting and rendered it invisible
        // under the transparent colored material used by the demo.
        //
        // Skip the compute + upload entirely for unlit materials (#878 audit) — they
        // never sample TANGENTS, so the per-frame Mikkelsen build + JNI roundtrip is
        // pure waste at ~30 Hz.
        val tangents = if (computeTangents) {
            computeTangents(vertices, normals, uvs, indices, vertexCount)
        } else {
            null
        }

        if (meshNode == null) {
            meshNode = MeshNode(
                engine = engine,
                primitiveType = PrimitiveType.TRIANGLES,
                vertexBuffer = VertexBuffer.Builder()
                    // Position + Tangents (quaternion) + UV Coordinates
                    .bufferCount(3)
                    // Position Attribute (x, y, z)
                    .attribute(POSITION, 0, AttributeType.FLOAT3)
                    // Tangents Attribute (Quaternion: x, y, z, w) — encodes normal + tangent
                    // + bitangent for PBR lighting. Must be FLOAT4.
                    .attribute(TANGENTS, 1, AttributeType.FLOAT4)
                    .normalized(TANGENTS)
                    // Uv Attribute (x, y)
                    .attribute(UV0, 2, AttributeType.FLOAT2)
                    .vertexCount(vertexCount)
                    .build(engine).apply {
                        // Fill all slots before the node becomes visible,
                        // so Filament can compute a non-empty AABB (build:552).
                        setBufferAt(engine, 0, vertices) // positions  (dynamic)
                        // Slot 1 (TANGENTS, FLOAT4) MUST be a 16-byte-per-vertex buffer
                        // even for unlit materials — Filament asserts on stride mismatch.
                        // For lit materials we upload the just-computed Mikkelsen quats;
                        // for unlit we upload an identity-quaternion buffer ONCE here
                        // (built lazily) and never touch it again per frame.
                        setBufferAt(
                            engine, 1,
                            tangents ?: identityTangentsBuffer(vertexCount)
                        )
                        setBufferAt(engine, 2, uvs)      // UVs        (static)
                    },
                indexBuffer = IndexBuffer.Builder()
                    .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                    .indexCount(indices.limit())
                    .build(engine).apply {
                        setBuffer(engine, indices)       // indices    (static)
                    },
                materialInstance = meshMaterialInstance,
                builder = {
                    // Filament computes AABB asynchronously after vertex buffer upload.
                    // If a render frame starts before AABB is updated, Filament aborts with
                    // "AABB can't be empty" (build:552). Disabling culling avoids this race
                    // condition. For a face mesh this may be acceptable since the mesh is always
                    // within the camera frustum while tracking.
                    culling(false)
                    castShadows(false)
                    receiveShadows(false)
                    builder()
                }
            ).apply { parent = centerNode }

            // Early return — buffers already filled above,
            // next frame will go to the update branch below
            centerNode.pose = augmentedFace.centerPose
            regionNodes.forEach { (regionType, regionNode) ->
                regionNode.pose = augmentedFace.getRegionPose(regionType)
            }
            return
        }

        // Update dynamic buffers every frame — positions + recomputed tangents.
        // Tangent quaternions depend on normals and must be rebuilt whenever the
        // mesh deforms (i.e. every tracked frame). For unlit materials we skip
        // the per-frame TANGENTS upload entirely (#878) — the static
        // identity-quaternion buffer uploaded at construction is unchanged.
        meshNode?.vertexBuffer?.apply {
            setBufferAt(engine, 0, vertices) // positions
            if (tangents != null) {
                setBufferAt(engine, 1, tangents) // tangent quaternions
            }
        }

        centerNode.pose = augmentedFace.centerPose

        regionNodes.forEach { (regionType, regionNode) ->
            regionNode.pose = augmentedFace.getRegionPose(regionType)
        }
    }

    /**
     * Updates face tracking state each frame.
     *
     * Overrides [TrackableNode.update] because [Frame.getUpdatedTrackables] always returns
     * an empty list for [AugmentedFace] on the front camera. Manually sets [PoseNode] state
     * (session, frame, cameraTrackingState) since `super` cannot be called without
     * re-triggering the broken `getUpdatedTrackables` check.
     *
     * [update] (trackable) is invoked every frame regardless of tracking state so the
     * `trackingState` setter in [TrackableNode] fires `onTrackingStateChanged` and
     * `updateVisibility()` on TRACKING -> STOPPED/PAUSED transitions (e.g. face leaving the
     * frame). Existing guards in [update] (trackable) skip mesh creation/buffer updates when
     * not TRACKING. `onUpdated` only fires while TRACKING since the face data is only
     * meaningful then.
     */
    override fun update(session: Session, frame: Frame) {
        // PoseNode state — set manually since we skip super
        this.session = session
        this.frame = frame
        this.cameraTrackingState = frame.camera.trackingState

        // Always propagate trackable state so onTrackingStateChanged fires on every
        // transition, including TRACKING -> STOPPED/PAUSED. update(trackable) guards mesh
        // work internally.
        update(augmentedFace)

        if (augmentedFace.trackingState == TrackingState.TRACKING) {
            onUpdated?.invoke(augmentedFace)
        }
    }

    /**
     * Identity-quaternion buffer for unlit face mesh — fills the TANGENTS slot once at
     * construction so Filament's stride contract is honoured, then is never re-uploaded.
     * Layout: 4 floats per vertex `(0, 0, 0, 1)` (identity quaternion). The shader
     * never samples it, so the value doesn't matter — but the slot must be the right
     * size and never empty (Filament asserts at build time).
     *
     * Cached on the node (separate slot from [tangentsBuffer] so a future toggle of
     * `computeTangents` doesn't clobber the lit tangents). The (0, 0, 0, 1) write
     * loop runs at most once per `vertexCount` change — typically exactly once for
     * the lifetime of the node since ARCore returns a fixed-topology face mesh
     * (#1758). The previous implementation re-ran the per-vertex write loop on every
     * call, which was a latent regression risk if this method ever moved back into
     * the per-frame path.
     */
    private fun identityTangentsBuffer(vertexCount: Int): ByteBuffer {
        val (buf, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = identityTangentsBuffer,
            cachedVertexCount = identityTangentsVertexCount,
            vertexCount = vertexCount
        )
        if (!wasCacheHit) {
            identityTangentsBuffer = buf
            identityTangentsVertexCount = vertexCount
        }
        return buf
    }

    private fun computeTangents(
        positions: java.nio.FloatBuffer,
        normals: java.nio.FloatBuffer,
        uvs: java.nio.FloatBuffer,
        indices: java.nio.ShortBuffer,
        vertexCount: Int,
    ): ByteBuffer {
        val neededBytes = vertexCount * 4 * 4
        val buf = tangentsBuffer?.takeIf { it.capacity() >= neededBytes }
            ?: ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder())
                .also { tangentsBuffer = it }

        buf.clear()
        buf.limit(neededBytes)

        val orientation = SurfaceOrientation.Builder()
            .vertexCount(vertexCount)
            .positions(positions.duplicate().rewind() as java.nio.Buffer)
            .normals(normals.duplicate().rewind() as java.nio.Buffer)
            .uvs(uvs.duplicate().rewind() as java.nio.Buffer)
            .triangleCount(indices.limit() / 3)
            .triangles_uint16(indices.duplicate().rewind() as java.nio.Buffer)
            .build()
        try {
            orientation.getQuatsAsFloat(buf)
        } finally {
            orientation.destroy()
        }
        buf.rewind()
        return buf
    }

    override fun destroy() {
        // Destroy the face mesh resources we built in update() before tearing down
        // the parent chain. Filament does not reclaim VertexBuffer / IndexBuffer when
        // the owning Renderable is destroyed — they stay in the engine registry until
        // Engine.destroy() runs, and because they're tied to this composable's
        // disposable lifecycle, each back-navigation leaks two buffers per tracked face.
        // More critically: destroying the Engine while a VertexBuffer is still
        // attached to a not-yet-unregistered Renderable triggers a Filament
        // PreconditionPanic ("resource still referenced") — that's the native abort
        // users hit when leaving the Face Mesh demo via the back gesture.
        val mn = meshNode
        if (mn != null) {
            val vb = mn.vertexBuffer
            val ib = mn.indexBuffer
            runCatching { mn.destroy() }
            runCatching { engine.destroyVertexBuffer(vb) }
            runCatching { engine.destroyIndexBuffer(ib) }
            meshNode = null
        }
        runCatching { centerNode.destroy() }
        regionNodes.values.forEach { runCatching { it.destroy() } }
        // Drop the cached tangent / identity-tangent buffers — direct ByteBuffers are
        // GC'd eventually, but releasing the reference frees the off-heap memory
        // immediately when the JVM's Cleaner runs.
        tangentsBuffer = null
        identityTangentsBuffer = null
        identityTangentsVertexCount = 0
        super.destroy()
    }
}

/**
 * Pure logic for [AugmentedFaceNode]'s identity-tangent buffer cache (#1758).
 *
 * Returns a [ByteBuffer] of `vertexCount * 16` bytes (4 floats per vertex)
 * filled with `(0, 0, 0, 1)` identity quaternions when first allocated. On a cache
 * hit (same [vertexCount] and capacity >= needed bytes), the cached buffer is
 * returned unchanged — **no per-vertex rewrite**, which is the perf bug the issue
 * flagged.
 *
 * Returns the buffer and a boolean indicating whether it was a cache hit. Callers
 * persist `buf` + `vertexCount` to their own state on a miss.
 *
 * Extracted as an internal top-level function so JVM unit tests can exercise the
 * caching contract without instantiating a Filament Engine or ARCore Session.
 */
internal fun obtainIdentityTangentsBuffer(
    cached: ByteBuffer?,
    cachedVertexCount: Int,
    vertexCount: Int
): Pair<ByteBuffer, Boolean> {
    val neededBytes = vertexCount * 4 * 4
    if (cached != null &&
        cached.capacity() >= neededBytes &&
        cachedVertexCount == vertexCount
    ) {
        cached.rewind()
        cached.limit(neededBytes)
        return cached to true
    }

    val buf = cached?.takeIf { it.capacity() >= neededBytes }
        ?: ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder())

    buf.clear()
    buf.limit(neededBytes)
    // Write (0, 0, 0, 1) per vertex — identity quaternion. Cheap one-shot pass.
    val floats = buf.asFloatBuffer()
    repeat(vertexCount) {
        floats.put(0f); floats.put(0f); floats.put(0f); floats.put(1f)
    }
    buf.rewind()
    return buf to false
}
