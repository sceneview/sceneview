package io.github.sceneview.demo.demos.squat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the voice says, driven by the real counter on synthetic squats. */
class SquatAnnouncementTest {

    private fun squat(bottom: Float): List<Float> {
        val down = (0..10).map { 175f + (bottom - 175f) * it / 10 }
        return down + List(3) { bottom } + down.reversed() + List(3) { 175f }
    }

    /** Runs [samples] through [counter] and collects every announcement, in order. */
    private fun spoken(
        samples: List<Float?>,
        counter: SquatRepCounter = SquatRepCounter(),
    ): List<SquatAnnouncement> {
        val said = mutableListOf<SquatAnnouncement>()
        samples.forEach { angle ->
            val before = counter.state
            squatAnnouncement(before, counter.onSample(angle))?.let(said::add)
        }
        return said
    }

    @Test
    fun fiveSquatsAreFiveSpokenCounts() {
        val said = spoken((1..5).flatMap { squat(bottom = 85f) })
        assertEquals((1..5).map { SquatAnnouncement.Rep(it) }, said)
    }

    @Test
    fun shallowSquatSaysGoLowerOnceAndCountsNothing() {
        val said = spoken(squat(bottom = 120f) + List(10) { 175f })
        assertEquals(listOf(SquatAnnouncement.TooShallow), said)
    }

    @Test
    fun mixedSetSaysEachEventOnceInOrder() {
        val said = spoken(squat(85f) + squat(120f) + squat(85f))
        assertEquals(
            listOf(SquatAnnouncement.Rep(1), SquatAnnouncement.TooShallow, SquatAnnouncement.Rep(2)),
            said,
        )
    }

    @Test
    fun resetIsSilent() {
        val counter = SquatRepCounter()
        spoken(squat(85f), counter)
        val before = counter.state
        counter.reset()
        assertNull(squatAnnouncement(before, counter.state))
    }
}
