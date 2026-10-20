package app.autoscroll

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** Full physical screen size in the current orientation, including system bars. */
fun Context.screenSize(): Point {
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val size = Point()
    @Suppress("DEPRECATION")
    display.getRealSize(size)
    return size
}

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

fun Context.dpInt(value: Float): Int = (dp(value) + 0.5f).toInt()
