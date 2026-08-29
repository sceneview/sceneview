package io.github.sceneview.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the *scope* of the demo app's web deep-link intent-filter (#3366).
 *
 * A `<data android:scheme="https" />` with no `android:host` makes the app a
 * candidate for **every** https link on the device: it joins the "Open with"
 * chooser for unrelated sites, and can win it outright once a user has tapped
 * "Always". On a shared QA emulator that looks exactly like the demo stealing
 * another app's taps, and there is nothing in the build that would say so —
 * a missing attribute is not a lint error, and no instrumentation test asserts
 * on the resolver table.
 *
 * `DeepLinkRouter.extractCandidate` applies the same host + path check in
 * code, so a widened manifest would not even reach a demo screen: it would
 * intercept the link and drop it. The two layers must move together or not at
 * all, which is why this test reads its expectations from the router's own
 * constants rather than repeating the literals.
 *
 * Plain JUnit on purpose: the assertion is about the manifest *source*, so it
 * needs no Android runtime, no Robolectric shadow and no device — and it fails
 * on the widening itself, not on a resolver behaviour that only reproduces once
 * a second app is installed.
 */
class DeepLinkManifestScopeTest {

    private val dataElement = Regex("""<data\b[^>]*>""")
    private val xmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private fun attribute(element: String, name: String): String? =
        Regex("""android:$name\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)

    @Test
    fun `every web scheme in the manifest is bound to a host and a path prefix`() {
        val manifest = manifestSource()
        val webData = dataElement.findAll(manifest)
            .map { it.value }
            .filter { attribute(it, "scheme") in setOf("http", "https") }
            .toList()

        assertTrue(
            "The demo declares no http/https <data> element any more. If the App-Link " +
                "was removed on purpose, delete this test; otherwise the deep link is broken.",
            webData.isNotEmpty(),
        )

        webData.forEach { element ->
            assertEquals(
                "A web <data> element must be scoped to the site that hosts " +
                    "assetlinks.json — never a bare scheme (#3366): $element",
                DeepLinkRouter.HOST_HTTPS,
                attribute(element, "host"),
            )
            assertEquals(
                "A web <data> element must be scoped to the deep-link path, matching " +
                    "DeepLinkRouter.PATH_HTTPS (#3366): $element",
                DeepLinkRouter.PATH_HTTPS,
                attribute(element, "pathPrefix"),
            )
        }
    }

    @Test
    fun `the custom scheme filter stays bound to the router's scheme and host`() {
        val manifest = manifestSource()
        val custom = dataElement.findAll(manifest)
            .map { it.value }
            .filter { attribute(it, "scheme") == DeepLinkRouter.SCHEME_CUSTOM }
            .toList()

        assertEquals(
            "Expected exactly one ${DeepLinkRouter.SCHEME_CUSTOM}:// <data> element",
            1,
            custom.size,
        )
        assertEquals(
            "The custom-scheme filter must match DeepLinkRouter.HOST_CUSTOM",
            DeepLinkRouter.HOST_CUSTOM,
            attribute(custom.single(), "host"),
        )
    }

    /**
     * The manifest with its XML comments stripped — the comment above the filter
     * quotes the very `<data android:scheme="https" />` shape this test forbids,
     * and a scanner that reads comments would fail on the documentation of the rule.
     *
     * The Gradle test task's working directory is the module directory; the search
     * walks up so the test also passes when a runner starts it from the repo root.
     */
    private fun manifestSource(): String {
        val relative = "samples/android-demo/src/main/AndroidManifest.xml"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return xmlComment.replace(candidate.readText(), "")
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }
}
