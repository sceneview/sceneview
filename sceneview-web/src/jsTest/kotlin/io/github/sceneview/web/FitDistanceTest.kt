package io.github.sceneview.web

import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `fitToModels(margin)` distance math ([ContentCentering.fitDistance], #2946):
 * the default keeps the historical `2.5 × radius` dolly, and `margin` is an iOS-style
 * multiplier clamped to the same `0.2…10` range as `.framingMargin(_:)`.
 */
class FitDistanceTest {

    @Test
    fun defaultMarginKeepsHistoricalFit() {
        assertEquals(2.5, ContentCentering.fitDistance(1.0), 1e-9)
        assertEquals(5.0, ContentCentering.fitDistance(2.0), 1e-9)
        assertEquals(ContentCentering.fitDistance(3.0), ContentCentering.fitDistance(3.0, 1.0), 1e-9)
    }

    @Test
    fun marginIsAMultiplierNotAnAdditiveFraction() {
        // iOS 1.15 == 15% more air; an Android caller passing the padding `0.15`
        // by mistake is clamped to the floor, not turned into 2.15x.
        assertEquals(2.5 * 1.15, ContentCentering.fitDistance(1.0, 1.15), 1e-9)
        assertEquals(2.5 * 0.95, ContentCentering.fitDistance(1.0, 0.95), 1e-9)
        assertEquals(2.5 * 0.2, ContentCentering.fitDistance(1.0, 0.15), 1e-9)
    }

    @Test
    fun marginIsClampedToTheIosRange() {
        assertEquals(2.5 * 0.2, ContentCentering.fitDistance(1.0, 0.0), 1e-9)
        assertEquals(2.5 * 0.2, ContentCentering.fitDistance(1.0, -3.0), 1e-9)
        assertEquals(2.5 * 10.0, ContentCentering.fitDistance(1.0, 42.0), 1e-9)
    }

    @Test
    fun nonFiniteMarginFallsBackToDefault() {
        assertEquals(2.5, ContentCentering.fitDistance(1.0, Double.NaN), 1e-9)
        assertEquals(2.5, ContentCentering.fitDistance(1.0, Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test
    fun degenerateRadiusYieldsZero() {
        assertEquals(0.0, ContentCentering.fitDistance(0.0), 0.0)
        assertEquals(0.0, ContentCentering.fitDistance(-1.0), 0.0)
        assertEquals(0.0, ContentCentering.fitDistance(Double.NaN), 0.0)
    }

    @Test
    fun fortyFiveDegreeFovKeepsHistoricalFitExactly() {
        val historical = ContentCentering.fitDistance(2.0, 1.15)
        assertEquals(historical, ContentCentering.fitDistance(2.0, 1.15, 45.0), 0.0)
    }

    @Test
    fun narrowerFovMovesTheCameraBack() {
        val historical = ContentCentering.fitDistance(2.0, 1.0)
        val ratio = tan(22.5 * PI / 180.0) / tan(11.25 * PI / 180.0)
        assertEquals(historical * ratio, ContentCentering.fitDistance(2.0, 1.0, 22.5), 1e-9)
    }

    @Test
    fun widerFovMovesTheCameraCloser() {
        val historical = ContentCentering.fitDistance(2.0, 1.0)
        val ratio = tan(22.5 * PI / 180.0) / tan(45.0 * PI / 180.0)
        assertEquals(historical * ratio, ContentCentering.fitDistance(2.0, 1.0, 90.0), 1e-9)
    }
}
