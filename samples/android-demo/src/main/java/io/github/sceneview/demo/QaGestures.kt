package io.github.sceneview.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.sqrt

/**
 * QA only (debug builds): gestures `adb shell input` cannot make — a pinch, a double tap, a
 * two-finger tap — played on the app's window as real [MotionEvent]s, so a device with no
 * multi-touch injection still films them (`camera.md`: the scan result's camera is judged on a
 * Pixel 4a video).
 *
 * ```
 * adb shell am broadcast -p io.github.sceneview.demo.qa -a io.github.sceneview.demo.QA_GESTURE \
 *     --es gesture pinch --ef scale 2.0      # fingers spread to twice their gap: zoom in
 * adb shell am broadcast ... --es gesture doubletap
 * adb shell am broadcast ... --es gesture twotap
 * ```
 *
 * `x` and `y` (shares of the window, default the middle of its upper part) place the gesture;
 * `ms` is a pinch's length. Never registered in a release build.
 */
internal object QaGestures {
    const val ACTION = "io.github.sceneview.demo.QA_GESTURE"

    /** One touch event of a gesture: [atMs] after its start, [action] on [pointers] (x, y in pixels). */
    data class Touch(val atMs: Long, val action: Int, val pointers: List<Pair<Float, Float>>)

    /**
     * Registers the receiver for [activity]'s life, in a debug build only. Exported, so `adb shell`
     * reaches it: what a debug build is for.
     */
    fun install(activity: ComponentActivity) {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val window = activity.window?.decorView ?: return
                val width = window.width.toFloat()
                val height = window.height.toFloat()
                if (width <= 0f || height <= 0f) return
                val x = intent.getFloatExtra("x", DEFAULT_X) * width
                val y = intent.getFloatExtra("y", DEFAULT_Y) * height
                val touches = when (intent.getStringExtra("gesture")) {
                    "pinch" -> pinch(
                        x, y,
                        fromGap = min(width, height) * PINCH_GAP,
                        scale = intent.getFloatExtra("scale", 2f),
                        durationMs = intent.getLongExtra("ms", PINCH_MS),
                    )
                    "doubletap" -> doubleTap(x, y)
                    "twotap" -> twoFingerTap(x, y, gap = min(width, height) * PINCH_GAP)
                    else -> return
                }
                play(touches) { window.dispatchTouchEvent(it) }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                activity.unregisterReceiver(receiver)
            }
        })
    }

    /**
     * Two fingers about ([x], [y]) on a diagonal, [fromGap] pixels apart, moved to [scale] times
     * that gap over [durationMs]: above 1 they spread (zoom in), under 1 they close. Their middle
     * stays put, so it reads as a pinch, never as a two-finger drag.
     */
    fun pinch(x: Float, y: Float, fromGap: Float, scale: Float, durationMs: Long = PINCH_MS): List<Touch> {
        fun fingers(gap: Float): List<Pair<Float, Float>> {
            val half = gap / 2f / SQRT2
            return listOf(x - half to y - half, x + half to y + half)
        }
        val start = fingers(fromGap)
        val steps = (durationMs / STEP_MS).coerceAtLeast(1L).toInt()
        val moves = (1..steps).map { i ->
            val gap = fromGap * (1f + (scale - 1f) * i / steps)
            Touch(DOWN_GAP_MS + i * STEP_MS, MotionEvent.ACTION_MOVE, fingers(gap))
        }
        val end = moves.last().pointers
        val last = moves.last().atMs
        return listOf(
            Touch(0, MotionEvent.ACTION_DOWN, start.take(1)),
            Touch(DOWN_GAP_MS, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), start),
        ) + moves + listOf(
            Touch(last + STEP_MS, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), end),
            Touch(last + 2 * STEP_MS, MotionEvent.ACTION_UP, end.take(1)),
        )
    }

    /** Two quick taps on ([x], [y]), inside the platform's double-tap window. */
    fun doubleTap(x: Float, y: Float): List<Touch> {
        val at = listOf(x to y)
        return listOf(
            Touch(0, MotionEvent.ACTION_DOWN, at),
            Touch(TAP_MS, MotionEvent.ACTION_UP, at),
            Touch(TAP_MS + TAP_GAP_MS, MotionEvent.ACTION_DOWN, at),
            Touch(2 * TAP_MS + TAP_GAP_MS, MotionEvent.ACTION_UP, at),
        )
    }

    /** Two fingers put down [gap] apart about ([x], [y]) and lifted at once, without moving. */
    fun twoFingerTap(x: Float, y: Float, gap: Float): List<Touch> {
        val half = gap / 2f
        val both = listOf(x - half to y, x + half to y)
        return listOf(
            Touch(0, MotionEvent.ACTION_DOWN, both.take(1)),
            Touch(DOWN_GAP_MS, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), both),
            Touch(TAP_MS, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), both),
            Touch(TAP_MS + DOWN_GAP_MS, MotionEvent.ACTION_UP, both.take(1)),
        )
    }

    private fun pointerAction(action: Int, index: Int): Int = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Posts [touches] on the main thread at their times, each one built as the platform would. */
    private fun play(touches: List<Touch>, dispatch: (MotionEvent) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val downTime = SystemClock.uptimeMillis() + LEAD_MS
        for (touch in touches) {
            val at = downTime + touch.atMs
            handler.postAtTime({
                val properties = Array(touch.pointers.size) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coords = Array(touch.pointers.size) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = touch.pointers[i].first
                        y = touch.pointers[i].second
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(
                    downTime, at, touch.action, touch.pointers.size, properties, coords,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                dispatch(event)
                event.recycle()
            }, at)
        }
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b

    private val SQRT2 = sqrt(2f)
    private const val DEFAULT_X = 0.5f
    private const val DEFAULT_Y = 0.42f
    private const val PINCH_GAP = 0.35f
    private const val PINCH_MS = 600L
    private const val STEP_MS = 16L
    private const val DOWN_GAP_MS = 20L
    private const val TAP_MS = 60L
    private const val TAP_GAP_MS = 90L
    private const val LEAD_MS = 50L
}
