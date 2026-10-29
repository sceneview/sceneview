package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.sceneview.demo.demos.internal.PlanMeasure
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.SurveyGap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** How long a room's measure usually takes at most: past it, the plan says it is still measuring. */
internal const val PLAN_MEASURE_MS = 20_000L

/**
 * [media]'s survey, measured off the main thread once [wanted] ([measureRoom]), `null` while it
 * is being read; [PlanMeasure.overdue] once that has taken longer than [PLAN_MEASURE_MS]. A long
 * measure says it is still under way, and its plan shows as soon as it ends.
 */
@Composable
internal fun rememberRoomSurvey(media: RerunReplayMedia?, wanted: Boolean): PlanMeasure {
    val survey by produceState<RoomSurvey?>(null, media, wanted) {
        value = null
        if (media == null || !wanted) return@produceState
        value = measureRoom(media)
    }
    val overdue by produceState(false, media, wanted) {
        value = false
        if (media == null || !wanted) return@produceState
        delay(PLAN_MEASURE_MS)
        value = true
    }
    return PlanMeasure(survey, overdue)
}

/**
 * [media]'s [survey], or a survey that says the room was not measured ([SurveyGap.Unmeasured])
 * when the measure failed, out of memory included: a long scan's grid is the largest thing the
 * plan builds. A measure takes as long as it takes; [rememberRoomSurvey] says when it is long.
 *
 * Scans are measured one at a time, on a thread of their own ([RoomSurveys]): the scan's model and
 * pictures never hold a measure up, and two grids are never in memory at once (#4675). A measure
 * runs apart from its callers: the export sheet and the plan asking for the scan that is open wait
 * on the same one, and closing the sheet does not throw it away. A scan is known by its instance:
 * one opened again, or the saved room the home's summary reads, is measured again, in the same
 * queue. A measure nobody waits for any more is dropped once another scan is measured, and every
 * one when the demo closes ([ForgetRoomSurveysOnExit]); a dropped one stops between two steps of
 * its loops and gives the thread back. A failed one is started again.
 */
internal suspend fun measureRoom(
    media: RerunReplayMedia,
    survey: (RerunReplayMedia) -> RoomSurvey = ::surveyOf,
): RoomSurvey {
    val measure = RoomSurveys.join(media, survey)
    val measured = try {
        // The caller leaving cancels this wait; a measure dropped while its caller still waits
        // (the demo closing) ends on no survey.
        measure.survey.join()
        if (measure.survey.isCancelled) null else measure.survey.await()
    } finally {
        RoomSurveys.leave(measure)
    }
    return measured ?: RoomSurvey.empty(SurveyGap.Unmeasured)
}

/** Drops every scan's measure when the demo closes: nothing shows them any more. */
@Composable
internal fun ForgetRoomSurveysOnExit() {
    DisposableEffect(Unit) { onDispose { RoomSurveys.forget() } }
}

/**
 * The scans' measures, under way or ended, newest last, run one at a time on a thread that ends
 * when there is nothing to measure. Each holds its scan weakly: a scan nobody shows any more is not
 * kept for its measure, only its survey is.
 */
internal object RoomSurveys {
    private val thread = ThreadPoolExecutor(1, 1, IDLE_S, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
    private val measures = ArrayList<Measure>()

    /** One scan's measure; [survey] is `null` when it failed. */
    class Measure(scan: RerunReplayMedia) {
        val scan = WeakReference(scan)
        lateinit var survey: Deferred<RoomSurvey?>
        var waiting = 0

        @Volatile
        var failed = false
    }

    /**
     * [media]'s measure, started unless one is under way or ended well, and one more caller
     * waiting on it. The other measures nobody waits for give way to it.
     */
    @Synchronized
    fun join(media: RerunReplayMedia, survey: (RerunReplayMedia) -> RoomSurvey): Measure {
        val measure = measures.firstOrNull { it.scan.get() === media && !it.failed && !it.survey.isCancelled }
            ?: Measure(media).also { it.survey = start(it, media, survey) }
        measures.remove(measure)
        measures.add(measure)
        measure.waiting++
        measures.removeAll { other ->
            (other !== measure && other.waiting == 0).also { dropped -> if (dropped) other.survey.cancel() }
        }
        return measure
    }

    /** One caller less on [measure]: the last scan's is kept for its next caller, an older one given up. */
    @Synchronized
    fun leave(measure: Measure) {
        measure.waiting--
        if (measure.waiting == 0 && measure !== measures.lastOrNull()) {
            measure.survey.cancel()
            measures.remove(measure)
        }
    }

    /** Gives up every measure: the demo has closed. */
    @Synchronized
    fun forget() {
        measures.forEach { it.survey.cancel() }
        measures.clear()
    }

    private fun start(measure: Measure, media: RerunReplayMedia, survey: (RerunReplayMedia) -> RoomSurvey) =
        System.nanoTime().let { asked ->
            scope.async {
                val began = System.nanoTime()
                val points = media.dense?.cloud?.count ?: 0
                try {
                    runInterruptible { survey(media) }.also {
                        Log.i(PLAN_LOG_TAG, "Room measure: queued ${millis(asked, began)} ms, " +
                            "measured ${millis(began, System.nanoTime())} ms, $points points")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    measure.failed = true
                    Log.w(PLAN_LOG_TAG, "Room measure failed ($points points)", e)
                    null
                } catch (e: OutOfMemoryError) {
                    measure.failed = true
                    Log.w(PLAN_LOG_TAG, "Room measure ran out of memory ($points points)", e)
                    null
                }
            }
        }

    private fun millis(from: Long, to: Long) = TimeUnit.NANOSECONDS.toMillis(to - from)

    /** How long the measuring thread waits for another scan before it ends. */
    private const val IDLE_S = 30L
    private const val THREAD_NAME = "room-measure"
}

private const val PLAN_LOG_TAG = "RerunPlan"
