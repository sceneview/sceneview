package io.github.sceneview.ar

import android.content.Context
import android.content.SharedPreferences
import io.github.sceneview.ar.node.CloudAnchorNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single Cloud Anchor entry tracked by a [CloudAnchorRegistry].
 *
 * Each entry pairs a user-meaningful [name] with the ARCore [cloudAnchorId] returned from
 * [CloudAnchorNode.host], plus the wall-clock host time and the requested [ttlDays] so callers
 * can detect when a hosted anchor has expired on Google's ARCore API and should no longer be
 * resolved.
 *
 * @param name           Caller-defined display name (e.g. "Living room marker"). Keys the entry.
 * @param cloudAnchorId  The ARCore-issued Cloud Anchor ID. Pass to
 *                       [CloudAnchorNode.Companion.resolve] to re-attach across launches.
 * @param hostedAtEpochMs Wall-clock time (`System.currentTimeMillis()`) when the anchor was hosted.
 * @param ttlDays        The ttl value passed to [CloudAnchorNode.host]; in `1..365`.
 */
data class CloudAnchorEntry(
    val name: String,
    val cloudAnchorId: String,
    val hostedAtEpochMs: Long,
    val ttlDays: Int
) {
    init {
        require(ttlDays in CloudAnchorNode.TTL_DAYS_RANGE) {
            "ttlDays must be in ${CloudAnchorNode.TTL_DAYS_RANGE}, was $ttlDays."
        }
    }

    /**
     * Returns `true` if the anchor's ttl window has elapsed relative to [now] (defaults to the
     * current wall-clock time). Expired entries are no longer resolvable on Google's ARCore API
     * and should typically be removed from the registry.
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        now >= hostedAtEpochMs + ttlDays * MILLIS_PER_DAY

    /**
     * The wall-clock epoch ms at which this anchor's ttl window ends.
     * `resolve()` on an ID past this point will fail with `CloudAnchorState.ERROR_RESOLVING_*`.
     */
    val expiresAtEpochMs: Long
        get() = hostedAtEpochMs + ttlDays * MILLIS_PER_DAY

    internal fun toJson(): JSONObject = JSONObject()
        .put(KEY_NAME, name)
        .put(KEY_CLOUD_ANCHOR_ID, cloudAnchorId)
        .put(KEY_HOSTED_AT, hostedAtEpochMs)
        .put(KEY_TTL_DAYS, ttlDays)

    companion object {
        internal const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
        private const val KEY_NAME = "name"
        private const val KEY_CLOUD_ANCHOR_ID = "cloudAnchorId"
        private const val KEY_HOSTED_AT = "hostedAtEpochMs"
        private const val KEY_TTL_DAYS = "ttlDays"

        internal fun fromJson(o: JSONObject): CloudAnchorEntry = CloudAnchorEntry(
            name = o.getString(KEY_NAME),
            cloudAnchorId = o.getString(KEY_CLOUD_ANCHOR_ID),
            hostedAtEpochMs = o.getLong(KEY_HOSTED_AT),
            ttlDays = o.getInt(KEY_TTL_DAYS)
        )
    }
}

