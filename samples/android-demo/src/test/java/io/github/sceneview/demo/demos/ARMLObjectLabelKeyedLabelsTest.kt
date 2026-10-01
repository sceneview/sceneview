package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ML Object Label was reported to crash once about four or more objects were on screen.
 *
 * The demo caps its labels at six and evicts the oldest. Its label loop was not keyed, so
 * evicting index 0 shifted every remaining label down one composition slot. Each slot's
 * `AnchorNode` is `remember(engine, anchor)`, so all of them were rebuilt and the old ones
 * destroyed, and `Node.destroy()` destroys its children. The child `BillboardNode` slot is
 * `remember(bitmap)`, and labels share cached bitmaps, so the child survived in Compose
 * already destroyed, and was destroyed a second time when its slot finally left. By then
 * its Filament entity id may have been recycled to another node. With fewer than six labels
 * nothing is ever evicted, which fits a crash that needs several objects. (Most likely cause;
 * the device run that confirms it is still owed.)
 *
 * This test replays that lifecycle with stand-ins that follow the `NodeLifecycle` contract
 * (attach on enter, detach + destroy on leave, a parent destroys its children), because the
 * real nodes need Filament and ARCore. The source check pins the demo's loop to the keyed
 * form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ARMLObjectLabelKeyedLabelsTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeNode(val name: String, val log: MutableList<String>) {
        val children = mutableListOf<FakeNode>()
        var destroyed = false
        fun destroy() {
            log += if (destroyed) "double-destroy $name" else "destroy $name"
            destroyed = true
            children.toList().forEach { it.destroy() }
            children.clear()
        }
    }

    private data class Label(val anchor: String, val bitmap: String)

    /** Parent node keyed on the anchor, one child keyed on the bitmap, as in the demo. */
    @Composable
    private fun LabelNode(label: Label, log: MutableList<String>) {
        val parent = remember(label.anchor) { FakeNode("anchor ${label.anchor}", log) }
        DisposableEffect(parent) { onDispose { parent.destroy() } }
        val child = remember(label.bitmap) { FakeNode("billboard of ${label.anchor}", log) }
        DisposableEffect(child) {
            parent.children += child
            onDispose {
                parent.children -= child
                child.destroy()
            }
        }
    }

    private fun evictOldest(keyed: Boolean): List<String> {
        val log = mutableListOf<String>()
        // Six labels sharing one cached bitmap: the usual "Home good" scene.
        val labels = mutableStateListOf<Label>().apply {
            repeat(6) { add(Label(anchor = "a$it", bitmap = "Home good@70")) }
        }
        rule.setContent {
            // The `if` stays outside the loop: a `key` nested under a per-item `if` group would
            // be positional again, which is not the shape the demo has.
            if (keyed) {
                labels.forEach { label -> key(label.anchor) { LabelNode(label, log) } }
            } else {
                labels.forEach { label -> LabelNode(label, log) }
            }
        }
        rule.runOnIdle {
            labels += Label(anchor = "a6", bitmap = "Home good@70")
            labels.removeAt(0)
        }
        rule.waitForIdle()
        // Leaving the screen (or a label's bitmap changing) disposes the child slots.
        rule.runOnIdle { labels.clear() }
        rule.waitForIdle()
        return log
    }

    @Test
    fun `unkeyed loop destroys surviving labels twice when the oldest is evicted`() {
        val log = evictOldest(keyed = false)
        assertTrue("Expected the unkeyed loop to double-destroy, log: $log", log.any { "double-destroy" in it })
    }

    @Test
    fun `keyed loop destroys every node exactly once`() {
        val log = evictOldest(keyed = true)
        assertTrue("No node may be destroyed twice, log: $log", log.none { "double-destroy" in it })
        // a0..a6: seven anchors and seven billboards, each destroyed once.
        assertEquals(log.toString(), 14, log.size)
    }

    @Test
    fun `the demo keys its label loop on the anchor`() {
        val source = File("src/main/java/io/github/sceneview/demo/demos/ARMLObjectLabelDemo.kt").readText()
        assertTrue(
            "ARMLObjectLabelDemo must wrap each label in key(entry.anchor)",
            Regex("""detections\.forEach\s*\{\s*entry\s*->\s*key\(entry\.anchor\)""").containsMatchIn(source)
        )
    }
}
