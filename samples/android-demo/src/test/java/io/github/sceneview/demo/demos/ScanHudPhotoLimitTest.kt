package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ScanCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The photo limit changes nothing of a scan's geometry: once the coaching line has said so
 * ("still scanning"), the HUD keeps no lasting reminder of it that reads as a
 * fault for the rest of the scan. Every word the capture screen shows comes from [ScanCopy], so
 * the one notice is the only copy that names it, and it never says "limit".
 */
class ScanHudPhotoLimitTest {

    @Test
    fun `the one-time notice is the only copy that names the photos running out`() {
        val naming = ScanCopy::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .filter { it.contains(LIMIT_WORDS, ignoreCase = true) }
        assertEquals(listOf(ScanCopy.FULL), naming)
        assertFalse(ScanCopy.FULL.contains("limit", ignoreCase = true))
    }

    private companion object {
        const val LIMIT_WORDS = "photos taken"
    }
}
