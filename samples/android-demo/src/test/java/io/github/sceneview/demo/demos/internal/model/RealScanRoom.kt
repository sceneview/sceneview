package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import io.github.sceneview.demo.demos.internal.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A room as a phone's depth really scans it, for the clean model: 5 × 7 m and 2.6 m high, every
 * surface swelling by up to ±2 cm (the depth's drift, a few tens of centimetres across, plus its
 * grain), two walls scanned twice with the second pass left 3 cm behind the first, and key photos
 * of 180 × 320 taken close to the walls and at an angle. Furnished, it adds a pleated curtain and
 * a sofa with its cushions against two other walls: shapes the flattening must leave as they are.
 * With its reliefs, it adds a TV standing 3.5 cm off one wall and a door set 3.5 cm into another:
 * flat panels as close to their wall as a second pass is, which must stay off it.
 *
 * Each pass is fused into the real TSDF and meshed by the real marching cubes, as a recording's.
 * A second pass 3 cm off is its own sheet: fused into the same voxels, the two would average into
 * one surface, which is not what the scans show.
 */
internal object RealScanRoom {
    val ROOM = Box(-2.5f, 0f, -3.5f, 2.5f, 2.6f, 3.5f)

    /** The two walls scanned twice: wall x = -2.5 and wall z = +3.5, each over a patch. */
    val DOUBLED_X_WALL = Box(-2.53f, 0.4f, -1.0f, -2.53f, 2.3f, 2.0f)
    val DOUBLED_Z_WALL = Box(-1.5f, 0.4f, 3.53f, 1.5f, 2.3f, 3.53f)

    /**
     * With a drifted floor ([scan]), most of the floor scanned twice, the second pass 4 cm below
     * the first: each pass misses its own patches, so the two sheets together cover the floor
     * that neither covers alone.
     */
    val DRIFTED_FLOOR = Box(-2.3f, -0.04f, -2.4f, 2.3f, -0.04f, 3.3f)

    /** The curtain hangs on wall x = +2.5; the sofa stands against wall z = -3.5. */
    val CURTAIN = Box(2.3f, 0.06f, -1.2f, 2.5f, 2.45f, 1.0f)
    val SOFA = Box(-1.0f, 0f, -3.5f, 1.0f, 0.85f, -2.6f)

    /**
     * The reliefs: a TV on wall x = +2.5, its screen 3.5 cm off the wall, and a door on wall
     * z = -3.5, its leaf 3.5 cm back in the wall — each its own flat panel, within the distance
     * a second pass of the wall lies at, and with no wall behind or in front of it.
     */
    val TV = Box(2.465f, 1.0f, 1.5f, 2.5f, 1.7f, 2.7f)
    val DOOR = Box(1.35f, 0f, -3.535f, 2.25f, 2.05f, -3.5f)

    const val PHOTO_WIDTH = 180
    const val PHOTO_HEIGHT = 320
    const val PHOTO_FOCAL = 260f
    val PHOTO_LENS = ReplayLens(PHOTO_WIDTH / 2f / PHOTO_FOCAL, PHOTO_HEIGHT / 2f / PHOTO_FOCAL)

    /** The marching cubes' surface of the room, its doubled walls appended, as the app meshes a recording. */
    fun raw(furnished: Boolean, depth: Depth = Depth.PHONE, reliefs: Boolean = false, driftedFloor: Boolean = false): RerunMesh =
        scan(furnished, depth, reliefs, driftedFloor).mesh

    /** A recording of the room as the result screen opens it: its TSDF, and the surface meshed from it. */
    class Scan(val tsdf: RerunTsdf, val mesh: RerunMesh)

