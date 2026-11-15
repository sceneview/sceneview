package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The live render loop needs native ARCore and Filament; pin its upload order at the call site. */
class GroundContactFrameOrderTest {

    @Test
    fun `ground contacts upload after all node movement and before presentation`() {
        val source = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt").readText()
        val update = source.substringAfter("val presented = sceneRenderer.renderFrame(")
            .substringBefore("// Pay the surface's debt")
        val stages = listOf(
            "onARFrame(",
            "notifyHostOfFrame(",
            "frameDispatch.dispatch(",
            "gestureDetector.advanceInertia(",
            "updateGroundContact(",
            "frameRateGate.shouldRender(",
        )
        var previous = -1
        for (stage in stages) {
            val index = update.indexOf(stage)
            assertTrue("$stage must run in the render update after the preceding stage", index > previous)
            previous = index
        }
        // An extra upload in onARFrame would sample providers early, before custom placement moves.
        assertEquals(1, Regex("""\bupdateGroundContact\(""").findAll(source).count())
    }
}
