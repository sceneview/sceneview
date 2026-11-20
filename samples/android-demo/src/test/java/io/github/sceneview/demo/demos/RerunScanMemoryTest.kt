package io.github.sceneview.demo.demos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanIntrinsics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunScanMemoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `TSDF survives capture to result and is forgotten when the demo leaves composition`() {
        var open by mutableStateOf(true)
        var result by mutableStateOf(false)
        lateinit var owner: RerunLiveModels.Owner
        val pack = pack(1)
        val tsdf = RerunTsdf()
        composeRule.setContent {
            if (open) {
                owner = rememberRerunLiveModels()
                // The same owner wraps capture and result, as ARRerunDemo does.
                if (result) Unit
            }
        }
        try {
            composeRule.runOnIdle { owner.keep(pack, tsdf) }
            composeRule.runOnIdle { result = true }
            composeRule.runOnIdle { assertSame(tsdf, RerunLiveModels.find(pack)) }
            composeRule.runOnIdle { open = false }
            composeRule.runOnIdle {
                assertNull(RerunLiveModels.find(pack))
                // A save outliving the closed demo must not put its 48 MiB back in the singleton.
                owner.keep(pack, tsdf)
                assertNull(RerunLiveModels.find(pack))
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `closing an old demo cannot forget a newer demo model`() {
        val first = RerunLiveModels.Owner()
        val second = RerunLiveModels.Owner()
        val newerPack = pack(2)
        val newer = RerunTsdf()
        try {
            first.keep(pack(1), RerunTsdf())
            second.keep(newerPack, newer)
            first.close()
            assertSame(newer, RerunLiveModels.find(newerPack))
            RerunModelSource.Live(newer, null).release()
            assertNull(RerunLiveModels.find(newerPack))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `discard forgets a transferred TSDF while a successful handoff keeps it for the result`() = runBlocking {
        val owner = RerunLiveModels.Owner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (keep in listOf(false, true)) {
                val live = RerunLiveModel(owner)
                val pack = pack(if (keep) 1 else 2)
                live.offer(
                    DepthFrame(2, 2, ShortArray(4), null, IntArray(4), 1f, 1f, 0f, 0f, DebugPose(0f, 0f, 0f)),
                    scope,
                )
                live.join()
                live.keepFor(pack)
                val tsdf = checkNotNull(RerunLiveModels.find(pack))
                live.close(keepTransferred = keep)
                if (keep) assertSame(tsdf, RerunLiveModels.find(pack)) else assertNull(RerunLiveModels.find(pack))
            }
        } finally {
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun `exit releases the retry journal and dense reader without clearing the trace under readers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = RerunLiveModels.Owner()
        val capture = ScanCapture(
            intrinsics = ScanIntrinsics(100f, 100f, 50f, 50f, 100, 100),
            textureIntrinsics = null,
            lens = ReplayLens(0.5f, 0.5f),
            scope = scope,
            modelOwner = owner,
            rawDepth = true,
        )
        try {
            capture.trace.addPose(0L, DebugPose(0f, 0f, 0f))
            capture.trace.addPoints(0L, floatArrayOf(0f, 0f, -1f))
            val reader = capture.trace
            capture.logMemory("start")
            assertTrue(capture.finish() != null)
            capture.logMemory("periodic")
            // The ScanMem lines are debug-only; the release checks below hold in both variants.
            if (BuildConfig.DEBUG) {
                val before = ShadowLog.getLogsForTag("SceneView/ScanMem").last().msg
                assertTrue(before, before.contains("finishedEvents=2"))
            }
            capture.close()
            capture.close()
            capture.releaseJob?.join()
            assertEquals(1, reader.mapPointCount)
            assertEquals(1, reader.observationCount)
            assertNull(capture.trace.journal)
            assertNull(capture.live.liveDense?.invoke())
            if (BuildConfig.DEBUG) {
                val logs = ShadowLog.getLogsForTag("SceneView/ScanMem").map { it.msg }
                val exit = logs.last { it.startsWith("exit used=") }
                assertTrue(exit, exit.startsWith("exit used="))
                assertTrue(exit, exit.contains(" max="))
                assertTrue(exit, exit.contains("journalEvents=0 journalBytes=0 finishedEvents=0 finishedBytes=0"))
                assertTrue(logs.any { it.startsWith("close-detach ms=") })
                assertTrue(logs.any { it.startsWith("close-cleanup ms=") && it.contains("DefaultDispatcher-worker") })
            }
        } finally {
            capture.close()
            owner.close()
            scope.cancel()
        }
    }

    private fun pack(id: Int) = RerunCapturePack(byteArrayOf(id.toByte()), ByteArray(0), ByteArray(0))
}
