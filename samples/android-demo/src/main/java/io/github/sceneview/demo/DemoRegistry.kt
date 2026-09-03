package io.github.sceneview.demo

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sceneview.demo.fragments.GeneratedDemos

/**
 * Runtime status of a demo as observed on the audited device matrix
 * (Pixel emulators + Pixel 9 hardware in `.claude/scripts/qa-android-demos.sh`).
 * Drives the badge rendered on the demo card on the home grid so users
 * see honest expectations rather than a uniformly green list that lies.
 */
enum class DemoStatus {
    /** Verified working on the audit device matrix. */
    Working,

    /** Known visual or interaction regression — surfaced with a yellow badge. */
    KnownIssue,

    /** Compiles but not yet wired up to a real implementation. */
    ComingSoon,

    /**
     * Newly shipped feature awaiting on-device review sign-off — surfaced with an
     * "In review" chip so testers know exactly which demos to exercise on the
     * store build. Flip to [Working] once the review pass validates it.
     */
    InReview,
}

/**
 * Whether the "In review" chip may be drawn.
 *
 * [DemoStatus.InReview] is a *process* state, addressed to whoever runs the
 * on-device review pass: it says "exercise this one before sign-off". Its KDoc
 * says as much — "so testers know exactly which demos to exercise". The chip was
 * nevertheless rendered unconditionally, so it shipped to the Play Store, where
 * "In review" reads to a user as *this demo may be broken* — the opposite of the
 * honest-expectations job the badge set was built for.
 *
 * The status itself stays: it is what drives the "New in this build — try them"
 * section, which is written for users. Only the internal vocabulary is gated.
 * #3237
 */
val IN_REVIEW_BADGE_VISIBLE: Boolean
    get() = BuildConfig.DEBUG

/**
 * One entry in the curated demo list shown on the Showcase (home) tab.
 *
 * String content is referenced through Android resources to keep literals
 * out of code and defined in a single place. The sample app is English-only
 * by design — see #1294. Closes #1099 / #955.
 *
 * @param id          Stable identifier used by the deep-link router
 *                    (`sceneview://demo/<id>`) and as a Compose key.
 * @param titleRes    Short headline shown on the card — resolved with
 *                    [androidx.compose.ui.res.stringResource] at the call site.
 * @param subtitleRes One-line description rendered under [titleRes].
 * @param category    Stable category key. Used as a map key when grouping
 *                    demos and to look up the per-category accent colour —
 *                    see [categoryDisplayNameRes] for the display header label.
 * @param icon        Material icon used to give the card visual identity
 *                    in the absence of a captured 3D thumbnail.
 * @param order       Editorial position on the home grid — unique, lower
 *                    renders first. Since #2239 it also carries the section
 *                    layout: sections run in [DEMO_CATEGORIES] order and a
 *                    category's demos are contiguous inside it, so the grid
 *                    can draw one header per section by watching for the
 *                    boundary. Within a section, foundational demos lead and
 *                    coming-soon / known-issue demos close it.
 *                    `collate-demos.sh` sorts on it and
 *                    [io.github.sceneview.demo.DemoRegistryIntegrityTest]
 *                    asserts uniqueness *and* contiguity.
 * @param tags        Capability keywords ("gltf", "hdr", "depth", …) matched
 *                    by the home search field alongside title, subtitle and
 *                    category. Never shown; never empty.
 * @param status      See [DemoStatus]. Defaults to [DemoStatus.Working].
 */
data class DemoEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val category: String,
    val icon: ImageVector,
    val order: Int,
    val tags: Set<String>,
    val status: DemoStatus = DemoStatus.Working,
)

/**
 * Stable category keys — they are map keys and registry filters, never
 * shown to the user. Use [categoryDisplayNameRes] to obtain the display
 * header label.
 *
 * Since #2239 a category is also a **section** on the home grid: the grid draws
 * one header per category in [DEMO_CATEGORIES] order, so a category boundary is
 * something the user sees rather than only a filter chip. Two rules follow, and
 * [io.github.sceneview.demo.DemoRegistryIntegrityTest] enforces them: every
 * category holds at least one demo (a header with nothing under it is a lie),
 * and [DemoEntry.order] keeps a category's demos contiguous (a section that
 * restarts further down the grid is not a section).
 *
 * The nine keys replace the six that shipped until #2239. The old set put 33 of
 * 53 cards behind a single "Augmented Reality" chip, which is what made the
 * catalogue unnavigable: AR is not one subject, it is placement, tracking,
 * scene understanding and anchors — four different ARCore API families.
 */
object DemoCategory {
    /** Load something and watch it — the first thing a newcomer opens. */
    const val VIEWER = "Viewer"

