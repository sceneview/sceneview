package io.github.sceneview

import io.github.sceneview.math.Direction
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.node.ContactShadowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM contract tests for the contact-shadow geometry and its per-context presets (#2740
 * sub-task C).
 *
 * Filament and ARCore are JNI-only and absent from the unit-test classpath, so a
 * [ContactShadowNode] cannot be instantiated here. What CAN be tested — and is what actually
 * matters — is the pure offset math and the design intent encoded in the presets.
 */
class ContactShadowMathTest {

    private val eps = 1e-5f

    // ── surfaceOffsetFor ──────────────────────────────────────────────────────────────────────

    @Test
    fun `floor quad is lifted along world up`() {
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(y = 1.0f))

        assertEquals(0.0f, offset.x, eps)
        assertEquals(ContactShadowNode.SURFACE_OFFSET_METERS, offset.y, eps)
        assertEquals(0.0f, offset.z, eps)
    }

    @Test
    fun `wall quad is lifted along its own normal, not up`() {
        // The whole reason the offset is a vector: `Plane` builds a wall quad in the XY plane, so
        // its normal is +Z. shadow_receiver.mat's hardcoded `pos.y += 0.005` would slide this quad
        // UP ITS OWN FACE — still embedded in the wall, still z-fighting.
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(z = 1.0f))

        assertEquals("a wall quad must not be lifted along world up", 0.0f, offset.y, eps)
        assertEquals(ContactShadowNode.SURFACE_OFFSET_METERS, offset.z, eps)
    }

    @Test
    fun `an unnormalised normal still yields exactly the configured distance`() {
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(0.0f, 7.3f, 0.0f))

        assertEquals(
            "the offset length must come from `distance`, never the normal's magnitude",
            ContactShadowNode.SURFACE_OFFSET_METERS,
            offset.y,
            eps
        )
    }

    @Test
    fun `a diagonal normal is lifted by the distance, measured as a length`() {
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(1.0f, 1.0f, 0.0f))
        val length = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)

        assertEquals(ContactShadowNode.SURFACE_OFFSET_METERS, length, eps)
    }

    @Test
    fun `a zero normal degrades to no lift instead of NaN`() {
        // Normalising a zero vector divides by zero. A NaN position would send the whole quad off
        // the screen — a mis-configured shadow must degrade to z-fighting, which is visible and
        // diagnosable, not to a silently vanished node.
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(0.0f, 0.0f, 0.0f))

        assertTrue(
            "a zero normal must not produce NaN",
            offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite()
        )
        assertEquals(0.0f, offset.x, eps)
        assertEquals(0.0f, offset.y, eps)
        assertEquals(0.0f, offset.z, eps)
    }

    @Test
    fun `the lift distance is overridable`() {
        val offset = ContactShadowNode.surfaceOffsetFor(Direction(y = 1.0f), distance = 0.02f)

        assertEquals(0.02f, offset.y, eps)
    }

    // ── Context presets ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every preset is renderable`() {
        for (context in ContactShadowContext.values()) {
            assertTrue(
                "$context intensity must be a valid opacity",
                context.intensity in 0.0f..1.0f
            )
            assertTrue(
                "$context needs a non-degenerate ellipse or the shader divides by ~zero",
                context.radiusU > 0.0f && context.radiusV > 0.0f
            )
            assertTrue("$context needs a non-zero fade width", context.softness > 0.0f)
            assertTrue(
                "$context centre must stay inside the quad",
                context.centerU in -0.5f..0.5f && context.centerV in -0.5f..0.5f
            )
        }
    }

    @Test
    fun `the wall preset pushes its pool below the object`() {
        // On a wall quad `v` grows with local +Y, so a negative centerV moves the pool DOWN — where
        // ceiling light actually leaves a shadow on a wall-mounted panel. This sign is the whole
        // difference between "mounted" and "floating".
        assertTrue(
            "the wall pool must sit below the object's midline",
            ContactShadowContext.Wall.centerV < 0.0f
        )
        assertEquals(
            "a floor shadow is directly underneath — no vertical offset",
            0.0f,
            ContactShadowContext.Floor.centerV,
            eps
        )
    }

    @Test
    fun `the wall preset is weaker than the floor preset`() {
        // Indoor light comes from the ceiling: it faces the floor and merely grazes the wall, so
        // the wall pool is a diffuse haze rather than a dense patch. Encoded here so a future
        // "let's make it pop" tweak has to argue with the physics first.
        assertTrue(
            "a grazing light leaves a fainter shadow than a facing one",
            ContactShadowContext.Wall.intensity < ContactShadowContext.Floor.intensity
        )
        assertTrue(
            "a grazing light also spreads the falloff wider",
            ContactShadowContext.Wall.softness > ContactShadowContext.Floor.softness
        )
    }

    @Test
    fun `the wall preset is wider than it is tall`() {
        assertTrue(
            "wall-mounted objects (TV, art) are landscape — a round pool reads as a sticker",
            ContactShadowContext.Wall.radiusU > ContactShadowContext.Wall.radiusV
        )
    }

    @Test
    fun `the tabletop preset is the crispest`() {
        // A small object sits close to its surface, so its contact patch has the least penumbra.
        for (context in listOf(ContactShadowContext.Floor, ContactShadowContext.Wall)) {
            assertTrue(
                "tabletop contact must be crisper than $context",
                ContactShadowContext.TableTop.softness < context.softness
            )
        }
    }

    // ── Gradient shape (mirrors the fragment shader) ──────────────────────────────────────────

    @Test
    fun `the pool fades from its centre outward`() {
        // The pool has a full plateau (alpha = intensity while d < 1), not a single peak — a
        // contact shadow has a solid core. So "darkest at the centre" is really "the centre is
        // the maximum, and opacity drops once you pass the ellipse", tested against a point that
        // is genuinely in the falloff (centre + radius + margin), not another plateau point.
        for (context in ContactShadowContext.values()) {
            val cu = 0.5f + context.centerU
            val cv = 0.5f + context.centerV
            val centre = alphaAt(context, cu, cv)
            val faded = alphaAt(context, cu + context.radiusU + 0.12f, cv)

            assertTrue("$context centre must be at least as dark as any point", centre >= faded)
            assertTrue("$context must fade below its centre out in the falloff", faded < centre)
        }
    }

    @Test
    fun `no preset leaves a hard edge at the quad border`() {
        // THE regression this guards. The gradient lives in UV space, so a preset whose falloff
        // has not reached zero by the border gets CUT OFF there — a rectangular seam around the
        // pool, reading as a hard-edged box instead of a shadow. Wall really does reach the
        // horizontal border at ~0.43 before the edge feather (0.5/0.34 = 1.47, short of the 1.85
        // where the falloff ends), so this is a real failure mode, not a theoretical one.
        for (context in ContactShadowContext.values()) {
            for (t in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                assertEquals("$context must vanish at the left border", 0.0f, alphaAt(context, 0.0f, t), 1e-4f)
                assertEquals("$context must vanish at the right border", 0.0f, alphaAt(context, 1.0f, t), 1e-4f)
                assertEquals("$context must vanish at the bottom border", 0.0f, alphaAt(context, t, 0.0f), 1e-4f)
                assertEquals("$context must vanish at the top border", 0.0f, alphaAt(context, t, 1.0f), 1e-4f)
            }
        }

        // Vanishing *at* the border only proves the value is zero there — a 1-px cliff would pass
        // it too. The seam this guards is a *transition-width* property, so also pin that the fade
        // ramps GRADUALLY inward. Sample the Wall preset (the one that reaches the horizontal border
        // hot, ~0.43 raw) along v = 0.5, where the vertical feather is saturated so only the
        // horizontal feather + gradient act: alpha must rise strictly across 0.02 → 0.06 → 0.10.
        val nearBorder = alphaAt(ContactShadowContext.Wall, 0.02f, 0.5f)
        val midFeather = alphaAt(ContactShadowContext.Wall, 0.06f, 0.5f)
        val pastFeather = alphaAt(ContactShadowContext.Wall, 0.10f, 0.5f)
        assertTrue("the wall fade must ramp in from the border, not cliff", nearBorder < midFeather)
        assertTrue("the wall fade must keep ramping past the feather", midFeather < pastFeather)
    }

    @Test
    fun `the wall pool leans below the object`() {
        // On a wall quad `v` grows with local +Y, so the pool must be denser below the midline
        // than the same distance above it.
        val below = alphaAt(ContactShadowContext.Wall, 0.5f, 0.35f)
        val above = alphaAt(ContactShadowContext.Wall, 0.5f, 0.65f)

        assertTrue("the wall pool must be denser below the midline than above it", below > above)
    }

    /**
     * Mirrors `contact_shadow.mat`'s fragment stage so the gradient's shape can be pinned from
     * plain JVM tests — there is no GPU here. **Keep in sync with the shader**: if the fragment
     * stage changes, this must change with it or the tests pin a shape that is no longer shipped.
     */
    private fun alphaAt(context: ContactShadowContext, u: Float, v: Float): Float {
        val px = u - 0.5f - context.centerU
        val py = v - 0.5f - context.centerV
        val qx = px / maxOf(context.radiusU, 1e-4f)
        val qy = py / maxOf(context.radiusV, 1e-4f)
        val d = sqrt(qx * qx + qy * qy)
        var a = 1.0f - smoothstep(1.0f, 1.0f + maxOf(context.softness, 1e-4f), d)
        // Edge feather — the shader's `smoothstep(0.0, 0.08, min(e.x, e.y))`.
        val edge = minOf(minOf(u, 1.0f - u), minOf(v, 1.0f - v))
        a *= smoothstep(0.0f, 0.08f, edge)
        return a * context.intensity
    }

    /** GLSL `smoothstep`. */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }
}
