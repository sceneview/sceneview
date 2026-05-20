package io.github.sceneview.demo.common

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the persistent Cloud Anchor registry — issue #1734.
 *
 * Robolectric provides a real `SharedPreferences` so the JSON round-trip,
 * upsert-by-name, and TTL clamp can be exercised on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class CloudAnchorStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: CloudAnchorStore

    @Before
    fun setUp() {
        // Wipe between tests so each one starts clean.
        context.getSharedPreferences("io.github.sceneview.demo.cloudanchor", 0)
            .edit().clear().commit()
        store = CloudAnchorStore(context)
    }

    @Test
    fun `empty store loads as empty list`() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `round-trips entries through SharedPreferences`() {
        val now = 1_000_000L
        val entry = CloudAnchorEntry(
            name = "Kitchen wall",
            cloudAnchorId = "ua-xxx-yyy",
            expiryEpochMillis = now + 365L * 86_400_000L,
        )
        store.save(listOf(entry))

        val reloaded = CloudAnchorStore(context).load()
        assertEquals(1, reloaded.size)
        assertEquals(entry, reloaded.first())
    }

    @Test
    fun `registry replaces entries with same name (latest wins)`() {
        val registry = CloudAnchorRegistry(initial = emptyList(), store = store)
        registry.save(name = "spot", cloudAnchorId = "ua-1", ttlDays = 1, nowEpochMillis = 0L)
        registry.save(name = "spot", cloudAnchorId = "ua-2", ttlDays = 1, nowEpochMillis = 0L)

        assertEquals(1, registry.entries.size)
        assertEquals("ua-2", registry.entries.first().cloudAnchorId)
    }

    @Test
    fun `registry clamps ttl to max 365 days`() {
        val registry = CloudAnchorRegistry(initial = emptyList(), store = store)
        registry.save(
            name = "huge",
            cloudAnchorId = "ua-big",
            ttlDays = 9_999,
            nowEpochMillis = 0L,
        )
        val expected = 365L * 86_400_000L
        assertEquals(expected, registry.entries.first().expiryEpochMillis)
    }

    @Test
    fun `registry clamps ttl to min 1 day`() {
        val registry = CloudAnchorRegistry(initial = emptyList(), store = store)
        registry.save(
            name = "zero",
            cloudAnchorId = "ua-zero",
            ttlDays = 0,
            nowEpochMillis = 0L,
        )
        // Clamped to 1 day, not 0.
        val expected = 86_400_000L
        assertEquals(expected, registry.entries.first().expiryEpochMillis)
    }

    @Test
    fun `pruneExpired drops entries whose TTL elapsed`() {
        val registry = CloudAnchorRegistry(initial = emptyList(), store = store)
        registry.save("fresh", "ua-fresh", ttlDays = 30, nowEpochMillis = 0L)
        registry.save("stale", "ua-stale", ttlDays = 1, nowEpochMillis = 0L)

        // Simulate "now = 2 days later"
        registry.pruneExpired(nowEpochMillis = 2L * 86_400_000L)

        assertEquals(1, registry.entries.size)
        assertEquals("fresh", registry.entries.first().name)
    }

    @Test
    fun `pruneExpired persists the trimmed list`() {
        val registry = CloudAnchorRegistry(initial = emptyList(), store = store)
        registry.save("stale", "ua-stale", ttlDays = 1, nowEpochMillis = 0L)
        registry.pruneExpired(nowEpochMillis = 365L * 86_400_000L)

        val reloaded = CloudAnchorStore(context).load()
        assertTrue(reloaded.isEmpty())
    }

    @Test
    fun `privacy acceptance round-trips`() {
        assertFalse(store.hasAcceptedPrivacy())
        store.setAcceptedPrivacy(true)
        assertTrue(CloudAnchorStore(context).hasAcceptedPrivacy())
    }

    @Test
    fun `isExpired returns true at or after expiry`() {
        val entry = CloudAnchorEntry("x", "ua", expiryEpochMillis = 1_000L)
        assertFalse(entry.isExpired(nowEpochMillis = 0L))
        assertTrue(entry.isExpired(nowEpochMillis = 1_000L))
        assertTrue(entry.isExpired(nowEpochMillis = 9_999L))
    }

    @Test
    fun `corrupt JSON loads as empty without throwing`() {
        context.getSharedPreferences("io.github.sceneview.demo.cloudanchor", 0)
            .edit().putString("entries", "{not valid json").commit()
        assertNotNull(store.load())
        assertTrue(store.load().isEmpty())
    }
}
