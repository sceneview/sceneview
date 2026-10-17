package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the two facts about the AR session lifecycle that no JVM test can run:
 * `ARSceneView` needs a Filament engine to be composed, and `ARCore` needs a native ARCore
 * `Session`. Both are build-time facts, read from the source like
 * `AnchorNodeEditableGateContractTest` does. The behaviour behind them is tested where it
 * lives: [HostLifecycleTest] for the default, [CameraArbiterTest] for the camera sharing.
 */
class ARSessionLifecycleContractTest {

    /** JVM tests run with the module directory as CWD. */
    private fun source(name: String) = File("src/main/java/io/github/sceneview/ar/$name").readText()

    @Test
    fun `every ARSceneView overload defaults its lifecycle to the host activity`() {
        val sceneView = source("ARSceneView.kt")
        val defaults = Regex("""\blifecycle: Lifecycle = ([^,\n]+),""").findAll(sceneView)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("ARSceneView.kt declares no lifecycle parameter", defaults.isNotEmpty())
        assertEquals(List(defaults.size) { "rememberHostLifecycle()" }, defaults)
    }

    @Test
    fun `ARCore resumes, pauses and closes its session through the camera arbiter only`() {
        val arCore = source("ARCore.kt")

        // One call site each: `resume(context, handler)` and `retrySession` go through
        // `resumeSession()`, never straight to the session.
        assertEquals(1, Regex("""cameraArbiter\.resume\(cameraClient\)""").findAll(arCore).count())
        assertEquals(1, Regex("""cameraArbiter\.pause\(cameraClient\)""").findAll(arCore).count())
        assertEquals(1, Regex("""cameraArbiter\.destroy\(cameraClient\)""").findAll(arCore).count())

        // The only direct session calls are the ones the arbiter makes through its client.
        assertEquals(
            listOf("override fun start() { session?.resume() }"),
            arCore.lines().map(String::trim).filter { "session?.resume()" in it || "session.resume()" in it },
        )
        assertEquals(
            listOf("override fun stop() { session?.takeIf { it.isResumed }?.pause() }"),
            arCore.lines().map(String::trim).filter { "?.pause()" in it || "session.pause()" in it },
        )
    }
}
