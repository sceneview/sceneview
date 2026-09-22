package io.github.sceneview.demo

import io.github.sceneview.demo.ui.home.GenerateWorldRedirect
import io.github.sceneview.demo.ui.home.GenerateWorldRedirect.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Generate a 3D world" redirect (`GenerateWorldRedirect`).
 *
 * Plain JUnit on purpose: the part worth pinning is the *decision* — which
 * destination is tried and in what order — which `plan` keeps free of any Android
 * type. `open()` itself is that list wrapped in `startActivity`, and needs a
 * device with (and without) the companion app to say anything more.
 *
 * The store URLs are asserted here because they are the only place the install
 * attribution lives: a `referrer` dropped in a refactor is invisible in the app
 * and surfaces only as a silent hole in the Play Console acquisition report.
 */
class GenerateWorldRedirectTest {

    // ── The order ─────────────────────────────────────────────────────────

    @Test
    fun `an installed app is tried by deep link first, then by its launcher`() {
        assertEquals(
            listOf(Step.DeepLink, Step.Launcher, Step.PlayStoreApp, Step.PlayStoreWeb),
            GenerateWorldRedirect.plan(installed = true),
        )
    }

    @Test
    fun `an absent app goes straight to the store, never through the app steps`() {
        assertEquals(
            listOf(Step.PlayStoreApp, Step.PlayStoreWeb),
            GenerateWorldRedirect.plan(installed = false),
        )
    }

    @Test
    fun `the store app is preferred over the web listing either way`() {
        listOf(true, false).forEach { installed ->
            val plan = GenerateWorldRedirect.plan(installed)
            assertTrue(
                "the store app must be tried before the browser (installed=$installed): $plan",
                plan.indexOf(Step.PlayStoreApp) < plan.indexOf(Step.PlayStoreWeb),
            )
            assertEquals(
                "the web listing must stay the last resort (installed=$installed)",
                Step.PlayStoreWeb,
                plan.last(),
            )
        }
    }

    // ── The destinations ──────────────────────────────────────────────────

    @Test
    fun `both store urls name the companion app and carry the referrer`() {
        listOf(
            GenerateWorldRedirect.PLAY_STORE_MARKET,
            GenerateWorldRedirect.PLAY_STORE_WEB,
        ).forEach { url ->
            assertTrue(
                "a store url must name the companion app: $url",
                url.contains("id=${GenerateWorldRedirect.PACKAGE}"),
            )
            assertTrue(
                "a store url must carry its install attribution: $url",
                url.contains("referrer="),
            )
            assertTrue(
                "the referrer must name this app as the source: $url",
                url.contains("utm_source%3Dsceneview-demo"),
            )
        }
    }

    @Test
    fun `the deep link uses the companion app's own scheme and names its source`() {
        val link = GenerateWorldRedirect.DEEP_LINK
        assertTrue(
            "the deep link must target the companion app's scheme: $link",
            link.startsWith("armodelviewer://"),
        )
        assertTrue(
            "the deep link must name this app as the source: $link",
            link.contains("source=sceneview-demo"),
        )
    }

    @Test
    fun `the companion package is declared for API 30 package visibility`() {
        // Without the <queries> entry, getLaunchIntentForPackage() returns null for
        // an app that IS installed and setPackage() resolves to nothing — every
        // installed user would be sent to a store listing they do not need, and
        // nothing in the build would say so.
        assertTrue(
            "AndroidManifest.xml must declare " +
                "<package android:name=\"${GenerateWorldRedirect.PACKAGE}\" /> inside <queries>",
            manifestSource().contains(
                """<package android:name="${GenerateWorldRedirect.PACKAGE}" />""",
            ),
        )
    }

    /**
     * The Gradle test task's working directory is the module directory; the search
     * walks up so the test also passes when a runner starts it from the repo root.
     */
    private fun manifestSource(): String {
        val relative = "samples/android-demo/src/main/AndroidManifest.xml"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }
}
