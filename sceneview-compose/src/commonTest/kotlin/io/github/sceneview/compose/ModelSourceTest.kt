package io.github.sceneview.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scheme restriction on [ModelSource.Url] lives in `commonMain` rather than in each
 * platform's downloader, so an app that forwards a user-supplied string gets the same
 * refusal everywhere. These tests are what keeps it there: an implementation that only
 * validated inside the Android fetcher would still pass a `file://` through on iOS.
 */
class ModelSourceTest {

    @Test
    fun url_accepts_http_and_https() {
        assertEquals("http://example.com/m.glb", ModelSource.Url("http://example.com/m.glb").url)
        assertEquals("https://example.com/m.glb", ModelSource.Url("https://example.com/m.glb").url)
    }

    @Test
    fun url_accepts_any_case_of_scheme() {
        ModelSource.Url("HTTPS://example.com/m.glb")
        ModelSource.Url("Http://example.com/m.glb")
    }

    @Test
    fun url_rejects_file_scheme() {
        // The one that matters: a `file://` slipped into a forwarded deep link would
        // otherwise turn a model URL into a local-file read.
        assertFailsWith<IllegalArgumentException> { ModelSource.Url("file:///etc/passwd") }
    }

    @Test
    fun url_rejects_other_schemes_and_relative_paths() {
        listOf(
            "ftp://example.com/m.glb",
            "jar:file:///a.jar!/m.glb",
            "content://media/external/file/1",
            "javascript:alert(1)",
            "models/m.glb",
            "//example.com/m.glb",
            "",
        ).forEach { candidate ->
            assertFailsWith<IllegalArgumentException>("should have rejected '$candidate'") {
                ModelSource.Url(candidate)
            }
        }
    }

    @Test
    fun url_rejects_a_string_that_merely_contains_http() {
        // The check is anchored, so an attacker-controlled prefix cannot smuggle a
        // different scheme past it.
        assertFailsWith<IllegalArgumentException> { ModelSource.Url("file:///x#http://a.com/") }
    }

    @Test
    fun url_requires_an_authority() {
        assertFailsWith<IllegalArgumentException> { ModelSource.Url("http://") }
    }

    // ── Bytes equality ──────────────────────────────────────────────────────
    // `ModelSource` is a recomposition key, so `Bytes` must compare by content:
    // the default data-class identity comparison would reload the model on every
    // recomposition even when the bytes never changed.

    @Test
    fun bytes_compares_by_content_not_identity() {
        val a = ModelSource.Bytes(byteArrayOf(1, 2, 3))
        val b = ModelSource.Bytes(byteArrayOf(1, 2, 3))
        assertTrue(a == b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun bytes_with_different_content_are_not_equal() {
        assertFalse(ModelSource.Bytes(byteArrayOf(1, 2, 3)) == ModelSource.Bytes(byteArrayOf(1, 2, 4)))
    }
}
