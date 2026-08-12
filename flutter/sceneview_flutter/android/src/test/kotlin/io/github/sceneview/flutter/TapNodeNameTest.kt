package io.github.sceneview.flutter

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tap payload's node name (#3071, #3062).
 *
 * This is the first JVM unit test this module has ever had. The derivation it
 * covers is a pure string transform whose output leaves the process — it is
 * handed to Dart as `nodeName` on every tap, and apps put it in labels and
 * analytics events. Nothing else could catch it being wrong: the Kotlin
 * compiles (`flutter build apk`), the Dart tests never reach it, and a wrong
 * name is a plausible-looking string, not a crash.
 *
 * The case table is mirrored in the React Native bridge's `NodeNameTest` —
 * the two bridges are separately published packages with no shared Kotlin, and
 * the tap payload is documented as identical on both. A divergence has to show
 * up as a diff between these two files.
 */
class TapNodeNameTest {

    private fun name(path: String) = tapNodeName(path, fallback = "node_0")

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
     * `robot.glb?sig=SIG&v=1` and publish a CDN signature (PR #3037).
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
     * CDN URL put its own credentials one tap away from the Dart payload.
     *
     * Asserted on the whole value rather than `contains("pa55w0rd")`: a leak
     * that changed shape — the bare host, or a percent-encoded password —
     * would slip past a substring probe while still publishing the authority.
     */
    @Test
    fun `does not publish the userinfo of a path-less URL`() {
        assertEquals("node_0", name("https://user:pa55w0rd@cdn.example"))
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
        assertEquals("node_0", name("https://user:pa55w0rd@cdn.example/"))
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
        assertEquals("node_0", name("https://user:pa55w0rd@cdn.example?sig=SECRET"))
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

    // ── Degenerate sources fall back rather than report a non-name ────────

    @Test
    fun `falls back on an empty source`() {
        assertEquals("node_0", name(""))
    }

    @Test
    fun `falls back on a trailing-slash asset path`() {
        assertEquals("node_0", name("models/"))
    }

    /** A file with no extension keeps its whole base name. */
    @Test
    fun `keeps an extensionless name`() {
        assertEquals("robot", name("models/robot"))
    }
}
