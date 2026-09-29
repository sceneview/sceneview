package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU-side geometry for the Cosmos demo, as flat interleaved arrays ready to be copied into
 * Filament vertex and index buffers.
 *
 * Nothing here touches Filament, so every scene is built off the main thread and covered by
 * plain JVM tests (`CosmosMeshesTest`). Two vertex layouts, one per material:
 *
 * - **Sprites** ([SPRITE_STRIDE] floats: position 3, colour 4, custom0 4) for
 *   `cosmos_sprite.mat` — four corners per sprite sharing one centre.
 * - **Ribbons** ([RIBBON_STRIDE] floats: position 3, colour 4, custom0 4, custom1 4) for
 *   `cosmos_ribbon.mat` — two vertices per curve point, one on each side of the stroke.
 *
 * Colours are linear HDR radiance: values above 1.0 are the point, since that is what the
 * bloom pass bleeds from.
 */
internal class GlowMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val stride: Int,
    /** Axis-aligned bounds of the drawn geometry, including sprite radii and stroke widths. */
    val boundsMin: FloatArray,
    val boundsMax: FloatArray,
) {
    val vertexCount: Int get() = vertices.size / stride
}

/** Floats per sprite vertex: position (3), colour (4), custom0 (4). */
internal const val SPRITE_STRIDE = 11

/** Floats per ribbon vertex: position (3), colour (4), custom0 (4), custom1 (4). */
internal const val RIBBON_STRIDE = 15

private class Bounds {
    val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    fun include(x: Float, y: Float, z: Float, pad: Float) {
        min[0] = min(min[0], x - pad)
        min[1] = min(min[1], y - pad)
        min[2] = min(min[2], z - pad)
        max[0] = max(max[0], x + pad)
        max[1] = max(max[1], y + pad)
        max[2] = max(max[2], z + pad)
    }
}

/** Accumulates camera-facing sprites. */
internal class SpriteBuilder(expected: Int = 1024) {
    private var vertices = FloatArray(expected * 4 * SPRITE_STRIDE)
    private var indices = IntArray(expected * 6)
    private var sprites = 0
    private val bounds = Bounds()

    val count: Int get() = sprites

    @Suppress("LongParameterList")
    fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, radius: Float, phase: Float) {
        ensure(sprites + 1)
        val base = sprites * 4
        var o = base * SPRITE_STRIDE
        for (corner in 0 until 4) {
            val cx = if (corner == 0 || corner == 3) -1f else 1f
            val cy = if (corner < 2) -1f else 1f
            vertices[o++] = x
            vertices[o++] = y
            vertices[o++] = z
            vertices[o++] = r
            vertices[o++] = g
            vertices[o++] = b
            vertices[o++] = 1f
            vertices[o++] = cx
            vertices[o++] = cy
            vertices[o++] = radius
            vertices[o++] = phase
        }
        var i = sprites * 6
        indices[i++] = base
        indices[i++] = base + 1
        indices[i++] = base + 2
        indices[i++] = base
        indices[i++] = base + 2
        indices[i] = base + 3
        bounds.include(x, y, z, radius)
        sprites++
    }

    private fun ensure(capacity: Int) {
        if (capacity * 6 <= indices.size) return
        val grown = max(capacity, sprites * 2)
        vertices = vertices.copyOf(grown * 4 * SPRITE_STRIDE)
        indices = indices.copyOf(grown * 6)
    }

    fun build(): GlowMesh = GlowMesh(
        vertices = vertices.copyOf(sprites * 4 * SPRITE_STRIDE),
        indices = indices.copyOf(sprites * 6),
        stride = SPRITE_STRIDE,
        boundsMin = bounds.min.copyOf(),
        boundsMax = bounds.max.copyOf(),
    )
}

/** Accumulates camera-facing strokes along polylines. */
internal class RibbonBuilder(expectedPoints: Int = 4096) {
    private var vertices = FloatArray(expectedPoints * 2 * RIBBON_STRIDE)
    private var indices = IntArray(expectedPoints * 6)
    private var vertexCount = 0
    private var indexCount = 0
    private val bounds = Bounds()

    var curves: Int = 0
        private set

