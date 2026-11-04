package io.github.sceneview.node

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rules [ReflectionProbeRegistry] applies to a scene's light, one test per defect of #4928
 * plus the cases they must not break. Pure JVM: the scene is a recording slot over fake lights.
 */
class ReflectionProbeRegistryTest {

    private class Light(val name: String) {
        var isAlive = true
        override fun toString() = name
    }

    /** A scene's light slot that records every write. */
    private class Slot(initial: Light? = null) : ReflectionProbeRegistry.Target<Light> {
        val writes = mutableListOf<Light?>()
        private var value: Light? = initial
        override var light: Light?
            get() = value
            set(new) {
                writes += new
                value = new
            }

        /** What the caller writes behind the registry's back. */
        fun writeDirectly(new: Light?) {
            value = new
        }

        override fun isAlive(light: Light) = light.isAlive
    }

    private val slot = Slot()
    private val registry = ReflectionProbeRegistry(slot)
    private val sky = Light("sky")
    private val office = Light("office")
    private val garage = Light("garage")

    private val origin = Position(0f, 0f, 0f)
    private val inOffice = Position(0f, 0f, 1f)
    private val inGarage = Position(10f, 0f, 1f)
    private val outside = Position(50f, 0f, 0f)

    private fun ReflectionProbeRegistry.Probe<Light>.show(
        light: Light?,
        position: Position = origin,
        radius: Float = 0f,
        priority: Int = 0,
        camera: Position = origin,
    ) = registry.update(this, light, position, radius, priority, camera)

    // ── #4928 (1): priority ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the highest priority wins whatever the composition order`() {
        registry.setEnvironmentLight(sky)
        val high = registry.newProbe()
        val low = registry.newProbe()

        high.show(office, priority = 2)
        low.show(garage, priority = 1)

