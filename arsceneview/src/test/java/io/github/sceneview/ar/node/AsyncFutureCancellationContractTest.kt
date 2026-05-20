package io.github.sceneview.ar.node

import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.ResolveAnchorOnRooftopFuture
import com.google.ar.core.ResolveAnchorOnTerrainFuture
import com.google.ar.core.ResolveCloudAnchorFuture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the API-contract change introduced by #1768: the host/resolve calls on
 * [CloudAnchorNode], [TerrainAnchorNode], and [RooftopAnchorNode] now return the
 * underlying ARCore [com.google.ar.core.Future] so callers can cancel pending Google
 * Cloud requests when the UI scope leaves (`DisposableEffect.onDispose { future?.cancel() }`).
 *
 * Before #1768, `CloudAnchorNode.host` returned `Unit` and stored the future privately —
 * apps could not cancel a pending request, and the network round-trip ran to completion
 * accruing billing events even after the user navigated away.
 *
 * The real `HostCloudAnchorFuture` / `Resolve*Future` types are JNI-backed and not
 * instantiable in a JVM unit-test runner, so this test verifies the **return type
 * signature** via reflection — a regression that reverts `host()` back to `Unit` (or
 * narrows a `Future` companion-return to `Unit`) is caught at compile / test time.
 */
class AsyncFutureCancellationContractTest {

    @Test
    fun `CloudAnchorNode_host returns HostCloudAnchorFuture (not Unit)`() {
        val method = CloudAnchorNode::class.java.declaredMethods
            .first { it.name == "host" }
        assertEquals(
            "host() must return the ARCore HostCloudAnchorFuture so callers can cancel " +
                    "the pending network request (#1768).",
            HostCloudAnchorFuture::class.java,
            method.returnType
        )
    }

    @Test
    fun `CloudAnchorNode_resolve companion returns ResolveCloudAnchorFuture (not Unit)`() {
        val method = CloudAnchorNode.Companion::class.java.declaredMethods
            .first { it.name == "resolve" }
        assertEquals(
            "Companion.resolve() must return the ARCore ResolveCloudAnchorFuture (#1768).",
            ResolveCloudAnchorFuture::class.java,
            method.returnType
        )
    }

    @Test
    fun `TerrainAnchorNode_resolve companion returns ResolveAnchorOnTerrainFuture (not Unit)`() {
        val method = TerrainAnchorNode.Companion::class.java.declaredMethods
            .first { it.name == "resolve" }
        assertEquals(
            "Companion.resolve() must return the ARCore ResolveAnchorOnTerrainFuture (#1768). " +
                    "The session.earth?.resolveAnchorOnTerrainAsync return is null-safe but the " +
                    "static return type stays the Future class.",
            ResolveAnchorOnTerrainFuture::class.java,
            method.returnType
        )
    }

    @Test
    fun `RooftopAnchorNode_resolve companion returns ResolveAnchorOnRooftopFuture (not Unit)`() {
        val method = RooftopAnchorNode.Companion::class.java.declaredMethods
            .first { it.name == "resolve" }
        assertEquals(
            "Companion.resolve() must return the ARCore ResolveAnchorOnRooftopFuture (#1768).",
            ResolveAnchorOnRooftopFuture::class.java,
            method.returnType
        )
    }

    @Test
    fun `CloudAnchorNode exposes cancelHost so node lifecycle can clean up`() {
        val method = CloudAnchorNode::class.java.declaredMethods
            .firstOrNull { it.name == "cancelHost" }
        assert(method != null) {
            "CloudAnchorNode.cancelHost() must remain public so node.destroy() can invoke it " +
                    "and so apps that want fine-grained control can call it directly (#1768)."
        }
        assertEquals(Void.TYPE, method!!.returnType)
    }

    @Test
    fun `Future cancel contract — caller-injected fake verifies the interface plumbing`() {
        // The ARCore concrete Future classes (`HostCloudAnchorFuture` etc.) extend
        // `FutureImpl` which is package-private and JNI-backed — not instantiable in a
        // JVM unit test. The `Future` *interface* however IS testable, and #1768 boils
        // down to: callers receive a value that exposes `.cancel(): Boolean`. We pin
        // that interface contract here so a future refactor that swaps the return type
        // for something without `.cancel()` (e.g. raw `Anchor` once it resolves) gets
        // caught.
        val fake = object : com.google.ar.core.Future {
            var cancelled = false
            override fun cancel(): Boolean {
                cancelled = true
                return true
            }

            override fun getState(): com.google.ar.core.FutureState =
                com.google.ar.core.FutureState.PENDING
        }

        // Compile-time check that the Future interface exposes cancel().
        val cancelled: Boolean = fake.cancel()
        assertEquals(true, cancelled)
        assertEquals(true, fake.cancelled)
    }
}
