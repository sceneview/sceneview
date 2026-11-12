package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelPressureTest {
    @Test fun constantPressureAndSizeUseHoldRampEvenWithoutMoveEvents() {
        var samples = PressureSamples()
        repeat(20) { samples = observePressure(samples, 1f, 0.1f) }
        assertEquals(PressureSource.Duration, inferPressure(samples, 700).source)
        assertEquals(0.12f, inferPressure(samples, 0).value, 0.0001f)
        assertEquals(0.56f, inferPressure(samples, 700).value, 0.0001f)
        assertEquals(1f, inferPressure(samples, 1400).value, 0.0001f)
        assertEquals(1f, inferPressure(samples, Long.MAX_VALUE).value, 0.0001f)
    }

    @Test fun varyingPressureTakesPriorityOverContactSize() {
        var samples = PressureSamples()
        for (p in listOf(0.2f, 0.4f, 0.8f)) samples = observePressure(samples, p, p / 2f)
        val inferred = inferPressure(samples, 1400)
        assertEquals(PressureSource.Pressure, inferred.source)
        assertEquals(0.8f, inferred.value, 0.0001f)
        repeat(30) { samples = observePressure(samples, 0.8f, 0.4f) }
        assertEquals(PressureSource.Pressure, inferPressure(samples, 4000).source)
    }

    @Test fun contactSizeTakesOverWhenPressureIsConstant() {
        var samples = PressureSamples()
        for (s in listOf(0.1f, 0.12f, 0.16f)) samples = observePressure(samples, 1f, s)
        assertEquals(PressureSource.ContactSize, inferPressure(samples, 100).source)
        assertEquals(1f, inferPressure(samples, 100).value, 0.0001f)
        samples = observePressure(samples, 1f, 0.1f)
        assertEquals(0.12f, inferPressure(samples, 500).value, 0.0001f)
    }

    @Test fun quantizationNoiseAndTwoSamplesDoNotQualify() {
        var samples = observePressure(PressureSamples(), 0.3f, 0.1f)
        samples = observePressure(samples, 0.8f, 0.2f)
        assertEquals(PressureSource.Duration, inferPressure(samples, 100).source)
        samples = PressureSamples()
        repeat(50) { samples = observePressure(samples, 0.5f + (it % 2) * 0.001f, 0.1f + (it % 2) * 0.001f) }
        assertEquals(PressureSource.Duration, inferPressure(samples, 100).source)
    }

    @Test fun invalidSensorsAndNegativeTimeAreFiniteAndBounded() {
        var samples = PressureSamples()
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) samples = observePressure(samples, v, v)
        val result = inferPressure(samples, -100)
        assertEquals(PressureSource.Duration, result.source)
        assertEquals(0.12f, result.value, 0.0001f)
        samples = PressureSamples()
        for (v in listOf(0.5f, 1f, 1.5f)) samples = observePressure(samples, v, 0f)
        assertEquals(1f, inferPressure(samples, 100).value, 0.0001f)
        assertTrue(inferPressure(PressureSamples(), 500).value.isFinite())
    }
}
