package app.autoscroll

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Floating pill with three cells: scroll up, center play/pause, scroll down.
 *
 * Touch model:
 * - Tap a cell to trigger it.
 * - Press and move right away to drag the bubble around.
 * - Press the center cell and hold still for [HOLD_MS] to enter speed mode. The finger's
 *   position then acts like a joystick: above the start point scrolls up, below scrolls down,
 *   and distance sets the speed on a curve that is fine near the start and steep far away.
 * - When minimized, the view is a slim edge tab: tap it to restore, drag it to move or pull out.
 *   The window can be larger than the visible tab, which then sits flush against the screen edge
 *   inside an invisible touch area.
 * - Touch-downs anywhere else on screen arrive as ACTION_OUTSIDE and are reported as such.
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context, private val callback: Callback) : View(context) {

    interface Callback {
        fun onTouchStart()
        fun onTouchEnd()
        fun onUpTap()
        fun onCenterTap()
        fun onDownTap()
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
        fun onSpeedModeStart()
        fun onSpeedChange(speed: Float, directionDown: Boolean)
        /** [changed] is false when the finger never left the dead zone around the start point. */
        fun onSpeedModeEnd(changed: Boolean)
        fun onMinimizedTap()
        fun onOutsideTouch()
    }

    private enum class Mode { IDLE, PRESS, DRAG, SPEED }

    var idleAlpha = 0.7f
        set(value) {
            field = value
            updateAlpha()
        }

    /** Shown while the bubble is dragged past its edge; releasing then minimizes it. */
    var minimizeArmed = false
        set(value) {
            if (field == value) return
            field = value
            if (value) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            updateAlpha()
        }

    private var running = false
    private var directionDown = true
    private var pausedByTouch = false
    private var resumeAt = 0L
    private var resumeDelayMs = 0L
    private var minimized = false
    private var minimizedOnRight = true
    private var tabThickness = 0f
    private var tabPadding = 0f

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRESS }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = ACCENT
    }
    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = TOUCH_PAUSED
    }
    private val path = Path()

    private var mode = Mode.IDLE
    private var zone = ZONE_CENTER
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var anchorY = 0f
    private var speedChanged = false
    private var speedDown = true
    private var lastTick = 0
    private var ringPhase = 0f
    private var ringAnimator: ValueAnimator? = null

    private val enterSpeedMode = Runnable {
        if (mode != Mode.PRESS || zone != ZONE_CENTER) return@Runnable
        mode = Mode.SPEED
        anchorY = lastY
        speedChanged = false
        speedDown = directionDown
        lastTick = 0
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startRing()
        callback.onSpeedModeStart()
        invalidate()
    }

    init {
        contentDescription = "Auto scroll controls"
        updateAlpha()
    }

    /** [resumeAt] is the uptime a touch pause ends on its own, or 0; [resumeDelayMs] its full length. */
    fun setState(
        running: Boolean,
        directionDown: Boolean,
        pausedByTouch: Boolean,
        resumeAt: Long,
        resumeDelayMs: Long,
    ) {
        this.running = running
        this.directionDown = directionDown
        this.pausedByTouch = pausedByTouch
        this.resumeAt = resumeAt
        this.resumeDelayMs = resumeDelayMs
        invalidate()
    }

    /** [thickness] is the visible tab width and [padding] the invisible touch area around it, in pixels. */
    fun setMinimized(minimized: Boolean, onRight: Boolean, thickness: Float, padding: Float) {
        this.minimized = minimized
        minimizedOnRight = onRight
        tabThickness = thickness
        tabPadding = padding
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> callback.onOutsideTouch()
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastY = e.rawY
                zone = (e.y / (height / 3f)).toInt().coerceIn(ZONE_UP, ZONE_DOWN)
                mode = Mode.PRESS
                callback.onTouchStart()
                if (zone == ZONE_CENTER && !minimized) postDelayed(enterSpeedMode, HOLD_MS)
                updateAlpha()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP -> {
                val ended = endTouch()
                when (ended) {
                    Mode.PRESS -> tap()
                    Mode.DRAG -> callback.onDragEnd()
                    Mode.SPEED -> callback.onSpeedModeEnd(speedChanged)
                    Mode.IDLE -> {}
                }
                callback.onTouchEnd()
            }
            MotionEvent.ACTION_CANCEL -> {
                when (endTouch()) {
                    Mode.DRAG -> callback.onDragEnd()
                    Mode.SPEED -> callback.onSpeedModeEnd(speedChanged)
                    else -> {}
                }
                callback.onTouchEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.PRESS -> {
                lastY = e.rawY
                if (hypot(e.rawX - downX, e.rawY - downY) > touchSlop) {
                    removeCallbacks(enterSpeedMode)
                    mode = Mode.DRAG
                    callback.onDragStart()
                    callback.onDrag(e.rawX - downX, e.rawY - downY)
                    invalidate()
                }
            }
            Mode.DRAG -> callback.onDrag(e.rawX - downX, e.rawY - downY)
            Mode.SPEED -> onSpeedDrag(e.rawY)
            Mode.IDLE -> {}
        }
    }

    private fun onSpeedDrag(rawY: Float) {
        val offsetDp = (anchorY - rawY) / density
        val distance = abs(offsetDp) - SPEED_DEAD_DP
        if (distance <= 0f && !speedChanged) return
        speedChanged = true
        val down = offsetDp < 0f
        val t = (distance.coerceAtLeast(0f) / SPEED_RANGE_DP).coerceAtMost(1f)
        val speed = Prefs.MIN_SPEED + (Prefs.MAX_SPEED - Prefs.MIN_SPEED) * t.pow(SPEED_CURVE)
        val tick = speed.toInt()
        if (down != speedDown) {
            speedDown = down
            lastTick = tick
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (tick != lastTick) {
            lastTick = tick
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        callback.onSpeedChange(speed, down)
    }

    private fun tap() {
        performClick()
        if (minimized) {
            callback.onMinimizedTap()
            return
        }
        when (zone) {
            ZONE_UP -> callback.onUpTap()
            ZONE_CENTER -> callback.onCenterTap()
            ZONE_DOWN -> callback.onDownTap()
        }
    }

    private fun endTouch(): Mode {
        val ended = mode
        mode = Mode.IDLE
        removeCallbacks(enterSpeedMode)
        stopRing()
        updateAlpha()
        invalidate()
        return ended
    }

    private fun updateAlpha() {
        alpha = when {
            minimizeArmed -> 0.4f
            mode == Mode.IDLE -> idleAlpha
            else -> 1f
        }
    }

    private fun startRing() {
        stopRing()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                ringPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopRing() {
        ringAnimator?.cancel()
        ringAnimator = null
        ringPhase = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(enterSpeedMode)
        stopRing()
    }

    override fun onDraw(canvas: Canvas) {
        if (minimized) {
            drawTab(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = h / 3f
        val cx = w / 2f

        canvas.drawRoundRect(0f, 0f, w, h, cx, cx, bgPaint)

        if (mode == Mode.PRESS && zone != ZONE_CENTER) {
            canvas.drawCircle(cx, cell * (zone + 0.5f), w * 0.42f, pressPaint)
        }

        drawArrow(canvas, cx, cell * 0.5f, w * 0.18f, up = true, color = arrowColor(selected = !directionDown))
        drawArrow(canvas, cx, cell * 2.5f, w * 0.18f, up = false, color = arrowColor(selected = directionDown))

        val cy = cell * 1.5f
        val r = w * 0.30f
        val speedMode = mode == Mode.SPEED
        if (speedMode) {
            ringPaint.alpha = ((1f - ringPhase) * 255).toInt()
            canvas.drawCircle(cx, cy, r + ringPhase * w * 0.17f, ringPaint)
        }
        fillPaint.color = when {
            speedMode -> ACCENT
            mode == Mode.PRESS && zone == ZONE_CENTER -> CIRCLE_PRESSED
            else -> CIRCLE
        }
        canvas.drawCircle(cx, cy, r, fillPaint)

        if (pausedByTouch && !speedMode) drawCountdown(canvas, cx, cy, r + w * 0.07f)

        fillPaint.color = when {
            speedMode -> BG_SOLID
            pausedByTouch -> TOUCH_PAUSED
            else -> FG
        }
        val p = r * 0.45f
        if (running) {
            canvas.drawRect(cx - p * 0.8f, cy - p * 0.85f, cx - p * 0.25f, cy + p * 0.85f, fillPaint)
            canvas.drawRect(cx + p * 0.25f, cy - p * 0.85f, cx + p * 0.8f, cy + p * 0.85f, fillPaint)
        } else {
            path.reset()
            path.moveTo(cx - p * 0.7f, cy - p)
            path.lineTo(cx - p * 0.7f, cy + p)
            path.lineTo(cx + p, cy)
            path.close()
            canvas.drawPath(path, fillPaint)
        }
    }

    /** Arc around the center button that shrinks until a touch pause ends on its own. */
    private fun drawCountdown(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (resumeAt == 0L || resumeDelayMs <= 0L) return
        val remaining = (resumeAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        val sweep = 360f * remaining / resumeDelayMs
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, sweep, false, countdownPaint)
        if (remaining > 0L) postInvalidateOnAnimation()
    }

    /**
     * Half-pill flush against the screen edge with a grip line, inside the padded touch area.
     * The grip is blue while scrolling and amber while paused by a touch.
     */
    private fun drawTab(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = tabThickness.coerceIn(1f, w)
        val top = tabPadding.coerceAtMost(h / 2f)
        val bottom = h - top
        val left = if (minimizedOnRight) w - t else -t
        val right = if (minimizedOnRight) w + t else t
        canvas.drawRoundRect(left, top, right, bottom, t, t, bgPaint)
        if (mode == Mode.PRESS) canvas.drawRoundRect(left, top, right, bottom, t, t, pressPaint)
        fillPaint.color = when {
            running -> ACCENT
            pausedByTouch -> TOUCH_PAUSED
            else -> FG
        }
        val gx = if (minimizedOnRight) w - t * 0.55f else t * 0.55f
        val gw = t * 0.13f
        val tabH = bottom - top
        canvas.drawRoundRect(gx - gw, top + tabH * 0.28f, gx + gw, top + tabH * 0.72f, gw, gw, fillPaint)
    }

    private fun arrowColor(selected: Boolean): Int = when {
        !selected -> FG
        running -> ACCENT
        else -> ACCENT_DIM
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        val tip = if (up) -s * 0.6f else s * 0.6f
        val base = if (up) s * 0.5f else -s * 0.5f
        path.reset()
        path.moveTo(cx, cy + tip)
        path.lineTo(cx - s, cy + base)
        path.lineTo(cx + s, cy + base)
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    private companion object {
        const val ZONE_UP = 0
        const val ZONE_CENTER = 1
        const val ZONE_DOWN = 2

        const val HOLD_MS = 350L
        /** Movement within this distance of the start point leaves speed and direction unchanged. */
        const val SPEED_DEAD_DP = 12f
        /** Distance past the dead zone that reaches maximum speed. */
        const val SPEED_RANGE_DP = 260f
        /** Exponent of the distance-to-speed curve; higher gives finer control near the start. */
        const val SPEED_CURVE = 2.2f

        const val BG = 0xE6202124.toInt()
        const val BG_SOLID = 0xFF202124.toInt()
        const val PRESS = 0x33FFFFFF
        const val FG = 0xFFE8EAED.toInt()
        const val CIRCLE = 0xFF3C4043.toInt()
        const val CIRCLE_PRESSED = 0xFF5F6368.toInt()
        const val ACCENT = 0xFF4FC3F7.toInt()
        const val ACCENT_DIM = 0x994FC3F7.toInt()
        const val TOUCH_PAUSED = 0xFFFFB74D.toInt()
    }
}
