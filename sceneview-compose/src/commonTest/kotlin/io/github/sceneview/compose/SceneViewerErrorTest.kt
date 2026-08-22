package io.github.sceneview.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An app's `onError` handler must be testable without a renderer (#3051): this file is
 * the proof that the type reaching it can be built from app code, in common code, with
 * nothing but a message.
 */
class SceneViewerErrorTest {

    @Test
    fun app_code_can_construct_one_with_a_message_only() {
        val error = SceneViewerError("loading asset 'models/helmet.glb'")
        assertEquals("loading asset 'models/helmet.glb'", error.message)
        assertNull(error.cause)
        assertEquals("SceneViewerError(loading asset 'models/helmet.glb')", error.toString())
    }

    @Test
    fun cause_is_carried_through() {
        val cause = IllegalStateException("boom")
        val error = SceneViewerError("downloading https://x/m.glb", cause)
        assertSame(cause, error.cause)
        assertEquals("SceneViewerError(downloading https://x/m.glb, cause=$cause)", error.toString())
    }

    @Test
    fun a_handler_can_be_exercised_without_a_renderer() {
        var retryShown = false
        val onError: (SceneViewerError) -> Unit = { retryShown = true }
        onError(SceneViewerError("parsing 12 in-memory bytes"))
        assertTrue(retryShown)
    }
}
