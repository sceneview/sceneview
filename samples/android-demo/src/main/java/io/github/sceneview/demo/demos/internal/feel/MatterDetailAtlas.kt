package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Procedural rest-space fields, evaluated once on the CPU. No Android/Filament calls here.
 * An RG8 atlas gives the top 512² texels and each side a separate padded chart. This is
 * deliberately NOT a repeating projection: the 3D cells and Carrara seams meet across
 * the rounded edges at the same rest-space points. UVs are shared with [MatterMeshes].
 * R/G: foam wall/unused, velvet pile/fibre, leather cell gap/unused, marble mineral/cloud,
 * steel streak/fine. Materials must set flipUV=false: these UVs address uploaded rows directly.
 * Colours, roughness and deformation remain shader parameters.
 * 1024×512×2 bytes, ~1.33 MiB including mipmaps per loaded opaque family.
 */
internal object MatterDetailAtlas {
    // Bump when fields, quantization, chart layout, dimensions or bevel/rest-space mapping change.
    // Disk cache keys are <family>-<CACHE_VERSION>.bin; shader-only edits do not invalidate RG8.
    const val CACHE_VERSION = 2
    const val WIDTH = 1024
    const val HEIGHT = 512
    private const val GUTTER = 4
    data class Chart(val axis: Int, val sign: Float, val uAxis: Int, val vAxis: Int,
                     val x: Int, val y: Int, val width: Int, val height: Int) {
        fun u(value: Float) = (x + GUTTER + .5f + value * (width - 2 * GUTTER - 1)) / WIDTH
        fun v(value: Float) = (y + GUTTER + .5f + value * (height - 2 * GUTTER - 1)) / HEIGHT

        fun point(u: Float, v: Float, out: FloatArray) {
            out[axis] = sign * half[axis]
            out[uAxis] = (u.coerceIn(0f, 1f) * 2f - 1f) * half[uAxis]
            out[vAxis] = (v.coerceIn(0f, 1f) * 2f - 1f) * half[vAxis]
            val cx = out[0].coerceIn(-half[0] + MatterMeshes.BEVEL, half[0] - MatterMeshes.BEVEL)
            val cy = out[1].coerceIn(-half[1] + MatterMeshes.BEVEL, half[1] - MatterMeshes.BEVEL)
            val cz = out[2].coerceIn(-half[2] + MatterMeshes.BEVEL, half[2] - MatterMeshes.BEVEL)
            val dx = out[0] - cx; val dy = out[1] - cy; val dz = out[2] - cz
            val scale = MatterMeshes.BEVEL / sqrt(dx * dx + dy * dy + dz * dz)
            out[0] = cx + dx * scale; out[1] = cy + dy * scale - half[1]; out[2] = cz + dz * scale
        }
    }
    private val half = floatArrayOf(FeelSimulation.PAD_HALF_WIDTH,
        FeelSimulation.PAD_THICKNESS / 2f, FeelSimulation.PAD_HALF_LENGTH)
    val charts = listOf(
        Chart(1, 1f, 0, 2, 0, 0, 512, 512),
        Chart(1, -1f, 0, 2, 512, 384, 256, 128),
        Chart(2, -1f, 0, 1, 512, 0, 512, 128),
        Chart(0, -1f, 2, 1, 512, 256, 256, 128),
        Chart(2, 1f, 0, 1, 512, 128, 512, 128),
        Chart(0, 1f, 2, 1, 768, 256, 256, 128),
    )

