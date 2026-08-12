package io.github.sceneview.reactnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tap payload's `nodeName` (#3071, #3062).
 *
 * This is the first JVM unit test this module has ever had. The derivation it
 * covers is a pure string transform whose output leaves the process — it is
 * dispatched to JS as `nodeName` on every tap, and apps put it in labels and
 * analytics events. Nothing else could catch it being wrong: `tools/rn-android-compile`
 * is a COMPILE gate, `samples/react-native-demo` has never been built
 * end-to-end, and a wrong name is a plausible-looking string, not a crash.
 *
 * The case table is mirrored in the Flutter bridge's `TapNodeNameTest` — the
 * two bridges are separately published packages with no shared Kotlin, and the
 * tap payload is documented as identical on both. A divergence has to show up
 * as a diff between these two files.
 *
 * The one intended difference is the degenerate-source answer: this bridge
 * reports `null` so the payload stays `nodeName: null`, where Flutter falls
 * back to `node_<index>`.
 */
class NodeNameTest {

    /**
     * Calls the derivation directly rather than through `ModelNodeData(src).nodeName()`.
     * The data class defaults `position`/`rotation` to types from the published
     * SceneView artifacts, which are built for JVM 21 while this module and its
     * CI gate are on 17 — constructing it in a JVM test throws
     * `UnsupportedClassVersionError` (measured on run 31582255097, all 13 tests
     * red at the constructor). `nodeName()` is a one-line delegate to this
     * function, so nothing about the covered behaviour is lost.
     */
    private fun name(src: String) = modelNodeName(src)

    // ── The ordinary paths ────────────────────────────────────────────────

    @Test
    fun `takes the base name of a plain asset path`() {
        assertEquals("black_dragon", name("models/black_dragon.glb"))
    }

    @Test
    fun `takes the base name of a URL with a path`() {
        assertEquals("robot", name("https://cdn.example/models/robot.glb"))
    }

    /**
     * The query strip has to happen BEFORE the extension cut: on a raw URL the
     * last `.` is inside the query, so `robot.glb?sig=SIG&v=1.2` would report
     * `robot.glb?sig=SIG&v=1` and publish a CDN signature.
     */
    @Test
    fun `strips a query string and its signature`() {
        assertEquals("robot", name("https://cdn.example/a/robot.glb?sig=SECRET&v=1.2"))
    }

    @Test
    fun `strips a fragment`() {
        assertEquals("robot", name("https://cdn.example/robot.glb#scene1"))
    }

    // ── The authority never reaches the name (#3071) ──────────────────────

    /**
     * The leak this closes. The last `/`-separated segment of a URL with NO
     * path IS the authority, and an authority may carry userinfo — so a signed
     * CDN URL put its own credentials one tap away from the JS payload.
     *
     * Asserted on the whole value rather than `contains("pa55w0rd")`: a leak
     * that changed shape — the bare host, or a percent-encoded password —
     * would slip past a substring probe while still publishing the authority.
     */
    @Test
    fun `does not publish the userinfo of a path-less URL`() {
        assertEquals(null, name("https://user:pa55w0rd@cdn.example"))
    }

    /**
     * NOT load-bearing on this side, and kept on purpose. Measured against the
     * pre-fix derivation: `substringAfterLast('/')` on a trailing-slash URL
     * already returned `""`, so this case never leaked in Kotlin — it is the
     * Swift bridge where `NSString.lastPathComponent` answers `"/"` for a
     * root-only path and had to be mapped to `""`.
     *
     * It stays because the two case tables are meant to be diffable, and
     * because the guard it pins is one `urlPathOf` rewrite away from mattering.
     */
    @Test
    fun `does not publish a root-only path as a name`() {
        assertEquals(null, name("https://user:pa55w0rd@cdn.example/"))
    }

    /**
     * Kept so the fix is shown to change only the broken case: with a path,
     * the cut already landed past the authority before #3071.
     */
    @Test
    fun `keeps working when a credentialed URL has a path`() {
        assertEquals("robot", name("https://user:pa55w0rd@cdn.example/models/robot.glb"))
    }

    @Test
    fun `drops userinfo and query together`() {
        assertEquals(null, name("https://user:pa55w0rd@cdn.example?sig=SECRET"))
    }

    // ── Inputs that must NOT be treated as URLs ───────────────────────────

    /**
     * A `://` that is not a scheme delimiter. Without the guard, everything
     * before it is dropped as if it were an authority.
     */
    @Test
    fun `does not treat an inner colon-slash-slash as a scheme`() {
        assertEquals("name", name("models/odd://name.glb"))
    }

    /** No scheme to cut at, so the derivation is unchanged. */
    @Test
    fun `leaves a scheme-relative source alone`() {
        assertEquals("robot", name("//cdn.example/models/robot.glb"))
    }

    // ── Degenerate sources report null rather than a non-name ─────────────

    @Test
    fun `reports null on an empty source`() {
        assertEquals(null, name(""))
    }

    @Test
    fun `reports null on a trailing-slash asset path`() {
        assertEquals(null, name("models/"))
    }

    /** A file with no extension keeps its whole base name. */
    @Test
    fun `keeps an extensionless name`() {
        assertEquals("robot", name("models/robot"))
    }
}
