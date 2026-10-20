package app.autoscroll

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * Decides when the user has deliberately scrolled in the autoscroll direction.
 *
 * Finger positions in other apps are not visible to the service, so direction comes from the
 * scroll events the content reports, in order of preference: the pixel delta (API 28+), the change
 * in scroll position, or the change in the first visible list item.
 *
 * Movement in the autoscroll direction accumulates. Reversing by more than [JITTER_DP] resets it,
 * so scrolling back to reread never counts. It fires once the total reaches the threshold and the
 * movement has lasted [MIN_DURATION_MS], or immediately when a burst covers twice the threshold.
 */
class ScrollDirectionTracker(private val context: Context) {

    private class Position(val scrollY: Int, val fromIndex: Int)

    private val positions = HashMap<String, Position>()
    private val jitterPx = context.dp(JITTER_DP)

    private var forward = 0f
    private var backward = 0f
    private var startedAt = 0L

    fun reset() {
        forward = 0f
        backward = 0f
        startedAt = 0L
    }

    /**
     * Records [event] and returns how far its content moved toward the end, in pixels, or null when
     * the direction cannot be told. Must see every scroll event so positions stay current.
     */
    fun delta(event: AccessibilityEvent): Float? {
        val key = "${event.windowId}/${event.className}"
        val previous = positions[key]
        positions[key] = Position(event.scrollY, event.fromIndex)
        if (positions.size > MAX_SOURCES) positions.keys.retainAll(setOf(key))

        if (Build.VERSION.SDK_INT >= 28) {
            val dy = event.scrollDeltaY
            if (dy != UNDEFINED && dy != 0) return dy.toFloat()
        }
        previous ?: return null
        if (event.scrollY >= 0 && previous.scrollY >= 0 && event.scrollY != previous.scrollY) {
            return (event.scrollY - previous.scrollY).toFloat()
        }
        if (event.fromIndex >= 0 && previous.fromIndex >= 0 && event.fromIndex != previous.fromIndex) {
            return (event.fromIndex - previous.fromIndex) * context.screenSize().y * ITEM_SCREEN_FRACTION
        }
        return null
    }

    /** Feeds a movement measured by [delta]. Returns true once the user's intent is clear. */
    fun feed(deltaPx: Float, directionDown: Boolean, thresholdPx: Float, now: Long): Boolean {
        val along = if (directionDown) deltaPx else -deltaPx
        if (along < 0f) {
            backward += -along
            if (backward > jitterPx) reset()
            return false
        }
        backward = 0f
        if (forward == 0f) startedAt = now
        forward += along
        return forward >= thresholdPx * BURST_FACTOR ||
            (forward >= thresholdPx && now - startedAt >= MIN_DURATION_MS)
    }

    private companion object {
        const val UNDEFINED = -1
        const val JITTER_DP = 24f
        const val MIN_DURATION_MS = 300L
        const val BURST_FACTOR = 2f
        /** Rough screen share of one list item, for lists that only report item positions. */
        const val ITEM_SCREEN_FRACTION = 0.1f
        const val MAX_SOURCES = 16
    }
}
