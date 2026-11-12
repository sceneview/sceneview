package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v9's rest-space fields, evaluated once on the CPU. No Android/Filament calls here.
 * An RG8 atlas gives the top 512² texels and each side a separate padded chart. This is
 * deliberately NOT a repeating projection: the 3D cells and Carrara seams meet across
 * the rounded edges at the same rest-space points. UVs are shared with [MatterMeshes].
 * R/G: foam wall/unused, velvet pile/fibre, leather cell gap/unused, marble mineral/cloud,
 * steel streak/fine. Colours, roughness and deformation remain shader parameters.
 * 1024×512×2 bytes, ~1.33 MiB including mipmaps per loaded opaque family.
 */
internal object MatterDetailAtlas {
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

    /** Allocation-free port of the old GLSL hash, Worley and marble domain warp. */
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
            // Adjacent texels and their four subsamples mostly share a neighbourhood.
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
                    val u = x + z * .58f + y * .43f; val along = z - x * .58f + y * .71f
                    val across = u + (noise(along * .8f, 7.2f) - .5f) * .85f +
                        (noise(along * 2.1f, 18.4f) - .5f) * .18f
                    val cloud = fbm(u * .95f + 3.4f, along * .95f + 3.4f)
                    val width = .22f + .24f * cloud
                    val soft = gaussian((across + .85f) / width)
                    val second = gaussian((across - 1f + .18f * sin(along * 1.6f)) / (width * 1.35f))
                    val flowX = sin(along * 1.2f); val flowY = cos(along * 1.2f)
                    val fadeX = sin(along * .85f); val fadeY = cos(along * .85f)
                    var fine = 0f
                    val stream = floor((across + 2.9f) / .34f)
                    for (i in -1..1) {
                        val seed = stream + i
                        if (seed < 0f || seed > 17f) continue
                        hash(seed, 3.1f, 9.7f)
                        val centre = -2.9f + seed * .34f + (hx - .5f) * .14f
                        val dx = hy * 2f - 1f; val dy = hz * 2f - 1f
                        val offset = across - centre + .10f * (flowX * dx + flowY * dy)
                        val life = .65f + .25f * (fadeX * dy + fadeY * dx)
                        // The atlas prefilter/mips replace the old per-fragment derivative width.
                        fine += gaussian(offset / (.018f + .027f * hy)) * life
                    }
                    red = (.10f + .22f * cloud + .34f * soft + .22f * second +
                        .25f * minOf(fine, 1f)).coerceIn(0f, .72f)
                    green = cloud
                }
                FeelMaterial.Gel -> error("Gel keeps its view-dependent refracted bubbles")
            }
        }
    }
}
