package app.autoscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Drives auto-scrolling by injecting long, slow swipes built from chained stroke segments.
 *
 * - The virtual finger stays down across segments, and the next segment is queued before the
 *   current one finishes so the system plays them back to back with no gaps.
 * - At the end of a swipe the finger lifts while still moving. The app's fling carries the content
 *   for the few milliseconds until the next swipe lands and catches it, so motion stays continuous.
 * - Pausing or reversing lifts after a near-motionless hold instead, so the content stops dead.
 * - A touch elsewhere on the screen pauses scrolling ("touch pause"). Depending on settings it
 *   resumes after a quiet period, or once the user scrolls on in the autoscroll direction.
 */
class ScrollController(private val service: AccessibilityService, private val prefs: Prefs) {

    fun interface Listener {
        fun onStateChanged()
    }

    var listener: Listener? = null

    var running = false
        private set

    /** Paused because the user touched the screen, as opposed to pausing from the controls. */
    var pausedByTouch = false
        private set

    /** Uptime at which a touch pause ends on its own, or 0 when it will not. */
    var resumeAt = 0L
        private set

    var directionDown = prefs.directionDown
        set(value) {
            if (field == value) return
            field = value
            prefs.directionDown = value
            liftRequested = true
            pump()
            listener?.onStateChanged()
        }

    var speed = prefs.speed
        set(value) {
            field = value.coerceIn(Prefs.MIN_SPEED, Prefs.MAX_SPEED)
        }

    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density

    private var held = false
    /** Last dispatched stroke if it leaves the finger down; null once a lift has been dispatched. */
    private var tail: StrokeDescription? = null
    private var inFlight = 0
    private var liftRequested = false
    private var generation = 0

    /** End point of the most recently dispatched segment. */
    private var x = 0f
    private var y = 0f
    private var endY = 0f
    private var strokeDown = true

    private var lastProgress = 0L
    private var sawScroll = false

    private val tracker = ScrollDirectionTracker(service)
    private var directionResumeArmed = false
    private var directionResumePending = false
    private var resumedByDirectionAt = 0L
    private var lastInjectedDownAt = 0L

