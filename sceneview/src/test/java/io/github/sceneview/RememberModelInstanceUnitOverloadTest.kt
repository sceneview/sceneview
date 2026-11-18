package io.github.sceneview

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.gltfio.AssetLoader
import io.github.sceneview.core.model.ModelUnit
import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.FilamentWithoutJni
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.Buffer
import java.util.Collections

/**
 * `rememberModelInstance(modelLoader, location, unit = …)` behaves exactly like
 * `rememberModelInstance(modelLoader, location)`: same `null`, same log, same load.
 *
 * The `unit` overload first went through `rememberModelLoadState`, i.e. `awaitModelInstance`: a
 * failure was `null` with **no log**, and a glTF requiring an extension gltfio cannot load was
 * refused before Filament saw it, where its neighbour logs it and loads it anyway. Each case below
 * runs both overloads in a headless composition over a Filament without JNI (its parse returns no
 * asset, so every result here is `null`; what differs is how the overloads got there).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [RememberModelInstanceUnitOverloadTest.RecordingAssetLoader::class],
)
class RememberModelInstanceUnitOverloadTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        parses.clear()
        ShadowLog.reset()
    }

    @Test
    fun `an unsupported format is null and logged with its type by both overloads`() = runTest {
        val location = fileLocation("part.fbx", "Kaydara FBX Binary")

        val plain = load(location) { loader -> rememberModelInstance(loader, location) }
        val plainLogs = unsupportedLogs()
        ShadowLog.reset()
        val withUnit = load(location) { loader ->
            rememberModelInstance(loader, location, unit = ModelUnit.Millimeters)
        }
        val unitLogs = unsupportedLogs()

        assertNull(plain)
        assertNull(withUnit)
        // `loadModelInstance` returns `null` for it (major version 4 keeps that contract) and
        // logs the typed refusal's message: nothing reaches the composable's own catch.
        assertEquals("The plain overload logs the failure once", 1, plainLogs.size)
        assertEquals("The unit overload logs the failure exactly as its neighbour does", 1, unitLogs.size)
        assertEquals(plainLogs.single().msg, unitLogs.single().msg)
        assertEquals("No failure escapes to the composable's catch", 0, failureLogs().size)
        assertEquals("Neither overload reaches Filament's parse for an unsupported format", 0, parses.size)
    }

    @Test
    fun `a glTF requiring an unsupported extension is logged and parsed by both overloads`() = runTest {
        val location = fileLocation(
            "avif.gltf",
            """{"asset":{"version":"2.0"},"extensionsUsed":["EXT_texture_avif"],""" +
                """"extensionsRequired":["EXT_texture_avif"]}""",
        )

        val plain = load(location) { loader -> rememberModelInstance(loader, location) }
        val plainParses = parses.size
        val plainWarnings = extensionWarnings()
        parses.clear()
        ShadowLog.reset()
        val withUnit = load(location) { loader ->
            rememberModelInstance(loader, location, unit = ModelUnit.Millimeters)
        }

        assertNull(plain)
        assertNull(withUnit)
        assertEquals("The plain overload loads such a glTF anyway: Filament parses it", 1, plainParses)
        assertEquals("The plain overload logs the unsupported extension", 1, plainWarnings)
        assertEquals(
            "The unit overload must load it the same way, not refuse it before Filament's parse",
            1,
            parses.size,
        )
        assertEquals("The unit overload logs the unsupported extension the same way", 1, extensionWarnings())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** Composes [content] and lets its load settle; the produced value once a log says it ended. */
    private fun TestScope.load(
        location: String,
        content: @Composable (ModelLoader) -> ModelInstance?,
    ): ModelInstance? {
        val loader = ModelLoader(FilamentWithoutJni.engine(), context)
        var produced: ModelInstance? = null
        Host(this, context) { produced = content(loader) }
            .settleUntil { ShadowLog.getLogs().any { location.substringAfterLast('/') in it.msg.orEmpty() } }
        // A last pass, so a value produced with the final log is composed.
        repeat(5) {
            Snapshot.sendApplyNotifications()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return produced
    }

    private fun failureLogs() = ShadowLog.getLogsForTag("rememberModelInstance")

    private fun unsupportedLogs() = ShadowLog.getLogsForTag("ModelLoader")
        .filter { UnsupportedModelFormat("fbx").message!! in it.msg.orEmpty() }

    private fun extensionWarnings() =
        ShadowLog.getLogsForTag("ModelLoader").count { "EXT_texture_avif" in it.msg.orEmpty() }

    private fun fileLocation(name: String, content: String): String {
        val dir = File.createTempFile("unit-overload", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val file = File(dir, name).apply {
            writeText(content)
            deleteOnExit()
        }
        return "file://${file.absolutePath}"
    }

    /** gltfio's loader with a non-zero handle (its constructor demands one), recording parses. */
    @Implements(AssetLoader::class)
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Robolectric shadows mirror native signatures
    class RecordingAssetLoader {
        companion object {
            @JvmStatic
            @Implementation
            fun nCreateAssetLoader(nativeEngine: Long, provider: Any?, nativeEntities: Long): Long = 1L

            @JvmStatic
            @Implementation
            fun nCreateAsset(nativeLoader: Long, buffer: Buffer, remaining: Int): Long {
                parses += Thread.currentThread()
                return 0L
            }
        }
    }

    /** A no-UI composition of [content], on the test's (main looper) thread. */
    private class Host(
        private val scope: TestScope,
        context: Context,
        content: @Composable () -> Unit,
    ) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)

        init {
            scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            Composition(NoNodes(), recomposer).setContent {
                CompositionLocalProvider(LocalContext provides context) { content() }
            }
        }

        /** Lets the load's hops (IO, Default, the main looper, back here) run, for up to 5 s. */
        fun settleUntil(done: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!done() && System.currentTimeMillis() < deadline) {
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        val parses: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
    }
}
