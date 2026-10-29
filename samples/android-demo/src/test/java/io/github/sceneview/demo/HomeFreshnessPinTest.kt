package io.github.sceneview.demo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freshness chips ("New" / "Updated") and the "What's new" featured page on the
 * Showcase tab, at a version pinned to the demo declarations rather than the build's
 * `VERSION_NAME` (#3666).
 *
 * Moved out of the former `HomeScreenSnapshotTest` when the Roborazzi suites were dropped.
 */
class HomeFreshnessPinTest {

    @Test
    fun the_pinned_version_still_badges_something_and_not_the_whole_grid() {
        // A [PINNED_BUILD_VERSION] that resolves to a version badging *nothing* covers
        // none of the freshness chips or the featured page; badging *everything* is the
        // same blind spot from the other side.
        val marked = freshDemos(PHONE_DEMOS, PINNED_BUILD_VERSION)
        assertTrue(
            "no demo is fresh at $PINNED_BUILD_VERSION — the freshness chips and the " +
                "\"What's new\" featured page show nothing",
            marked.isNotEmpty(),
        )
        assertTrue(
            "${marked.size} of ${PHONE_DEMOS.size} demos are fresh at $PINNED_BUILD_VERSION — " +
                "that looks like a release that declared everything",
            marked.size * 2 <= PHONE_DEMOS.size,
        )
    }

    private companion object {
        /**
         * The catalogue a phone shows: [ALL_DEMOS] without the cards listed on an Android XR
         * device only (`ar-xr`, samples step 0).
         */
        val PHONE_DEMOS: List<DemoEntry> = listedDemos(ALL_DEMOS, xrDevice = false)

        /**
         * The newest version any demo declares, never `BuildConfig.VERSION_NAME` (#3666): a
         * pure function of the `addedIn` / `updatedIn` fields, so a release commit that only
         * moves `VERSION_NAME` changes nothing here.
         *
         * `isRecentVersion(a, b, window = 0)` is "a is at least b" at the major.minor
         * granularity the badges use.
         */
        val PINNED_BUILD_VERSION: String =
            PHONE_DEMOS.flatMap { listOfNotNull(it.addedIn, it.updatedIn) }
                .reduceOrNull { newest, candidate ->
                    if (isRecentVersion(candidate, newest, window = 0)) candidate else newest
                }
                ?: "0.0.0"
    }
}
