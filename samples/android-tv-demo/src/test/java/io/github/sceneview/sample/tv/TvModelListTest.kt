package io.github.sceneview.sample.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Asserts that every model entry in [models] — the list TvModelViewerActivity
 * renders in its D-pad gallery — resolves to a bundled asset in either:
 *   - `src/main/assets/` (TV-only models), OR
 *   - `../android-demo/src/main/assets/` (shared models picked up via the
 *     `sourceSets.main.assets.srcDirs` entry in the TV demo's build.gradle).
 *
 * This is the JVM-level backstop for the session-34 fix where
 * [TvModelViewerActivity] was shipping five "models/…glb" paths that were
 * never bundled (`space_helmet.glb`, `toy_car.glb`, `geisha_mask.glb`,
 * `iridescence_lamp.glb`, `sheen_chair.glb`). Before this test, the only
 * protection was `.claude/scripts/validate-demo-assets.sh`, which is bash
 * and has to be invoked explicitly.
 *
 * With this test, `./gradlew :samples:android-tv-demo:testDebugUnitTest`
 * fails fast on any PR that adds a model entry without bundling the file
 * in EITHER asset folder.
 */
class TvModelListTest {

    /**
     * Gradle sets the unit-test working directory to the module's `projectDir`
     * (i.e. `samples/android-tv-demo/`). Assets live at `src/main/assets/`
     * relative to that AND at `../android-demo/src/main/assets/` for shared
     * models (the same set of folders that `sourceSets.main.assets.srcDirs`
     * declares in `build.gradle` — keep these two lists in sync).
     */
    private val assetSearchDirs = listOf(
        File("src/main/assets"),
        File("../android-demo/src/main/assets"),
    )

    private fun resolveAsset(path: String): File? =
        assetSearchDirs.map { File(it, path) }.firstOrNull { it.isFile }

    @Test
    fun `models list is not empty`() {
        assertTrue(
            "TvModelViewerActivity.models is empty — the TV demo would have nothing to render",
            models.isNotEmpty(),
        )
    }

    @Test
    fun `every model entry resolves to a bundled asset`() {
        val missing = models.filter { entry -> resolveAsset(entry.assetPath) == null }

        assertTrue(
            "TvModelViewerActivity.models references assets that are not bundled in any " +
                "of the TV demo's asset source dirs (${assetSearchDirs.joinToString { it.path }}):\n" +
                missing.joinToString("\n") { "  - ${it.label}: ${it.assetPath}" } +
                "\n\nFix: either bundle the missing file into src/main/assets/ (TV-only) or " +
                "../android-demo/src/main/assets/ (shared with the phone demo), or replace " +
                "the entry with a model that already exists.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every model entry credits its author and licence`() {
        // The credit line is on screen under the model name: CC BY 4.0 requires the author
        // wherever the work is shown, so an entry without one must not ship.
        val bad = models.filter { entry ->
            val parts = entry.credit.split(" · ")
            parts.size != 2 || parts[0].isBlank() || parts[1] !in KNOWN_LICENCES
        }
        assertTrue(
            "TvModelViewerActivity.models entries must read \"<author> · <licence>\" with a " +
                "licence in $KNOWN_LICENCES (see assets/CREDITS.md). " +
                "Offenders: ${bad.joinToString { "${it.label}=\"${it.credit}\"" }}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `every model entry has a non-blank label`() {
        val blank = models.filter { it.label.isBlank() }
        assertTrue(
            "TvModelViewerActivity.models entries must have non-blank labels. " +
                "Offenders (by assetPath): ${blank.joinToString { it.assetPath }}",
            blank.isEmpty(),
        )
    }

    @Test
    fun `no duplicate asset paths`() {
        val duplicates = models
            .groupBy { it.assetPath }
            .filterValues { it.size > 1 }
            .keys
        assertFalse(
            "TvModelViewerActivity.models has duplicate asset paths: $duplicates",
            duplicates.isNotEmpty(),
        )
    }

    private companion object {
        val KNOWN_LICENCES = setOf("CC BY 4.0", "CC0")
    }
}
