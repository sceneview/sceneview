package io.github.sceneview.demo.demos.internal

/**
 * A cupboard under a sink, in a run of kitchen units against a wall: 60 cm wide, 56 high and
 * 50 deep inside, a drain pipe hanging in it. The stand-in for a real one where no camera films
 * one: the JVM tests, and the QA state of a cupboard's result. [frames] films it as its capture
 * asks, from before its opening: the back, the bottom, each side, the top.
 */
object RerunSyntheticCupboard {
    /** The inside: across (x, centred on 0), upright (y, from [BOTTOM]), and from the wall (z = 0) to the opening. */
    const val WIDTH = 0.6f
    const val HEIGHT = 0.56f
    const val DEPTH = 0.5f

    /** The inside's bottom above the kitchen floor: the plinth. */
    const val BOTTOM = 0.12f

    /** The drain pipe, in the inside's own frame: from its back-left-bottom corner. */
    val PIPE = SpaceBox(x = 0.26f, y = 0.24f, z = 0.1f, width = 0.08f, height = 0.32f, depth = 0.08f)

    /** Where the phone is held: before the opening, at arm's length from it. */
    val EYE = Vec3(0f, 0.45f, DEPTH + 0.6f)

    /**
     * The kitchen. With [backPanelM], the cupboard has a back of its own that far before the wall,
     * as most units do: its inside is that much less deep.
     */
    fun scene(backPanelM: Float = 0f): SyntheticScene = SyntheticScene(shapes(backPanelM))

    /** A capture's depth frames, in the order a phone would take them. */
    fun frames(poses: List<DebugPose> = poses(), scene: SyntheticScene = scene()): Sequence<DepthFrame> =
        poses.asSequence().map { scene.render(it) }

    /** [frames]' depth, fused into coloured points as a recording fuses them. */
    fun cloud(frames: Sequence<DepthFrame> = frames()): DenseCloud {
        val fusion = DenseFusion()
        frames.forEach { fusion.add(DepthBackProjection.project(it)) }
        return fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
    }

    /** How [poses]' capture was filmed, read off them as off a recording's path. */
    fun view(poses: List<DebugPose> = poses()): SpaceView {
        val trace = ArDebugTrace()
        poses.forEachIndexed { i, pose -> trace.addPose(i * FRAME_NANOS, pose) }
        return checkNotNull(SpaceFitter.viewOf(trace))
    }

    /**
     * The phone turned to each face in turn, an arm's sway from [eye] and looking [tiltDeg] up
     * from level on the whole: turned to a side it leans the other way, tilted down it rises, as
     * a hand does to see a face less at a slant.
     */
    fun poses(eye: Vec3 = EYE, tiltDeg: Int = 0): List<DebugPose> = buildList {
        for (pitch in PITCHES_DEG) {
            for (yaw in YAWS_DEG) {
                val at = Vec3(eye.x + SWAY_M * yaw / YAWS_DEG.last(), eye.y - SWAY_M * pitch / PITCHES_DEG.last(), eye.z)
                add(
                    RerunSyntheticRoom.lookPose(
                        at,
                        Math.toRadians(yaw.toDouble()).toFloat(),
                        Math.toRadians((tiltDeg + pitch).toDouble()).toFloat(),
                    ),
                )
            }
        }
    }

    private fun shapes(backPanelM: Float): List<SyntheticShape> {
        val half = WIDTH / 2
        val top = BOTTOM + HEIGHT
        val panel = if (backPanelM > 0f) {
            listOf(SyntheticShape.Box(Vec3(-half, BOTTOM, 0f), Vec3(half, top, backPanelM)) { _, _, _ -> UNIT })
        } else {
            emptyList()
        }
        return panel + listOf(
            // The kitchen: its floor, and the wall the units stand against.
            SyntheticShape.Room(Vec3(-KITCHEN_HALF_M, 0f, 0f), Vec3(KITCHEN_HALF_M, CEILING_M, KITCHEN_DEPTH_M)) { _, y, _ ->
                if (y < SURFACE) TILE else WALL
            },
            // The units either side, their doors shut, and the plinth under the cupboard.
            SyntheticShape.Box(Vec3(-half - UNIT_M, 0f, 0f), Vec3(-half, top, DEPTH)) { _, _, _ -> UNIT },
            SyntheticShape.Box(Vec3(half, 0f, 0f), Vec3(half + UNIT_M, top, DEPTH)) { _, _, _ -> UNIT },
            SyntheticShape.Box(Vec3(-half, 0f, 0f), Vec3(half, BOTTOM, DEPTH)) { _, _, _ -> UNIT_DARK },
            // The worktop over them all.
            SyntheticShape.Box(Vec3(-half - UNIT_M, top, 0f), Vec3(half + UNIT_M, top + WORKTOP_M, DEPTH + OVERHANG_M)) { _, _, _ -> WORKTOP },
            SyntheticShape.Box(
                Vec3(-half + PIPE.x, BOTTOM + PIPE.y, PIPE.z),
                Vec3(-half + PIPE.x + PIPE.width, BOTTOM + PIPE.y + PIPE.height, PIPE.z + PIPE.depth),
            ) { _, _, _ -> PIPE_GREY },
        )
    }

    private val YAWS_DEG = listOf(-33, -22, -11, 0, 11, 22, 33)
    private val PITCHES_DEG = listOf(-24, -12, 0, 12, 24)
    private const val SWAY_M = 0.15f
    private const val FRAME_NANOS = 100_000_000L
    private const val KITCHEN_HALF_M = 1.6f
    private const val KITCHEN_DEPTH_M = 2.6f
    private const val CEILING_M = 2.4f
    private const val UNIT_M = 0.6f
    private const val WORKTOP_M = 0.04f
    private const val OVERHANG_M = 0.02f
    private const val SURFACE = 1e-3f

    // Scene content, not UI: the colours of the things in the kitchen.
    private const val TILE = 0xFFB9B4AA.toInt()
    private const val WALL = 0xFFE8E2D6.toInt()
    private const val UNIT = 0xFFF1EEE8.toInt()
    private const val UNIT_DARK = 0xFFD8D3C9.toInt()
    private const val WORKTOP = 0xFF6B5B4B.toInt()
    private const val PIPE_GREY = 0xFF9AA0A6.toInt()
}
