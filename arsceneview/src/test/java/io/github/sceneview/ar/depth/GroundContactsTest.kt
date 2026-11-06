package io.github.sceneview.ar.depth

import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundContactsTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Column-major view matrix of a camera standing at (0, [height], 0), looking down -Z. */
    private fun cameraAt(height: Float) = identity.copyOf().also { it[13] = -height }

    private val out = FloatArray(8)

    @Test
    fun `no contact turns the rule off`() {
        val contacts = GroundContacts()
        out.fill(7f)
        assertFalse(contacts.viewParameters(identity, out))
        assertArrayEquals(FloatArray(8), out, 0f)

        contacts.register { null }
        out.fill(7f)
        assertFalse(contacts.viewParameters(identity, out))
        assertArrayEquals(FloatArray(8), out, 0f)
    }

    @Test
    fun `the contact is written in view space with a unit normal`() {
        val contacts = GroundContacts()
        contacts.register { GroundContact(Float3(0f, 0f, -2f), Float3(0f, 3f, 0f), 0.3f) }
        assertTrue(contacts.viewParameters(cameraAt(1.2f), out))
        assertArrayEquals(floatArrayOf(0f, -1.2f, -2f, 0.3f, 0f, 1f, 0f, 1f), out, 1e-6f)
    }

    @Test
    fun `the contact nearest the camera wins`() {
        val contacts = GroundContacts()
        contacts.register { GroundContact(Float3(0f, 0f, -4f), Float3(0f, 1f, 0f), 0.5f) }
        contacts.register { GroundContact(Float3(0f, 0f, -1.5f), Float3(0f, 1f, 0f), 0.2f) }
        contacts.register { GroundContact(Float3(0f, 0f, -3f), Float3(0f, 1f, 0f), 0.4f) }
        assertTrue(contacts.viewParameters(identity, out))
        assertEquals(-1.5f, out[2], 0f)
        assertEquals(0.2f, out[3], 0f)
    }

    @Test
    fun `degenerate contacts are skipped`() {
        val contacts = GroundContacts()
        contacts.register { GroundContact(Float3(0f, 0f, -1f), Float3(0f, 0f, 0f), 0.2f) }
        contacts.register { GroundContact(Float3(0f, 0f, -1f), Float3(0f, 1f, 0f), 0f) }
        contacts.register { GroundContact(Float3(Float.NaN, 0f, -1f), Float3(0f, 1f, 0f), 0.2f) }
        assertFalse(contacts.viewParameters(identity, out))
        assertArrayEquals(FloatArray(8), out, 0f)
    }

    @Test
    fun `unregistering removes the contact`() {
        val contacts = GroundContacts()
        val unregister = contacts.register { GroundContact(Float3(0f, 0f, -2f), Float3(0f, 1f, 0f), 0.3f) }
        assertTrue(contacts.viewParameters(identity, out))
        unregister()
        assertFalse(contacts.viewParameters(identity, out))
        assertArrayEquals(FloatArray(8), out, 0f)
    }
}
