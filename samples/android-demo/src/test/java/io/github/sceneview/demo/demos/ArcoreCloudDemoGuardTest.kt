package io.github.sceneview.demo.demos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every demo that depends on the ARCore Cloud API key must tell the user when
 * that key is missing or rejected (#3210).
 *
 * Two of the five Cloud demos detected a missing key and then said nothing about
 * it, so a fork without the secret — or a Play build whose key restriction
 * names the wrong SHA-1 (#3185) — presented as a demo that simply did not work.
 * The fix put the wording in one place, `common/ArcoreCloudApiKey.kt`; this test
 * keeps a sixth Cloud demo from shipping without it.
 *
 * The set of Cloud demos is discovered, not listed: any demo source that turns
 * on `GeospatialMode.ENABLED` or `CloudAnchorMode.ENABLED` is one. Pinning the
 * count at five would pass the day someone adds a sixth without the guard,
 * which is exactly the regression this exists to catch.
 */
class ArcoreCloudDemoGuardTest {

    private val cloudModeMarkers = listOf(
        "GeospatialMode.ENABLED",
        "CloudAnchorMode.ENABLED",
    )

    private val requiredGuards = listOf(
        // Detects the missing key and names it on screen.
        "rememberHasArcoreApiKey()",
        "ARCORE_API_KEY_MISSING_MESSAGE",
        // Names the rejected key, with the SHA-1 + billing hint, on screen.
        "arcoreApiKeyRejectedMessage(",
    )

    @Test
    fun `every ARCore Cloud demo names a missing or rejected key on screen`() {
        val cloudDemos = demoSources().filter { file ->
            val source = file.readText()
            cloudModeMarkers.any { it in source }
        }
        assertTrue(
            "Expected at least the five known Cloud demos; the marker list is stale",
            cloudDemos.size >= 5,
        )
        val offenders = cloudDemos.mapNotNull { file ->
            val source = file.readText()
            val missing = requiredGuards.filterNot { it in source }
            if (missing.isEmpty()) null else "${file.name} lacks ${missing.joinToString()}"
        }
        assertTrue(
            "Cloud demos must surface the ARCore Cloud API key state via " +
                "common/ArcoreCloudApiKey.kt (#3210):\n" + offenders.joinToString("\n"),
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
