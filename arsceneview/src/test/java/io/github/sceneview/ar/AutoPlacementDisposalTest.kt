package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text pin for the anchor released when [AutoPlacementScene] leaves the composition.
 *
 * The `DisposableEffect` is keyed on the placement state alone — it must be, or a placement would
 * tear its own anchor down. That makes its `onDispose` lambda capture whatever `placement` held
 * when the effect was created, which is `null` on the composition that precedes any placement. The
 * wrapper-owned ARCore anchor then survives navigation until the whole session is torn down, and
 * nothing else releases it when the caller's content composes no `AnchorNode` of its own.
 *
 * Reading the value through `rememberUpdatedState` is the fix, so this test pins that the disposal
 * path reads the updated holder and never the raw `placement` variable again.
 *
 * Same source-grep strategy as [ARTypedConfigModesTest]: driving a real composition here would
 * need an ARCore session, and reflection over a `@Composable` is brittle.
 */
class AutoPlacementDisposalTest {

    private val sceneFile = File("src/main/java/io/github/sceneview/ar/AutoPlacementScene.kt")

    private val source: String by lazy {
        assertTrue(
            "Expected to find ${sceneFile.absolutePath} — JVM test must run from arsceneview module root.",
            sceneFile.exists(),
        )
        sceneFile.readText()
    }

    private val disposeBlock: String by lazy {
        val start = source.indexOf("DisposableEffect(state)")
        assertTrue("AutoPlacementScene must dispose its placement state.", start >= 0)
        val open = source.indexOf("onDispose {", start)
        assertTrue("DisposableEffect(state) must declare an onDispose block.", open >= 0)
        val end = source.indexOf("\n    }", open)
        assertTrue("Could not delimit the onDispose block.", end > open)
        source.substring(open, end)
    }

    @Test
    fun `the latest placement is exposed through rememberUpdatedState`() {
        assertTrue(
            "The placement read at disposal must come from rememberUpdatedState, otherwise the " +
                "disposal lambda captures the pre-placement value.",
            Regex("""val\s+currentPlacement\s+by\s+rememberUpdatedState\(\s*placement\s*\)""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun `disposal detaches the anchor of the current placement`() {
        assertTrue(
            "onDispose must detach the anchor of the current placement: was <$disposeBlock>",
            Regex("""currentPlacement\?\.anchor\?\.detach\(\)""").containsMatchIn(disposeBlock),
        )
        assertTrue(
            "onDispose must still dismiss the placement state: was <$disposeBlock>",
            disposeBlock.contains("state.dismiss()"),
        )
    }

    @Test
    fun `disposal never reads the captured placement variable`() {
        assertFalse(
            "onDispose must not read the raw `placement` variable — that value is captured when " +
                "the effect is created, so the anchor would leak: was <$disposeBlock>",
            disposeBlock.replace("currentPlacement", "CURRENT").contains("placement?.anchor"),
        )
    }
}