    /**
     * Adds one stroke through [points] (xyz triples, at least two points).
     *
     * [colors] holds one rgb triple per point; the stroke's [halfWidth] is in model units and
     * [seed] in [0, 1] staggers the per-curve animation in the shader.
     */
    fun addCurve(points: FloatArray, colors: FloatArray, halfWidth: Float, seed: Float) {
        val n = points.size / 3
        require(n >= 2) { "a stroke needs at least two points" }
        require(colors.size == n * 3) { "one rgb triple per point" }
        ensureVertices(vertexCount + n * 2)
        ensureIndices(indexCount + (n - 1) * 6)
        val first = vertexCount
        var arc = 0f
        for (i in 0 until n) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            if (i > 0) {
                val dx = x - points[i * 3 - 3]
                val dy = y - points[i * 3 - 2]
                val dz = z - points[i * 3 - 1]
                arc += sqrt(dx * dx + dy * dy + dz * dz)
            }
            tangentAt(points, i, n, tangent)
            val tx = tangent[0]
            val ty = tangent[1]
            val tz = tangent[2]
            val t = i.toFloat() / (n - 1)
            for (side in 0 until 2) {
                var o = vertexCount * RIBBON_STRIDE
                vertices[o++] = x
                vertices[o++] = y
                vertices[o++] = z
                vertices[o++] = colors[i * 3]
                vertices[o++] = colors[i * 3 + 1]
                vertices[o++] = colors[i * 3 + 2]
                vertices[o++] = 1f
                vertices[o++] = tx
                vertices[o++] = ty
                vertices[o++] = tz
                vertices[o++] = if (side == 0) -1f else 1f
                vertices[o++] = halfWidth
                vertices[o++] = t
                vertices[o++] = seed
                vertices[o] = arc
                vertexCount++
            }
            bounds.include(x, y, z, halfWidth)
        }
        appendStripIndices(first, n)
        curves++
    }

    private val tangent = FloatArray(3)

    /** Unit central-difference tangent at point [i] of an [n]-point polyline, into [out]. */
    private fun tangentAt(points: FloatArray, i: Int, n: Int, out: FloatArray) {
        val prev = max(i - 1, 0) * 3
        val next = min(i + 1, n - 1) * 3
        val tx = points[next] - points[prev]
        val ty = points[next + 1] - points[prev + 1]
        val tz = points[next + 2] - points[prev + 2]
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl > 1e-9f) {
            out[0] = tx / tl
            out[1] = ty / tl
            out[2] = tz / tl
        } else {
            out[0] = 1f
            out[1] = 0f
            out[2] = 0f
        }
    }

    /** Two triangles per segment of the strip that starts at vertex [first]. */
    private fun appendStripIndices(first: Int, n: Int) {
        for (i in 0 until n - 1) {
            val a = first + i * 2
            val b = a + 2
            indices[indexCount++] = a
            indices[indexCount++] = a + 1
            indices[indexCount++] = b
            indices[indexCount++] = a + 1
            indices[indexCount++] = b + 1
            indices[indexCount++] = b
        }
    }

    private fun ensureVertices(capacity: Int) {
        if (capacity * RIBBON_STRIDE <= vertices.size) return
        vertices = vertices.copyOf(max(capacity, vertexCount * 2) * RIBBON_STRIDE)
    }

    private fun ensureIndices(capacity: Int) {
        if (capacity <= indices.size) return
        indices = indices.copyOf(max(capacity, indexCount * 2))
    }

    fun build(): GlowMesh = GlowMesh(
        vertices = vertices.copyOf(vertexCount * RIBBON_STRIDE),
        indices = indices.copyOf(indexCount),
        stride = RIBBON_STRIDE,
        boundsMin = bounds.min.copyOf(),
        boundsMax = bounds.max.copyOf(),
    )
}