/**
 * Lightweight local store for ARCore Cloud Anchor IDs that an app has hosted (or wants to
 * remember after resolving). The library does not persist anchors itself — Cloud Anchors are
 * server-side state managed by Google's ARCore API — but apps that host long-ttl anchors
 * typically need a small client-side index keyed by a user-meaningful name so the IDs can be
 * passed back to [CloudAnchorNode.Companion.resolve] on the next launch.
 *
 * Implementations must be safe to call from the main thread (no blocking I/O on hot paths).
 * The default [SharedPreferencesCloudAnchorRegistry] is backed by an apply-based
 * [SharedPreferences] write, which is non-blocking.
 *
 * ### Privacy
 *
 * The IDs stored here are opaque tokens, not personal data, but the anchors they reference were
 * hosted by uploading visual features from the user's surroundings to Google. Apps must surface
 * a clear in-app disclosure (see [CloudAnchorNode.host]'s KDoc).
 *
 * ### Typical usage
 *
 * ```kotlin
 * val registry = SharedPreferencesCloudAnchorRegistry(context)
 *
 * // On host success:
 * cloudAnchorNode.host(session, ttlDays = 7) { id, state ->
 *     if (id != null && !state.isError) {
 *         registry.add(CloudAnchorEntry(
 *             name = "Living room marker",
 *             cloudAnchorId = id,
 *             hostedAtEpochMs = System.currentTimeMillis(),
 *             ttlDays = 7
 *         ))
 *     }
 * }
 *
 * // On next launch:
 * registry.list().filterNot { it.isExpired() }.forEach { entry ->
 *     CloudAnchorNode.resolve(engine, session, entry.cloudAnchorId) { state, node -> /* ... */ }
 * }
 * ```
 */
interface CloudAnchorRegistry {

    /**
     * Inserts or replaces the entry keyed by [CloudAnchorEntry.name].
     */
    fun add(entry: CloudAnchorEntry)

    /**
     * Removes the entry with the given [name], if any. Returns `true` if an entry was removed.
     */
    fun remove(name: String): Boolean

    /**
     * Returns the entry with the given [name], or `null` if no such entry is stored.
     */
    fun get(name: String): CloudAnchorEntry?

    /**
     * Returns a snapshot list of all stored entries (insertion order not guaranteed).
     */
    fun list(): List<CloudAnchorEntry>

    /**
     * Removes every entry from the registry.
     */
    fun clear()

    /**
     * Removes every entry whose [CloudAnchorEntry.isExpired] returns `true` at [now].
     * Returns the number of entries removed.
     */
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        list().forEach { entry ->
            if (entry.isExpired(now) && remove(entry.name)) removed++
        }
        return removed
    }
}

/**
 * Default [CloudAnchorRegistry] implementation backed by a per-app [SharedPreferences] file.
 * Entries are serialised as a single JSON array under the [PREFS_KEY] key. All writes use
 * `apply()` so callers can invoke `add`/`remove`/`clear` from the main thread without blocking.
 *
 * @param prefs The [SharedPreferences] file to read/write. Defaults to a dedicated
 *              `"sceneview_cloud_anchors"` preferences file in `MODE_PRIVATE`.
 */
class SharedPreferencesCloudAnchorRegistry(
    private val prefs: SharedPreferences
) : CloudAnchorRegistry {

    /**
     * Convenience constructor that opens (or creates) the default
     * `"sceneview_cloud_anchors"` [SharedPreferences] file in `MODE_PRIVATE`.
     */
    constructor(context: Context, prefsName: String = DEFAULT_PREFS_NAME) : this(
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    )

    override fun add(entry: CloudAnchorEntry) {
        val updated = readAll().toMutableMap()
        updated[entry.name] = entry
        writeAll(updated.values)
    }

    override fun remove(name: String): Boolean {
        val current = readAll()
        if (name !in current) return false
        val updated = current.toMutableMap().apply { remove(name) }
        writeAll(updated.values)
        return true
    }

    override fun get(name: String): CloudAnchorEntry? = readAll()[name]

    override fun list(): List<CloudAnchorEntry> = readAll().values.toList()

    override fun clear() {
        prefs.edit().remove(PREFS_KEY).apply()
    }

    private fun readAll(): Map<String, CloudAnchorEntry> {
        val raw = prefs.getString(PREFS_KEY, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<String, CloudAnchorEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val entry = CloudAnchorEntry.fromJson(arr.getJSONObject(i))
                out[entry.name] = entry
            }
            out
        } catch (_: Exception) {
            // Corrupt payload — surface nothing rather than crashing the app.
            emptyMap()
        }
    }

    private fun writeAll(entries: Collection<CloudAnchorEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    companion object {
        /** Default [SharedPreferences] file name used by the no-`prefs` constructor. */
        const val DEFAULT_PREFS_NAME: String = "sceneview_cloud_anchors"

        /** SharedPreferences key under which the JSON-encoded entry array is stored. */
        const val PREFS_KEY: String = "entries"
    }
}
