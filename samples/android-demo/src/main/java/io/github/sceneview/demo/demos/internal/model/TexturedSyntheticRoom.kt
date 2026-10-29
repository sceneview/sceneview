package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.SyntheticScene
import io.github.sceneview.demo.demos.internal.SyntheticShape
import kotlin.math.sin

/**
 * [RerunSyntheticRoom] with detail finer than the model's voxels — a framed print of 5 cm checks
 * on the sage wall, dark seams between the floor planks — and the key photos a recording keeps:
 * what a texture is for, where no camera sees a room (the JVM tests and the emulator's QA state).
 * The shared room itself is left as it is: its other users (the plan, the survey, the walls)
 * keep their scene.
 */
internal object TexturedSyntheticRoom {
    const val PHOTO_WIDTH = 240
    const val PHOTO_HEIGHT = 320

    /** The key photos' lens: upright [PHOTO_WIDTH] × [PHOTO_HEIGHT], a phone's ~63° tall view. */
    val PHOTO_LENS = ReplayLens(PHOTO_WIDTH / 2f / PHOTO_FOCAL, PHOTO_HEIGHT / 2f / PHOTO_FOCAL)

    /** The room with its print and seams: [RerunSyntheticRoom]'s shell repainted, its furniture as is. */
    val scene: SyntheticScene by lazy {
        val shapes = RerunSyntheticRoom.scene.shapes
        val shell = shapes.first() as SyntheticShape.Room
        val decorated = SyntheticShape.Room(shell.min, shell.max) { x, y, z ->
            decor(x, y, z) ?: shell.color(x, y, z)
        }
        SyntheticScene(listOf(decorated) + shapes.drop(1))
    }

    /** The scan's depth frames, along [RerunSyntheticRoom.poses]' walk. */
    fun frames(): Sequence<DepthFrame> = RerunSyntheticRoom.poses().asSequence().map { scene.render(it) }

    /**
     * The walk's key photos: every [every]th of its poses, ray-cast upright through [PHOTO_LENS],
     * each with its own exposure — up to [exposureSpread] brighter or darker, as a phone's auto
     * exposure drifts across a room — so a texture has seams to level.
     */
    fun photos(every: Int = PHOTO_EVERY, exposureSpread: Float = EXPOSURE_SPREAD): List<BakePhoto> =
        RerunSyntheticRoom.poses().filterIndexed { i, _ -> i % every == 0 }.mapIndexed { i, pose ->
            // Pixel centres at half-pixels: the mapping a photo is projected with (PlaneBake).
            val frame = scene.render(
                pose, PHOTO_WIDTH, PHOTO_HEIGHT, PHOTO_FOCAL, PHOTO_FOCAL,
                PHOTO_WIDTH / 2f - HALF_PIXEL, PHOTO_HEIGHT / 2f - HALF_PIXEL,
            )
            val gain = 1f + exposureSpread * sin(i * EXPOSURE_STEP)
            val argb = IntArray(frame.colors.size) { p ->
                val c = frame.colors[p]
                val r = ((c shr 16 and 0xFF) * gain).toInt().coerceIn(0, 255)
                val g = ((c shr 8 and 0xFF) * gain).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * gain).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            BakePhoto(pose, PHOTO_WIDTH, PHOTO_HEIGHT, argb)
        }

    /** Whether ([x], [y]) on the sage wall lies on the print, its frame included. */
    fun onPoster(x: Float, y: Float) = x in POSTER_X0..POSTER_X1 && y in POSTER_Y0..POSTER_Y1

    /** The print's and the seams' colours, `null` where the shared room's own paint shows. */
    private fun decor(x: Float, y: Float, z: Float): Int? = when {
        y < SURFACE -> seam(x, z)
        y > RerunSyntheticRoom.MAX.y - SURFACE -> null
        z < RerunSyntheticRoom.MIN.z + SURFACE -> poster(x, y)
        else -> null
    }

    /** A dark gap between two planks; the rug covers the floor's middle and has none. */
    private fun seam(x: Float, z: Float): Int? {
        if (x in RUG_X0..RUG_X1 && z in RUG_Z0..RUG_Z1) return null
        val along = (x - RerunSyntheticRoom.MIN.x) / PLANK_M
        return if ((along - along.toInt()) * PLANK_M < SEAM_M) SEAM else null
    }

    private fun poster(x: Float, y: Float): Int? {
        if (!onPoster(x, y)) return null
        val inset = minOf(x - POSTER_X0, POSTER_X1 - x, y - POSTER_Y0, POSTER_Y1 - y)
        if (inset < FRAME_M) return FRAME
        val check = ((x - POSTER_X0) / CHECK_M).toInt() + ((y - POSTER_Y0) / CHECK_M).toInt()
        return if (check % 2 == 0) PRINT_LIGHT else PRINT_DARK
    }

    private const val SURFACE = 1e-3f

    // The shared room's planks and rug (RerunSyntheticRoom.floor), which the seams follow.
    private const val PLANK_M = 0.18f
    private const val RUG_X0 = -1.2f
    private const val RUG_X1 = 0.9f
    private const val RUG_Z0 = -0.9f
    private const val RUG_Z1 = 1.1f

    private const val SEAM_M = 0.008f
    private const val POSTER_X0 = 0.2f
    private const val POSTER_X1 = 1.4f
    private const val POSTER_Y0 = 1.1f
    private const val POSTER_Y1 = 1.9f
    private const val FRAME_M = 0.03f
    private const val CHECK_M = 0.05f
    private const val PHOTO_FOCAL = 260f
    private const val PHOTO_EVERY = 3
    private const val EXPOSURE_SPREAD = 0.12f
    private const val EXPOSURE_STEP = 1.7f
    private const val HALF_PIXEL = 0.5f

    // Scene content, not UI: the colours of the print and the seams.
    private const val SEAM = 0xFF5C4128.toInt()
    private const val FRAME = 0xFF2B2B2B.toInt()
    private const val PRINT_LIGHT = 0xFFF1E3C6.toInt()
    private const val PRINT_DARK = 0xFF2F4F6F.toInt()
}