    /** checkCancelled is called once per scanline, so leaving the demo stops a pending bake. */
    fun bake(kind: FeelMaterial, checkCancelled: () -> Unit = {}): ByteArray {
        require(kind != FeelMaterial.Gel)
        val pixels = ByteArray(WIDTH * HEIGHT * 2)
        val p = FloatArray(3)
        val field = Field()
        for (chart in charts) {
            val du = 1f / (chart.width - 2 * GUTTER - 1)
            val dv = 1f / (chart.height - 2 * GUTTER - 1)
            for (y in 0 until chart.height) {
                checkCancelled()
                for (x in 0 until chart.width) {
                    // One sample per texel: the uploaded texture is mipmapped, which filters the fine
                    // cell walls during orbit. A 2×2 prefilter made each family a 10 s+ bake on a Pixel 4a.
                    chart.point((x - GUTTER) * du, (y - GUTTER) * dv, p)
                    field.sample(kind, p[0], p[1], p[2])
                    val offset = ((chart.y + y) * WIDTH + chart.x + x) * 2
                    pixels[offset] = (field.red * 255f + .5f).toInt().coerceIn(0, 255).toByte()
                    pixels[offset + 1] = (field.green * 255f + .5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return pixels
    }

    /** Allocation-free procedural cells, fibre noise and branching mineral paths. */
    internal class Field {
        var red = 0f; private set
        var green = 0f; private set
        private var hx = 0f; private var hy = 0f; private var hz = 0f
        private var cellX = Float.NaN; private var cellY = Float.NaN; private var cellZ = Float.NaN
        private val cellOffsets = FloatArray(27 * 3)
        private fun fract(v: Float) = v - floor(v)
        private fun hash(x: Float, y: Float, z: Float) {
            var a = fract(x * .1031f); var b = fract(y * .1030f); var c = fract(z * .0973f)
            val dot = a * (b + 33.33f) + b * (a + 33.33f) + c * (c + 33.33f)
            a += dot; b += dot; c += dot
            hx = fract((a + b) * c); hy = fract((a + a) * b); hz = fract((b + a) * a)
        }
        private fun hashX(x: Float, y: Float): Float { hash(x, y, 0f); return hx }
        private fun noise(x: Float, y: Float): Float {
            val ix = floor(x); val iy = floor(y)
            val fx = x - ix; val fy = y - iy
            val u = fx * fx * (3f - 2f * fx); val v = fy * fy * (3f - 2f * fy)
            val a = hashX(ix, iy); val b = hashX(ix + 1f, iy)
            val c = hashX(ix, iy + 1f); val d = hashX(ix + 1f, iy + 1f)
            return (a + (b - a) * u) * (1f - v) + (c + (d - c) * u) * v
        }
        private fun fbm(x: Float, y: Float) = noise(x, y) * .57f +
            noise(x * 2.13f + 7.1f, y * 2.13f + 7.1f) * .29f +
            noise(x * 4.37f + 19.7f, y * 4.37f + 19.7f) * .14f
        private fun gap(x: Float, y: Float, z: Float): Float {
            val ix = floor(x); val iy = floor(y); val iz = floor(z)
            val fx = x - ix; val fy = y - iy; val fz = z - iz
            // Adjacent texels mostly share a neighbourhood.
            // Cache its hashes during the bake; this does not change the field at all.
            if (ix != cellX || iy != cellY || iz != cellZ) {
                cellX = ix; cellY = iy; cellZ = iz
                var offset = 0
                for (k in -1..1) for (j in -1..1) for (i in -1..1) {
                    hash(ix + i, iy + j, iz + k)
                    cellOffsets[offset++] = i + hx
                    cellOffsets[offset++] = j + hy
                    cellOffsets[offset++] = k + hz
                }
            }
            var first = 10f; var second = 10f
            for (offset in cellOffsets.indices step 3) {
                val dx = cellOffsets[offset] - fx
                val dy = cellOffsets[offset + 1] - fy
                val dz = cellOffsets[offset + 2] - fz
                val d = dx * dx + dy * dy + dz * dz
                second = minOf(second, maxOf(first, d)); first = minOf(first, d)
            }
            return sqrt(second) - sqrt(first)
        }
        private fun smooth(a: Float, b: Float, v: Float): Float {
            val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        private fun gaussian(q: Float) = exp(-q * q)

        fun sample(kind: FeelMaterial, x: Float, y: Float, z: Float) {
            green = 0f
            when (kind) {
                FeelMaterial.MemoryFoam -> red = 1f - smooth(.025f, .20f, gap(x * 32f, y * 32f, z * 32f))
                FeelMaterial.Leather -> red = gap(x * 14f, y * 14f, z * 14f)
                FeelMaterial.Velvet -> {
                    val u = x + y * .31f; val v = z + y * .73f
                    red = noise(u * 3f, v * 8f); green = noise(u * 65f, v * 240f)
                }
                FeelMaterial.BrushedSteel -> {
                    red = noise(x * 1.4f, (z + y) * 155f)
                    green = noise(x * 1.4f, (z + y) * 775f)
                }
                FeelMaterial.Marble -> {
                    // Sparse mineral paths, each in its own direction, with tributaries
                    // joining the parent before tapering out. No repeated stripes or closed
                    // cellular outlines. One rest-space field continues over every bevel.
                    val u = x + y * .43f
                    val v = z + y * .71f
                    val cloud = fbm(u * .8f + 3.4f, v * .8f + 3.4f)
                    var mineral = 0f
                    for (i in 0..2) {
                        val c = when (i) { 0 -> .819f; 1 -> .423f; else -> .966f }
                        val s = when (i) { 0 -> -.574f; 1 -> .906f; else -> .259f }
                        val across = u * c - v * s
                        val along = u * s + v * c
                        val seed = i * 13.7f
                        val centre = when (i) { 0 -> -.75f; 1 -> .95f; else -> 1.85f }
                        val warp = (fbm(along * .85f + seed, 7.2f + seed) - .5f) * .65f +
                            (noise(along * 4.3f, 18.4f + seed) - .5f) * .065f
                        val offset = across - centre - warp
                        val width = .018f + .016f * noise(along * 2.1f, seed + 3.1f)
                        val strength = .25f + .15f * noise(along * 1.7f + seed, 4.6f)
                        val parent = gaussian(offset / width) * strength +
                            gaussian(offset / .14f) * .08f
                        // Non-periodic forks: zero separation at the junction, then each
                        // curves away at a different angle and fades into the white ground.
                        val t = along + .9f - i * .7f
                        val sign = if (i == 1) -1f else 1f
                        val branchOffset = sign * t * (.32f + .14f * t) +
                            .06f * t * (noise(along * 3.2f, seed + 5.4f) - .5f)
                        val life = smooth(0f, .12f, t) * (1f - smooth(.65f, 1.8f, t))
                        val branch = gaussian((offset - branchOffset) / (width * .85f)) * .22f * life
                        mineral = maxOf(mineral, parent, branch)
                    }
                    red = (.06f + .12f * cloud + mineral).coerceIn(0f, .72f)
                    green = cloud
                }
                FeelMaterial.Gel -> error("Gel keeps its view-dependent refracted bubbles")
            }
        }
    }
}
