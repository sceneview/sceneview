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
 * **Where the data lives.** Two fields on [DemoEntry] — the required
 * [DemoEntry.addedIn] and the optional [DemoEntry.updatedIn] — declared in the
 * demo's own append-only `fragments/<Id>Fragment.kt`, back-filled from git
 * history (the first release tag that contains the demo). That is the file a PR touching a
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
 * **Declare the next version.** `VERSION_NAME` is bumped only at release time,
 * so work merged on `main` declares the version it will ship in: one minor
 * ahead of the build during development. The registry test accepts exactly
 * that one-minor lead and rejects anything further, so a typo (`4.15.0` for
 * `4.51.0`, `5.0.0`) cannot pin a pill on a card for good.
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
 * `2`: a 4.51 build marks what declares 4.49, 4.50 or 4.51. Shared with iOS
 * (`DemoFreshness.windowMinors`). At `1` a release cadence of several minors a
 * week left the pill on almost nothing by the time anyone opened the app, and
 * the maintainer could not find what was new (02/10). It stays small on
 * purpose: marking more than half of the phone catalogue signals a release
 * that declared everything, not a budget that should ration honest markers.
 */
const val FRESHNESS_WINDOW_MINORS: Int = 2

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
 * Whether a demo may declare [version] while running [buildVersion].
 *
 * A declaration may be from an older release, the build's minor, or exactly
 * one minor ahead on the same major. Patch levels do not affect that bound.
 */
internal fun isDeclarableVersion(version: String?, buildVersion: String): Boolean {
    val parts = version?.split('.') ?: return false
    if (parts.size != 3 || parts.any { it.toIntOrNull() == null }) return false
    val declared = parseSemVer(version) ?: return false
    val build = parseSemVer(buildVersion) ?: return false
    return if (declared.major != build.major) {
        declared.major < build.major
    } else {
        declared.minor <= build.minor + 1
    }
}

/**
 * The marker for one demo. [DemoFreshness.New] wins over
 * [DemoFreshness.Updated]: a demo that both arrived and was then touched inside
 * the window is still, to a user, new.
 *
 * A [DemoStatus.ComingSoon] demo is never marked, whatever it declares: there is
 * nothing new to try on a card that does not run yet. Same rule as iOS
 * (`DemoFreshness` in `HomeFilter.swift`).
 */
fun DemoEntry.freshness(
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): DemoFreshness = when {
    status == DemoStatus.ComingSoon -> DemoFreshness.None
    isRecentVersion(addedIn, buildVersion, window) -> DemoFreshness.New
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
 * The oldest release still inside the window, as `major.minor` — "since 4.49" on a 4.51
 * build. Falls back to the build's own short version when it does not parse.
 */
fun freshnessWindowStart(
    buildVersion: String,
    window: Int = FRESHNESS_WINDOW_MINORS,
): String {
    val v = parseSemVer(buildVersion) ?: return buildVersion
    return "${v.major}.${(v.minor - window).coerceAtLeast(0)}"
}
