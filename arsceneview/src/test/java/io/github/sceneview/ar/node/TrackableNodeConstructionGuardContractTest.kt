package io.github.sceneview.ar.node

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for #2624, the init-time open-dispatch regression behind the v4.21.0
 * `ShadowReceiverPlaneNode` crash. ARCore trackables and Filament nodes are native-bound.
 */
class TrackableNodeConstructionGuardContractTest {

    private val guardedNodes = mapOf(
        "PlaneNode" to "plane",
        "AugmentedImageNode" to "augmentedImage",
        "AugmentedFaceNode" to "augmentedFace",
        "StreetscapeGeometryNode" to "streetscapeGeometry",
    )

    @Test
    fun `native trackable nodes defer subclass state until construction completes`() {
        guardedNodes.forEach { (name, initialTrackable) ->
            val source = File("src/main/java/io/github/sceneview/ar/node/$name.kt")
                .readText()
                .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
                .replace(Regex("""\s+"""), "")
            val initialization = source.substringAfter("init{").substringBefore("}")
            val assignment = initialization.indexOf("trackable=$initialTrackable")
            val publication = initialization.indexOf("constructed=true")
            val apply = initialization.indexOf("applyTrackableState()")
            assertTrue(
                "$name must publish construction after assigning its trackable",
                assignment >= 0 && publication > assignment,
            )
            assertTrue("$name must reapply state after construction", apply > publication)

            val update = source.substringAfter("overridefunupdate(trackable:")
                .substringBefore("privatefunapplyTrackableState")
            val baseUpdate = update.indexOf("super.update(trackable)")
            val guard = update.indexOf("if(!constructed)return")
            assertTrue(
                "$name must guard subclass state after its base update",
                baseUpdate >= 0 && guard > baseUpdate,
            )
            assertTrue(
                "$name must apply trackable state after the guard",
                update.indexOf("applyTrackableState()") > guard,
            )
        }
    }
}
