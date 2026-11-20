package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.content.pm.ApplicationInfo
import org.robolectric.RuntimeEnvironment
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
    fun `saved room handoffs keep only the last TSDF for reopening in the same demo`() = runBlocking {
        val owner = RerunLiveModels.Owner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var previous: RerunCapturePack? = null
        try {
            repeat(3) { room ->
                val live = RerunLiveModel(owner)
                val pack = pack(room + 20)
                live.offer(
                    DepthFrame(2, 2, ShortArray(4), null, IntArray(4), 1f, 1f, 0f, 0f, DebugPose(0f, 0f, 0f)),
                    scope,
                )
                live.join()
                live.keepFor(pack)
                val tsdf = checkNotNull(RerunLiveModels.find(pack))
                // Successful saves all hand off this way: Open, NextRoom, FinishHome and saves
                // outliving the capture screen. No model screen needs to have opened yet.
                live.close(keepTransferred = true)
                previous?.let { assertNull(RerunLiveModels.find(it)) }
                val reopened = RerunModelSource.of(pack, null) as RerunModelSource.Live
                assertSame(tsdf, reopened.tsdf)
                previous = pack
            }
            owner.close()
            assertNull(RerunLiveModels.find(previous))
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
                assertTrue(before, before.contains("depthAccepted=0 depthBusy=0 depthStale=0 tsdfDropped=0"))
            }
            val thumbnail = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            (capture.live.thumbnails as MutableMap<String, Bitmap>)["test.jpg"] = thumbnail
            capture.close()
            capture.close()
            capture.releaseJob?.join()
            assertEquals(1, reader.mapPointCount)
            assertEquals(1, reader.observationCount)
            assertNull(capture.trace.journal)
            assertTrue(capture.live.thumbnails.isEmpty())
            assertTrue("Compose may still draw the closed scan's thumbnail", !thumbnail.isRecycled)
            assertNull(capture.live.liveDense?.invoke())
            if (BuildConfig.DEBUG) {
                val logs = ShadowLog.getLogsForTag("SceneView/ScanMem").map { it.msg }
                val exit = logs.last { it.startsWith("exit used=") }
                assertTrue(exit, exit.startsWith("exit used="))
                assertTrue(exit, exit.contains(" max="))
                assertTrue(exit, exit.contains("depthAccepted=0 depthBusy=0 depthStale=0 tsdfDropped=0"))
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

    @Test
    fun `demo opts into a large heap`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertTrue(flags and ApplicationInfo.FLAG_LARGE_HEAP != 0)
    }

    @Test
    fun `stale semantics click drops its callback when the screen closes`() {
        var open by mutableStateOf(true)
        var tapped = 0
        lateinit var staleClick: RerunClick
        composeRule.setContent {
            if (open) staleClick = rememberRerunClick { tapped++ }
        }
        composeRule.runOnIdle {
            staleClick()
            assertEquals(1, tapped)
            open = false
        }
        composeRule.runOnIdle {
            assertNull(staleClick.action)
            staleClick()
            assertEquals(1, tapped)
        }
    }

    @Test
    fun `a small TSDF stays for a retry until its owner closes`() {
        for (fails in listOf(false, true)) {
            val owner = RerunLiveModels.Owner()
            val pack = pack(if (fails) 10 else 11)
            val tsdf = RerunTsdf()
            owner.keep(pack, tsdf)
            val source = RerunModelSource.Live(tsdf, null)
            try {
                runCatching { buildRerunModel(source) { if (fails) error("cancel extraction") } }
                    .also { assertEquals(!fails, it.isSuccess) }
                // A retry with another method still meshes the scan's own depth, not its surfels.
                assertSame(tsdf, source.tsdf)
                assertSame(tsdf, RerunLiveModels.find(pack))
            } finally {
                owner.close()
            }
            // Save, Discard and exit close the owner: nothing keeps the volume past the demo.
            assertNull(RerunLiveModels.find(pack))
        }
    }

    private fun pack(id: Int) = RerunCapturePack(byteArrayOf(id.toByte()), ByteArray(0), ByteArray(0))
}
