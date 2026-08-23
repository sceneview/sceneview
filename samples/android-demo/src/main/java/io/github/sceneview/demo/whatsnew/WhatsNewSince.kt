package io.github.sceneview.demo.whatsnew

import android.content.Context

/**
 * "What's new **since you last tested**" — the marker, and the rule that turns
 * it into a list of sections.
 *
 * **The problem this solves.** The demo app is tested by hand, irregularly, and
 * several releases can pass between two sessions. Opening the app told you
 * nothing about what had moved, so every session started by re-testing
 * everything blind. The card added in #3232 shows the last three *releases*,
 * which is the wrong axis: it is the same for everybody and it says nothing
 * about what **this** install has already been through.
 *
 * **Why the marker is explicit.** It is set by a button, never by looking. A
 * sheet that marks itself read on dismissal loses the list the first time it is
 * swiped away by accident — and this list is not decorative, it is the test
 * plan. Dismissing the sheet leaves the marker untouched and the content comes
 * back behind a badge in the top bar, quietly, instead of re-opening a modal on
 * every resume.
 *
 * **Why the marker is a build, not a timestamp.** The app versions as
 * `4.31.0-main.<commit>`, so `versionName` changes on every build that ships,
 * while `versionCode` orders them. Storing both means "the exact build I last
 * signed off", which is the only thing that can be compared against the
 * changelog embedded in the build being run now.
 *
 * **Why entry ids are stored alongside.** Releases are rare; merges are not.
 * Between two releases every new entry lands in the synthesised `## Unreleased`
 * section, which means a version comparison alone would re-present the entire
 * pending backlog on every single build. The acknowledged entry ids
 * ([WhatsNewEntry.id], a hash of the entry's text) are what make the list
 * *incremental* — and because the id follows an entry from fragment to released
 * section, acknowledging something while it is unreleased also silences it once
 * it ships.
 *
 * The set stays bounded on its own: once the marker advances to base version
 * `V`, every released section at or below `V` is permanently out of scope, so
 * the only ids still worth remembering are the still-unreleased ones — a few
 * dozen at most.
 */
data class WhatsNewSeen(
    val versionCode: Int,
    val versionName: String,
    /** Ids of the entries acknowledged at the moment the marker was written. */
    val entryIds: Set<String>,
) {
    /** `4.31.0-main.abc1234` → `4.31.0`. */
    val baseVersion: String get() = baseVersionOf(versionName)
}

/**
 * Strips the build suffix from a `versionName`: `4.31.0-main.abc1234` and
 * `4.31.0+ci.7` both reduce to `4.31.0`, so two builds of the same release
 * compare equal and only a genuine release moves the version axis.
 */
fun baseVersionOf(versionName: String): String =
    versionName.takeWhile { it != '-' && it != '+' }.trim()

/**
 * Compares two dotted numeric versions. Missing components read as zero, so
 * `4.31` and `4.31.0` are equal. Non-numeric components compare as zero rather
 * than throwing — an unparseable version must never crash the launch path.
 */
fun compareVersions(a: String, b: String): Int {
    val left = a.split('.')
    val right = b.split('.')
    for (i in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val r = right.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (l != r) return l.compareTo(r)
    }
    return 0
}

/**
 * The cumulative list to show: every section strictly newer than [seen]'s base
 * version, plus the unreleased block, minus every entry already acknowledged.
 * Sections left with no fresh entries drop out entirely.
 *
 * Skipping three releases therefore yields all three, newest first — not just
 * the latest one.
 */
fun unseenSections(
    sections: List<WhatsNewSection>,
    seen: WhatsNewSeen?,
): List<WhatsNewSection> {
    // No marker at all means a fresh install. Callers are expected to have
    // initialised it (see WhatsNewSeenStore.initialise), so this is defensive:
    // showing nothing is the safe answer, never the whole history.
    if (seen == null) return emptyList()
    return sections.mapNotNull { section ->
        val inScope = section.isUnreleased ||
            compareVersions(section.version.orEmpty(), seen.baseVersion) > 0
        if (!inScope) return@mapNotNull null
        val fresh = section.entries.filterNot { it.id in seen.entryIds }
        if (fresh.isEmpty()) null else section.copy(entries = fresh)
    }
}

/** Ids of every entry still awaiting a release in [sections]. */
fun unreleasedEntryIds(sections: List<WhatsNewSection>): Set<String> =
    sections.filter { it.isUnreleased }.flatMap { section -> section.entries.map { it.id } }.toSet()

/**
 * Persistence for the "seen" marker.
 *
 * `SharedPreferences`, not DataStore — the same call as [io.github.sceneview.demo.DemoSheetDetentStore]
 * and `RecentSearchesStore`: the payload is two scalars and a small string set,
 * and DataStore would add ~1.5 MB to the APK for no gain. What matters for the
 * requirement is that the file lives in the app's private storage and survives
 * an app *update*, which it does — it is only cleared by an uninstall or an
 * explicit "clear data".
 */
object WhatsNewSeenStore {
    private const val PREFS = "sceneview_whats_new_seen"
    private const val KEY_VERSION_CODE = "seen_version_code"
    private const val KEY_VERSION_NAME = "seen_version_name"
    private const val KEY_ENTRY_IDS = "seen_entry_ids"

    /** The last explicitly acknowledged build, or `null` on a fresh install. */
    fun seen(context: Context): WhatsNewSeen? {
        val prefs = prefs(context)
        val versionName = prefs.getString(KEY_VERSION_NAME, null) ?: return null
        return WhatsNewSeen(
            versionCode = prefs.getInt(KEY_VERSION_CODE, 0),
            versionName = versionName,
            // The set returned by getStringSet must not be mutated or retained —
            // copy it.
            entryIds = prefs.getStringSet(KEY_ENTRY_IDS, emptySet())?.toSet().orEmpty(),
        )
    }

    /**
     * Records [versionCode]/[versionName] as acknowledged, together with
     * [entryIds] — which callers pass as *every* unreleased id in the running
     * build, not merely the ones on screen: acknowledging means "I have seen
     * this build", and anything still pending in it is part of that.
     */
    fun markSeen(
        context: Context,
        versionCode: Int,
        versionName: String,
        entryIds: Set<String>,
    ) {
        prefs(context).edit()
            .putInt(KEY_VERSION_CODE, versionCode)
            .putString(KEY_VERSION_NAME, versionName)
            .putStringSet(KEY_ENTRY_IDS, entryIds)
            .apply()
    }

    /**
     * Fresh-install baseline. Writes the marker only when none exists, so the
     * very first launch after an install shows nothing at all and the surface
     * arms itself from the *next* build onwards. Dumping the entire history at
     * someone who has just installed the app would be noise, not a test plan.
     *
     * Returns the marker in force afterwards.
     */
    fun initialise(
        context: Context,
        versionCode: Int,
        versionName: String,
        entryIds: Set<String>,
    ): WhatsNewSeen =
        seen(context) ?: WhatsNewSeen(versionCode, versionName, entryIds)
            .also { markSeen(context, it.versionCode, it.versionName, it.entryIds) }

    // applicationContext — prefs access must never pin an Activity context.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
