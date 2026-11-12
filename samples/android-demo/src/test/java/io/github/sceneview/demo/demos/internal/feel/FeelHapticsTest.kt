package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FeelHapticsTest {
    private fun contact(kind: FeelMaterial, state: Indentation = Indentation(2f, 4f)): FeelEffect? =
        FeelHaptics().run { begin(kind, Indentation()); advance(state, .5f, 0f, 0f, .01f, 0) }

    @Test fun sixMaterialsHaveDistinctContactSignatures() {
        val signatures = FeelMaterial.entries.map { contact(it) }
        assertEquals(6, signatures.distinct().size)
        assertEquals(FeelPrimitive.SlowRise, signatures[0]!!.pulses.single().kind)
        assertEquals(FeelPrimitive.QuickRise, signatures[1]!!.pulses.single().kind)
        assertNull(signatures[2]) // Velvet only brushes, never a contact knock.
        assertEquals(FeelPrimitive.Tick, signatures[3]!!.pulses.single().kind)
        assertEquals(listOf(FeelPulse(FeelPrimitive.Click, .90f)), signatures[4]!!.pulses)
        assertEquals(listOf(FeelPrimitive.Click, FeelPrimitive.LowTick, FeelPrimitive.LowTick), signatures[5]!!.pulses.map { it.kind })
        assertEquals(listOf(.85f, .28f, .12f), signatures[5]!!.pulses.map { it.scale })
        assertEquals(listOf(0, 12, 12), signatures[5]!!.pulses.map { it.pauseMillis })
    }

    @Test fun foamFollowsSinkingDepthAndVelocityThenStopsWhenHeld() {
        assertNull(contact(FeelMaterial.MemoryFoam, Indentation()))
        val shallow = contact(FeelMaterial.MemoryFoam, Indentation(2f, 1f))!!.pulses.single().scale
        val deep = contact(FeelMaterial.MemoryFoam, Indentation(20f, 15f))!!.pulses.single().scale
        assertTrue(deep > shallow)
        val h = FeelHaptics().apply { begin(FeelMaterial.MemoryFoam, Indentation()) }
        for (now in 0L..2000L step 10L) {
            val effect = h.advance(Indentation(10f, 2f), .5f, 0f, 0f, .01f, now)
            if (now % 500 == 0L) assertEquals(FeelPrimitive.SlowRise, effect!!.pulses.single().kind)
            else assertNull(effect)
        }
        assertNull(h.advance(Indentation(10f, 0f), .5f, 0f, 0f, .01f, 2600))
        h.release(Indentation(10f), 2700)
        assertNull(h.advance(Indentation(10f), 0f, 0f, 0f, .01f, 2839))
        val tail = h.advance(Indentation(10f), 0f, 0f, 0f, .01f, 2840)!!.pulses.single()
        assertEquals(FeelPrimitive.LowTick, tail.kind)
        assertTrue(tail.scale in .035f.. .06f)
        assertNull(h.advance(Indentation(9f), 0f, 0f, 0f, .01f, 4000))
    }

    @Test fun gelTicksAtActualSimulationOvershootsAndScalesWithPeakHeight() {
        for (stepMillis in listOf(4L, 8L, 16L)) {
            val heldDepth = FeelMaterial.MAX_FORCE_N / FeelMaterial.Gel.stiffness
            val h = FeelHaptics().apply { begin(FeelMaterial.Gel, Indentation(heldDepth)) }
            var state = Indentation(heldDepth)
            h.release(state, 0)
            val peaks = mutableListOf<Float>()
            val ticks = mutableListOf<FeelPulse>()
            for (now in stepMillis..3500L step stepMillis) {
                val old = state
                state = stepIndentation(state, FeelMaterial.Gel, 0f, stepMillis / 1000f)
                val isPeak = old.velocity < 0 && state.velocity >= 0 && minOf(old.depthMm, state.depthMm) < -.15f
                val effect = h.advance(state, 0f, 0f, 0f, stepMillis / 1000f, now)
                if (isPeak && peaks.size < 3) {
                    val peak = abs(minOf(old.depthMm, state.depthMm))
                    peaks += peak
                    assertNotNull("tick at velocity reversal at $now ms", effect)
                    ticks += effect!!.pulses.single()
                    assertEquals(.65f * peak / 6.15f, ticks.last().scale, .00001f)
                } else assertNull(effect)
            }
            assertEquals(3, ticks.size)
            for ((actual, expected) in peaks.zip(listOf(6.15f, 1.53f, .38f))) assertEquals(expected, actual, .08f)
            assertTrue(ticks.zipWithNext().all { (a, b) -> a.kind == FeelPrimitive.LowTick && b.scale < a.scale })
        }
    }

    private fun stream(kind: FeelMaterial, speed: Float, across: Boolean = false): List<Pair<Long, FeelPulse>> {
        val h = FeelHaptics().apply { begin(kind, Indentation()) }
        val events = mutableListOf<Pair<Long, FeelPulse>>()
        h.advance(Indentation(2f), .5f, 0f, 0f, .005f, 0)
        for (now in 5L..2000L step 5L) {
            val distance = speed * .005f
            h.advance(Indentation(2f), .5f, if (across) 0f else distance,
                if (across) distance else 0f, .005f, now)?.let { events += now to it.pulses.single() }
        }
        return events
    }

    @Test fun dragRatesFollowSpeedAreCappedAndLeatherIsSparserAndStronger() {
        for ((kind, cap) in listOf(FeelMaterial.Velvet to 40, FeelMaterial.Leather to 18, FeelMaterial.BrushedSteel to 35)) {
            val slow = stream(kind, 5f)
            val fast = stream(kind, 1000f)
            assertTrue("$kind increases with speed", fast.size > slow.size * 2)
            assertTrue("$kind capped at $cap Hz: ${fast.size}", fast.size <= cap * 2)
            assertTrue(fast.zipWithNext().all { (a, b) -> b.first - a.first >= 25L })
            assertTrue(stream(kind, 0f).isEmpty())
        }
        val velvet = stream(FeelMaterial.Velvet, 80f)
        val leather = stream(FeelMaterial.Leather, 80f)
        assertTrue(velvet.size > leather.size)
        assertTrue(velvet.first().second.scale < leather.first().second.scale)
    }

    @Test fun steelAlongBrushingIsFinerAndAcrossBrushingIsCoarser() {
        val along = stream(FeelMaterial.BrushedSteel, 35f)
        val across = stream(FeelMaterial.BrushedSteel, 35f, across = true)
        assertTrue(along.size > across.size)
        assertTrue(along.first().second.scale < across.first().second.scale)
    }

    @Test fun marbleIsSilentThroughoutHoldingDraggingAndRelease() {
        val h = FeelHaptics().apply { begin(FeelMaterial.Marble, Indentation()) }
        assertNotNull(h.advance(Indentation(), 1f, 0f, 0f, .01f, 0))
        for (now in 10L..3000L step 10) assertNull(h.advance(Indentation(), 1f, 10f, 10f, .01f, now))
        h.release(Indentation(), 3000)
        assertNull(h.advance(Indentation(), 0f, 0f, 0f, .01f, 4000))
    }

    @Test fun cancellationNewPressAndSwitchDropReleaseEffectsWithoutBypassingThrottle() {
        val h = FeelHaptics().apply { begin(FeelMaterial.MemoryFoam, Indentation()) }
        assertNotNull(h.advance(Indentation(10f, 5f), 1f, 0f, 0f, .01f, 0))
        h.release(Indentation(10f), 1)
        h.cancel()
        assertNull(h.advance(Indentation(-6f, 2f), 0f, 0f, 0f, .01f, 200))
        val emitted = mutableListOf<Long>()
        for (now in 205L..500L step 5) {
            h.begin(FeelMaterial.BrushedSteel, Indentation())
            h.advance(Indentation(), 1f, 0f, 0f, .005f, now)?.let { emitted += now }
        }
        assertTrue(emitted.size > 1)
        assertTrue(emitted.zipWithNext().all { (a, b) -> b - a >= 25L })
    }

    @Test fun stalledFramesDoNotQueueGrainOrLeaveAStationaryBuzz() {
        val h = FeelHaptics().apply { begin(FeelMaterial.Velvet, Indentation()) }
        assertNull(h.advance(Indentation(2f), 1f, 100f, 0f, .5f, 500))
        for (now in 510L..1000L step 10) assertNull(h.advance(Indentation(2f), 1f, 0f, 0f, .01f, now))
    }

    @Test fun waveformFallbackRemapsOnlyActiveSegmentsAndPreservesOrderingPausesAndDurations() {
        for (kind in FeelPrimitive.entries) {
            val low = FeelEffect(kind, .1f).waveform()
            val high = FeelEffect(kind, .7f).waveform()
            assertEquals(kind.durationMillis, low.timings.sum())
            assertTrue(low.amplitudes.max() < high.amplitudes.max())
            assertTrue(low.amplitudes.all { it == 0 || it in 64..204 })
            assertTrue(high.amplitudes.all { it == 0 || it in 64..204 })
            assertEquals(204, FeelEffect(kind, 1f).waveform().amplitudes.max())
        }
        val steel = contact(FeelMaterial.BrushedSteel)!!
        val wave = steel.waveform()
        assertEquals(steel.durationMillis, wave.timings.sum())
        assertEquals(2, wave.timings.indices.count { wave.timings[it] == 12L && wave.amplitudes[it] == 0 })
        assertTrue(wave.amplitudes.max() <= 204)
        assertTrue(FeelEffect(FeelPrimitive.LowTick, .035f).waveform().amplitudes.filter { it > 0 }.all { it >= 64 })
        assertTrue(contact(FeelMaterial.MemoryFoam)!!.waveform().amplitudes.max() <
            contact(FeelMaterial.Leather)!!.waveform().amplitudes.max())
        assertTrue(contact(FeelMaterial.Leather)!!.waveform().amplitudes.max() <
            contact(FeelMaterial.Marble)!!.waveform().amplitudes.max())
    }
}
