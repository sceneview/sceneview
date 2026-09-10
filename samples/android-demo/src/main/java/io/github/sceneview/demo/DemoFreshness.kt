package io.github.sceneview.demo

/**
 * Per-sample "New" / "Updated" marker — what the Showcase says about a demo the
 * user has not seen yet.
 *
 * **The problem.** The Showcase draws 51 cards that all look equally old. After
 * a release there is no way to tell which one is worth opening, so the answer to
 * "which feature can I test?" lived only in the changelog. The maintainer asked
 * for it on the cards themselves (#3566).
 *
 * **Why this is not [DemoStatus].** [DemoStatus.InReview] already exists and
 * looks like it should do this job. It does not, for two reasons. It is a
 * *process* state addressed to whoever runs the sign-off pass, which is why
 * [IN_REVIEW_BADGE_VISIBLE] hides it in release builds — so on the Play Store
 * build under QA no card carried any chip at all. And it is flipped by hand with
 * nothing to flip it back, so it never expires. Freshness is the opposite on
 * both counts: it is addressed to users, it ships in release builds, and it
 * expires on its own as the version moves.
 *
 * **Where the data lives.** Two optional fields on [DemoEntry] —
 * [DemoEntry.sinceVersion] and [DemoEntry.updatedIn] — declared in the demo's
 * own append-only `fragments/<Id>Fragment.kt`. That is the file a PR touching a
 * demo already edits (#1797), so maintaining the marker costs one line in a file
 * that is already open, and two PRs can never conflict over a shared list.
 *
 * **How it expires.** The declared version is compared against the running
 * build's `versionName`, not against a date and not against "the last N demos
 * edited". A marker is drawn while the release that carries it is still within
 * [FRESHNESS_WINDOW_MINORS] minor releases of the build; after that it goes
 * quiet with no edit and no cleanup PR. Leaving a stale `updatedIn` in a
 * fragment is therefore harmless, which is the property that makes a
 * hand-maintained field survivable.
 *
 * **Why a version declared *ahead* of the build still counts as fresh.**
 * `VERSION_NAME` is bumped at release and then stays put until the next one, so
 * every `main` build between two releases reports the version that already
 * shipped. Work merged today lands in the *next* release and is therefore
 * declared with a version strictly greater than the build reports it. That work
 * is the newest thing in the app — precisely what the marker exists to point at
 * — so "newer than the build" is the freshest case, not an error.
 */
enum class DemoFreshness {
    /** First shipped within the freshness window — drawn as "New". */
    New,

    /** User-visible behaviour changed within the window — drawn as "Updated". */
    Updated,

    /** Nothing to say. No chip. */
    None,
}

/**
 * How many minor releases back a marker keeps being drawn.
 *
 * `1` means the current release and the one before it. The window is small on
 * purpose: a marker that is on a third of the grid is decoration, and the QA
 * pass it serves happens per release, not per quarter.
 */
const val FRESHNESS_WINDOW_MINORS: Int = 1

/**
 * A parsed `major.minor.patch`. Only [major] and [minor] take part in the
 * comparison — a patch release does not re-open the window on everything the
 * minor before it shipped, and nothing declares a patch-level marker.
 */
private data class SemVer(val major: Int, val minor: Int)

/**
 * `"4.35.0"` → `SemVer(4, 35)`; `"4.35.0-main.abc1234"` → the same.
 * Anything that is not at least `major.minor` of digits returns `null`, and a
 * `null` never renders a chip — a typo in a fragment must lose the badge, never
 * crash the Showcase.
 */
private fun parseSemVer(version: String?): SemVer? {
    if (version.isNullOrBlank()) return null
    val base = version.takeWhile { it != '-' && it != '+' }.trim()
    val parts = base.split('.')
    if (parts.size < 2) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    return SemVer(major, minor)
}

/**
 * Whether [version] is recent enough, relative to the running [buildVersion], to
 * earn a chip.
 *
 * A different major is decided by the major alone: a demo declared for the next
 * major is the newest thing there is, and one from the previous major is
 * permanently out of the window however high its minor was.
 */
internal fun isRecentVersion(
    version: String?,
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): Boolean {
    val declared = parseSemVer(version) ?: return false
    val build = parseSemVer(buildVersion) ?: return false
    if (declared.major != build.major) return declared.major > build.major
    return declared.minor >= build.minor - window
}

/**
 * The marker for one demo. [DemoFreshness.New] wins over
 * [DemoFreshness.Updated]: a demo that both arrived and was then touched inside
 * the window is still, to a user, new.
 */
fun DemoEntry.freshness(
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): DemoFreshness = when {
    isRecentVersion(sinceVersion, buildVersion, window) -> DemoFreshness.New
    isRecentVersion(updatedIn, buildVersion, window) -> DemoFreshness.Updated
    else -> DemoFreshness.None
}

/**
 * The demos the "What's new in 4.x" entry lists, in Showcase order — every demo
 * carrying a marker. Empty between releases in which no demo moved, and the
 * entry is then not drawn at all rather than drawn empty.
 */
fun freshDemos(
    demos: List<DemoEntry>,
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): List<DemoEntry> = demos.filter { it.freshness(buildVersion, window) != DemoFreshness.None }

/**
 * The `major.minor` the "What's new" entry names.
 *
 * It is the highest version *declared by a demo in the window*, not the build's
 * own `versionName`, because between two releases those differ and the honest
 * headline is the one that matches what the cards below it are badged with. With
 * nothing in the window it falls back to the build's own version.
 */
fun freshnessHeadlineVersion(
    demos: List<DemoEntry>,
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): String {
    val declared = demos
        .flatMap { listOfNotNull(it.sinceVersion, it.updatedIn) }
        .filter { isRecentVersion(it, buildVersion, window) }
        .mapNotNull { raw -> parseSemVer(raw)?.let { it to raw } }
        .maxByOrNull { (v, _) -> v.major * 1_000 + v.minor }
        ?.second
    return shortVersionOf(declared ?: buildVersion)
}

/** `"4.35.0-main.abc1234"` → `"4.35"`. The headline never shows a patch or a build suffix. */
fun shortVersionOf(version: String): String {
    val v = parseSemVer(version) ?: return version
    return "${v.major}.${v.minor}"
}
