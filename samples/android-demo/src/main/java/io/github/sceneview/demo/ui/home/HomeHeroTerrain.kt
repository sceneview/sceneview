package io.github.sceneview.demo.ui.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The landscape under the home hero's dusk flight (#3948), as pure maths.
 *
 * A valley running along −Z, walled by ridges that climb with |x|, sampled from a
 * heightfield that is **periodic along Z**: every term in [heroTerrainHeight] is an
 * integer harmonic of [HeroTerrainSpec.period], so a strip [HeroTerrainSpec.periods]
 * periods long can slide towards the camera by `(t · speed) mod period` forever without
 * a seam and without ever being rebuilt. The flight is the terrain moving, not the
 * camera — one transform write per frame, no streaming, no allocation.
 *
 * The mesh is **flat-shaded on purpose**: each triangle gets its own three vertices, its
 * face normal and one colour picked from the dusk palette by altitude (valley, rock,
 * ochre, snow) with a per-face jitter, so the sun rakes across facets the way it does in
 * a low-poly flight game. That is the look; it also means no textures, no UVs, and one
 * draw call for the whole landscape through `hero_terrain.filamat`.
 *
 * Nothing here touches Filament; [HomeHeroScene] turns the arrays into a `Geometry`.
 * See `HomeHeroTerrainTest`.
 */
internal data class HeroTerrainSpec(
    /** Quads across X. */
    val columns: Int,
    /** Quads along Z over the whole strip. */
    val rows: Int,
    /** World width of the strip, centred on X = 0. */
    val width: Float = 84f,
    /** World length after which the heightfield repeats. */
    val period: Float = 40f,
    /** How many periods the strip covers; the camera only ever sees the nearest ones. */
    val periods: Int = 4,
    /** Where the strip ends, ahead of the camera at Z = 0 by this much (behind it, really). */
    val zNear: Float = 20f,
) {
    val length: Float get() = period * periods
    val zFar: Float get() = zNear - length

    init {
        require(columns >= 2 && rows >= 2) { "A strip needs at least 2×2 quads" }
        require(width > 0f && period > 0f && periods >= 2) { "Degenerate strip" }
    }

    companion object {
        /** Pixel 7a class: 56 × 160 quads = 17 920 triangles, 53 760 flat-shaded vertices. */
        val Full = HeroTerrainSpec(columns = 56, rows = 160)

        /** `isLowRamDevice` class: a quarter of the triangles, the same silhouette. */
        val Light = HeroTerrainSpec(columns = 28, rows = 80)
    }
}

/**
 * Altitude at ([x], [z]) — periodic in [z] with [period].
 *
 * Every `z` term is `k · 2π / period` for an integer `k`, which is the whole tiling
 * contract. The `x` terms are free. The valley corridor is a smoothstep on |x|: flat
 * and low near the centre line the camera flies along, rising into ridges at the sides.
 */
internal fun heroTerrainHeight(x: Float, z: Float, period: Float): Float {
    val u = (2.0 * PI * z / period).toFloat()
    var ridge = 0f
    ridge += sin(x * 0.21f + 1.3f) * cos(u + 0.4f)
    ridge += sin(x * 0.47f - u * 2f) * 0.7f
    ridge += cos(x * 0.9f + u * 3f + 2.1f) * 0.35f
    ridge += sin(x * 1.7f + 0.5f) * cos(u * 5f) * 0.18f
    val wall = smoothstep(4f, 26f, abs(x))
    val floor = -0.35f + ridge * 0.25f
    return floor + wall * (2.5f + (ridge + 1.5f) * 2.2f)
}

/** Linear-RGB colour of a facet at [altitude], before the per-face jitter. */
internal fun heroTerrainColor(altitude: Float, jitter: Float = 0f): FloatArray {
    val valley = floatArrayOf(0.030f, 0.028f, 0.075f)
    val rock = floatArrayOf(0.145f, 0.078f, 0.125f)
    val ochre = floatArrayOf(0.300f, 0.150f, 0.105f)
    val snow = floatArrayOf(0.700f, 0.560f, 0.640f)
    val a = mix(valley, rock, smoothstep(-0.6f, 1.8f, altitude))
    val b = mix(a, ochre, smoothstep(2.6f, 6.0f, altitude))
    val c = mix(b, snow, smoothstep(7.2f, 9.2f, altitude))
    val k = 1f + jitter
    return floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
}

/**
 * Flat-shaded triangle soup for [spec]: `count` vertices with positions, normals and
 * linear RGBA colours, and the trivial index list Filament still wants.
 */
internal class HeroTerrainMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

internal fun buildHeroTerrain(spec: HeroTerrainSpec): HeroTerrainMesh {
    val triangles = spec.columns * spec.rows * 2
    val count = triangles * 3
    val positions = FloatArray(count * 3)
    val normals = FloatArray(count * 3)
    val colors = FloatArray(count * 4)
    val indices = IntArray(count) { it }
    val dx = spec.width / spec.columns
    val dz = spec.length / spec.rows
    val x0 = -spec.width / 2f
    var v = 0

    fun emit(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float, face: Int) {
        val ay = heroTerrainHeight(ax, az, spec.period)
        val by = heroTerrainHeight(bx, bz, spec.period)
        val cy = heroTerrainHeight(cx, cz, spec.period)
        // Face normal = (b − a) × (c − a), then flipped if it points down: the winding
        // below is counter-clockwise seen from above, which is what Filament's default
        // front face expects, so this is a guard, not the rule.
        var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
        var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
        var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= len; ny /= len; nz /= len
        if (ny < 0f) { nx = -nx; ny = -ny; nz = -nz }
        val color = heroTerrainColor((ay + by + cy) / 3f, faceJitter(face))
        for ((px, py, pz) in listOf(Triple(ax, ay, az), Triple(bx, by, bz), Triple(cx, cy, cz))) {
            positions[v * 3] = px; positions[v * 3 + 1] = py; positions[v * 3 + 2] = pz
            normals[v * 3] = nx; normals[v * 3 + 1] = ny; normals[v * 3 + 2] = nz
            colors[v * 4] = color[0]; colors[v * 4 + 1] = color[1]
            colors[v * 4 + 2] = color[2]; colors[v * 4 + 3] = 1f
            v++
        }
    }

    var face = 0
    for (row in 0 until spec.rows) {
        val zA = spec.zFar + row * dz
        val zB = zA + dz
        for (column in 0 until spec.columns) {
            val xA = x0 + column * dx
            val xB = xA + dx
            // Alternate the diagonal per quad: a checkerboard of diagonals reads as
            // facets, a single slanted diagonal reads as corduroy.
            if ((row + column) % 2 == 0) {
                emit(xA, zA, xA, zB, xB, zB, face++)
                emit(xA, zA, xB, zB, xB, zA, face++)
            } else {
                emit(xA, zA, xA, zB, xB, zA, face++)
                emit(xB, zA, xA, zB, xB, zB, face++)
            }
        }
    }
    return HeroTerrainMesh(positions, normals, colors, indices)
}

/** Deterministic ±6 % per face, so two builds of the same spec are byte-identical. */
private fun faceJitter(face: Int): Float {
    var h = face * 374761393 + 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return ((h and 0xFFFF) / 65535f - 0.5f) * 0.12f
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(a: FloatArray, b: FloatArray, t: Float) =
    FloatArray(3) { a[it] + (b[it] - a[it]) * t }
