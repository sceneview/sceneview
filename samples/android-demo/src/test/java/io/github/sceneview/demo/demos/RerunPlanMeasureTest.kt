package io.github.sceneview.demo.demos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.PlanMeasure
import io.github.sceneview.demo.demos.internal.PlanOffer
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.RoomSurveyInput
import io.github.sceneview.demo.demos.internal.RoomSurveyor
import io.github.sceneview.demo.demos.internal.SurveyGap
import io.github.sceneview.demo.demos.internal.planSurvey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The export sheet's "Floor plan" row ends: on the plan or on why there is none, never on
 * "Measuring the room…" for good — even when every thread of the app's pool is busy building
 * the scan's model. A measure that takes long says it is still under way, then shows its plan;
 * only one that fails says the room was not measured. Scans are measured one at a time, and a
 * measure nobody waits for any more gives way to the next, or to the demo closing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunPlanMeasureTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A scan with a dense cloud: what the export sheet measures for its plan. */
    private fun scan() = RerunReplayMedia(
        trace = ArDebugTrace(),
        manifest = ReplayManifest(ReplayLens.Default, frameRate = 1f, frameCount = 0, floorY = null, textures = emptyList()),
        planeBitmaps = emptyMap(),
        thumbnails = emptyMap(),
        archive = ByteArray(0),
        dense = ReplayDenseLayer(
            cloud = DenseCloud(floatArrayOf(0f, 0f, 0f), intArrayOf(0), normals = floatArrayOf(0f, 1f, 0f)),
            voxelM = VOXEL_M,
            mesh = DebugMesh(),
            atlas = ByteArray(0),
        ),
    )

    /**
     * The surfels of an empty [ROOM_M] square room — its floor and four walls, [STEP_M] apart —
     * and a camera at its middle: a cloud the survey reads through, loop after loop.
     */
    private fun room(): RoomSurveyInput {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            positions += listOf(x, y, z)
            normals += listOf(nx, ny, nz)
        }
        val across = (ROOM_M / STEP_M).toInt()
        val high = (WALL_M / STEP_M).toInt()
        for (i in 0 until across) {
            val a = (i + 0.5f) * STEP_M
            for (j in 0 until across) add(a, 0f, (j + 0.5f) * STEP_M, 0f, 1f, 0f)
            for (h in 0 until high) {
                val y = (h + 0.5f) * STEP_M
                add(a, y, 0f, 0f, 0f, 1f)
                add(a, y, ROOM_M, 0f, 0f, -1f)
                add(0f, y, a, 1f, 0f, 0f)
                add(ROOM_M, y, a, -1f, 0f, 0f)
            }
        }
        val middle = ROOM_M / 2
        val trail = floatArrayOf(middle, EYE_M, middle, middle + STEP_M, EYE_M, middle)
        return RoomSurveyInput(positions.toFloatArray(), normals.toFloatArray(), trail)
    }

    /**
     * Holds every thread of [Dispatchers.Default] until the returned latch is released (or
     * [HOLD_S] pass): blockers are added until one no longer starts, so the pool is full.
     */
    private fun occupyDefault(): CountDownLatch {
        val release = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Default)
        while (true) {
            val started = CountDownLatch(1)
            scope.launch {
                started.countDown()
                release.await(HOLD_S, TimeUnit.SECONDS)
            }
            if (!started.await(START_MS, TimeUnit.MILLISECONDS)) return release
        }
    }

    @Test
    fun `the floor plan row ends on the room's survey even while every thread of the app's pool is busy`() {
        val release = occupyDefault()
        try {
            val media = scan()
            var offer: PlanOffer? = null
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent { offer = PlanOffer.of(rememberRoomSurvey(media, wanted = true)) }
            // The measure runs on a thread of its own: it ends while the pool is still held.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(ENDED_S)
            while (offer != PlanOffer.NoDepth && System.nanoTime() < until) {
                composeRule.mainClock.advanceTimeByFrame()
                Thread.sleep(POLL_MS)
            }
            // One point is too little depth for a plan.
            assertEquals(PlanOffer.NoDepth, offer)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a measure that fails ends on a plan that says the room was not measured`() {
        val survey = runBlocking { measureRoom(scan()) { error("no grid") } }
        assertEquals(listOf(SurveyGap.Unmeasured), survey.gaps)
        assertEquals(PlanOffer.Failed, PlanOffer.of(survey))
    }

    @Test
    fun `a measure past its time says it is still measuring, then shows the plan once it ends`() {
        val media = scan()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val room = planSurvey(0f, 0f, ROOM_M, 0f, ROOM_M, ROOM_M, 0f, ROOM_M)
        // A first caller starts the measure: the sheet below waits on that same one.
        val first = thread {
            runBlocking {
                measureRoom(media) {
                    started.countDown()
                    gate.await(HOLD_S, TimeUnit.SECONDS)
                    room
                }
            }
        }
        try {
            assertTrue(started.await(HOLD_S, TimeUnit.SECONDS))
            var offer: PlanOffer? = null
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent { offer = PlanOffer.of(rememberRoomSurvey(media, wanted = true)) }
            composeRule.mainClock.advanceTimeBy(PLAN_MEASURE_MS)
            composeRule.mainClock.advanceTimeByFrame()
            // Past its time the measure is still under way: the row says so, not that it failed.
            assertEquals(PlanOffer.StillMeasuring, offer)
            gate.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_S)
            while (offer != PlanOffer.Ready && System.nanoTime() < until) {
                composeRule.mainClock.advanceTimeByFrame()
                Thread.sleep(POLL_MS)
            }
            assertEquals(PlanOffer.Ready, offer)
        } finally {
            gate.countDown()
            first.join()
        }
    }

    @Test
    fun `a measure says it is still measuring past its time, until its survey comes`() {
        assertEquals(PlanOffer.Measuring, PlanOffer.of(PlanMeasure(null, overdue = false)))
        assertEquals(PlanOffer.StillMeasuring, PlanOffer.of(PlanMeasure(null, overdue = true)))
        val late = RoomSurvey.empty(SurveyGap.NoWalls)
        assertEquals(PlanOffer.NoWalls, PlanOffer.of(PlanMeasure(late, overdue = true)))
        val failed = RoomSurvey.empty(SurveyGap.Unmeasured)
        assertEquals(PlanOffer.Failed, PlanOffer.of(PlanMeasure(failed, overdue = true)))
    }

    @Test
    fun `a measure that runs out of memory ends on a plan that says the room was not measured`() {
        val offer = runCatching { runBlocking { measureRoom(scan()) { throw OutOfMemoryError("grid") } } }
            .getOrNull()
            ?.let { PlanOffer.of(it) }
        assertEquals(PlanOffer.Failed, offer)
    }

    @Test
    fun `two scans are never measured at once, each measure holding a grid of its own`() {
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        val both = CountDownLatch(2)
        val survey = { _: RerunReplayMedia ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            // Two measures at once meet here; one at a time, the first waits out SHORT_MS alone.
            both.countDown()
            both.await(SHORT_MS, TimeUnit.MILLISECONDS)
            running.decrementAndGet()
            RoomSurvey.empty(SurveyGap.NoWalls)
        }
        runBlocking {
            val one = async(Dispatchers.IO) { measureRoom(scan(), survey = survey) }
            val other = async(Dispatchers.IO) { measureRoom(scan(), survey = survey) }
            awaitAll(one, other)
        }
        assertEquals(1, most.get())
    }

    @Test
    fun `a measure nobody waits for is dropped once another scan is measured`() {
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val ended = CountDownLatch(1)
        try {
            runBlocking {
                val sheet = launch(Dispatchers.IO) {
                    measureRoom(scan()) {
                        started.countDown()
                        try {
                            held.await(HOLD_S, TimeUnit.SECONDS)
                        } finally {
                            ended.countDown()
                        }
                        RoomSurvey.empty(SurveyGap.NoWalls)
                    }
                }
                assertTrue(started.await(HOLD_S, TimeUnit.SECONDS))
                // The sheet closes, then another scan is opened and measured.
                sheet.cancelAndJoin()
                measureRoom(scan()) { RoomSurvey.empty(SurveyGap.NoWalls) }
            }
            assertTrue("the first scan is still being measured", ended.await(ENDED_S, TimeUnit.SECONDS))
        } finally {
            held.countDown()
        }
    }

    @Test
    fun `a dropped measure busy computing gives the thread to the next scan`() {
        val room = room()
        val started = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        try {
            val next = runBlocking {
                val sheet = launch(Dispatchers.IO) {
                    measureRoom(scan()) {
                        started.countDown()
                        // Nothing here waits on anything: only the survey itself can see it was dropped.
                        while (!stop.get()) RoomSurveyor.survey(room)
                        RoomSurvey.empty(SurveyGap.NoWalls)
                    }
                }
                assertTrue(started.await(HOLD_S, TimeUnit.SECONDS))
                // The sheet closes, then another scan is opened and measured on the one thread.
                sheet.cancelAndJoin()
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(ENDED_S)) {
                    measureRoom(scan()) { RoomSurvey.empty(SurveyGap.NoDepth) }
                }
            }
            assertEquals("the next scan still waits for the thread", SurveyGap.NoDepth, next?.gaps?.first())
        } finally {
            stop.set(true)
        }
    }

    @Test
    fun `a measure still waited for is kept when another scan is measured`() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val measured = RoomSurvey.empty(SurveyGap.NoWalls)
        try {
            runBlocking {
                val waited = async(Dispatchers.IO) {
                    measureRoom(scan()) {
                        started.countDown()
                        gate.await(HOLD_S, TimeUnit.SECONDS)
                        measured
                    }
                }
                assertTrue(started.await(HOLD_S, TimeUnit.SECONDS))
                // Asked before the first one ends: undispatched, it is asked by the time this returns.
                val other = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    measureRoom(scan()) { RoomSurvey.empty(SurveyGap.NoDepth) }
                }
                gate.countDown()
                assertSame(measured, waited.await())
                other.await()
            }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `closing the demo drops the measure under way`() {
        val started = CountDownLatch(1)
        val held = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var open by mutableStateOf(true)
        composeRule.setContent { if (open) ForgetRoomSurveysOnExit() }
        try {
            val waiting = CoroutineScope(Dispatchers.IO).async {
                measureRoom(scan()) {
                    started.countDown()
                    try {
                        held.await(HOLD_S, TimeUnit.SECONDS)
                    } finally {
                        ended.countDown()
                    }
                    RoomSurvey.empty(SurveyGap.NoWalls)
                }
            }
            assertTrue(started.await(HOLD_S, TimeUnit.SECONDS))
            composeRule.runOnIdle { open = false }
            composeRule.waitForIdle()
            assertTrue("the scan is still being measured", ended.await(ENDED_S, TimeUnit.SECONDS))
            // Whoever still waited is told the room was not measured.
            assertEquals(listOf(SurveyGap.Unmeasured), runBlocking { waiting.await() }.gaps)
        } finally {
            held.countDown()
        }
    }

    @Test
    fun `the same scan asked twice is measured once, and its survey kept`() {
        val media = scan()
        val runs = AtomicInteger(0)
        val measured = RoomSurvey.empty(SurveyGap.NoWalls)
        val survey = { _: RerunReplayMedia -> runs.incrementAndGet(); measured }
        val first = runBlocking { measureRoom(media, survey = survey) }
        val again = runBlocking { measureRoom(media, survey = survey) }
        assertSame(measured, first)
        assertSame(measured, again)
        assertEquals(1, runs.get())
        assertEquals(PlanOffer.NoWalls, PlanOffer.of(first))
    }

    private companion object {
        const val VOXEL_M = 0.05f

        /** How long a measure waits for a second one running beside it. */
        const val SHORT_MS = 200L

        /** How long a dropped measure gets to end. */
        const val ENDED_S = 5L

        /** Between two frames while a measure ends on its own thread. */
        const val POLL_MS = 10L

        /** The side of the room the slow measure finds, and of the room [room] builds. */
        const val ROOM_M = 4f

        /** Between two surfels of [room], and the height of its walls. */
        const val STEP_M = 0.05f
        const val WALL_M = 2.5f

        /** The height the camera of [room] moves at. */
        const val EYE_M = 1.4f

        /** How long a blocker gets to start before the pool counts as full. */
        const val START_MS = 500L

        /** The longest a blocker holds its thread, should the test never release it. */
        const val HOLD_S = 30L
    }
}
