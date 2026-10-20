package app.autoscroll

import android.content.Context
import android.content.SharedPreferences

/** What happens after a touch on the screen pauses scrolling. */
enum class TouchPauseMode { STAY_PAUSED, RESUME_AFTER }

class Prefs(context: Context) {

    val sp: SharedPreferences = context.getSharedPreferences("autoscroll", Context.MODE_PRIVATE)

    var speed: Float
        get() = sp.getFloat(KEY_SPEED, 4f).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) = sp.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()

    var directionDown: Boolean
        get() = sp.getBoolean(KEY_DIRECTION_DOWN, true)
        set(value) = sp.edit().putBoolean(KEY_DIRECTION_DOWN, value).apply()

    /** Saved bubble position in screen pixels, or -1 when never placed. */
    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(value) = sp.edit().putInt(KEY_BUBBLE_Y, value).apply()

    var bubbleScale: Float
        get() = sp.getFloat(KEY_BUBBLE_SCALE, 1f).coerceIn(0.75f, 1.5f)
        set(value) = sp.edit().putFloat(KEY_BUBBLE_SCALE, value).apply()

    var idleAlpha: Float
        get() = sp.getFloat(KEY_IDLE_ALPHA, 0.7f).coerceIn(0.2f, 1f)
        set(value) = sp.edit().putFloat(KEY_IDLE_ALPHA, value).apply()

    /** Master switch: when off, the service hides its controls and does no work. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, sp.getBoolean(KEY_OVERLAY_VISIBLE_LEGACY, true))
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var autoStop: Boolean
        get() = sp.getBoolean(KEY_AUTO_STOP, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_STOP, value).apply()

    /** Bubble collapsed into a small tab at the screen edge. */
    var minimized: Boolean
        get() = sp.getBoolean(KEY_MINIMIZED, false)
        set(value) = sp.edit().putBoolean(KEY_MINIMIZED, value).apply()

    var touchPauseMode: TouchPauseMode
        get() = sp.getString(KEY_TOUCH_PAUSE_MODE, null)
            ?.let { name -> TouchPauseMode.entries.firstOrNull { it.name == name } }
            ?: TouchPauseMode.RESUME_AFTER
        set(value) = sp.edit().putString(KEY_TOUCH_PAUSE_MODE, value.name).apply()

    /** Seconds without touching or scrolling before a touch pause ends on its own. */
    var touchResumeDelay: Float
        get() = sp.getFloat(KEY_TOUCH_RESUME_DELAY, 2f).coerceIn(MIN_RESUME_DELAY, MAX_RESUME_DELAY)
        set(value) = sp.edit()
            .putFloat(KEY_TOUCH_RESUME_DELAY, value.coerceIn(MIN_RESUME_DELAY, MAX_RESUME_DELAY)).apply()

    var resumeOnSameDirection: Boolean
        get() = sp.getBoolean(KEY_RESUME_SAME_DIRECTION, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME_SAME_DIRECTION, value).apply()

    /** Fraction of the screen height the user must scroll in the autoscroll direction to resume. */
    var sameDirectionDistance: Float
        get() = sp.getFloat(KEY_SAME_DIRECTION_DISTANCE, 0.3f).coerceIn(0.1f, 1f)
        set(value) = sp.edit().putFloat(KEY_SAME_DIRECTION_DISTANCE, value).apply()

    var tabThicknessDp: Float
        get() = sp.getFloat(KEY_TAB_THICKNESS, 18f).coerceIn(8f, 40f)
        set(value) = sp.edit().putFloat(KEY_TAB_THICKNESS, value).apply()

    var tabLengthDp: Float
        get() = sp.getFloat(KEY_TAB_LENGTH, 64f).coerceIn(32f, 160f)
        set(value) = sp.edit().putFloat(KEY_TAB_LENGTH, value).apply()

    /** Invisible touch area around the tab, so a thin tab is still easy to grab. */
    var tabTouchPaddingDp: Float
        get() = sp.getFloat(KEY_TAB_TOUCH_PADDING, 12f).coerceIn(0f, 32f)
        set(value) = sp.edit().putFloat(KEY_TAB_TOUCH_PADDING, value).apply()

    companion object {
        const val MIN_SPEED = 1f
        const val MAX_SPEED = 30f
        const val MIN_RESUME_DELAY = 0.5f
        const val MAX_RESUME_DELAY = 30f

        const val KEY_SPEED = "speed"
        const val KEY_DIRECTION_DOWN = "direction_down"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_BUBBLE_SCALE = "bubble_scale"
        const val KEY_IDLE_ALPHA = "idle_alpha"
        const val KEY_ENABLED = "enabled"
        private const val KEY_OVERLAY_VISIBLE_LEGACY = "overlay_visible"
        const val KEY_AUTO_STOP = "auto_stop"
        const val KEY_MINIMIZED = "minimized"
        const val KEY_TOUCH_PAUSE_MODE = "touch_pause_mode"
        const val KEY_TOUCH_RESUME_DELAY = "touch_resume_delay"
        const val KEY_RESUME_SAME_DIRECTION = "resume_same_direction"
        const val KEY_SAME_DIRECTION_DISTANCE = "same_direction_distance"
        const val KEY_TAB_THICKNESS = "tab_thickness"
        const val KEY_TAB_LENGTH = "tab_length"
        const val KEY_TAB_TOUCH_PADDING = "tab_touch_padding"
    }
}
