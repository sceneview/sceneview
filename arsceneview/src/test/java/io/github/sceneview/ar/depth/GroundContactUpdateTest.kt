package io.github.sceneview.ar.depth

import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundContactUpdateTest {

    @Test
    fun `throwing provider is reported once across frames and can recover`() {
        val failures = mutableListOf<Exception>()
        val update = GroundContactUpdate { failures += it }
        val contacts = GroundContacts()
        val failure = IllegalStateException("Contact unavailable")
        var throwing = true
        var reads = 0
        contacts.register {
            reads++
            if (throwing) throw failure
            GroundContact(Float3(0f, 0f, -2f), Float3(0f, 1f, 0f), 0.3f)
        }
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(8)

        repeat(120) { update.update { contacts.viewParameters(identity, out) } }
        assertEquals(120, reads)
        assertEquals(1, failures.size)
        assertSame(failure, failures.single())

        throwing = false
        update.update { assertTrue(contacts.viewParameters(identity, out)) }
        assertArrayEquals(floatArrayOf(0f, 0f, -2f, 0.3f, 0f, 1f, 0f, 1f), out, 1e-6f)

        throwing = true
        repeat(120) { update.update { contacts.viewParameters(identity, out) } }
        assertEquals(241, reads)
        assertEquals(1, failures.size)
    }

    @Test
    fun `each scene reports its first failure`() {
        val failures = mutableListOf<Exception>()
        val first = GroundContactUpdate { failures += it }
        val second = GroundContactUpdate { failures += it }
        val firstFailure = IllegalStateException("First scene")
        val secondFailure = IllegalStateException("Second scene")

        repeat(2) {
            first.update { throw firstFailure }
            second.update { throw secondFailure }
        }

        assertEquals(listOf(firstFailure, secondFailure), failures)
    }
}