    /** [raw], with the room's TSDF the surface was meshed from; [driftedFloor] adds [DRIFTED_FLOOR]'s second pass. */
    fun scan(furnished: Boolean, depth: Depth = Depth.PHONE, reliefs: Boolean = false, driftedFloor: Boolean = false): Scan {
        val room = fuse(ROOM.inflate(MARGIN_M), depth, seed = 11, reliefs = reliefs) { x, y, z -> shell(x, y, z, furnished, reliefs) }
        val meshes = mutableListOf(RerunMarchingCubes.extractWhole(room, FRAMES_MIN_WEIGHT).mesh)
        // The second passes: open sheets facing the room, 3 cm behind the wall they repeat.
        meshes += RerunMarchingCubes.extractWhole(
            fuse(DOUBLED_X_WALL.inflate(SHEET_MARGIN_M), depth, seed = 23, phase = SECOND_PASS_PHASE, region = DOUBLED_X_WALL) { x, _, _ ->
                x - DOUBLED_X_WALL.x0
            },
            FRAMES_MIN_WEIGHT,
        ).mesh
        meshes += RerunMarchingCubes.extractWhole(
            fuse(DOUBLED_Z_WALL.inflate(SHEET_MARGIN_M), depth, seed = 37, phase = SECOND_PASS_PHASE, region = DOUBLED_Z_WALL) { _, _, z ->
                DOUBLED_Z_WALL.z0 - z
            },
            FRAMES_MIN_WEIGHT,
        ).mesh
        if (driftedFloor) {
            meshes += RerunMarchingCubes.extractWhole(
                fuse(DRIFTED_FLOOR.inflate(SHEET_MARGIN_M), depth, seed = 53, phase = SECOND_PASS_PHASE, region = DRIFTED_FLOOR) { _, y, _ ->
                    y - DRIFTED_FLOOR.y0
                },
                FRAMES_MIN_WEIGHT,
            ).mesh
        }
        return Scan(room, concat(meshes))
    }

    /** Signed distance to the room's surfaces, positive in its free space. */
    fun shell(x: Float, y: Float, z: Float, furnished: Boolean, reliefs: Boolean = false): Float {
        var walls = -ROOM.sdf(x, y, z)
        if (reliefs) walls = max(min(walls, TV_BODY.sdf(x, y, z)), -DOOR_RECESS.sdf(x, y, z))
        if (!furnished) return walls
        return min(walls, min(curtain(x, y, z), sofa(x, y, z)))
    }

    /**
     * How a phone's depth errs, fused once per voxel as a recording's frames are (weight 1): a
     * drift of up to [driftM] across 20–45 cm ([swell]), blotches of up to [blotchM] about
     * [blotchCellM] wide (the depth map's own grain, upsampled), white noise of [whiteM] per voxel,
     * and [missedShare] of the voxels never seen, in patches about [missedCellM] wide — the
     * holes that leave a real wall as lace.
     */
    class Depth(
        val driftM: Float,
        val blotchM: Float,
        val blotchCellM: Float,
        val whiteM: Float,
        val missedShare: Float,
        val missedCellM: Float,
    ) {
        val maxErrorM = driftM + blotchM + whiteM + GRAIN_M

        fun error(x: Float, y: Float, z: Float, phase: Float, seed: Int): Float =
            swell(x, y, z, phase) * driftM / SWELL_PEAK + blotchM * lattice(x, y, z, blotchCellM, seed)

        /** Whether the voxel at ([x], [y], [z]) was never seen. */
        fun missed(x: Float, y: Float, z: Float, seed: Int): Boolean =
            missedShare > 0f && (lattice(x, y, z, missedCellM, seed * MISSED_SEED) + 1f) / 2f < missedShare

        companion object {
            /**
             * As the app's recordings fuse: about ±2 cm on a wall, and a third of it never seen,
             * in holes of about 8 cm — the lace a real wall comes out as.
             */
            val PHONE = Depth(
                driftM = 0.009f, blotchM = 0.009f, blotchCellM = 0.05f, whiteM = 0.003f,
                missedShare = 0.33f, missedCellM = 0.08f,
            )

            /** The swell alone, as the room was first written. */
            val SWELL = Depth(
                driftM = SWELL_PEAK, blotchM = 0f, blotchCellM = 1f, whiteM = 0.002f,
                missedShare = 0f, missedCellM = 1f,
            )
        }
    }

