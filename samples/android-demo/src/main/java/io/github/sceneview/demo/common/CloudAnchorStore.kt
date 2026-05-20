package io.github.sceneview.demo.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed registry of hosted ARCore Cloud Anchors.
 *
 * Models the persistent-cloud-anchor pattern from Google's
 * [`persistent_cloud_anchor_java`](https://github.com/google-ar/arcore-android-sdk/tree/main/samples/persistent_cloud_anchor_java)
 * sample: each entry maps a user-chosen `name` to its `cloudAnchorId` plus the
 * `expiryEpochMillis` at which the Cloud Anchor backend will stop resolving it
 * (`System.currentTimeMillis() + ttlDays * 86_400_000L`).
 *
 * ### TTL contract (ARCore 1.33+)
 * `Session.hostCloudAnchorAsync(anchor, ttlDays)` accepts `ttlDays` between
 * `1` and `365`. Anchors hosted with `ttlDays > 1` are billed as
 * "persistent" cloud anchors in the Google Cloud Console and will keep
 * resolving for the requested window before the backend GCs them. After
 * expiry, `Session.resolveCloudAnchorAsync` returns
 * `Anchor.CloudAnchorState.ERROR_RESOLVING_CLOUD_ANCHOR_ID_NOT_FOUND`.
 *
 * ### Privacy disclosure
 * Hosting a Cloud Anchor uploads visual feature points around the user to
 * Google's ARCore Cloud Anchor service. Per ARCore's terms, apps using this
 * API MUST surface a privacy notice to the user before the first host call.
 * This sample's [io.github.sceneview.demo.demos.ARCloudAnchorDemo] shows a
 * one-time disclosure dialog backed by [hasAcceptedPrivacy] /
 * [setAcceptedPrivacy] below.
 *
 * ### Implementation
 * We persist a single JSON-encoded array under one SharedPreferences key.
 * Json keeps the schema explicit (name / id / expiry) and is trivially
 * forward-compatible if we want to add `hostedAt` or `lastResolvedAt` later.
 * This stays consistent with [io.github.sceneview.demo.ui.explore.RecentSearchesStore]
 * which also chose SharedPreferences over DataStore for the demo app to
 * keep the APK lean.
 *
 * Storage is keyed per-app on the device only — anchor IDs themselves are
 * scoped to the Cloud project that hosted them, so the registry is a pure
 * convenience bookmark, not a security boundary.
 */
data class CloudAnchorEntry(
    val name: String,
    val cloudAnchorId: String,
    val expiryEpochMillis: Long,
) {
    /** `true` once the Cloud Anchor backend's TTL has elapsed for this entry. */
    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        nowEpochMillis >= expiryEpochMillis
}

private const val PREFS_NAME = "io.github.sceneview.demo.cloudanchor"
private const val KEY_ENTRIES = "entries"
private const val KEY_PRIVACY_ACCEPTED = "privacyAccepted"

/** Maximum TTL (in days) accepted by `Session.hostCloudAnchorAsync` since ARCore 1.33. */
const val CLOUD_ANCHOR_MAX_TTL_DAYS: Int = 365

/** Default TTL the demo uses when the user picks "Host & save". */
const val CLOUD_ANCHOR_DEFAULT_TTL_DAYS: Int = 365

class CloudAnchorStore internal constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<CloudAnchorEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = o.optString("cloudAnchorId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val expiry = o.optLong("expiryEpochMillis", 0L)
                CloudAnchorEntry(name, id, expiry)
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<CloudAnchorEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("cloudAnchorId", e.cloudAnchorId)
                    .put("expiryEpochMillis", e.expiryEpochMillis)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /** Whether the user has acknowledged the Cloud Anchor privacy disclosure. */
    fun hasAcceptedPrivacy(): Boolean = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun setAcceptedPrivacy(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, accepted).apply()
    }
}

/** Compose-friendly handle around [CloudAnchorStore]. */
class CloudAnchorRegistry internal constructor(
    initial: List<CloudAnchorEntry>,
    private val store: CloudAnchorStore,
) {
    private val _entries = mutableStateListOf<CloudAnchorEntry>().also { it.addAll(initial) }
    val entries: List<CloudAnchorEntry> get() = _entries

    /**
     * Persist [name] → [cloudAnchorId] with an `expiryEpochMillis` of `now + ttlDays`.
     * If [name] already exists, the entry is replaced (latest wins).
     */
    fun save(
        name: String,
        cloudAnchorId: String,
        ttlDays: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val trimmedName = name.trim().ifEmpty { cloudAnchorId.take(8) }
        val clampedTtl = ttlDays.coerceIn(1, CLOUD_ANCHOR_MAX_TTL_DAYS)
        val expiry = nowEpochMillis + clampedTtl * 86_400_000L
        _entries.removeAll { it.name.equals(trimmedName, ignoreCase = true) }
        _entries.add(0, CloudAnchorEntry(trimmedName, cloudAnchorId, expiry))
        store.save(_entries.toList())
    }

    fun remove(name: String) {
        _entries.removeAll { it.name == name }
        store.save(_entries.toList())
    }

    /** Drop any entries whose TTL has elapsed — they will not resolve any more. */
    fun pruneExpired(nowEpochMillis: Long = System.currentTimeMillis()) {
        val before = _entries.size
        _entries.removeAll { it.isExpired(nowEpochMillis) }
        if (_entries.size != before) store.save(_entries.toList())
    }
}

@Composable
fun rememberCloudAnchorRegistry(): CloudAnchorRegistry {
    val context = LocalContext.current
    val store = remember(context) { CloudAnchorStore(context) }
    return remember(store) { CloudAnchorRegistry(store.load(), store) }
}

/** Compose-friendly read of the one-time privacy-acceptance flag. */
@Composable
fun rememberCloudAnchorPrivacyAccepted(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val store = remember(context) { CloudAnchorStore(context) }
    val initial = remember(store) { store.hasAcceptedPrivacy() }
    val state = remember { mutableStateOf(initial) }
    val setter: (Boolean) -> Unit = { value ->
        state.value = value
        store.setAcceptedPrivacy(value)
    }
    return state.value to setter
}
