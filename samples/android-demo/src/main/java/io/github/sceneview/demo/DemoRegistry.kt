package io.github.sceneview.demo

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sceneview.demo.fragments.GeneratedDemos

/**
 * Runtime status of a demo as observed on the audited device matrix
 * (Pixel emulators + Pixel 9 hardware in `.claude/scripts/qa-android-demos.sh`).
 * Drives the badge rendered on the demo card in [DemoListScreen] so users
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
 * #3231
 */
val IN_REVIEW_BADGE_VISIBLE: Boolean
    get() = BuildConfig.DEBUG

/**
 * One entry in the curated demo list shown on the Samples tab.
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
 * @param status      See [DemoStatus]. Defaults to [DemoStatus.Working].
 */
data class DemoEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val category: String,
    val icon: ImageVector,
    val status: DemoStatus = DemoStatus.Working,
)

/**
 * Stable category keys — they are map keys and registry filters, never
 * shown to the user. Use [categoryDisplayNameRes] to obtain the display
 * header label.
 */
object DemoCategory {
    const val BASICS_3D = "3D Basics"
    const val LIGHTING_ENVIRONMENT = "Lighting & Environment"
    const val CONTENT = "Content"
    const val INTERACTION = "Interaction"
    const val ADVANCED = "Advanced"
    const val AUGMENTED_REALITY = "Augmented Reality"
}

/** Ordered list of category keys — controls display order in the list. */
val DEMO_CATEGORIES = listOf(
    DemoCategory.BASICS_3D,
    DemoCategory.LIGHTING_ENVIRONMENT,
    DemoCategory.CONTENT,
    DemoCategory.INTERACTION,
    DemoCategory.ADVANCED,
    DemoCategory.AUGMENTED_REALITY,
)

/**
 * Maps a stable category key to its display-name resource ID.
 * Unknown keys fall back to [R.string.category_3d] (safe default — never
 * surfaces a raw key like "3D Basics" to the user).
 */
@StringRes
fun categoryDisplayNameRes(category: String): Int = when (category) {
    DemoCategory.BASICS_3D -> R.string.category_3d_basics
    DemoCategory.LIGHTING_ENVIRONMENT -> R.string.category_lighting_environment
    DemoCategory.CONTENT -> R.string.category_content
    DemoCategory.INTERACTION -> R.string.category_interaction
    DemoCategory.ADVANCED -> R.string.category_advanced
    DemoCategory.AUGMENTED_REALITY -> R.string.category_augmented_reality
    else -> R.string.category_3d_basics
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
