package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The texture's budget on the ray-cast room, its walk's photos mixed as a recording keeps them —
 * a few sharp ones three times as wide among the small ones: within [TextureBudget.BASE] (a
 * Pixel 4a's heap) and [TextureBudget.LARGE] (256 MB and more). Readability is read where the
 * room has detail finer than its voxels — the print's 5 cm checks, the floor's 8 mm plank seams —
 * as the correlation between the luma the model shows and the room's true paint, densely sampled
 * across each face: blur lowers it, exposure does not.
 */
class TextureBudgetTest {
    @Test
    fun `the heap picks the budget, a Pixel 4a keeping the small one`() {
        assertEquals(TextureBudget.BASE, TextureBudget.forHeap(192L * MB, GPU_4096))
        assertEquals(TextureBudget.BASE, TextureBudget.forHeap(255L * MB, GPU_4096))
        assertEquals(TextureBudget.LARGE, TextureBudget.forHeap(256L * MB, GPU_4096))
        assertEquals(TextureBudget.LARGE, TextureBudget.forHeap(512L * MB, GPU_4096))
        assertEquals(PhotoTexturing.MAX_PHOTO_PIXELS, TextureBudget.BASE.photoPixels)
    }

    @Test
    fun `the atlas stays within the GPU's largest texture, unknown counting as the least`() {
        val gpu2048 = TextureBudget.forHeap(512L * MB, maxTextureSize = TextureBudget.BASE.atlasSize)
        assertEquals(TextureBudget.BASE.atlasSize, gpu2048.atlasSize)
        assertEquals("the photos still kept whole", TextureBudget.LARGE.photoPixels, gpu2048.photoPixels)
        assertEquals(gpu2048, TextureBudget.forHeap(512L * MB, maxTextureSize = 0))
        assertEquals(TextureBudget.LARGE, TextureBudget.forHeap(512L * MB, maxTextureSize = 16384))
    }

    /**
     * The sharp photos burnt out (each channel ×[BURN], clipped): a large photo's resolution must
     * not outweigh the penalty of its clipped views, or the large budget paints the walls white
     * where the small one keeps the small photos' colours.
     */
    @Test
    fun `a large burnt-out photo does not beat a small exposed one`() {
        val raw = room()
        val count = (RerunSyntheticRoom.poses().size + PHOTO_EVERY_KEPT - 1) / PHOTO_EVERY_KEPT
        val every = maxOf(1, count / SHARP_PHOTOS_RECORDED)
        val shots = TexturedSyntheticRoom.photos(every = PHOTO_EVERY_KEPT, sharpEvery = every, sharpScale = SHARP_SCALE)
        val photos = ModelPhotos(
            TexturedSyntheticRoom.PHOTO_LENS,
            shots.map { shot ->
                val burnt = if (shot.width > TexturedSyntheticRoom.PHOTO_WIDTH) burn(shot) else shot
                ModelPhoto(shot.pose) { burnt }
            },
        )
        val base = measure(raw, photos, TextureBudget.BASE)
        val large = measure(raw, photos, TextureBudget.LARGE)
        println("texture budget, sharp photos burnt out:")
        println("  base:  $base")
        println("  large: $large")
        assertTrue("print ${base.print} -> ${large.print}", large.print >= base.print - CORRELATION_SLACK)
        assertTrue("planks ${base.planks} -> ${large.planks}", large.planks >= base.planks - CORRELATION_SLACK)
    }

    @Test
    fun `writing out of memory within the large budget falls back to the small one`() {
        val raw = room()
        var thrown = false
        val encoder = AtlasEncoder { argb, width, height ->
            if (!thrown) {
                thrown = true
                throw OutOfMemoryError("synthetic")
            }
            JvmAtlasEncoder.jpeg(argb, width, height)
        }
        val result = CleanModel.build(raw, mixedPhotos(PHOTO_EVERY_KEPT), encoder, budget = TextureBudget.LARGE)
        assertTrue(thrown)
        assertFalse(result.stats.rawKept)
        assertTrue(result.stats.textureBudgetFellBack)
        assertEquals(TextureBudget.BASE, result.stats.textureBudget)
        assertNotNull("still textured", result.stats.texture)
        assertTrue(result.stats.atlasWidth <= TextureBudget.BASE.atlasSize)
        assertTrue(ModelGlb.isTextured(result.glb))
    }

    private fun burn(shot: BakePhoto): BakePhoto {
        val argb = IntArray(shot.argb.size) { i ->
            val c = shot.argb[i]
            val r = minOf(BYTE, (c shr 16 and BYTE) * BURN)
            val g = minOf(BYTE, (c shr 8 and BYTE) * BURN)
            val b = minOf(BYTE, (c and BYTE) * BURN)
            (c and OPAQUE) or (r shl 16) or (g shl 8) or b
        }
        return BakePhoto(shot.pose, shot.width, shot.height, argb)
    }

    @Test
    fun `the large budget's photos stay within its total, however many are large`() {
        val budget = TextureBudget.LARGE
        val count = PhotoTexturing.MAX_PHOTOS
        var used = 0L
        var whole = 0
        for (i in 0 until count) {
            val cap = budget.photoCap(used, count - i - 1)
            val kept = PhotoTexturing.downscaled(BakePhoto(ORIGIN, SHARP_W, SHARP_H, IntArray(SHARP_W * SHARP_H)), cap)
            if (kept.width == SHARP_W) whole++
            used += kept.width.toLong() * kept.height
        }
        assertTrue("$used photo pixels", used <= budget.totalPhotoPixels)
        // The recorder keeps at most 15 sharp photos: all of them whole.
        assertTrue("$whole whole", whole >= SHARP_PHOTOS_RECORDED)
    }

    @Test
    fun `the large budget makes the room's detail readable, within the file's cap`() {
        val raw = room()
        // All the photos kept: a recording of at most MAX_PHOTOS of them.
        val photos = mixedPhotos(PHOTO_EVERY_KEPT)
        assertTrue(photos.photos.size <= PhotoTexturing.MAX_PHOTOS)
        val base = measure(raw, photos, TextureBudget.BASE, shot = "base")
        val large = measure(raw, photos, TextureBudget.LARGE, shot = "large")
        println("texture budget, synthetic room (${photos.photos.size} photos, $sharpCount at ${SHARP_W}x$SHARP_H):")
        println("  base:  $base")
        println("  large: $large")

        assertTrue("texels/m ${base.texelsPerMetre} -> ${large.texelsPerMetre}", large.texelsPerMetre > base.texelsPerMetre * MIN_TEXEL_GAIN)
        assertTrue("print ${base.print} -> ${large.print}", large.print > base.print)
        assertTrue("planks ${base.planks} -> ${large.planks}", large.planks >= base.planks - CORRELATION_SLACK)
        assertTrue("glb ${large.glbBytes}", large.glbBytes <= CleanModel.MAX_GLB_BYTES)
        assertEquals("no halving needed", 0, large.halvings)
        assertTrue("seams ${base.seamFinal} -> ${large.seamFinal}", large.seamFinal <= base.seamFinal + SEAM_SLACK)
    }

    /**
     * More photos than [PhotoTexturing.MAX_PHOTOS]: the photos kept are chosen on their poses
     * alone, the sharp ones not preferred — what this measures, without asserting a gain.
     */
    @Test
    fun `more photos than kept, the sharp ones are chosen on their poses alone`() {
        val raw = room()
        val photos = mixedPhotos()
        assertTrue(photos.photos.size > PhotoTexturing.MAX_PHOTOS)
        val base = measure(raw, photos, TextureBudget.BASE)
        val large = measure(raw, photos, TextureBudget.LARGE)
        println("texture budget, synthetic room, more photos than kept (${photos.photos.size} photos, $sharpCount at ${SHARP_W}x$SHARP_H):")
        println("  base:  $base")
        println("  large: $large")
        assertTrue("texels/m ${base.texelsPerMetre} -> ${large.texelsPerMetre}", large.texelsPerMetre >= base.texelsPerMetre)
        assertTrue("glb ${large.glbBytes}", large.glbBytes <= CleanModel.MAX_GLB_BYTES)
    }

    @Test
    fun `small photos only, the large budget changes nothing but the atlas's room`() {
        val raw = room()
        val shots = TexturedSyntheticRoom.photos()
        val photos = ModelPhotos(TexturedSyntheticRoom.PHOTO_LENS, shots.map { shot -> ModelPhoto(shot.pose) { shot } })
        val base = measure(raw, photos, TextureBudget.BASE)
        val large = measure(raw, photos, TextureBudget.LARGE)
        println("texture budget, synthetic room, small photos only:\n  base:  $base\n  large: $large")
        assertEquals(base.texelsPerMetre, large.texelsPerMetre, base.texelsPerMetre * 0.01f)
    }

    @Test
    fun `texturing out of memory within the large budget falls back to the small one`() {
        val raw = room()
        val shots = TexturedSyntheticRoom.photos()
        var thrown = false
        val photos = ModelPhotos(
            TexturedSyntheticRoom.PHOTO_LENS,
            shots.map { shot ->
                ModelPhoto(shot.pose) {
                    if (!thrown) {
                        thrown = true
                        throw OutOfMemoryError("synthetic")
                    }
                    shot
                }
            },
        )
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder, budget = TextureBudget.LARGE)
        assertTrue(thrown)
        assertFalse(result.stats.rawKept)
        assertTrue(result.stats.textureBudgetFellBack)
        assertEquals(TextureBudget.BASE, result.stats.textureBudget)
        assertNotNull("still textured", result.stats.texture)
        assertTrue(ModelGlb.isTextured(result.glb))
    }

    @Test
    fun `texturing out of memory within the small budget still keeps the raw surface`() {
        val raw = room()
        val shots = TexturedSyntheticRoom.photos()
        val photos = ModelPhotos(
            TexturedSyntheticRoom.PHOTO_LENS,
            shots.map { shot -> ModelPhoto(shot.pose) { throw OutOfMemoryError("synthetic") } },
        )
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder, budget = TextureBudget.BASE)
        assertTrue(result.stats.rawKept)
        assertEquals(CleanModel.Stage.Texture, result.stats.outOfMemoryIn)
        assertFalse(result.stats.textureBudgetFellBack)
    }

    /**
     * The finish within the heap's own budget, its heap's peak read: what `-Xmx192m` and
     * `-Xmx512m` runs of this test compare (see the PR). Under Gradle's 2 GB, the large budget.
     */
    @Test
    @Suppress("ExplicitGarbageCollectionCall")
    fun `the heap's own budget, its peak reported`() {
        val raw = room()
        val photos = mixedPhotos()
        // The GPU of a recent phone: the heap alone picks the budget.
        TextureBudget.gpuMaxTextureSize = GPU_4096
        System.gc()
        resetPeaks()
        val start = System.nanoTime()
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder)
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        val stats = result.stats
        println(
            "texture budget, heap ${Runtime.getRuntime().maxMemory() / MB} MB: budget ${stats.textureBudget.atlasSize} atlas, " +
                "fell back ${stats.textureBudgetFellBack}, raw kept ${stats.rawKept}, " +
                "atlas ${stats.atlasWidth}x${stats.atlasHeight}, " +
                "texels/m ${"%.0f".format(stats.texture?.texelsPerMetre ?: 0f)}, glb ${stats.glbBytes / KB} KB, " +
                "finish $ms ms (texture ${stats.textureMs} ms), heap peak ${peakHeap() / MB} MB",
        )
        assertTrue(stats.glbBytes <= CleanModel.MAX_GLB_BYTES)
    }

    private class Measure(
        val texelsPerMetre: Float,
        val print: Double,
        val planks: Double,
        val seamRaw: Float,
        val seamFinal: Float,
        val atlas: String,
        val glbBytes: Int,
        val halvings: Int,
        val textureMs: Long,
        val totalMs: Long,
        val photosUsed: Int,
    ) {
        override fun toString() =
            "texels/m %.0f, print r %.3f, planks r %.3f, seams %.1f -> %.1f, atlas %s, glb %d KB, halvings %d, texture %d ms, finish %d ms, %d photos used"
                .format(texelsPerMetre, print, planks, seamRaw, seamFinal, atlas, glbBytes / KB, halvings, textureMs, totalMs, photosUsed)
    }

    private fun measure(raw: RerunMesh, photos: ModelPhotos, budget: TextureBudget, shot: String? = null): Measure {
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder, budget = budget)
        val stats = result.stats
        val texture = checkNotNull(stats.texture) { "the room's photos should texture it" }
        val model = checkNotNull(result.model)
        val atlas = checkNotNull(model.texture)
        val image = BakePhoto(ORIGIN, atlas.width, atlas.height, atlas.atlas)
        val uv = checkNotNull(model.uv)
        val indices = model.texturedIndices
        val p = model.positions
        val print = Correlation()
        val planks = Correlation()
        val faces = FaceGeometry(p, indices)
        for (f in 0 until faces.count) {
            val cx = faces.centroids[f * 3]
            val cy = faces.centroids[f * 3 + 1]
            val cz = faces.centroids[f * 3 + 2]
            val onPrint = cz < RerunSyntheticRoom.MIN.z + SHELL_M && TexturedSyntheticRoom.onPoster(cx, cy)
            val onPlanks = cy < SHELL_M && !(cx in RUG_X0..RUG_X1 && cz in RUG_Z0..RUG_Z1)
            if (!onPrint && !onPlanks) continue
            for (i in 0..GRID) for (j in 0..GRID - i) {
                val w = floatArrayOf(i.toFloat() / GRID, j.toFloat() / GRID, (GRID - i - j).toFloat() / GRID)
                var x = 0f
                var y = 0f
                var z = 0f
                var u = 0f
                var v = 0f
                for (k in 0 until 3) {
                    val vi = indices[f * 3 + k]
                    x += p[vi * 3] * w[k]
                    y += p[vi * 3 + 1] * w[k]
                    z += p[vi * 3 + 2] * w[k]
                    u += uv[vi * 2] * w[k]
                    v += uv[vi * 2 + 1] * w[k]
                }
                val truth = truth(x, y, z, faces.normals, f) ?: continue
                val shown = PhotoTexturing.sample(image, u * image.width - HALF, v * image.height - HALF)
                (if (onPrint) print else planks).add(luma(shown), luma(truth))
            }
        }
        if (shot != null) System.getenv(OUT)?.let { dir -> writePrint(File(dir, "print-$shot.png"), model.positions, uv, indices, image) }
        return Measure(
            texture.texelsPerMetre, print.r(), planks.r(), texture.seamRaw, texture.seamFinal,
            "${stats.atlasWidth}x${stats.atlasHeight}", stats.glbBytes, stats.atlasHalvings,
            stats.textureMs, stats.totalMs, texture.photosUsed,
        )
    }

    /**
     * The print's wall as the model shows it, front on at [SHOT_M] a pixel: each textured face on
     * the print sampled finely and splatted where it lies. Uncovered pixels stay grey.
     */
    private fun writePrint(file: File, p: FloatArray, uv: FloatArray, indices: IntArray, image: BakePhoto) {
        val w = ((PRINT_X1 - PRINT_X0) / SHOT_M).toInt()
        val h = ((PRINT_Y1 - PRINT_Y0) / SHOT_M).toInt()
        val argb = IntArray(w * h) { SHOT_BACKGROUND }
        val faces = FaceGeometry(p, indices)
        for (f in 0 until faces.count) {
            if (faces.centroids[f * 3 + 2] >= RerunSyntheticRoom.MIN.z + SHELL_M) continue
            if (!TexturedSyntheticRoom.onPoster(faces.centroids[f * 3], faces.centroids[f * 3 + 1])) continue
            for (i in 0..SHOT_GRID) for (j in 0..SHOT_GRID - i) {
                val wt = floatArrayOf(i.toFloat() / SHOT_GRID, j.toFloat() / SHOT_GRID, (SHOT_GRID - i - j).toFloat() / SHOT_GRID)
                var x = 0f
                var y = 0f
                var u = 0f
                var v = 0f
                for (k in 0 until 3) {
                    val vi = indices[f * 3 + k]
                    x += p[vi * 3] * wt[k]
                    y += p[vi * 3 + 1] * wt[k]
                    u += uv[vi * 2] * wt[k]
                    v += uv[vi * 2 + 1] * wt[k]
                }
                val px = ((x - PRINT_X0) / SHOT_M).toInt()
                val py = ((PRINT_Y1 - y) / SHOT_M).toInt()
                if (px !in 0 until w || py !in 0 until h) continue
                argb[py * w + px] = PhotoTexturing.sample(image, u * image.width - HALF, v * image.height - HALF)
            }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(JvmImages.png(argb, w, h))
    }

    private class Correlation {
        private var n = 0
        private var sa = 0.0
        private var sb = 0.0
        private var saa = 0.0
        private var sbb = 0.0
        private var sab = 0.0

        fun add(a: Double, b: Double) {
            n++
            sa += a
            sb += b
            saa += a * a
            sbb += b * b
            sab += a * b
        }

        fun r(): Double {
            if (n < 2) return 0.0
            val cov = sab / n - sa / n * sb / n
            val va = saa / n - sa / n * sa / n
            val vb = sbb / n - sb / n * sb / n
            return if (va <= 0.0 || vb <= 0.0) 0.0 else cov / sqrt(va * vb)
        }
    }

    private fun room(): RerunMesh {
        val tsdf = RerunTsdf()
        TexturedSyntheticRoom.frames().forEach { tsdf.integrate(it) }
        return RerunMarchingCubes.extractWhole(tsdf, RerunMarchingCubes.DEFAULT_MIN_WEIGHT).mesh
    }

    private var sharpCount = 0

    /** The walk's photos, about [SHARP_PHOTOS_RECORDED] of them sharp and three times as wide: a recording's mix. */
    private fun mixedPhotos(photoEvery: Int = PHOTO_EVERY): ModelPhotos {
        val count = (RerunSyntheticRoom.poses().size + photoEvery - 1) / photoEvery
        val every = maxOf(1, count / SHARP_PHOTOS_RECORDED)
        val shots = TexturedSyntheticRoom.photos(every = photoEvery, sharpEvery = every, sharpScale = SHARP_SCALE)
        sharpCount = shots.count { it.width > TexturedSyntheticRoom.PHOTO_WIDTH }
        return ModelPhotos(TexturedSyntheticRoom.PHOTO_LENS, shots.map { shot -> ModelPhoto(shot.pose) { shot } })
    }

    /** The room's paint at the surface nearest ([x], [y], [z]) along face [f]'s normal. */
    private fun truth(x: Float, y: Float, z: Float, normals: FloatArray, f: Int): Int? {
        val nx = normals[f * 3]
        val ny = normals[f * 3 + 1]
        val nz = normals[f * 3 + 2]
        val ox = x + nx * LIFT_M
        val oy = y + ny * LIFT_M
        val oz = z + nz * LIFT_M
        var best = Float.POSITIVE_INFINITY
        var color: Int? = null
        for (s in TexturedSyntheticRoom.scene.shapes) {
            val t = s.hit(ox, oy, oz, -nx, -ny, -nz)
            if (t < best) {
                best = t
                color = s.color(ox - nx * t, oy - ny * t, oz - nz * t)
            }
        }
        return color.takeIf { abs(best - LIFT_M) < MAX_MISS_M }
    }

    private fun luma(c: Int): Double =
        0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)

    /**
     * The heap's memory pools, read by reflection: the unit tests compile against `android.jar`,
     * which has no `java.lang.management`, while they run on a desktop JVM that does.
     */
    private fun heapPools(): List<Any> {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val pool = Class.forName("java.lang.management.MemoryPoolMXBean")
        val heap = Class.forName("java.lang.management.MemoryType").getField("HEAP").get(null)
        val pools = factory.getMethod("getMemoryPoolMXBeans").invoke(null) as List<*>
        return pools.filterNotNull().filter { pool.getMethod("getType").invoke(it) == heap }
    }

    private fun resetPeaks() {
        val pool = Class.forName("java.lang.management.MemoryPoolMXBean")
        heapPools().forEach { pool.getMethod("resetPeakUsage").invoke(it) }
    }

    /** The sum of the heap pools' peaks: an upper bound on the heap's peak, exact under the serial collector's two pools' own peaks. */
    private fun peakHeap(): Long {
        val pool = Class.forName("java.lang.management.MemoryPoolMXBean")
        val usage = Class.forName("java.lang.management.MemoryUsage")
        return heapPools().sumOf { usage.getMethod("getUsed").invoke(pool.getMethod("getPeakUsage").invoke(it)) as Long }
    }

    private companion object {
        val ORIGIN = io.github.sceneview.demo.demos.internal.DebugPose(0f, 0f, 0f)
        const val MB = 1024L * 1024
        const val GPU_4096 = 4096
        const val BURN = 4
        const val BYTE = 0xFF
        const val OPAQUE = 0xFF000000.toInt()
        const val KB = 1024
        const val NANOS_PER_MS = 1_000_000L
        const val SHARP_SCALE = 3
        const val PHOTO_EVERY = 3
        const val PHOTO_EVERY_KEPT = 5

        /** The directory the print's before/after PNGs go to, when set. */
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val SHOT_M = 0.002f
        const val SHOT_GRID = 40
        const val SHOT_BACKGROUND = 0xFF404040.toInt()

        // The print's frame on its wall (TexturedSyntheticRoom), a little margin around it.
        const val PRINT_X0 = 0.15f
        const val PRINT_X1 = 1.45f
        const val PRINT_Y0 = 1.05f
        const val PRINT_Y1 = 1.95f
        const val SHARP_W = TexturedSyntheticRoom.PHOTO_WIDTH * SHARP_SCALE
        const val SHARP_H = TexturedSyntheticRoom.PHOTO_HEIGHT * SHARP_SCALE
        const val SHARP_PHOTOS_RECORDED = 15
        const val GRID = 8
        const val LIFT_M = 0.05f
        const val SHELL_M = 0.03f
        const val MAX_MISS_M = 0.04f
        const val HALF = 0.5f
        const val MIN_TEXEL_GAIN = 1.5f
        const val CORRELATION_SLACK = 0.02
        const val SEAM_SLACK = 1f

        // The shared room's rug (RerunSyntheticRoom.floor), where the planks have no seams.
        const val RUG_X0 = -1.2f
        const val RUG_X1 = 0.9f
        const val RUG_Z0 = -0.9f
        const val RUG_Z1 = 1.1f
    }
}
