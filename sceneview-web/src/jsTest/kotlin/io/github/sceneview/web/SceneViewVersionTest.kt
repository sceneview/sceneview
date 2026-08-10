package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression pin for [SCENEVIEW_VERSION] (#1357).
 *
 * The constant is `@JsExport`-reachable, so web consumers querying the library version get
 * whatever this literal says. It once drifted two majors stale (`3.6.0` while the repo shipped
 * `4.4.0`); these tests fail loudly if it is left behind a real version bump again.
 */
class SceneViewVersionTest {

    @Test
    fun versionIsCurrent() {
        // Bump in lockstep with `gradle.properties` -> `VERSION_NAME`.
        assertEquals("4.27.0", SCENEVIEW_VERSION)
    }

    @Test
    fun versionIsSemver() {
        assertTrue(
            Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""").matches(SCENEVIEW_VERSION),
            "SCENEVIEW_VERSION must be a semver string, was '$SCENEVIEW_VERSION'.",
        )
    }

    @Test
    fun versionIsNotTheKnownStaleValue() {
        // The exact stale value from #1357 — guards against an accidental revert.
        assertTrue(SCENEVIEW_VERSION != "3.6.0", "SCENEVIEW_VERSION reverted to the stale 3.6.0.")
    }
}
