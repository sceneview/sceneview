package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the `isEditable` gate on AR node editable flags.
 *
 * ## What broke
 *
 * [Node][io.github.sceneview.node.Node] declares every `is*Editable` flag with a custom
 * getter — `get() = isEditable && field` — so no gesture edits a node unless the caller
 * opted in with `isEditable = true`. [io.github.sceneview.ar.node.AnchorNode] overrode
 * `isPositionEditable` as a **plain field** (`override var isPositionEditable = true`),
 * which replaces the getter and silently drops the gate: every `AnchorNode` answered move
 * gestures even with `isEditable = false`. `onMoveBegin` then detached the anchor and
 * `onMoveEnd` re-anchored it, so a stray drag on a non-editable placed model detached its
 * anchor mid-session — on the ar-instant-placement rig this raced the demo's lost-anchor
 * reconciliation (#1184) and killed placed models with ARCore "already removed/detached"
 * warning bursts.
 *
 * ## Why a source contract rather than a behavioral test
 *
 * The behavioral half of the gate is already pinned by `NodeGestureTest`
 * (`isPositionEditable_requiresIsEditable`, androidTest) on a live `Node`. What cannot be
 * tested behaviorally here is the *subclass override*: constructing an `AnchorNode`
 * requires a real ARCore [com.google.ar.core.Anchor] (final, native-backed, only issued by
 * a tracking `Session`), which neither a JVM test nor a plain emulator instrumentation
 * test can produce. The defect, however, is a build-time fact — an override either
 * re-declares the gate or it does not — so this test reads it from the source of truth,
 * the same idiom as `PlaneVisualizerAttributeContractTest`.
 *
 * It generalises: any `override var is*Editable` added to an AR node source file without
 * re-declaring `get() = isEditable && field` fails here, except overrides of `isEditable`
 * itself (the gate variable, plain by design — see `LightNode` / `CameraNode`).
 */
class AnchorNodeEditableGateContractTest {

    /** JVM tests run with the module directory as CWD. */
    private val nodeSources = File("src/main/java/io/github/sceneview/ar/node")
        .listFiles { file -> file.extension == "kt" }!!
        .associate { it.name to it.readText() }

    /** Matches a plain-field editable-flag override NOT followed by the gating getter. */
    private val overrideRegex =
        Regex("""override\s+var\s+(is(?:Position|Rotation|Scale)Editable)[^\n]*\n(\s*get\(\)\s*=\s*isEditable\s*&&\s*field)?""")

    @Test
    fun `every editable flag override keeps the isEditable gate`() {
        val ungated = nodeSources.flatMap { (name, source) ->
            overrideRegex.findAll(source)
                .filter { it.groupValues[2].isEmpty() }
                .map { "$name: ${it.groupValues[1]}" }
        }
        assertTrue(
            "Editable-flag override(s) drop Node's `get() = isEditable && field` gate " +
                "(a plain `override var` replaces the custom getter): $ungated",
            ungated.isEmpty()
        )
    }

    @Test
    fun `AnchorNode declares the gated isPositionEditable override`() {
        // The generic scan above passes vacuously if the override is deleted outright;
        // this pins that AnchorNode still opts position editing in, gated.
        val anchorNode = nodeSources.getValue("AnchorNode.kt")
        assertTrue(
            "AnchorNode.kt must override isPositionEditable with the isEditable gate",
            Regex("""override\s+var\s+isPositionEditable\s*=\s*true\s*\n\s*get\(\)\s*=\s*isEditable\s*&&\s*field""")
                .containsMatchIn(anchorNode)
        )
    }

    @Test
    fun `AnchorNode move handlers stay guarded by isPositionEditable`() {
        // The gate only protects the anchor if onMoveBegin/onMoveEnd consult it before
        // detaching/re-creating the anchor.
        val anchorNode = nodeSources.getValue("AnchorNode.kt")
        for (handler in listOf("onMoveBegin", "onMoveEnd")) {
            val body = anchorNode.substringAfter("override fun $handler")
                .substringBefore("override fun")
            assertTrue(
                "AnchorNode.$handler must guard anchor detach/re-anchor with isPositionEditable",
                "if (isPositionEditable)" in body
            )
        }
    }
}
