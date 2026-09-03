package io.github.sceneview.demo.demos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every demo that depends on the ARCore Cloud API key must tell the user when
 * that key is missing or rejected — and, since #3262, do it through the one
 * shared `CloudServiceStatus` banner in the main AR view rather than a
 * per-demo copy that can silently drift or end up settings-only.
 *
 * Two of the five Cloud demos detected a missing key and then said nothing about
 * it, so a fork without the secret — or a Play build whose key restriction
 * names the wrong SHA-1 (#3185) — presented as a demo that simply did not work
 * (#3210). A later pass found the fix itself had drifted: two demos only
 * showed it in the Settings sheet, and none of the five distinguished a
 * rejected key from an exhausted quota or a plain network outage (#3262). The
 * wording and the state now live in one place, `common/CloudServiceStatus.kt`;
 * this test keeps a sixth Cloud demo — or a regression in one of the five —
 * from shipping without it.
 *
 * The set of Cloud demos is discovered, not listed: any demo source that turns
 * on `GeospatialMode.ENABLED` or `CloudAnchorMode.ENABLED` is one. Discovery is
 * the point — pinning a fixed list would pass the day someone adds a Cloud demo
 * without the guard, which is exactly the regression this exists to catch.
 *
 * The sanity check on discovery used to be "at least five files matched", which
 * confused a count of FILES with a count of CAPABILITIES: #2239 merged
 * `ar-terrain` and `ar-rooftop` into one `ARGeospatialAnchorsDemo.kt`, so five
 * Cloud demos now live in four files and the guard failed on a merge that
 * removed nothing. The check is now "every file we know to be a Cloud demo is
 * still discovered", which catches a marker that stopped matching without
 * caring how the demos are packed into files.
 */
class ArcoreCloudDemoGuardTest {

    private val cloudModeMarkers = listOf(
        "GeospatialMode.ENABLED",
        "CloudAnchorMode.ENABLED",
    )

    private val requiredGuards = listOf(
        // Detects the missing key up front.
        "rememberHasArcoreApiKey()",
        // Computes the shared "why can't this demo work right now" state...
        "CloudServiceStatus",
        // ...and renders it as the one shared banner, in the main AR view.
        "CloudServiceStatusBanner(",
    )

    @Test
    fun `every ARCore Cloud demo names a missing or rejected key on screen`() {
        val cloudDemos = demoSources().filter { file ->
            val source = file.readText()
            cloudModeMarkers.any { it in source }
        }
        // Named, not counted — see the class KDoc. Add a file here when a new Cloud
        // demo lands; never remove one to make a red build green.
        val knownCloudSources = setOf(
            "ARCloudAnchorDemo.kt",
            "ARGeospatialAnchorsDemo.kt",
            "ARSceneMeshDemo.kt",
            "ARStreetscapeDemo.kt",
        )
        val discovered = cloudDemos.map { it.name }.toSet()
        assertTrue(
            "The marker list is stale — these known Cloud demos were not discovered: " +
                (knownCloudSources - discovered).joinToString(),
            discovered.containsAll(knownCloudSources),
        )
        val offenders = cloudDemos.mapNotNull { file ->
            val source = file.readText()
            val missing = requiredGuards.filterNot { it in source }
            if (missing.isEmpty()) null else "${file.name} lacks ${missing.joinToString()}"
        }
        assertTrue(
            "Cloud demos must surface the ARCore Cloud service state via the shared " +
                "common/CloudServiceStatus.kt banner (#3262):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Lists the demo sources. The Gradle test task's working directory is the module
     * directory, but the search walks up so the test also passes when a runner starts
     * it from the repository root.
     */
    private fun demoSources(): List<File> {
        val relative = "samples/android-demo/src/main/java/io/github/sceneview/demo/demos"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) {
                return candidate.listFiles { f -> f.isFile && f.extension == "kt" }!!.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }
}