private fun Random.gaussian(): Float {
    // Box–Muller; one of the pair is enough here.
    val u = max(nextFloat(), 1e-7f)
    val v = nextFloat()
    return (sqrt(-2f * ln(u)) * cos(2f * PI.toFloat() * v))
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The four procedural scenes of the Cosmos demo. Each builder is deterministic for a given
 * seed, so the render golden and the screenshots are reproducible.
 */
internal object CosmosMeshes {

    /** Radius of the galaxy disc in model units; the camera frames it. */
    const val GALAXY_RADIUS = 1f

    /** Logarithmic spiral: `theta = ln(r / r0) / tan(pitch)`. */
    private const val GALAXY_ARM_START = 0.14f
    private const val GALAXY_PITCH_TAN = 0.27f

    /** Where arm `arm` of two sits at radius [r], in radians. */
    fun galaxyArmAngle(r: Float, arm: Int): Float =
        ln(max(r, GALAXY_ARM_START) / GALAXY_ARM_START) / GALAXY_PITCH_TAN + arm * PI.toFloat()

    /**
     * A barred two-arm spiral galaxy in the XZ plane: a warm bar and bulge, blue-white arms on
     * a logarithmic spiral, pink star-forming knots strung along them, a faint reddish dust
     * haze on their inner edge, and a thin population of field stars between the arms.
     */
    @Suppress("LongMethod")
    fun galaxy(seed: Int = 7, armStars: Int = 34_000): GlowMesh {
        val rnd = Random(seed)
        val out = SpriteBuilder(armStars + 16_000)
        val twoPi = 2f * PI.toFloat()

        // Bulge and bar — warm, dense, the white-hot core once additive blending stacks it up.
        repeat(armStars / 6) {
            val bar = rnd.nextFloat() < 0.55f
            val x: Float
            val z: Float
            val y: Float
            if (bar) {
                x = rnd.gaussian() * 0.16f
                z = rnd.gaussian() * 0.045f
                y = rnd.gaussian() * 0.03f
            } else {
                val r = abs(rnd.gaussian()) * 0.09f
                val a = rnd.nextFloat() * twoPi
                x = cos(a) * r
                z = sin(a) * r
                y = rnd.gaussian() * 0.045f
            }
            val heat = rnd.nextFloat()
            out.add(
                x, y, z,
                r = 1.0f * 0.34f, g = mix(0.70f, 0.80f, heat) * 0.34f, b = mix(0.42f, 0.58f, heat) * 0.34f,
                radius = 0.010f + rnd.nextFloat() * 0.012f,
                phase = rnd.nextFloat(),
            )
        }

        // Arms.
        repeat(armStars) {
            val arm = rnd.nextInt(2)
            // A fifth of the stars trace fainter spurs that branch off the outer arms.
            val spur = rnd.nextFloat() < 0.2f
            // Exponential disc profile, clipped to the rim.
            val disc = min(
                GALAXY_ARM_START + (-ln(1f - rnd.nextFloat() * 0.96f)) / 2.8f,
                GALAXY_RADIUS * 1.05f,
            )
            val r = if (spur) max(disc, 0.42f) else disc
            val spread = (0.034f + 0.11f * r) * (if (spur) 1.3f else 1f)
            val angle = galaxyArmAngle(r, arm) + (if (spur) 1.15f else 0f)
            val along = rnd.gaussian() * spread
            val across = rnd.gaussian() * spread * 0.9f
            val x = cos(angle) * (r + along) - sin(angle) * across
            val z = sin(angle) * (r + along) + cos(angle) * across
            val y = rnd.gaussian() * (0.018f * (1.1f - r))
            // Young blue stars concentrate on the arm crest; older, whiter ones are spread.
            val crest = exp(-(along * along + across * across) / (spread * spread * 0.5f))
            val outer = smoothstep(0.1f, 0.75f, r)
            val red = mix(1.0f, 0.56f, outer)
            val green = mix(0.84f, 0.64f, outer)
            val blue = mix(0.70f, 1.0f, outer)
            val brightness = (0.13f + 0.46f * crest) * (if (rnd.nextFloat() < 0.04f) 3.2f else 1f) *
                (if (spur) 0.5f else 1f)
            out.add(
                x, y, z,
                r = red * brightness, g = green * brightness, b = blue * brightness,
                radius = 0.0045f + rnd.nextFloat() * 0.007f,
                phase = rnd.nextFloat(),
            )
        }

        // A soft lavender glow under the arms, so they read as light and not only as grain.
        repeat(armStars / 12) {
            val arm = rnd.nextInt(2)
            val r = GALAXY_ARM_START + rnd.nextFloat() * (GALAXY_RADIUS - GALAXY_ARM_START)
            val angle = galaxyArmAngle(r, arm) + rnd.gaussian() * 0.12f
            val fade = 1f - 0.7f * r
            out.add(
                cos(angle) * r, rnd.gaussian() * 0.01f, sin(angle) * r,
                r = 0.026f * fade, g = 0.03f * fade, b = 0.056f * fade,
                radius = 0.05f + rnd.nextFloat() * 0.05f,
                phase = rnd.nextFloat(),
            )
        }

        // Pink star-forming knots along the outer arms.
        repeat(48) {
            val arm = rnd.nextInt(2)
            val r = 0.55f + rnd.nextFloat() * 0.55f
            val angle = galaxyArmAngle(r, arm) + rnd.gaussian() * 0.3f
            val cx = cos(angle) * r
            val cz = sin(angle) * r
            repeat(4 + rnd.nextInt(7)) {
                out.add(
                    cx + rnd.gaussian() * 0.012f,
                    rnd.gaussian() * 0.006f,
                    cz + rnd.gaussian() * 0.012f,
                    r = 1.5f, g = 0.2f, b = 0.38f,
                    radius = 0.006f + rnd.nextFloat() * 0.007f,
                    phase = rnd.nextFloat(),
                )
            }
        }

        // Reddish dust haze on the inner (trailing) edge of the arms.
        repeat(1_800) {
            val arm = rnd.nextInt(2)
            val r = 0.2f + rnd.nextFloat() * 0.8f
            val angle = galaxyArmAngle(r, arm) - 0.22f + rnd.gaussian() * 0.06f
            out.add(
                cos(angle) * r + rnd.gaussian() * 0.02f,
                rnd.gaussian() * 0.01f,
                sin(angle) * r + rnd.gaussian() * 0.02f,
                r = 0.018f, g = 0.005f, b = 0.006f,
                radius = 0.015f + rnd.nextFloat() * 0.02f,
                phase = rnd.nextFloat(),
            )
        }

        // Field stars between the arms, and a sparse halo.
        repeat(5_000) {
            val r = sqrt(rnd.nextFloat()) * GALAXY_RADIUS * 1.15f
            val a = rnd.nextFloat() * twoPi
            val b = 0.12f + 0.2f * rnd.nextFloat()
            out.add(
                cos(a) * r, rnd.gaussian() * 0.03f, sin(a) * r,
                r = 0.75f * b, g = 0.82f * b, b = 1.0f * b,
                radius = 0.004f + rnd.nextFloat() * 0.004f,
                phase = rnd.nextFloat(),
            )
        }

        // The glow of the core itself — one big soft sprite the bloom turns into a halo.
        out.add(0f, 0f, 0f, r = 0.95f, g = 0.62f, b = 0.34f, radius = 0.24f, phase = 0f)
        out.add(0f, 0f, 0f, r = 0.09f, g = 0.1f, b = 0.17f, radius = 1.4f, phase = 0f)
        return out.build()
    }

    /**
     * A background star field on a shell of radius [radius] around the origin: mostly faint,
     * a few bright, in the colours real stars come in (blue-white, white, yellow, orange).
     */
    fun starField(seed: Int = 3, count: Int = 2_600, radius: Float = 40f): GlowMesh {
        val rnd = Random(seed)
        val out = SpriteBuilder(count)
        repeat(count) {
            val z = rnd.nextFloat() * 2f - 1f
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - z * z)
            val d = radius * (0.9f + 0.2f * rnd.nextFloat())
            val u = rnd.nextFloat()
            val brightness = 0.25f + 5f * u * u * u * u * u * u * u * u
            val kind = rnd.nextFloat()
            val (r, g, b) = when {
                kind < 0.35f -> Triple(0.72f, 0.82f, 1.0f)
                kind < 0.8f -> Triple(1.0f, 0.97f, 0.94f)
                kind < 0.95f -> Triple(1.0f, 0.86f, 0.64f)
                else -> Triple(1.0f, 0.62f, 0.42f)
            }
            out.add(
                cos(a) * s * d, z * d, sin(a) * s * d,
                r = r * brightness, g = g * brightness, b = b * brightness,
                radius = 0.07f + 0.08f * rnd.nextFloat(),
                phase = rnd.nextFloat(),
            )
        }
        return out.build()
    }

    /**
     * The particle tracks of a detonation: charged particles leave the origin, mostly along
     * the X axis, and curl in a magnetic field along Z while drag slows them, so each track is
     * a spiral whose tightness depends on its speed — slow ones curl into small loops, fast
     * ones sweep wide arcs. Positive charges are red and magenta, negative ones blue and
     * violet; every track starts white-hot at the centre.
     */
    fun burst(seed: Int = 11, tracks: Int = 460, steps: Int = 96): GlowMesh {
        val rnd = Random(seed)
        val out = RibbonBuilder(tracks * steps)
        val points = FloatArray(steps * 3)
        val colors = FloatArray(steps * 3)
        repeat(tracks) { index ->
            val positive = rnd.nextFloat() < 0.62f
            val sideways = if (rnd.nextBoolean()) 1f else -1f
            // Red tracks fan out sideways, blue ones climb and dive, so the two colours stay apart
            // instead of washing into one pink.
            var vx = sideways * (if (positive) 0.6f + rnd.nextFloat() else 0.15f + 0.6f * rnd.nextFloat())
            var vy = rnd.gaussian() * (if (positive) 0.32f else 0.95f)
            var vz = rnd.gaussian() * 0.35f
            val len = sqrt(vx * vx + vy * vy + vz * vz)
            val speed = 0.9f + 2.1f * rnd.nextFloat() * rnd.nextFloat() + rnd.nextFloat() * 0.6f
            vx = vx / len * speed
            vy = vy / len * speed
            vz = vz / len * speed
            val curl = rnd.nextFloat()
            val charge = (if (positive) 1f else -1f) * (0.5f + 3.1f * curl * curl * curl)
            // Field mostly along Z (the view axis), tilted a little so the loops are not flat.
            val bx = 0.18f
            val by = 0.3f
            val bz = 1f
            val drag = 0.25f + 0.9f * rnd.nextFloat()
            val dt = 0.022f
            var x = rnd.gaussian() * 0.01f
            var y = rnd.gaussian() * 0.01f
            var z = rnd.gaussian() * 0.01f
            val hue = rnd.nextFloat()
            val (br, bg, bb) = if (positive) {
                // Red through magenta.
                Triple(1.0f, 0.04f + 0.08f * hue, 0.08f + 0.35f * hue * hue)
            } else {
                // Blue through violet.
                Triple(0.12f + 0.4f * hue, 0.16f + 0.12f * hue, 1.0f)
            }
            val gain = 0.9f + 1.6f * rnd.nextFloat()
            for (i in 0 until steps) {
                points[i * 3] = x
                points[i * 3 + 1] = y
                points[i * 3 + 2] = z
                val t = i.toFloat() / (steps - 1)
                val white = 1f - smoothstep(0f, 0.2f, t)
                colors[i * 3] = mix(br, 1f, white) * gain * (1f + 2.5f * white)
                colors[i * 3 + 1] = mix(bg, 0.85f, white) * gain * (1f + 2.5f * white)
                colors[i * 3 + 2] = mix(bb, 1f, white) * gain * (1f + 2.5f * white)
                // Lorentz force v × B, then drag.
                val ax = charge * (vy * bz - vz * by) - drag * vx
                val ay = charge * (vz * bx - vx * bz) - drag * vy
                val az = charge * (vx * by - vy * bx) - drag * vz
                vx += ax * dt
                vy += ay * dt
                vz += az * dt
                x += vx * dt
                y += vy * dt
                z += vz * dt
            }
            out.addCurve(
                points, colors,
                halfWidth = 0.0035f + 0.003f * rnd.nextFloat(),
                seed = ((index * 0.618034f) % 1f),
            )
        }
        return out.build()
    }

    /** Stray sparks around the burst: small red and violet points that twinkle. */
    fun burstSparks(seed: Int = 13, count: Int = 520): GlowMesh {
        val rnd = Random(seed)
        val out = SpriteBuilder(count)
        repeat(count) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 0.3f + 2.4f * sqrt(rnd.nextFloat())
            val red = rnd.nextFloat() < 0.75f
            val b = 0.6f + 2.2f * rnd.nextFloat()
            out.add(
                cos(a) * r, sin(a) * r * 1.3f, rnd.gaussian() * 0.5f,
                r = (if (red) 1f else 0.45f) * b, g = 0.06f * b, b = (if (red) 0.12f else 1f) * b,
                radius = 0.006f + 0.01f * rnd.nextFloat(),
                phase = rnd.nextFloat(),
            )
        }
        return out.build()
    }

    /** One point vortex of the flow field: centre, circulation, inflow and core radius. */
    data class Vortex(val x: Float, val y: Float, val circulation: Float, val inflow: Float, val core: Float)

    /** Half extents of the flow field's domain in the XY plane — portrait, like the phone. */
    const val FLOW_HALF_WIDTH = 1.25f
    const val FLOW_HALF_HEIGHT = 2.2f

    val flowVortices: List<Vortex> = listOf(
        Vortex(0.12f, 0.1f, 1.25f, 0.18f, 0.05f),
        Vortex(-0.6f, 1.25f, -0.9f, 0.1f, 0.06f),
        Vortex(0.7f, 1.55f, 0.75f, 0.12f, 0.05f),
        Vortex(-0.55f, -1.2f, 0.95f, 0.14f, 0.06f),
        Vortex(0.72f, -0.55f, -0.7f, 0.08f, 0.06f),
        Vortex(0.25f, -1.85f, 0.6f, 0.1f, 0.05f),
    )

    /** True when (x, y) has fallen into a vortex's eye, where a streamline stops. */
    private fun insideVortexCore(x: Float, y: Float): Boolean = flowVortices.any { v ->
        val dx = x - v.x
        val dy = y - v.y
        dx * dx + dy * dy < (v.core * 0.9f) * (v.core * 0.9f)
    }

    /** The velocity of the flow field at (x, y): a meandering drift plus the vortices. */
    fun flowVelocity(x: Float, y: Float, out: FloatArray) {
        var vx = 0.08f + 0.22f * sin(1.4f * y + 0.6f)
        var vy = -0.32f + 0.12f * sin(1.7f * x - 0.4f)
        for (v in flowVortices) {
            val dx = x - v.x
            val dy = y - v.y
            val r2 = dx * dx + dy * dy + v.core * v.core
            val k = v.circulation / (2f * PI.toFloat() * r2)
            val sink = v.inflow / (2f * PI.toFloat() * r2)
            vx += -dy * k - dx * sink
            vy += dx * k - dy * sink
        }
        out[0] = vx
        out[1] = vy
    }

    /** How far below the plane the field dips at (x, y): a funnel into each vortex. */
    fun flowDepth(x: Float, y: Float): Float {
        var z = 0f
        for (v in flowVortices) {
            val dx = x - v.x
            val dy = y - v.y
            z -= 0.35f * abs(v.circulation) * exp(-(dx * dx + dy * dy) / 0.06f)
        }
        return z
    }

    /**
     * Streamlines of [flowVelocity], seeded on a jittered grid and integrated with RK2 in
     * equal arc-length steps until they leave the domain or fall into a vortex core. Each
     * line glows brighter where the flow is faster, so the vortices light up.
     */
    @Suppress("LongMethod", "NestedBlockDepth")
    fun flowField(seed: Int = 5, columns: Int = 30, rows: Int = 52, maxSteps: Int = 110): GlowMesh {
        val rnd = Random(seed)
        val out = RibbonBuilder(columns * rows * maxSteps / 2)
        val points = FloatArray(maxSteps * 3)
        val colors = FloatArray(maxSteps * 3)
        val v1 = FloatArray(2)
        val v2 = FloatArray(2)
        val h = 0.02f
        val margin = 0.15f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var x = -FLOW_HALF_WIDTH + (column + rnd.nextFloat()) * (2f * FLOW_HALF_WIDTH / columns)
                var y = -FLOW_HALF_HEIGHT + (row + rnd.nextFloat()) * (2f * FLOW_HALF_HEIGHT / rows)
                val layer = rnd.gaussian() * 0.06f
                val lineGain = 0.55f + 0.9f * rnd.nextFloat()
                var n = 0
                var tracing = true
                while (tracing && n < maxSteps) {
                    flowVelocity(x, y, v1)
                    val speed = sqrt(v1[0] * v1[0] + v1[1] * v1[1])
                    points[n * 3] = x
                    points[n * 3 + 1] = y
                    points[n * 3 + 2] = flowDepth(x, y) + layer
                    val glow = smoothstep(0.15f, 2.2f, speed)
                    val bright = (0.22f + 1.9f * glow) * lineGain
                    colors[n * 3] = mix(0.12f, 0.7f, glow) * bright
                    colors[n * 3 + 1] = mix(0.42f, 0.9f, glow) * bright
                    colors[n * 3 + 2] = 1.0f * bright
                    n++
                    // Midpoint step of length h along the normalised velocity.
                    val mx = x + 0.5f * h * v1[0] / max(speed, 1e-6f)
                    val my = y + 0.5f * h * v1[1] / max(speed, 1e-6f)
                    flowVelocity(mx, my, v2)
                    val s2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1])
                    if (speed < 1e-4f || s2 < 1e-4f) {
                        tracing = false
                    } else {
                        x += h * v2[0] / s2
                        y += h * v2[1] / s2
                        tracing = abs(x) <= FLOW_HALF_WIDTH + margin &&
                            abs(y) <= FLOW_HALF_HEIGHT + margin &&
                            !insideVortexCore(x, y)
                    }
                }
                if (n >= 12) {
                    out.addCurve(
                        points.copyOf(n * 3), colors.copyOf(n * 3),
                        halfWidth = 0.0032f,
                        seed = rnd.nextFloat(),
                    )
                }
            }
        }
        return out.build()
    }

    /** Glinting dust carried by the flow: bright points dropped on the field's streamlines. */
    fun flowDust(seed: Int = 9, count: Int = 1_400): GlowMesh {
        val rnd = Random(seed)
        val out = SpriteBuilder(count)
        repeat(count) {
            val x = (rnd.nextFloat() * 2f - 1f) * FLOW_HALF_WIDTH
            val y = (rnd.nextFloat() * 2f - 1f) * FLOW_HALF_HEIGHT
            val b = 0.5f + 2.5f * rnd.nextFloat() * rnd.nextFloat()
            out.add(
                x, y, flowDepth(x, y) + rnd.gaussian() * 0.08f,
                r = 0.7f * b, g = 0.85f * b, b = 1.0f * b,
                radius = 0.005f + 0.006f * rnd.nextFloat(),
                phase = rnd.nextFloat(),
            )
        }
        return out.build()
    }

    /**
     * Magnetic loops and wisps around the plasma star (radius [starRadius]): closed arcs that
     * rise off the surface and fall back, and open plumes that curl away from it.
     */
    fun prominences(seed: Int = 17, loops: Int = 150, plumes: Int = 90, starRadius: Float = 1f): GlowMesh {
        val rnd = Random(seed)
        val steps = 48
        val out = RibbonBuilder((loops + plumes) * steps)
        val points = FloatArray(steps * 3)
        val colors = FloatArray(steps * 3)
        fun randomUnit(): FloatArray {
            val z = rnd.nextFloat() * 2f - 1f
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - z * z)
            return floatArrayOf(cos(a) * s, z, sin(a) * s)
        }
        repeat(loops) {
            val a = randomUnit()
            // A second foot point a short great-circle hop away.
            val t = randomUnit()
            val dot = a[0] * t[0] + a[1] * t[1] + a[2] * t[2]
            val px = t[0] - dot * a[0]
            val py = t[1] - dot * a[1]
            val pz = t[2] - dot * a[2]
            val pl = max(sqrt(px * px + py * py + pz * pz), 1e-4f)
            val hop = 0.25f + 0.6f * rnd.nextFloat()
            val height = 0.08f + 0.45f * rnd.nextFloat() * rnd.nextFloat()
            val bright = 0.14f + 0.36f * rnd.nextFloat()
            for (i in 0 until steps) {
                val u = i.toFloat() / (steps - 1)
                val ang = hop * u
                val dx = a[0] * cos(ang) + px / pl * sin(ang)
                val dy = a[1] * cos(ang) + py / pl * sin(ang)
                val dz = a[2] * cos(ang) + pz / pl * sin(ang)
                val lift = starRadius * (1.005f + height * sin(PI.toFloat() * u))
                points[i * 3] = dx * lift
                points[i * 3 + 1] = dy * lift
                points[i * 3 + 2] = dz * lift
                colors[i * 3] = 0.08f * bright
                colors[i * 3 + 1] = 0.38f * bright
                colors[i * 3 + 2] = 1.4f * bright
            }
            out.addCurve(points, colors, halfWidth = 0.006f + 0.01f * rnd.nextFloat(), seed = rnd.nextFloat())
        }
        repeat(plumes) {
            val a = randomUnit()
            val twist = randomUnit()
            val reach = 0.4f + 1.4f * rnd.nextFloat()
            val bright = 0.12f + 0.35f * rnd.nextFloat()
            for (i in 0 until steps) {
                val u = i.toFloat() / (steps - 1)
                val out1 = starRadius * (1.0f + reach * u)
                val curl = 0.5f * u * u
                val dx = a[0] + twist[0] * curl
                val dy = a[1] + twist[1] * curl
                val dz = a[2] + twist[2] * curl
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                points[i * 3] = dx / l * out1
                points[i * 3 + 1] = dy / l * out1
                points[i * 3 + 2] = dz / l * out1
                val fade = 1f - u
                colors[i * 3] = 0.06f * bright * fade
                colors[i * 3 + 1] = 0.34f * bright * fade
                colors[i * 3 + 2] = 1.5f * bright * fade
            }
            out.addCurve(points, colors, halfWidth = 0.012f + 0.02f * rnd.nextFloat(), seed = rnd.nextFloat())
        }
        return out.build()
    }

    /** The glow around the plasma star: a few nested soft sprites, and a wide blue nebula. */
    fun starHalo(starRadius: Float = 1f): GlowMesh {
        val out = SpriteBuilder(4)
        out.add(0f, 0f, 0f, r = 0.35f, g = 0.75f, b = 1.6f, radius = starRadius * 1.5f, phase = 0f)
        out.add(0f, 0f, 0f, r = 0.12f, g = 0.35f, b = 1.0f, radius = starRadius * 2.6f, phase = 0f)
        out.add(0f, 0f, 0f, r = 0.03f, g = 0.1f, b = 0.35f, radius = starRadius * 5.5f, phase = 0f)
        return out.build()
    }

    /** A wide, dim blue glow behind the flow field, so its ground is deep blue rather than void. */
    fun flowBackdrop(): GlowMesh {
        val out = SpriteBuilder(2)
        out.add(0f, 0.2f, -1.6f, r = 0.02f, g = 0.07f, b = 0.2f, radius = 3.6f, phase = 0f)
        out.add(0.1f, 0.1f, -0.8f, r = 0.02f, g = 0.06f, b = 0.16f, radius = 1.5f, phase = 0f)
        return out.build()
    }

    /** The flash at the heart of the burst. */
    fun burstCore(): GlowMesh {
        val out = SpriteBuilder(2)
        out.add(0f, 0f, 0f, r = 1.0f, g = 0.8f, b = 1.0f, radius = 0.35f, phase = 0f)
        out.add(0f, 0f, 0f, r = 0.55f, g = 0.2f, b = 0.6f, radius = 1.1f, phase = 0f)
        return out.build()
    }

    /**
     * The burst's loop, as (head, fade, flash) at [phase] in [0, 1): the tracks grow out fast
     * and decelerate, hold, then fade before the next detonation; the flash is brightest at
     * the instant of detonation.
     */
    fun burstEnvelope(phase: Float): FloatArray {
        val grow = (phase / 0.42f).coerceIn(0f, 1f)
        val head = 1f - (1f - grow) * (1f - grow) * (1f - grow)
        val fade = 1f - smoothstep(0.7f, 0.97f, phase)
        val flash = exp(-phase * 14f) * 6f + 0.35f * fade
        return floatArrayOf(head * 1.25f, fade, flash)
    }
}

