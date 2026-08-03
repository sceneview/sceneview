package io.github.sceneview.demo.sketchfab

import io.github.sceneview.demo.AssetSourceState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests for [AssetSourceProbe] — the rule behind every demo's asset-source pill.
 *
 * Pure JVM: [SketchfabAssetResolver.isBundledFallback] keys on the parent directory NAME,
 * so a `File` path is enough and nothing has to touch the disk or Robolectric.
 *
 * The defect these pin (#2933 → #2936 → #2953) always had the same shape: **a key is
 * configured AND the resolver fell back**, which the config-based inference called
 * "streamed". Every test below that carries `hasApiKey = true` with a fallback file is a
 * direct regression probe — flip the measured branch off and it goes red.
 */
class AssetSourceProbeTest {

    private val cacheRoot = File("/data/user/0/io.github.sceneview.demo/cache/sketchfab")

    /** What the resolver hands back on the network path. */
    private fun streamed(uid: String) = File(cacheRoot, "$uid.glb")

    /** What it hands back on every failure path — `cache/sketchfab/fallback/<uid>.glb`. */
    private fun fallback(uid: String) =
        File(File(cacheRoot, SketchfabAssetResolver.FALLBACK_DIR_NAME), "$uid.glb")

    // ─── the regression: a KEYED build that fell back ──────────────────────

    @Test
    fun `a keyed build that fell back reports Bundled, not Streamed`() {
        assertEquals(
            "a configured key says nothing about whether the download succeeded — " +
                "the resolved file came out of fallback/, so the pill must say Bundled",
            AssetSourceState.Bundled,
            AssetSourceProbe.of(fallback("car"), hasApiKey = true, loaded = true),
        )
    }

    @Test
    fun `one fallen-back slot out of four sinks the whole scene`() {
        val files = listOf(streamed("a"), streamed("b"), fallback("c"), streamed("d"))
        assertEquals(
            "the verdict is whole-scene and pessimistic: one stand-in in the formation " +
                "means the pill must not promise a streamed scene",
            AssetSourceState.Bundled,
            AssetSourceProbe.ofAll(files, hasApiKey = true, loaded = true),
        )
    }

    @Test
    fun `a fallback beats loaded — the measured branch wins over every guess`() {
        // Same inputs as the Streamed case below except the parent directory. If the
        // measured branch is removed, `loaded = true` + a key falls through to Streamed
        // and this test is the one that catches it.
        assertEquals(
            AssetSourceState.Bundled,
            AssetSourceProbe.of(fallback("car"), hasApiKey = true, loaded = true),
        )
        assertEquals(
            AssetSourceState.Streamed,
            AssetSourceProbe.of(streamed("car"), hasApiKey = true, loaded = true),
        )
    }

    // ─── the pre-resolve path: the only place the key is read ──────────────

    @Test
    fun `nothing resolved yet with a key reads Streaming`() {
        assertEquals(
            AssetSourceState.Streaming,
            AssetSourceProbe.of(null, hasApiKey = true, loaded = false),
        )
    }

    @Test
    fun `nothing resolved yet without a key reads Bundled`() {
        // Keyless is where this ends regardless: `resolve` short-circuits to the bundled
        // fallback, so claiming "Streaming…" would be a spinner that never lands.
        assertEquals(
            AssetSourceState.Bundled,
            AssetSourceProbe.of(null, hasApiKey = false, loaded = false),
        )
    }

    @Test
    fun `a resolved streamed file that has not finished loading still reads Streaming`() {
        // AR Placement ties `loaded` to the file and Gallery ties it to the parsed
        // ModelInstance; this is the Gallery shape — file in hand, parse still running.
        assertEquals(
            AssetSourceState.Streaming,
            AssetSourceProbe.of(streamed("car"), hasApiKey = true, loaded = false),
        )
    }

    @Test
    fun `a resolved fallback reports Bundled before the parse finishes`() {
        // The origin is settled the moment the file is known, so the chip does NOT wait
        // for the parse to say so — #1465 is about never claiming *completion* early, and
        // "Offline model" claims an origin, not a finished download.
        assertEquals(
            AssetSourceState.Bundled,
            AssetSourceProbe.of(fallback("car"), hasApiKey = true, loaded = false),
        )
    }

    // ─── the happy path ────────────────────────────────────────────────────

    @Test
    fun `every slot streamed and loaded reports Streamed`() {
        val files = listOf(streamed("a"), streamed("b"), streamed("c"), streamed("d"))
        assertEquals(
            AssetSourceState.Streamed,
            AssetSourceProbe.ofAll(files, hasApiKey = true, loaded = true),
        )
    }

    @Test
    fun `a partially resolved keyed scene reads Streaming, not Streamed`() {
        val files = listOf(streamed("a"), null, streamed("c"), null)
        assertEquals(
            AssetSourceState.Streaming,
            AssetSourceProbe.ofAll(files, hasApiKey = true, loaded = false),
        )
    }

    @Test
    fun `a partially resolved keyless scene reads Bundled`() {
        val files = listOf(streamed("a"), null, null, null)
        assertEquals(
            AssetSourceState.Bundled,
            AssetSourceProbe.ofAll(files, hasApiKey = false, loaded = false),
        )
    }

    // ─── the single-slot form is the list form, and must stay that way ─────

    @Test
    fun `the single-slot form agrees with the one-element list form everywhere`() {
        val files = listOf(null, streamed("car"), fallback("car"))
        for (file in files) {
            for (hasApiKey in listOf(true, false)) {
                for (loaded in listOf(true, false)) {
                    assertEquals(
                        "of($file, key=$hasApiKey, loaded=$loaded) must equal ofAll of the " +
                            "same single slot — the two forms differ only in arity",
                        AssetSourceProbe.ofAll(listOf(file), hasApiKey, loaded),
                        AssetSourceProbe.of(file, hasApiKey, loaded),
                    )
                }
            }
        }
    }

    // ─── the fallback probe is keyed on the directory the resolver stages into ─

    @Test
    fun `the probe reads the real staging directory name, not a hardcoded string`() {
        // If FALLBACK_DIR_NAME ever changes, `fallback()` above follows it and these tests
        // keep testing the truth. This asserts the constant is what the resolver's KDoc
        // promises, so the helpers above cannot quietly drift into testing nothing.
        assertEquals("fallback", SketchfabAssetResolver.FALLBACK_DIR_NAME)
        assertEquals(
            AssetSourceState.Bundled,
            AssetSourceProbe.of(
                File(File(cacheRoot, "fallback"), "car.glb"),
                hasApiKey = true,
                loaded = true,
            ),
        )
    }
}
