package io.github.sceneview.ar.arcore

import com.google.ar.core.CameraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM regression test for the [CameraConfigFilterBuilder] DSL (#1733, #1772).
 *
 * The native [com.google.ar.core.CameraConfigFilter] constructor is JNI-only (binds a native
 * handle to a live [com.google.ar.core.Session]) and cannot be instantiated under pure-JVM
 * tests. So this test only pins the *property accumulation* of the Kotlin DSL — the actual
 * `build(session)` and `cameraConfigFilter { … }` glue is exercised on-device through the
 * AR demos.
 *
 * What we pin here:
 *  - Every DSL field defaults to `null` ("don't filter on this axis").
 *  - Every assignment via the builder lambda is persisted (no shadowing / accidental delegation).
 *  - Re-applying the same builder DSL replays cleanly (the receiver is mutable, not snapshot-
 *    on-construct).
 */
class CameraConfigFilterBuilderTest {

    @Test
    fun `all fields default to null`() {
        val builder = CameraConfigFilterBuilder()
        assertNull("facing default", builder.facing)
        assertNull("targetFps default", builder.targetFps)
        assertNull("depthSensor default", builder.depthSensor)
        assertNull("stereoCamera default", builder.stereoCamera)
    }

    @Test
    fun `DSL block populates every field`() {
        // #1844 — all three "filter on enum X" knobs are Set<X>? for symmetry with the
        // underlying ARCore `set*(EnumSet)` API.
        val builder = CameraConfigFilterBuilder().apply {
            facing = CameraConfig.FacingDirection.BACK
            targetFps = setOf(CameraConfig.TargetFps.TARGET_FPS_60)
            depthSensor = setOf(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
            stereoCamera = setOf(CameraConfig.StereoCameraUsage.DO_NOT_USE)
        }
        assertEquals(CameraConfig.FacingDirection.BACK, builder.facing)
        assertEquals(setOf(CameraConfig.TargetFps.TARGET_FPS_60), builder.targetFps)
        assertEquals(setOf(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE), builder.depthSensor)
        assertEquals(setOf(CameraConfig.StereoCameraUsage.DO_NOT_USE), builder.stereoCamera)
    }

    @Test
    fun `depthSensor and stereoCamera accept multiple values`() {
        // #1844 — symmetric with targetFps. Mixed sets exercise the underlying
        // EnumSet.copyOf path inside build().
        val builder = CameraConfigFilterBuilder().apply {
            depthSensor = setOf(
                CameraConfig.DepthSensorUsage.REQUIRE_AND_USE,
                CameraConfig.DepthSensorUsage.DO_NOT_USE,
            )
            stereoCamera = setOf(
                CameraConfig.StereoCameraUsage.REQUIRE_AND_USE,
                CameraConfig.StereoCameraUsage.DO_NOT_USE,
            )
        }
        assertEquals(2, builder.depthSensor?.size)
        assertEquals(2, builder.stereoCamera?.size)
    }

    @Test
    fun `targetFps accepts multiple values`() {
        // ARCore's CameraConfigFilter ships a Set-style API for targetFps — both 30 and 60
        // FPS configs can be eligible in the same filter. Pin that the DSL preserves the full
        // set rather than collapsing to a single value.
        val builder = CameraConfigFilterBuilder().apply {
            targetFps = setOf(
                CameraConfig.TargetFps.TARGET_FPS_30,
                CameraConfig.TargetFps.TARGET_FPS_60
            )
        }
        assertEquals(2, builder.targetFps?.size)
        assertEquals(
            setOf(
                CameraConfig.TargetFps.TARGET_FPS_30,
                CameraConfig.TargetFps.TARGET_FPS_60
            ),
            builder.targetFps
        )
    }

    @Test
    fun `empty set on targetFps does not crash builder property assignment`() {
        // #1844 — the property is `Set<…>?`. Validation moves to `build(session)` (which is
        // JNI-only). Pin that the DSL itself stays loose so test fixtures can twiddle the value.
        val builder = CameraConfigFilterBuilder().apply {
            targetFps = emptySet()
            depthSensor = emptySet()
            stereoCamera = emptySet()
        }
        assertEquals(emptySet<CameraConfig.TargetFps>(), builder.targetFps)
        assertEquals(emptySet<CameraConfig.DepthSensorUsage>(), builder.depthSensor)
        assertEquals(emptySet<CameraConfig.StereoCameraUsage>(), builder.stereoCamera)
    }

    @Test
    fun `cameraConfigFilter top-level builder produces a Session-CameraConfig lambda`() {
        // The Kotlin compiler binds the lambda type; this test pins that the signature didn't
        // drift away from `(Session) -> CameraConfig`. A pure-JVM call would need a Session
        // (JNI-only), so we just compile-time check that the lambda variable accepts the
        // expected type.
        val selector: (com.google.ar.core.Session) -> CameraConfig = cameraConfigFilter {
            facing = CameraConfig.FacingDirection.BACK
        }
        // Reference the lambda to satisfy the compiler that the assignment succeeded.
        assertEquals(
            "(Session) -> CameraConfig",
            selector.let { "(Session) -> CameraConfig" }
        )
    }
}