    /** Author the geometry and shade it. */
    const val GEOMETRY_MATERIALS = "Geometry & Materials"

    /** Light the scene and post-process the frame. */
    const val RENDERING = "Rendering"

    /** Touch the scene — camera manipulators, picking, node gestures. */
    const val INTERACTION = "Interaction"

    /** Put virtual content in the real room. */
    const val AR_PLACEMENT = "AR Placement"

    /** Track a subject — faces, images, bodies, hands. */
    const val AR_TRACKING = "AR Tracking"

    /** Read the room — depth, point clouds, meshes, semantics. */
    const val AR_UNDERSTANDING = "AR Understanding"

    /** Anchors that outlive the frame — cloud, geospatial, collaborative. */
    const val AR_ANCHORS = "AR Anchors"

    /** The plumbing around the renderer — audio, capture, recording, debug. */
    const val PLATFORM = "Platform"
}

/** Ordered list of category keys — controls the home filter-chip order. */
val DEMO_CATEGORIES = listOf(
    DemoCategory.VIEWER,
    DemoCategory.GEOMETRY_MATERIALS,
    DemoCategory.RENDERING,
    DemoCategory.INTERACTION,
    DemoCategory.AR_PLACEMENT,
    DemoCategory.AR_TRACKING,
    DemoCategory.AR_UNDERSTANDING,
    DemoCategory.AR_ANCHORS,
    DemoCategory.PLATFORM,
)

/**
 * The sections whose demos are AR demos.
 *
 * Before #2239 this was a single equality test against one `AUGMENTED_REALITY`
 * category. The regroup split AR across four sections, so every "is this an AR
 * demo?" question now goes through [isArDemo] rather than re-listing the four
 * keys at each call site.
 */
val AR_CATEGORIES: Set<String> = setOf(
    DemoCategory.AR_PLACEMENT,
    DemoCategory.AR_TRACKING,
    DemoCategory.AR_UNDERSTANDING,
    DemoCategory.AR_ANCHORS,
)

/**
 * AR demos that are deliberately filed outside [AR_CATEGORIES].
 *
 * `ar-record-playback` and `ar-rerun` sit under [DemoCategory.PLATFORM] because
 * their subject is capture and replay tooling — the plumbing around the session,
 * not what the session sees. They still open an ARCore session, so anything that
 * enumerates AR demos (the AR View tab's list, the replay harness) has to include
 * them or it silently drops two demos that need the AR device path.
 *
 * Keep this list empty if you can. It exists because a demo's *section* answers
 * "where does a user look for this" and that is not always the same question as
 * "does this need ARCore".
 */
private val AR_DEMOS_OUTSIDE_AR_SECTIONS: Set<String> = setOf(
    "ar-record-playback",
    "ar-rerun",
)

/** Whether this demo runs on ARCore — see [AR_CATEGORIES]. */
val DemoEntry.isArDemo: Boolean
    get() = category in AR_CATEGORIES || id in AR_DEMOS_OUTSIDE_AR_SECTIONS

/**
 * Maps a stable category key to its display-name resource ID.
 * Unknown keys fall back to [R.string.category_viewer] (safe default — never
 * surfaces a raw key like "AR Understanding" to the user).
 */
@StringRes
fun categoryDisplayNameRes(category: String): Int = when (category) {
    DemoCategory.VIEWER -> R.string.category_viewer
    DemoCategory.GEOMETRY_MATERIALS -> R.string.category_geometry_materials
    DemoCategory.RENDERING -> R.string.category_rendering
    DemoCategory.INTERACTION -> R.string.category_interaction
    DemoCategory.AR_PLACEMENT -> R.string.category_ar_placement
    DemoCategory.AR_TRACKING -> R.string.category_ar_tracking
    DemoCategory.AR_UNDERSTANDING -> R.string.category_ar_understanding
    DemoCategory.AR_ANCHORS -> R.string.category_ar_anchors
    DemoCategory.PLATFORM -> R.string.category_platform
    else -> R.string.category_viewer
}

/**
 * Every demo surfaced on the Samples tab and routable through the deep-link
 * router. Sourced from the append-only per-demo fragments under
 * `io.github.sceneview.demo.fragments` — see
 * [io.github.sceneview.demo.fragments.DemoFragment] for the registration
 * pattern and [io.github.sceneview.demo.fragments.GeneratedDemos] for the
 * collator output. Adding a demo means dropping one new fragment file in that
 * package and running `samples/android-demo/scripts/collate-demos.sh`; no edit
 * to this file or to [io.github.sceneview.demo.MainActivity] is needed (#1797).
 */
val ALL_DEMOS: List<DemoEntry> = GeneratedDemos.all
