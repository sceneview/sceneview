package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the world-rotation extraction contract for scaled transforms (#3738).
 *
 * `Node.refreshWorldCache()` derived `worldQuaternion` by calling kotlin-math's `toQuaternion()`
 * member on the Filament world matrix. That is Shepperd's trace method run on the raw basis: it
 * assumes an orthonormal one, so a `T·R·S` matrix folds `S` into the trace and the renormalised
 * result is a **different rotation** — uniform scale included. Every node under a scaled ancestor
 * therefore reported a wrong world rotation, and every consumer (billboards, `lookAt`, AR anchor
 * alignment, the `worldQuaternion` setter's round trip) inherited it.
 *
 * The fix routes those reads through [Mat4.quaternion], which normalises each basis column first.
 * These cases are pure math on `Mat4`, so they run on every target; the Filament-backed proof on a
 * real `Node` is the instrumented `NodeWorldQuaternionRoundTripTest`.
 */
class Mat4QuaternionUnderScaleTest {

    /** Exactly what `Node.refreshWorldCache()` does to derive `worldQuaternion`. */
    private fun Transform.extractWorldQuaternion(): Quaternion = quaternion

    private val position = Position(1f, 2f, 3f)

    private val rotXMinus90 = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), -90f)
    private val tilted = Quaternion.fromAxisAngle(normalize(Float3(0.3f, 0.7f, -0.6f)), 37f)

    /** Uniform *and* non-uniform, at a single level of the hierarchy. */
    private val scales = listOf(
        Scale(0.25f, 0.25f, 0.25f),
        Scale(2f, 2f, 2f),
        Scale(10f, 10f, 10f),
        Scale(3f, 1f, 0.5f),
        Scale(0.25f, 2f, 10f)
    )

    /**
     * `q` and `-q` are the same rotation, so compare on `|dot|`, never component by component.
     */
    private fun assertSameRotation(
        expected: Quaternion,
        actual: Quaternion,
        tolerance: Float = 1e-5f,
        message: String
    ) {
        val cos = abs(dot(normalize(expected), normalize(actual)))
        assertTrue(
            cos > 1f - tolerance,
            "$message — expected $expected but got $actual (|dot| = $cos)"
        )
    }

    private fun assertFiniteUnit(quaternion: Quaternion, message: String) {
        val norm = dot(quaternion, quaternion)
        assertTrue(
            !norm.isNaN() && abs(1f - norm) < 1e-4f,
            "$message — expected a finite unit quaternion but got $quaternion (|q|² = $norm)"
        )
    }

    /**
     * The headline case: the rotation read back out of `T·R·S` must be `R`, at any scale.
     *
     * Fails on the pre-fix extraction. Worked example from #3738, `rotX(-90°)` at uniform scale 2:
     * the trace method returns `(-0.8, 0, 0, 0.6)` — a 106.26° rotation instead of 90°.
     */
    @Test
    fun worldRotationIsExactUnderEveryScale() {
        listOf("rotX(-90)" to rotXMinus90, "axis(0.3,0.7,-0.6)@37" to tilted).forEach { (name, r) ->
            scales.forEach { scale ->
                val world = Transform(position = position, quaternion = r, scale = scale)
                assertSameRotation(
                    r,
                    world.extractWorldQuaternion(),
                    message = "$name under scale $scale"
                )
            }
        }
    }

    /**
     * The defect [Mat4.quaternion] exists for, asserted rather than described: the kotlin-math
     * trace method is *wrong* on a scaled basis, so nothing may route a world matrix through it.
     * If this ever starts failing, kotlin-math has fixed `toQuaternion()` upstream and the
     * normalisation in [Mat4.quaternion] can be revisited — it is not a regression.
     */
    @Test
    fun traceExtractionIsWrongUnderUniformScale() {
        val world = Transform(position = position, quaternion = rotXMinus90, scale = Scale(2f, 2f, 2f))
        val viaTrace = world.toQuaternion()
        // The measured |dot| is 0.98994958 (an analytic cos 8.13°, so deterministic), which
        // clears a 0.99 bar by 5e-5 — too thin to survive a different libm. 0.995 costs nothing.
        assertTrue(
            abs(dot(normalize(rotXMinus90), normalize(viaTrace))) < 0.995f,
            "kotlin-math's Mat4.toQuaternion() is expected to mis-extract a scaled basis, " +
                "but it returned $viaTrace for rotX(-90) at uniform scale 2"
        )
    }

    /**
     * Why a turntable scene never caught this: a yaw of exactly 0° or 180° is a fixed point of
     * the trace method's renormalisation, so those two angles pass even through the broken path.
     */
    @Test
    fun pureYawAt0And180MasksTheDefect() {
        listOf(0f, 180f).forEach { angle ->
            val yaw = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), angle)
            scales.filter { it.x == it.y && it.y == it.z }.forEach { scale ->
                val world = Transform(position = position, quaternion = yaw, scale = scale)
                assertSameRotation(
                    yaw,
                    world.toQuaternion(),
                    message = "rotY($angle) at scale $scale is expected to survive even the " +
                        "broken trace extraction (documenting the mask, not a contract)"
                )
                assertSameRotation(yaw, world.extractWorldQuaternion(), message = "rotY($angle) at $scale")
            }
        }
    }

    /**
     * And why an axis-only assertion never caught it either: for *any* pure yaw the broken path
     * keeps the axis exactly (`x = z = 0`) and corrupts only the angle — 37° reads back as 42.5°
     * at scale 2. Only an assertion on the whole rotation sees it.
     */
    @Test
    fun pureYawKeepsTheAxisButCorruptsTheAngle() {
        val yaw = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 37f)
        val world = Transform(position = position, quaternion = yaw, scale = Scale(2f, 2f, 2f))
        val viaTrace = world.toQuaternion()

        assertTrue(viaTrace.x == 0f && viaTrace.z == 0f, "the yaw axis is preserved: $viaTrace")
        assertTrue(
            abs(dot(normalize(yaw), normalize(viaTrace))) < 0.999f,
            "but the angle is not: $viaTrace"
        )
        assertSameRotation(yaw, world.extractWorldQuaternion(), message = "rotY(37) at uniform scale 2")
    }

    /**
     * One collapsed axis: normalising a zero-length column is `0 / 0`. The NaN is [Mat4.quaternion]'s
     * own — the trace method `Node` used before #3738 stayed finite here and returned a silently
     * wrong rotation (12.73° off) — so without the guard this fix would have swapped a wrong value
     * for one that propagates into every child transform and any frame-loop driver integrating it.
     * The orientation is still fully determined by the two surviving axes, so it is recovered exactly.
     */
    @Test
    fun oneCollapsedAxisIsRecoveredExactlyInsteadOfNaN() {
        listOf(Scale(0f, 1f, 1f), Scale(1f, 0f, 1f), Scale(1f, 1f, 0f)).forEach { scale ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            val extracted = world.extractWorldQuaternion()
            assertFiniteUnit(extracted, "collapsed axis at scale $scale")
            assertSameRotation(tilted, extracted, message = "collapsed axis at scale $scale")
        }
    }

    /**
     * Two or three collapsed axes leave nothing to recover the orientation from: the contract is
     * a finite identity, never NaN.
     */
    @Test
    fun fullyCollapsedBasisFallsBackToIdentityInsteadOfNaN() {
        listOf(Scale(1f, 0f, 0f), Scale(0f, 0f, 1f), Scale(0f, 0f, 0f)).forEach { scale ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            val extracted = world.extractWorldQuaternion()
            assertFiniteUnit(extracted, "degenerate basis at scale $scale")
            assertSameRotation(
                Quaternion(),
                extracted,
                message = "a basis with no recoverable orientation (scale $scale) returns identity"
            )
        }
    }

    /**
     * An *odd* number of negative axes mirrors the basis, which is not a rotation at all — there
     * is no right answer to return, and nothing downstream can even detect the case, since
     * [Mat4.scale] reports column *lengths* (a scale of `(-2, -2, -2)` reads back as `(2, 2, 2)`).
     * The only contract is that the value stays finite and unit so it cannot poison a scene graph.
     */
    @Test
    fun oddNegativeScaleStaysFiniteAndUnit() {
        listOf(Scale(-1f, 1f, 1f), Scale(-2f, -2f, -2f), Scale(1f, -3f, 1f)).forEach { scale ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            assertFiniteUnit(world.extractWorldQuaternion(), "mirrored basis at scale $scale")
        }
    }

    /**
     * An *even* number of negative axes is not a mirror: the determinant stays positive, so the
     * basis is a genuine rotation — `R` followed by a 180° turn about the axis that kept its sign
     * — and is extracted exactly. Pinned so the caveat documented on [Mat4.quaternion] stays as
     * narrow as the maths: only an odd count is unanswerable.
     */
    @Test
    fun evenNegativeScaleIsARealRotationAndIsExact() {
        listOf(
            Scale(-1f, -1f, 1f) to Float3(0f, 0f, 1f),
            Scale(-1f, 1f, -1f) to Float3(0f, 1f, 0f),
            Scale(1f, -1f, -1f) to Float3(1f, 0f, 0f),
            Scale(-2f, -2f, 2f) to Float3(0f, 0f, 1f)
        ).forEach { (scale, keptAxis) ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            assertSameRotation(
                tilted * Quaternion.fromAxisAngle(keptAxis, 180f),
                world.extractWorldQuaternion(),
                message = "an even negative scale $scale is a 180° turn about $keptAxis"
            )
        }
    }

    /**
     * A non-uniformly scaled ancestor followed by a rotated descendant shears the world basis
     * (`dot(col0, col1) = -0.8` here): it is no longer a rotation times a per-axis scale, so
     * normalising its columns cannot give back the child's rotation. That normalisation rescales
     * the basis without re-orthogonalising it, so what comes back is *a*
     * unit rotation — not the nearest one, and not bounded in any useful way once the parent is
     * itself rotated (29.13° under this scale, ~180° in the worst pose under `(0.25, 2, 10)`).
     * An exact answer does exist for a single scaled ancestor and is tracked in #3744; this case
     * pins today's behaviour so that landing it is a visible change rather than a silent one.
     *
     * The bar below is empirical, not a contract: it is tight enough to catch a regression on
     * this fixed parent and child, and says nothing about the general case.
     */
    @Test
    fun shearedWorldBasisIsApproximatedNotExact() {
        val parentWorld = Transform(scale = Scale(3f, 1f, 1f))
        // Every 15°, not just 15/45/90: the error is not monotonic in the angle, and sampling
        // three points had put the worst case in the wrong place.
        listOf(15f, 30f, 45f, 60f, 75f, 90f).forEach { angle ->
            val childLocal = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), angle)
            val childWorld = parentWorld * rotation(childLocal)
            val extracted = childWorld.extractWorldQuaternion()

            assertFiniteUnit(extracted, "sheared basis, child rotZ($angle)")
            val cos = abs(dot(normalize(childLocal), normalize(extracted)))
            // |dot| is the half-angle cosine, so 0.996 allows 10.25° of rotation error against
            // a worst measured 8.64°. The 0.99 this used to assert allowed 16.22°.
            assertTrue(
                cos > 0.996f,
                "the approximation must stay within 10.25° of the child rotation for this " +
                    "parent (rotZ($angle), |dot| = $cos)"
            )
        }
        // Measured on this parent: 6.46° at 15°, 3.71° at 30°, 2.64° at 45°, 8.24° at 60°,
        // 8.64° at 75°, 0.00° at 90° — so 75°, not 15°, is the worst, and the error is not
        // monotonic. No exact answer exists here, so the 1e-5 bar every unsheared case meets is
        // deliberately NOT asserted.
        val worstAngle = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), 75f)
        val worst = (parentWorld * rotation(worstAngle)).extractWorldQuaternion()
        assertTrue(
            abs(dot(normalize(worstAngle), normalize(worst))) < 1f - 1e-5f,
            "a sheared basis is expected to be inexact — if this now passes the 1e-5 bar, " +
                "the extraction has become shear-aware (#3744) and this case can be tightened"
        )
    }

    /**
     * `Mat4.rotation` (the Euler decomposition backing `Node.worldRotation`) normalises the basis
     * internally, so it never had the defect. Pinned so the two world-rotation reads cannot drift
     * apart again.
     */
    @Test
    fun eulerRotationExtractionIsScaleImmune() {
        val reference = rotation(tilted).rotation
        scales.forEach { scale ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            val extracted = world.rotation
            listOf(
                Triple("pitch", reference.x, extracted.x),
                Triple("yaw", reference.y, extracted.y),
                Triple("roll", reference.z, extracted.z)
            ).forEach { (axis, expected, actual) ->
                assertTrue(
                    abs(expected - actual) < 1e-3f,
                    "Mat4.rotation $axis must not depend on scale $scale: $expected vs $actual"
                )
            }
        }
    }

    /**
     * The world→local direction, which backs the `worldQuaternion` setter: inverting the scaled
     * world matrix and extracting must agree with inverting the extracted rotation. The pre-fix
     * `inverse(world).toQuaternion()` did not (|dot| = 0.997 at uniform scale 2), so a set/get
     * round trip under a scaled parent lost the posed value.
     */
    @Test
    fun inverseOfScaledMatrixExtractsTheInverseRotation() {
        scales.filter { it.x == it.y && it.y == it.z }.forEach { scale ->
            val world = Transform(position = position, quaternion = tilted, scale = scale)
            assertSameRotation(
                inverse(world.extractWorldQuaternion()),
                inverse(world).extractWorldQuaternion(),
                message = "inverse(world).quaternion at scale $scale"
            )
        }
    }

    /**
     * End to end, at matrix level, what the instrumented test asserts on a real `Node`: posing a
     * child's world rotation under a *scaled, tilted* parent and reading it straight back must
     * return the posed value.
     */
    @Test
    fun worldQuaternionRoundTripsUnderAUniformlyScaledParent() {
        listOf(Scale(2f, 2f, 2f), Scale(0.25f, 0.25f, 0.25f), Scale(10f, 10f, 10f)).forEach { scale ->
            val parentWorld = Transform(position = position, quaternion = rotXMinus90, scale = scale)
            val target = tilted

            // What `Node.getLocalQuaternion` computes, then what Filament composes from it.
            val local = inverse(parentWorld.extractWorldQuaternion()) * target
            val childWorld = parentWorld * rotation(local)

            assertSameRotation(
                target,
                childWorld.extractWorldQuaternion(),
                message = "worldQuaternion round trip under a parent scaled $scale"
            )
        }
    }
}