        assertSame(office, slot.light)
    }

    @Test
    fun `a tie goes to the probe composed last, and an update keeps that order`() {
        registry.setEnvironmentLight(sky)
        val first = registry.newProbe()
        val second = registry.newProbe()
        second.show(garage)
        first.show(office)
        assertSame("composed last wins, not updated last", garage, slot.light)

        first.show(office, priority = 0, camera = inOffice)
        assertSame(garage, slot.light)
    }

    @Test
    fun `raising a probe's priority hands it the scene`() {
        registry.setEnvironmentLight(sky)
        val a = registry.newProbe()
        val b = registry.newProbe()
        a.show(office)
        b.show(garage)
        assertSame(garage, slot.light)

        a.show(office, priority = 5)
        assertSame(office, slot.light)
    }

    // ── #4928 (2): leaving the zone ──────────────────────────────────────────────────────────────

    @Test
    fun `leaving a local zone hands the scene its environment back`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()
        probe.show(office, position = origin, radius = 3f, camera = inOffice)
        assertSame(office, slot.light)

        probe.show(office, position = origin, radius = 3f, camera = outside)
        assertSame(sky, slot.light)

        probe.show(office, position = origin, radius = 3f, camera = inOffice)
        assertSame(office, slot.light)
    }

    @Test
    fun `walking from one zone into another follows the camera`() {
        registry.setEnvironmentLight(sky)
        val officeProbe = registry.newProbe()
        val garageProbe = registry.newProbe()
        fun walkTo(camera: Position) {
            officeProbe.show(office, position = origin, radius = 3f, camera = camera)
            garageProbe.show(garage, position = Position(10f, 0f, 0f), radius = 3f, camera = camera)
        }

        walkTo(inOffice)
        assertSame(office, slot.light)
        walkTo(inGarage)
        assertSame(garage, slot.light)
        walkTo(outside)
        assertSame(sky, slot.light)
    }

    @Test
    fun `a camera moving inside a zone writes nothing`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()
        probe.show(office, radius = 3f, camera = inOffice)
        val writes = slot.writes.size

        repeat(60) { frame -> probe.show(office, radius = 3f, camera = Position(0f, 0f, frame / 60f)) }

        assertEquals(writes, slot.writes.size)
    }

    // ── #4928 (3): a new scene environment ───────────────────────────────────────────────────────

    @Test
    fun `a new scene environment does not override an active probe`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()
        probe.show(office, radius = 3f, camera = inOffice)

        // `SceneView`'s environment effect on a dark/light toggle: release the old, declare the new.
        val night = Light("night")
        registry.releaseEnvironmentLight(sky)
        sky.isAlive = false
        registry.setEnvironmentLight(night)
        assertSame(office, slot.light)

        probe.show(office, radius = 3f, camera = outside)
        assertSame("the new environment, not the one the probe replaced", night, slot.light)
    }

    @Test
    fun `a new scene environment shows at once when no probe is active`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()
        probe.show(office, radius = 3f, camera = outside)

        val night = Light("night")
        registry.releaseEnvironmentLight(sky)
        registry.setEnvironmentLight(night)

        assertSame(night, slot.light)
    }

    // ── #4928 (4): chained probes ────────────────────────────────────────────────────────────────

    @Test
    fun `removing chained probes in any order ends on the scene's environment`() {
        registry.setEnvironmentLight(sky)
        val a = registry.newProbe()
        val b = registry.newProbe()
        a.show(office)
        b.show(garage)

        registry.remove(a)
        assertSame(garage, slot.light)
        registry.remove(b)
        assertSame("not A's light, which B used to remember", sky, slot.light)
    }

    @Test
    fun `removing the winner hands the scene to the next probe in line`() {
        registry.setEnvironmentLight(sky)
        val a = registry.newProbe()
        val b = registry.newProbe()
        a.show(office)
        b.show(garage)

        registry.remove(b)
        assertSame(office, slot.light)
        registry.remove(a)
        assertSame(sky, slot.light)
    }

    @Test
    fun `a destroyed light is never handed back`() {
        registry.setEnvironmentLight(sky)
        val a = registry.newProbe()
        val b = registry.newProbe()
        a.show(office)
        b.show(garage)

        // A's environment is destroyed while B wins: it must not come back when B goes.
        office.isAlive = false
        registry.remove(b)

        assertSame(sky, slot.light)
    }

    // ── Scenes no SceneView drives, and the caller's own writes ──────────────────────────────────

    @Test
    fun `without a declared environment the probe falls back to the caller's light`() {
        val caller = Light("caller")
        slot.writeDirectly(caller)
        val probe = registry.newProbe()

        probe.show(office, radius = 3f, camera = inOffice)
        assertSame(office, slot.light)
        probe.show(office, radius = 3f, camera = outside)
        assertSame(caller, slot.light)
    }

    @Test
    fun `without a declared environment a light the caller wrote since is the new fallback`() {
        slot.writeDirectly(Light("caller"))
        val probe = registry.newProbe()
        probe.show(office, radius = 3f, camera = outside)

        val later = Light("later")
        slot.writeDirectly(later)
        probe.show(office, radius = 3f, camera = inOffice)
        assertSame(office, slot.light)
        registry.remove(probe)

        assertSame(later, slot.light)
    }

    @Test
    fun `releasing the environment leaves a light the caller wrote over it`() {
        registry.setEnvironmentLight(sky)
        val custom = Light("custom")
        slot.writeDirectly(custom)

        registry.releaseEnvironmentLight(sky)

        assertSame(custom, slot.light)
    }

    @Test
    fun `releasing the environment with no probe empties the scene, like before`() {
        registry.setEnvironmentLight(sky)
        registry.releaseEnvironmentLight(sky)

        assertNull(slot.light)
    }

    @Test
    fun `releasing a light that is no longer the environment does nothing`() {
        val night = Light("night")
        registry.setEnvironmentLight(night)

        registry.releaseEnvironmentLight(sky)

        assertSame(night, slot.light)
    }

    @Test
    fun `a probe without a light empties the scene inside its zone`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()

        probe.show(null, radius = 3f, camera = inOffice)
        assertNull(slot.light)
        probe.show(null, radius = 3f, camera = outside)
        assertSame(sky, slot.light)
    }

    @Test
    fun `removing a probe twice is harmless`() {
        registry.setEnvironmentLight(sky)
        val probe = registry.newProbe()
        probe.show(office)
        registry.remove(probe)
        registry.remove(probe)

        assertEquals(0, registry.probeCount)
        assertSame(sky, slot.light)
    }
}
