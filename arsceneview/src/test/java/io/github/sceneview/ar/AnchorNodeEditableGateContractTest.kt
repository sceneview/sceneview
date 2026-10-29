package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for #1184. A live [io.github.sceneview.ar.node.AnchorNode] requires native ARCore,
 * and dropping this gate detached anchors after stray drags on non-editable nodes.
 */
class AnchorNodeEditableGateContractTest {

    @Test
    fun `AnchorNode keeps editing behind the isEditable gate`() {
        val source = File("src/main/java/io/github/sceneview/ar/node/AnchorNode.kt")
            .readText()
            .replace(Regex("""\s+"""), "")

        assertTrue(source.contains("overridevarisPositionEditable=trueget()=isEditable&&field"))
        for (handler in listOf("onMoveBegin", "onMoveEnd")) {
            val body = source.substringAfter("overridefun$handler")
                .substringBefore("overridefun")
            assertTrue("AnchorNode.$handler must consult isPositionEditable", body.contains("if(isPositionEditable)"))
        }
    }
}