    /**
     * Smooth noise in -1..1, its features about [cellM] wide: random values on a lattice of
     * [cellM], smoothly interpolated between them.
     */
    fun lattice(x: Float, y: Float, z: Float, cellM: Float, seed: Int): Float {
        val fx = x / cellM
        val fy = y / cellM
        val fz = z / cellM
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val iz = floor(fz).toInt()
        val tx = smooth(fx - ix)
        val ty = smooth(fy - iy)
        val tz = smooth(fz - iz)
        var sum = 0f
        for (dz in 0..1) {
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val w = (if (dx == 0) 1f - tx else tx) * (if (dy == 0) 1f - ty else ty) * (if (dz == 0) 1f - tz else tz)
                    sum += w * hash(ix + dx, iy + dy, iz + dz, seed)
                }
            }
        }
        return sum
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun hash(x: Int, y: Int, z: Int, seed: Int): Float {
        var h = x * HASH_X + y * HASH_Y + z * HASH_Z + seed * HASH_SEED
        h = (h xor (h ushr HASH_SHIFT)) * HASH_MIX
        h = h xor (h ushr HASH_SHIFT)
        return (h and HASH_MASK) / HASH_MASK.toFloat() * 2f - 1f
    }

    /**
     * The depth's error on a surface at ([x], [y], [z]): a swell of up to ±1.7 cm across 20–45 cm
     * and a finer grain. [phase] sets a pass apart; a second pass repeats most of the first's
     * swell (the same sensor on the same wall), not all of it.
     */
    fun swell(x: Float, y: Float, z: Float, phase: Float = 0f): Float =
        SWELL_M * (
            sin(x * 14.7f + y * 5.3f + phase) * sin(z * 17.1f - y * 6.9f + 0.6f * phase) +
                SECOND_TERM * sin(x * 11.3f - z * 19.7f + y * 23.9f + 1.3f * phase)
            ) + GRAIN_M * sin(x * 59f + y * 47f - z * 53f + phase)

    /** The pleated curtain: a front waving 6 cm either way every 22 cm, 14 cm off the wall. */
    fun curtainFront(z: Float): Float = CURTAIN.x1 - CURTAIN_DEPTH_M - CURTAIN_PLEAT_M * sin(TWO_PI * (z - CURTAIN.z0) / CURTAIN_PERIOD_M)

    private fun curtain(x: Float, y: Float, z: Float): Float {
        val front = curtainFront(z)
        val slope = -CURTAIN_PLEAT_M * TWO_PI / CURTAIN_PERIOD_M * cos(TWO_PI * (z - CURTAIN.z0) / CURTAIN_PERIOD_M)
        val face = (front - x) / sqrt(1f + slope * slope)
        val span = maxOf(CURTAIN.y0 - y, y - CURTAIN.y1, CURTAIN.z0 - z, z - CURTAIN.z1)
        return maxOf(face, span)
    }

    /** The sofa: a base, two arms and a back, three seat cushions and three back cushions, all rounded. */
    private fun sofa(x: Float, y: Float, z: Float): Float {
        var d = Float.MAX_VALUE
        for ((box, radius) in SOFA_PARTS) d = min(d, rounded(box, radius, x, y, z))
        return d
    }

    private fun rounded(box: Box, radius: Float, x: Float, y: Float, z: Float): Float =
        Box(box.x0 + radius, box.y0 + radius, box.z0 + radius, box.x1 - radius, box.y1 - radius, box.z1 - radius)
            .sdf(x, y, z) - radius

    /**
     * Fuses [sdf] over [bounds] with the depth's error ([swell] at [phase], then per-voxel noise
     * seeded by [seed]); only voxels inside [region], when given, are seen.
     */
    private fun fuse(
        bounds: Box,
        depth: Depth,
        seed: Int,
        phase: Float = 0f,
        region: Box? = null,
        reliefs: Boolean = false,
        sdf: (Float, Float, Float) -> Float,
    ): RerunTsdf {
        val tsdf = RerunTsdf(maxBytes = TSDF_BYTES)
        val voxel = tsdf.voxelM
        val band = tsdf.truncationM * BAND_TRUNCATIONS + depth.maxErrorM
        val random = Random(seed)
        for (gz in floor(bounds.z0 / voxel).toInt()..floor(bounds.z1 / voxel).toInt() + 1) {
            for (gy in floor(bounds.y0 / voxel).toInt()..floor(bounds.y1 / voxel).toInt() + 1) {
                for (gx in floor(bounds.x0 / voxel).toInt()..floor(bounds.x1 / voxel).toInt() + 1) {
                    val x = gx * voxel
                    val y = gy * voxel
                    val z = gz * voxel
                    if (region != null && !region.spans(x, y, z)) continue
                    val clean = sdf(x, y, z)
                    if (abs(clean) > band) continue
                    if (depth.missed(x, y, z, seed)) continue
                    val error = if (phase == 0f) {
                        depth.error(x, y, z, 0f, seed)
                    } else {
                        SHARED_SWELL * depth.error(x, y, z, 0f, seed) + (1f - SHARED_SWELL) * depth.error(x, y, z, phase, seed + 1)
                    }
                    val d = clean + error + (random.nextFloat() * 2f - 1f) * depth.whiteM
                    tsdf.update(gx, gy, gz, d, 1f, paint(x, y, z, reliefs))
                }
            }
        }
        return tsdf
    }

    /** Whether ([x], [y], [z]) lies within this patch's extent on its two long axes. */
    private fun Box.spans(x: Float, y: Float, z: Float): Boolean = when {
        x0 == x1 -> y in y0..y1 && z in z0..z1
        y0 == y1 -> x in x0..x1 && z in z0..z1
        z0 == z1 -> x in x0..x1 && y in y0..y1
        else -> x in x0..x1 && y in y0..y1 && z in z0..z1
    }

    private fun Box.inflate(m: Float) = Box(x0 - m, y0 - m, z0 - m, x1 + m, y1 + m, z1 + m)

    private fun concat(meshes: List<RerunMesh>): RerunMesh {
        val vertices = meshes.sumOf { it.vertexCount }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        val indices = IntArray(meshes.sumOf { it.indices.size })
        var v = 0
        var i = 0
        for (mesh in meshes) {
            mesh.positions.copyInto(positions, v * 3)
            mesh.normals.copyInto(normals, v * 3)
            mesh.colors.copyInto(colors, v)
            for (k in mesh.indices.indices) indices[i + k] = mesh.indices[k] + v
            v += mesh.vertexCount
            i += mesh.indices.size
        }
        return RerunMesh(positions, normals, colors, indices)
    }

    // ── Key photos ─────────────────────────────────────────────────────────────

    /** The key photos, each ray-cast only when the texture asks for it, as a recording's are decoded. */
    fun modelPhotos(furnished: Boolean, reliefs: Boolean = false): ModelPhotos {
        val scene = scene(furnished, reliefs)
        return ModelPhotos(PHOTO_LENS, poses().mapIndexed { i, pose -> ModelPhoto(pose) { photo(scene, pose, i) } })
    }

    /**
     * Where the key photos are taken: along each wall, 1.1 m from it, every metre, turned 40° to
     * one side then the other, at chest and eye height; then the floor and the ceiling.
     */
    fun poses(): List<DebugPose> {
        val poses = ArrayList<DebugPose>()
        var k = 0
        fun stop(x: Float, z: Float, facing: Float) {
            for (side in intArrayOf(-1, 1)) {
                val height = if (k % 2 == 0) LOW_EYE_M else HIGH_EYE_M
                val pitch = if (k % 2 == 0) -LEVEL_PITCH else LEVEL_PITCH / 2f
                poses += RerunSyntheticRoom.lookPose(Vec3(x, height, z), facing + side * ANGLED, pitch)
                k++
            }
        }
        for (x in STOPS_X) stop(x, ROOM.z0 + STANDOFF_M, facing = 0f)
        for (x in STOPS_X) stop(x, ROOM.z1 - STANDOFF_M, facing = PI.toFloat())
        for (z in STOPS_Z) stop(ROOM.x0 + STANDOFF_M, z, facing = PI.toFloat() / 2f)
        for (z in STOPS_Z) stop(ROOM.x1 - STANDOFF_M, z, facing = -PI.toFloat() / 2f)
        for ((i, z) in STOPS_Z.withIndex()) {
            for (x in floatArrayOf(-1.2f, 1.2f)) {
                poses += RerunSyntheticRoom.lookPose(Vec3(x, HIGH_EYE_M, z), i * TURN, FLOOR_PITCH)
            }
            if (i % 2 == 0) poses += RerunSyntheticRoom.lookPose(Vec3(0f, LOW_EYE_M, z), i * TURN, CEILING_PITCH)
        }
        return poses
    }

    private fun photo(scene: SyntheticScene, pose: DebugPose, index: Int): BakePhoto {
        val frame = scene.render(
            pose, PHOTO_WIDTH, PHOTO_HEIGHT, PHOTO_FOCAL, PHOTO_FOCAL,
            PHOTO_WIDTH / 2f - HALF_PIXEL, PHOTO_HEIGHT / 2f - HALF_PIXEL,
        )
        val gain = 1f + EXPOSURE_SPREAD * sin(index * EXPOSURE_STEP)
        val argb = IntArray(frame.colors.size) { p ->
            val c = frame.colors[p]
            val grain = ((p * GRAIN_HASH + index * INDEX_HASH) ushr GRAIN_SHIFT) % GRAIN_LEVELS - GRAIN_LEVELS / 2
            val r = ((c shr 16 and 0xFF) * gain + grain).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 0xFF) * gain + grain).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * gain + grain).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return BakePhoto(pose, PHOTO_WIDTH, PHOTO_HEIGHT, argb)
    }

    /**
     * What the photos see: the room's shell, and its furniture as boxes, coloured. The door is
     * painted on its wall: 3.5 cm of recess moves its picture by under a pixel.
     */
    private fun scene(furnished: Boolean, reliefs: Boolean): SyntheticScene {
        val shell = SyntheticShape.Room(Vec3(ROOM.x0, ROOM.y0, ROOM.z0), Vec3(ROOM.x1, ROOM.y1, ROOM.z1)) { x, y, z ->
            paint(x, y, z, reliefs)
        }
        val tv = SyntheticShape.Box(Vec3(TV.x0, TV.y0, TV.z0), Vec3(TV.x1, TV.y1, TV.z1)) { x, y, z -> paint(x, y, z, reliefs) }
        val reliefShapes = if (reliefs) listOf(tv) else emptyList()
        if (!furnished) return SyntheticScene(listOf(shell) + reliefShapes)
        val curtain = SyntheticShape.Box(
            Vec3(CURTAIN.x0, CURTAIN.y0, CURTAIN.z0),
            Vec3(CURTAIN.x1, CURTAIN.y1, CURTAIN.z1),
        ) { x, y, z -> paint(x, y, z) }
        val sofa = SOFA_PARTS.map { (box, _) ->
            SyntheticShape.Box(Vec3(box.x0, box.y0, box.z0), Vec3(box.x1, box.y1, box.z1)) { x, y, z -> paint(x, y, z) }
        }
        return SyntheticScene(listOf(shell, curtain) + sofa + reliefShapes)
    }

    /** The scene's colours: planks on the floor, paint on the walls, a red curtain, a green sofa, a black TV, a wooden door. */
    fun paint(x: Float, y: Float, z: Float, reliefs: Boolean = false): Int = when {
        x in CURTAIN.x0 - PAINT_MARGIN_M..CURTAIN.x1 - SURFACE_M && y in CURTAIN.y0..CURTAIN.y1 && z in CURTAIN.z0..CURTAIN.z1 ->
            if (sin(TWO_PI * (z - CURTAIN.z0) / CURTAIN_PERIOD_M) > 0f) CURTAIN_LIGHT else CURTAIN_DARK
        x in SOFA.x0..SOFA.x1 && y in SOFA.y0 + SURFACE_M..SOFA.y1 + PAINT_MARGIN_M && z in SOFA.z0 + SURFACE_M..SOFA.z1 + PAINT_MARGIN_M ->
            SOFA_GREEN
        reliefs && x > TV.x0 - PAINT_MARGIN_M && y in TV.y0..TV.y1 && z in TV.z0..TV.z1 -> TV_BLACK
        y < SURFACE_M + MAX_ERROR_M -> if (floor((x - ROOM.x0) / PLANK_M).toInt() % 2 == 0) OAK else OAK_DARK
        y > ROOM.y1 - SURFACE_M - MAX_ERROR_M -> CEILING
        reliefs && z < ROOM.z0 + PAINT_MARGIN_M && x in DOOR.x0..DOOR.x1 && y < DOOR.y1 -> DOOR_WOOD
        z > ROOM.z1 - SURFACE_M - MAX_ERROR_M -> BLUE_GREY
        else -> WALL
    }

    private val SOFA_PARTS: List<Pair<Box, Float>> = buildList {
        add(Box(-1.0f, 0f, -3.5f, 1.0f, 0.28f, -2.6f) to 0.04f) // base
        add(Box(-1.0f, 0f, -3.5f, -0.8f, 0.62f, -2.6f) to 0.06f) // arms
        add(Box(0.8f, 0f, -3.5f, 1.0f, 0.62f, -2.6f) to 0.06f)
        add(Box(-0.8f, 0.28f, -3.5f, 0.8f, 0.85f, -3.28f) to 0.06f) // back
        for ((x0, x1) in listOf(-0.79f to -0.27f, -0.26f to 0.26f, 0.27f to 0.79f)) {
            add(Box(x0, 0.26f, -3.28f, x1, 0.46f, -2.62f) to 0.07f) // seat cushions
            add(Box(x0, 0.46f, -3.28f, x1, 0.84f, -3.12f) to 0.06f) // back cushions
        }
    }

    /** The TV's body, run into the wall behind it; the door's recess, open to the room in front of it. */
    private val TV_BODY = Box(TV.x0, TV.y0, TV.z0, TV.x1 + MARGIN_M, TV.y1, TV.z1)
    private val DOOR_RECESS = Box(DOOR.x0, DOOR.y0, DOOR.z0, DOOR.x1, DOOR.y1, DOOR.z1 + MARGIN_M)

    /** As the app meshes a recording's depth frames (RerunModelUi's FRAMES_MIN_WEIGHT). */
    private const val FRAMES_MIN_WEIGHT = 1f
    private const val TSDF_BYTES = 256L * 1024 * 1024
    private const val MARGIN_M = 0.2f
    private const val SHEET_MARGIN_M = 0.15f
    private const val BAND_TRUNCATIONS = 1.5f

    private const val SWELL_M = 0.0105f
    private const val SECOND_TERM = 0.6f
    private const val GRAIN_M = 0.0012f
    private const val SWELL_PEAK = SWELL_M * (1f + SECOND_TERM)
    private const val MAX_ERROR_M = 0.04f
    private const val MISSED_SEED = 7919
    private const val HASH_X = 73856093
    private const val HASH_Y = 19349663
    private const val HASH_Z = 83492791
    private const val HASH_SEED = 2654435
    private const val HASH_SHIFT = 15
    private const val HASH_MIX = 0x2C1B3C6D
    private const val HASH_MASK = 0xFFFFFF
    private const val SECOND_PASS_PHASE = 2.1f
    private const val SHARED_SWELL = 0.6f

    private const val CURTAIN_DEPTH_M = 0.14f
    private const val CURTAIN_PLEAT_M = 0.06f
    private const val CURTAIN_PERIOD_M = 0.22f
    private const val TWO_PI = (2 * PI).toFloat()

    private val STOPS_X = floatArrayOf(-2f, -1f, 0f, 1f, 2f)
    private val STOPS_Z = floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f)
    private const val STANDOFF_M = 1.1f
    private const val LOW_EYE_M = 1.0f
    private const val HIGH_EYE_M = 1.5f
    private const val ANGLED = (40 * PI / 180).toFloat()
    private const val LEVEL_PITCH = (10 * PI / 180).toFloat()
    private const val FLOOR_PITCH = (-55 * PI / 180).toFloat()
    private const val CEILING_PITCH = (50 * PI / 180).toFloat()
    private const val TURN = 1.3f
    private const val HALF_PIXEL = 0.5f
    private const val EXPOSURE_SPREAD = 0.12f
    private const val EXPOSURE_STEP = 1.7f
    private const val GRAIN_HASH = -0x61c88647
    private const val INDEX_HASH = 0x2545F491
    private const val GRAIN_SHIFT = 20
    private const val GRAIN_LEVELS = 13

    private const val SURFACE_M = 1e-3f
    private const val PAINT_MARGIN_M = 0.05f
    private const val PLANK_M = 0.18f

    // Scene content, not UI: the room's colours.
    private const val WALL = 0xFFE6E0D4.toInt()
    private const val BLUE_GREY = 0xFF8C9AA6.toInt()
    private const val CEILING = 0xFFF4F2EE.toInt()
    private const val OAK = 0xFFB58A5A.toInt()
    private const val OAK_DARK = 0xFF9C7448.toInt()
    private const val CURTAIN_LIGHT = 0xFFA33A3A.toInt()
    private const val CURTAIN_DARK = 0xFF7A2626.toInt()
    private const val SOFA_GREEN = 0xFF5E7A62.toInt()
    private const val TV_BLACK = 0xFF26282B.toInt()
    private const val DOOR_WOOD = 0xFF7B5A3C.toInt()
}
