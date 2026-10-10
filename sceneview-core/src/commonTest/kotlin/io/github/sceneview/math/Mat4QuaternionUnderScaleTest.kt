package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
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
 * The fix routes those reads through [Mat4.quaternion], which normalises each basis column and,
 * when shear makes the columns non-orthogonal, extracts the orthogonal polar factor. These cases
 * are pure math on `Mat4`, so they run on every target; the Filament-backed proof on a real `Node`
 * is the instrumented `NodeWorldQuaternionRoundTripTest`.
 */
class Mat4QuaternionUnderScaleTest {

    /** Exactly what `Node.refreshWorldCache()` does to derive `worldQuaternion`. */
    private fun Transform.extractWorldQuaternion(): Quaternion = quaternion

    private val position = Position(1f, 2f, 3f)

    private val rotXMinus90 = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), -90f)
    private val tilted = Quaternion.fromAxisAngle(normalize(Float3(0.3f, 0.7f, -0.6f)), 37f)

    private data class ParentCase(
        val name: String,
        val quaternion: Quaternion,
        val scale: Scale
    )

    private val shearedParentCases = listOf(
        ParentCase("scale (3, 1, 1)", Quaternion(), Scale(3f, 1f, 1f)),
        ParentCase("scale (0.25, 2, 10)", Quaternion(), Scale(0.25f, 2f, 10f)),
        ParentCase("rotX(-90), scale (3, 1, 1)", rotXMinus90, Scale(3f, 1f, 1f)),
        ParentCase("tilted 37 degrees, scale (10, 1, 1)", tilted, Scale(10f, 1f, 1f)),
        // Absolute scale must not matter: det = 3e-9 here, a millimetre-scale model.
        ParentCase("tilted 37 degrees, scale (0.003, 0.001, 0.001)", tilted, Scale(0.003f, 0.001f, 0.001f)),
        // A 1000:1 axis ratio, where an unscaled Newton iteration needs many more steps.
        ParentCase("rotX(-90), scale (1000, 1, 1)", rotXMinus90, Scale(1000f, 1f, 1f))
    )

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

    private fun angularErrorDegrees(expected: Quaternion, actual: Quaternion): Float {
        val cosHalfAngle = abs(dot(normalize(expected), normalize(actual))).coerceIn(0f, 1f)
        return (2.0 * acos(cosHalfAngle.toDouble()) * 180.0 / PI).toFloat()
    }

    private fun childPose(yaw: Int, pitch: Int) =
        Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yaw.toFloat()) *
            Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), pitch.toFloat())

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

    /** A single non-uniformly scaled ancestor has an exact polar-factor world rotation. */
    @Test
    fun shearedWorldBasisRecoversExactRotation() {
        val parentWorld = Transform(scale = Scale(3f, 1f, 1f))
        listOf(15f, 30f, 45f, 60f, 75f, 90f).forEach { angle ->
            val childLocal = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), angle)
            val childWorld = parentWorld * rotation(childLocal)
            val extracted = childWorld.extractWorldQuaternion()

            assertFiniteUnit(extracted, "sheared basis, child rotZ($angle)")
            assertTrue(
                angularErrorDegrees(childLocal, extracted) < 0.1f,
                "polar extraction must recover child rotZ($angle): expected $childLocal, got $extracted"
            )
        }
    }

    /** The complete issue sweep: 37 yaw poses × 19 pitch poses for each scaled parent. */
    @Test
    fun shearedWorldRotationSweepStaysWithinPointOneDegree() {
        shearedParentCases.forEach { parent ->
            val parentWorld = Transform(quaternion = parent.quaternion, scale = parent.scale)
            var worstError = 0f
            var worstPose = ""
            for (yaw in -180..180 step 10) {
                for (pitch in -90..90 step 10) {
                    val childLocal = childPose(yaw, pitch)
                    val expected = parent.quaternion * childLocal
                    val actual = (parentWorld * rotation(childLocal)).extractWorldQuaternion()
                    val error = angularErrorDegrees(expected, actual)
                    if (error > worstError) {
                        worstError = error
                        worstPose = "yaw=$yaw, pitch=$pitch"
                    }
                }
            }
            assertTrue(
                worstError < 0.1f,
                "${parent.name}: worst angular error was $worstError degrees at $worstPose"
            )
        }
    }

    /** Setting a world quaternion and extracting the recomposed matrix round-trips under shear. */
    @Test
    fun worldQuaternionSetterGetterRoundTripsUnderShearedParents() {
        shearedParentCases.forEach { parent ->
            val parentWorld = Transform(quaternion = parent.quaternion, scale = parent.scale)
            var worstError = 0f
            var worstPose = ""
            for (yaw in -180..180 step 10) {
                for (pitch in -90..90 step 10) {
                    val target = parent.quaternion * childPose(yaw, pitch)
                    val localQuaternion = worldToLocalQuaternion(
                        worldQuaternion = target,
                        parentWorldQuaternion = parent.quaternion
                    )
                    val actual = (parentWorld * rotation(localQuaternion)).extractWorldQuaternion()
                    val error = angularErrorDegrees(target, actual)
                    if (error > worstError) {
                        worstError = error
                        worstPose = "yaw=$yaw, pitch=$pitch"
                    }
                }
            }
            assertTrue(
                worstError < 0.1f,
                "${parent.name}: setter/getter worst error was $worstError degrees at $worstPose"
            )
        }
    }

    /** Nested non-uniform scales have no exact composed-rotation contract, only a safe result. */
    @Test
    fun nestedNonUniformScalesReturnFiniteUnitQuaternion() {
        val world = Transform(quaternion = tilted, scale = Scale(3f, 1f, 0.5f)) *
            Transform(
                quaternion = Quaternion.fromEuler(Rotation(24f, -51f, 13f)),
                scale = Scale(0.25f, 2f, 10f)
            ) * rotation(Quaternion.fromEuler(Rotation(-33f, 68f, 17f)))

        assertFiniteUnit(world.extractWorldQuaternion(), "two nested non-uniform scales")
    }

    /** An odd negative scale on a sheared path must use the finite pre-polar fallback. */
    @Test
    fun mirroredShearedBasisReturnsFiniteUnitQuaternion() {
        val world = Transform(scale = Scale(3f, 1f, 1f)) *
            Transform(
                quaternion = Quaternion.fromEuler(Rotation(20f, 35f, -15f)),
                scale = Scale(-1f, 1f, 1f)
            )

        assertFiniteUnit(world.extractWorldQuaternion(), "mirrored sheared basis")
    }

    /**
     * `Mat4.rotation` normalises an unsheared basis internally, so single-level scale does not
     * affect it. It no longer backs `Node.worldRotation`, which reads its Euler angles from
     * `worldQuaternion` since #3745.
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