    private val pumpRunnable = Runnable { pump() }
    private val resumeRunnable = Runnable { start() }
    private val settleRunnable = Runnable {
        resumedByDirectionAt = SystemClock.uptimeMillis()
        start()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkStalled()
            if (running) handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun start() {
        val wasTouchPaused = clearTouchPause()
        if (running) {
            if (wasTouchPaused) listener?.onStateChanged()
            return
        }
        running = true
        resetProgress()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        pump()
        listener?.onStateChanged()
    }

    fun pause() {
        val wasTouchPaused = clearTouchPause()
        if (!running) {
            if (wasTouchPaused) listener?.onStateChanged()
            return
        }
        running = false
        handler.removeCallbacks(watchdog)
        pump()
        listener?.onStateChanged()
    }

    /** A touch-down somewhere outside the controls. */
    fun onUserTouch() {
        val now = SystemClock.uptimeMillis()
        if (now - lastInjectedDownAt < INJECTED_DOWN_MS) return
        when {
            running -> pauseForTouch(directionResume = true)
            pausedByTouch -> {
                directionResumeArmed = prefs.resumeOnSameDirection
                directionResumePending = false
                handler.removeCallbacks(settleRunnable)
                tracker.reset()
                scheduleResume()
            }
        }
    }

    private fun pauseForTouch(directionResume: Boolean) {
        pausedByTouch = true
        directionResumeArmed = directionResume && prefs.resumeOnSameDirection
        directionResumePending = false
        handler.removeCallbacks(settleRunnable)
        tracker.reset()
        if (running) {
            running = false
            handler.removeCallbacks(watchdog)
            pump()
        }
        scheduleResume()
    }

    /** Pushes the automatic resume back so it happens only after the user goes quiet. */
    private fun scheduleResume() {
        handler.removeCallbacks(resumeRunnable)
        resumeAt = if (prefs.touchPauseMode == TouchPauseMode.RESUME_AFTER) {
            val delayMs = (prefs.touchResumeDelay * 1000f).toLong()
            handler.postDelayed(resumeRunnable, delayMs)
            SystemClock.uptimeMillis() + delayMs
        } else {
            0L
        }
        listener?.onStateChanged()
    }

    /** Returns whether a touch pause was active. */
    private fun clearTouchPause(): Boolean {
        handler.removeCallbacks(resumeRunnable)
        handler.removeCallbacks(settleRunnable)
        directionResumePending = false
        resumeAt = 0L
        if (!pausedByTouch) return false
        pausedByTouch = false
        return true
    }

    fun toggle() = if (running) pause() else start()

    fun commitSpeed() {
        prefs.speed = speed
    }

    /** Real touches cancel injected gestures, so scrolling yields while the user touches the controls. */
    fun setHeld(value: Boolean) {
        if (held == value) return
        held = value
        if (!value) resetProgress()
        pump()
    }

    fun onContentScrolled(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        sawScroll = true
        lastProgress = now
        val delta = tracker.delta(event)
        if (!pausedByTouch) return
        if (directionResumePending) {
            handler.removeCallbacks(settleRunnable)
            handler.postDelayed(settleRunnable, SETTLE_MS)
            return
        }
        scheduleResume()
        if (!directionResumeArmed || delta == null) return
        val threshold = service.screenSize().y * prefs.sameDirectionDistance
        if (tracker.feed(delta, directionDown, threshold, now)) {
            directionResumePending = true
            handler.removeCallbacks(resumeRunnable)
            handler.postDelayed(settleRunnable, SETTLE_MS)
        }
    }

    fun release() {
        running = false
        clearTouchPause()
        resetGestureState()
        handler.removeCallbacksAndMessages(null)
    }

    private fun resetProgress() {
        sawScroll = false
        lastProgress = SystemClock.uptimeMillis()
    }

    /** Drops all in-flight gestures; their late callbacks are ignored via [generation]. */
    private fun resetGestureState() {
        generation++
        tail = null
        inFlight = 0
    }

    /**
     * Only arms after the foreground app has reported at least one scroll event, so apps that
     * never emit scroll events are not stopped by mistake.
     */
    private fun checkStalled() {
        if (!running || held || !sawScroll || !prefs.autoStop) return
        val slopMs = (START_SLOP_DP * density / pxPerSecond() * 1000f).toLong()
        if (SystemClock.uptimeMillis() - lastProgress > STALL_MS + slopMs + LOOKAHEAD * SEGMENT_MS) pause()
    }

    private fun pxPerSecond(): Float = density * 10f * speed.pow(1.3f)

    private fun pump() {
        handler.removeCallbacks(pumpRunnable)
        while (true) {
            val active = running && !held
            val current = tail
            val dispatched = when {
                // A new swipe can only start once the previous lift has fully played out.
                current == null -> if (inFlight == 0 && active) beginStroke() else return
                inFlight >= LOOKAHEAD -> return
                !active || liftRequested -> holdAndLift(current)
                else -> advance(current)
            }
            if (!dispatched) return
        }
    }

    private fun beginStroke(): Boolean {
        liftRequested = false
        val size = service.screenSize()
        x = size.x / 2f
        strokeDown = directionDown
        val top = size.y * TRAVEL_TOP
        val bottom = size.y * TRAVEL_BOTTOM
        // Scrolling content down means dragging the finger up.
        if (strokeDown) {
            y = bottom
            endY = top
        } else {
            y = top
            endY = bottom
        }
        lastInjectedDownAt = SystemClock.uptimeMillis()
        return dispatch(StrokeDescription(nextSegment(), 0, SEGMENT_MS, true))
    }

    private fun advance(current: StrokeDescription): Boolean {
        val path = nextSegment()
        val reachedEnd = y == endY
        return dispatch(current.continueStroke(path, 0, SEGMENT_MS, !reachedEnd))
    }

    private fun holdAndLift(current: StrokeDescription): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + if (strokeDown) -1f else 1f)
        }
        return dispatch(current.continueStroke(path, 0, HOLD_MS, false))
    }

    private fun nextSegment(): Path {
        val step = pxPerSecond() * SEGMENT_MS / 1000f
        val path = Path()
        path.moveTo(x, y)
        y = if (strokeDown) max(endY, y - step) else min(endY, y + step)
        path.lineTo(x, y)
        return path
    }

    private fun dispatch(next: StrokeDescription): Boolean {
        val gen = generation
        val gesture = GestureDescription.Builder().addStroke(next).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (gen != generation) return
                inFlight--
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                if (gen != generation) return
                resetGestureState()
                if (running && !held) {
                    // A cancel right after a direction resume means the finger is likely still down.
                    val recent = SystemClock.uptimeMillis() - resumedByDirectionAt < DIRECTION_RECANCEL_MS
                    pauseForTouch(directionResume = !recent)
                    return
                }
                handler.postDelayed(pumpRunnable, RETRY_MS)
            }
        }, handler)
        if (!accepted) {
            resetGestureState()
            if (running) {
                running = false
                listener?.onStateChanged()
            }
            return false
        }
        inFlight++
        tail = if (next.willContinue()) next else null
        return true
    }

    private companion object {
        const val SEGMENT_MS = 150L
        /** Segments queued ahead of the one playing, so playback never waits on a callback. */
        const val LOOKAHEAD = 2
        const val HOLD_MS = 150L
        const val TRAVEL_TOP = 0.2f
        const val TRAVEL_BOTTOM = 0.8f
        const val RETRY_MS = 400L
        const val WATCHDOG_MS = 500L
        const val STALL_MS = 2500L
        const val START_SLOP_DP = 16f
        /** Touch-downs this soon after starting an injected swipe are assumed to be that swipe. */
        const val INJECTED_DOWN_MS = 150L
        /** Quiet time after a same-direction scroll before resuming, so a fling can finish. */
        const val SETTLE_MS = 250L
        const val DIRECTION_RECANCEL_MS = 1000L
    }
}