/** The four procedural scenes of the Cosmos demo, in tour order. */
internal enum class CosmosScene(val label: String) {
    Galaxy("Galaxy"),
    Star("Star"),
    Burst("Burst"),
    Flow("Flow"),
}

/** Camera framing of each Cosmos scene, as pure functions of time and viewport aspect. */
internal object CosmosFraming {

    /** tan(half vertical FOV) of the default 28 mm lens on a 24 mm sensor height. */
    private const val TAN_HALF_VERTICAL_FOV = 12f / 28f

    /** Distance at which a [halfWidth] × [halfHeight] rectangle just fits the viewport. */
    fun fitDistance(halfWidth: Float, halfHeight: Float, aspect: Float): Float {
        val tanH = TAN_HALF_VERTICAL_FOV * aspect.coerceAtLeast(0.1f)
        return max(halfWidth / tanH, halfHeight / TAN_HALF_VERTICAL_FOV)
    }

    /** Fade-in of a scene after a switch, 0 → 1 over [duration] seconds. */
    fun reveal(sceneTime: Float, duration: Float): Float = smoothstep(0f, duration, sceneTime)

    /**
     * Camera eye (xyz) then target (xyz) for [scene] at [time] seconds, on a viewport of
     * width / height [aspect]. Every scene drifts a little, so even a still subject reads as 3D.
     */
    fun pose(scene: CosmosScene, time: Float, aspect: Float): FloatArray {
        val deg = PI.toFloat() / 180f
        return when (scene) {
            CosmosScene.Galaxy -> {
                val elevation = 58f * deg
                val yaw = (25f + 12f * sin(time * 0.07f)) * deg
                val d = fitDistance(0.98f, 0.98f * sin(elevation) + 0.1f, aspect)
                orbit(d, elevation, yaw)
            }
            CosmosScene.Star -> {
                val d = fitDistance(1.3f, 1.3f, aspect)
                orbit(d, (6f + 3f * sin(time * 0.09f)) * deg, (8f * sin(time * 0.11f)) * deg)
            }
            CosmosScene.Burst -> {
                val d = fitDistance(1.6f, 1.6f, aspect)
                orbit(d, (8f + 4f * sin(time * 0.13f)) * deg, (16f * sin(time * 0.1f)) * deg)
            }
            CosmosScene.Flow -> {
                val d = fitDistance(1.12f, 2.05f, aspect)
                orbit(d, (12f + 2f * sin(time * 0.08f)) * deg, (4f * sin(time * 0.1f)) * deg)
            }
        }
    }

    private fun orbit(distance: Float, elevation: Float, yaw: Float): FloatArray = floatArrayOf(
        distance * cos(elevation) * sin(yaw),
        distance * sin(elevation),
        distance * cos(elevation) * cos(yaw),
        0f,
        0f,
        0f,
    )
}
