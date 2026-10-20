package app.autoscroll

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.Locale
import kotlin.math.max

class ScrollService : AccessibilityService(),
    OverlayView.Callback,
    ScrollController.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var controller: ScrollController
    private lateinit var wm: WindowManager

    private var overlay: OverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var labelParams: WindowManager.LayoutParams? = null
    private var labelAttached = false
    private var snapAnimator: ValueAnimator? = null
    private var dragStartX = 0
    private var dragStartY = 0
    /** Edge the bubble was docked on when the current drag began: -1 left, 1 right, 0 neither. */
    private var dragStartEdge = 0
    /** How far a minimized tab has been pulled away from its edge in the current drag. */
    private var pullOut = 0f
    private var dockedRight = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        controller = ScrollController(this, prefs)
        controller.listener = this
        wm = getSystemService(WindowManager::class.java)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        instance = this
        if (prefs.enabled) showOverlay()
        requestTileUpdate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (overlay == null || !::controller.isInitialized) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) controller.onContentScrolled(event)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.post {
            val params = overlayParams ?: return@post
            params.x = if (dockedRight) screenSize().x else 0
            snap(animate = false)
        }
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (!::prefs.isInitialized) return
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        hideOverlay()
        controller.release()
        requestTileUpdate()
    }

    // region Overlay windows

    private fun showOverlay() {
        if (overlay != null) return
        val view = OverlayView(this, this)
        val size = bubbleSize()
        val screen = screenSize()
        val params = overlayLayoutParams(size.first, size.second, touchable = true).apply {
            x = if (prefs.bubbleX >= 0) prefs.bubbleX else screen.x - width
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else (screen.y * 0.4f).toInt()
        }
        overlay = view
        overlayParams = params
        params.x = applySnap(params, screen.x, screen.y)
        wm.addView(view, params)
        syncOverlay()
    }

    private fun hideOverlay() {
        controller.pause()
        snapAnimator?.cancel()
        snapAnimator = null
        hideLabel()
        overlay?.let { removeSafely(it) }
        overlay = null
        overlayParams = null
    }

    private fun resizeOverlay() {
        val view = overlay ?: return
        val params = overlayParams ?: return
        val size = bubbleSize()
        params.width = size.first
        params.height = size.second
        wm.updateViewLayout(view, params)
        snap(animate = false)
    }

    private fun syncOverlay() {
        val view = overlay ?: return
        view.idleAlpha = prefs.idleAlpha
        view.setState(
            controller.running,
            controller.directionDown,
            controller.pausedByTouch,
            controller.resumeAt,
            (prefs.touchResumeDelay * 1000f).toLong(),
        )
    }

    /** When minimized, the window is the visible tab plus its invisible touch padding. */
    private fun bubbleSize(): Pair<Int, Int> {
        if (prefs.minimized) {
            val pad = dpInt(prefs.tabTouchPaddingDp)
            return dpInt(prefs.tabThicknessDp) + pad to dpInt(prefs.tabLengthDp) + pad * 2
        }
        val cell = dpInt(52f * prefs.bubbleScale)
        return cell to cell * 3
    }

    /** Switches between the full bubble and the edge tab, keeping the same side and vertical center. */
    private fun setMinimized(value: Boolean) {
        val params = overlayParams ?: return
        if (overlay == null || prefs.minimized == value) return
        snapAnimator?.cancel()
        val screen = screenSize()
        val onRight = params.x + params.width / 2 >= screen.x / 2
        val centerY = params.y + params.height / 2
        prefs.minimized = value
        val size = bubbleSize()
        params.width = size.first
        params.height = size.second
        params.x = if (onRight) screen.x - params.width else 0
        params.y = centerY - params.height / 2
        snap(animate = false)
    }

    private fun overlayLayoutParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = if (touchable) {
            // Reports touch-downs elsewhere on screen, so a user touch can pause scrolling.
            flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    /** Clamps vertically and moves to the nearest side edge. Returns the target x. */
    private fun applySnap(params: WindowManager.LayoutParams, screenW: Int, screenH: Int): Int {
        params.y = params.y.coerceIn(0, max(0, screenH - params.height))
        dockedRight = params.x + params.width / 2 >= screenW / 2
        val targetX = if (dockedRight) screenW - params.width else 0
        prefs.bubbleX = targetX
        prefs.bubbleY = params.y
        overlay?.setMinimized(prefs.minimized, dockedRight, dp(prefs.tabThicknessDp), dp(prefs.tabTouchPaddingDp))
        return targetX
    }

    private fun snap(animate: Boolean) {
        val view = overlay ?: return
        val params = overlayParams ?: return
        val screen = screenSize()
        snapAnimator?.cancel()
        val targetX = applySnap(params, screen.x, screen.y)
        if (!animate) {
            params.x = targetX
            wm.updateViewLayout(view, params)
            return
        }
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 160
            addUpdateListener {
                if (overlay !== view) return@addUpdateListener
                params.x = it.animatedValue as Int
                wm.updateViewLayout(view, params)
            }
            start()
        }
    }

    private fun showLabel() {
        val view = label ?: TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            val h = dpInt(14f)
            val v = dpInt(8f)
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(0xE6202124.toInt())
            }
        }.also {
            label = it
            labelParams = overlayLayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                touchable = false,
            )
        }
        updateLabel()
        if (!labelAttached) {
            wm.addView(view, labelParams)
            labelAttached = true
        }
    }

    private fun updateLabel() {
        val view = label ?: return
        val lp = labelParams ?: return
        val bubble = overlayParams ?: return
        val arrow = if (controller.directionDown) "\u25BC" else "\u25B2"
        view.text = String.format(Locale.US, "%s  Speed %.1f", arrow, controller.speed)
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val gap = dpInt(10f)
        val onRight = bubble.x + bubble.width / 2 > screenSize().x / 2
        lp.x = if (onRight) bubble.x - view.measuredWidth - gap else bubble.x + bubble.width + gap
        lp.y = bubble.y + bubble.height / 2 - view.measuredHeight / 2
        if (labelAttached) wm.updateViewLayout(view, lp)
    }

    private fun hideLabel() {
        val view = label ?: return
        if (labelAttached) removeSafely(view)
        labelAttached = false
    }

    private fun removeSafely(view: View) {
        try {
            wm.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    // endregion

    // region OverlayView.Callback

    override fun onTouchStart() {
        snapAnimator?.cancel()
        controller.setHeld(true)
    }

    override fun onTouchEnd() {
        controller.setHeld(false)
    }

    override fun onUpTap() = tapDirection(down = false)

    override fun onDownTap() = tapDirection(down = true)

    override fun onCenterTap() = controller.toggle()

    private fun tapDirection(down: Boolean) {
        if (controller.running && controller.directionDown == down) {
            controller.pause()
        } else {
            controller.directionDown = down
            controller.start()
        }
    }

    override fun onDragStart() {
        val params = overlayParams ?: return
        dragStartX = params.x
        dragStartY = params.y
        val maxX = screenSize().x - params.width
        dragStartEdge = when {
            params.x <= 0 -> -1
            params.x >= maxX -> 1
            else -> 0
        }
        pullOut = 0f
    }

    override fun onDrag(dx: Float, dy: Float) {
        val view = overlay ?: return
        val params = overlayParams ?: return
        val screen = screenSize()
        val maxX = max(0, screen.x - params.width)
        params.y = (dragStartY + dy.toInt()).coerceIn(0, max(0, screen.y - params.height))
        if (prefs.minimized) {
            // The tab stays on its edge; horizontal motion only counts toward pulling it back out.
            pullOut = if (dockedRight) -dx else dx
        } else {
            val desiredX = dragStartX + dx.toInt()
            val overshoot = when (dragStartEdge) {
                -1 -> -desiredX
                1 -> desiredX - maxX
                else -> 0
            }
            // Pushing past the edge it was already docked on lets it slide partly off screen.
            params.x = if (overshoot > 0) {
                desiredX.coerceIn(-params.width / 2, maxX + params.width / 2)
            } else {
                desiredX.coerceIn(0, maxX)
            }
            view.minimizeArmed = overshoot > params.width * MINIMIZE_OVERSHOOT
        }
        wm.updateViewLayout(view, params)
    }

    override fun onDragEnd() {
        val view = overlay ?: return
        val armed = view.minimizeArmed
        view.minimizeArmed = false
        when {
            prefs.minimized && pullOut > dp(RESTORE_PULL_DP) -> setMinimized(false)
            armed -> setMinimized(true)
            else -> snap(animate = true)
        }
        pullOut = 0f
    }

    override fun onMinimizedTap() = setMinimized(false)

    override fun onOutsideTouch() = controller.onUserTouch()

    override fun onSpeedModeStart() = showLabel()

    override fun onSpeedChange(speed: Float, directionDown: Boolean) {
        controller.speed = speed
        controller.directionDown = directionDown
        updateLabel()
    }

    override fun onSpeedModeEnd(changed: Boolean) {
        hideLabel()
        if (!changed) return
        controller.commitSpeed()
        controller.start()
    }

    // endregion

    override fun onStateChanged() = syncOverlay()

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_ENABLED -> {
                if (prefs.enabled) showOverlay() else hideOverlay()
                requestTileUpdate()
            }
            Prefs.KEY_BUBBLE_SCALE,
            Prefs.KEY_TAB_THICKNESS,
            Prefs.KEY_TAB_LENGTH,
            Prefs.KEY_TAB_TOUCH_PADDING -> resizeOverlay()
            Prefs.KEY_IDLE_ALPHA, Prefs.KEY_TOUCH_RESUME_DELAY -> syncOverlay()
            Prefs.KEY_SPEED -> controller.speed = prefs.speed
            Prefs.KEY_DIRECTION_DOWN -> controller.directionDown = prefs.directionDown
        }
    }

    private fun requestTileUpdate() {
        TileService.requestListeningState(this, ComponentName(this, ToggleTileService::class.java))
    }

    companion object {
        /** Fraction of the bubble width it must be pushed past its edge to minimize on release. */
        private const val MINIMIZE_OVERSHOOT = 0.35f
        private const val RESTORE_PULL_DP = 32f

        @Volatile
        var instance: ScrollService? = null
            private set
    }
}
