package io.github.sceneview.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelSnapshotOptionsTest {

    @Test
    fun theDefaultAngleIsAThreeQuarterViewFromSlightlyAbove() {
        val options = ModelSnapshotOptions()
        assertEquals(30f, options.yawDegrees)
        assertEquals(20f, options.pitchDegrees)
    }

    @Test
    fun outOfRangeValuesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ModelSnapshotOptions(padding = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { ModelSnapshotOptions(padding = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { ModelSnapshotOptions(pitchDegrees = 90f) }
        assertThrows(IllegalArgumentException::class.java) { ModelSnapshotOptions(focalLength = 0f) }
    }

    @Test
    fun theCacheKeyTellsSizesAndAnglesApart() {
        val key = SnapshotKey("models/a.glb", 128, 128, ModelSnapshotOptions())
        assertEquals(key, SnapshotKey("models/a.glb", 128, 128, ModelSnapshotOptions()))
        assertNotEquals(key, SnapshotKey("models/a.glb", 256, 256, ModelSnapshotOptions()))
        assertNotEquals(key, SnapshotKey("models/a.glb", 128, 128, ModelSnapshotOptions(yawDegrees = -30f)))
        assertNotEquals(key, SnapshotKey("models/b.glb", 128, 128, ModelSnapshotOptions()))
    }
}
