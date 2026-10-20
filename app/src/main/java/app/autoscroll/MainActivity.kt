package app.autoscroll

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    /** A labelled control whose value and enabled state can be set from code. */
    private class Control(private val views: List<View>, private val setter: (Float) -> Unit) {
        fun set(value: Float) = setter(value)

        var enabled: Boolean = true
            set(value) {
                field = value
                views.forEach {
                    it.isEnabled = value
                    it.alpha = if (value) 1f else 0.4f
                }
            }
    }

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var masterSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var autoStopSwitch: Switch
    private lateinit var speed: Control
    private lateinit var size: Control
    private lateinit var opacity: Control
    private lateinit var touchModeGroup: RadioGroup
    private lateinit var stayPausedButton: RadioButton
    private lateinit var resumeAfterButton: RadioButton
    private lateinit var resumeDelay: Control
    private lateinit var sameDirectionSwitch: Switch
    private lateinit var sameDirectionDistance: Control
    private lateinit var tabThickness: Control
    private lateinit var tabLength: Control
    private lateinit var tabPadding: Control

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = dpInt(20f)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        add(text(getString(R.string.app_name), 26f, bold = true))
        masterSwitch = Switch(this).apply {
            text = "Auto scroll"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setOnCheckedChangeListener { _, checked ->
                if (prefs.enabled != checked) prefs.enabled = checked
                updateStatus()
            }
        }
        add(masterSwitch, top = 16f)
        statusText = text("", 15f)
        add(statusText, top = 8f)
        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        add(enableButton, top = 8f)
        add(
            text(
                "If Android says the setting is restricted or the toggle is greyed out, open App info, " +
                    "tap the \u22EE menu, choose \"Allow restricted settings\", then try again.",
                13f,
                secondary = true,
            ),
            top = 8f,
        )
        add(Button(this).apply {
            text = "Open app info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        })

        section("Controls")
        autoStopSwitch = switch("Stop automatically at the end of content") { prefs.autoStop = it }
        speed = slider("Speed", 10, 300, { String.format(Locale.US, "%.1f", it / 10f) }) { prefs.speed = it / 10f }
        size = slider("Bubble size", 75, 150, { "$it%" }) { prefs.bubbleScale = it / 100f }
        opacity = slider("Opacity when idle", 20, 100, { "$it%" }) { prefs.idleAlpha = it / 100f }

        section("Touching the screen")
        add(
            text("Touching the screen anywhere outside the controls pauses scrolling.", 14f, secondary = true),
            top = 4f,
        )
        stayPausedButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Stay paused until I tap the controls"
            textSize = 15f
        }
        resumeAfterButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Resume after I stop touching for a while"
            textSize = 15f
        }
        touchModeGroup = RadioGroup(this).apply {
            addView(stayPausedButton)
            addView(resumeAfterButton)
            setOnCheckedChangeListener { _, checkedId ->
                val mode = if (checkedId == stayPausedButton.id) TouchPauseMode.STAY_PAUSED else TouchPauseMode.RESUME_AFTER
                prefs.touchPauseMode = mode
                resumeDelay.enabled = mode == TouchPauseMode.RESUME_AFTER
            }
        }
        add(touchModeGroup, top = 8f)
        resumeDelay = numberInput(
            "Resume after (seconds)",
            Prefs.MIN_RESUME_DELAY,
            Prefs.MAX_RESUME_DELAY,
            step = 0.5f,
        ) { prefs.touchResumeDelay = it }
        sameDirectionSwitch = switch("Resume when I scroll in the auto scroll direction") {
            prefs.resumeOnSameDirection = it
            sameDirectionDistance.enabled = it
        }
        sameDirectionDistance = slider("How far to scroll (screen height)", 10, 100, { "$it%" }) {
            prefs.sameDirectionDistance = it / 100f
        }
        add(
            text(
                "Tapping the bubble or tab always resumes right away.",
                13f,
                secondary = true,
            ),
            top = 8f,
        )

        section("Minimized tab")
        tabThickness = slider("Tab thickness", 8, 40, { "$it dp" }) { prefs.tabThicknessDp = it.toFloat() }
        tabLength = slider("Tab length", 32, 160, { "$it dp" }) { prefs.tabLengthDp = it.toFloat() }
        tabPadding = slider("Extra touch area", 0, 32, { "$it dp" }) { prefs.tabTouchPaddingDp = it.toFloat() }
        add(
            text(
                "The extra touch area is invisible. It lets you keep a thin tab that is still easy to grab.",
                13f,
                secondary = true,
            ),
            top = 8f,
        )

        section("How to use")
        add(
            text(
                "\u2022 Turn Auto scroll on above to show the floating controls. Turn it off to hide them and " +
                    "stop all background work.\n" +
                    "\u2022 Tap \u25B2 or \u25BC to start scrolling that way. Tap it again to pause.\n" +
                    "\u2022 Tap the center button to pause or resume.\n" +
                    "\u2022 Touch the screen anywhere else to pause. The center button turns amber, and a ring " +
                    "counts down if it will resume on its own. Scroll back to reread, or keep scrolling forward " +
                    "to resume sooner.\n" +
                    "\u2022 Press and drag the bubble to move it. It snaps to the nearest edge.\n" +
                    "\u2022 To minimize, drag the bubble further into the edge it is already on until it dims, " +
                    "then let go. It shrinks to a small tab and keeps scrolling. Tap the tab or pull it out " +
                    "to bring the controls back.\n" +
                    "\u2022 Press and hold the center button without moving until it turns blue and pulses, " +
                    "then drag up to scroll up or down to scroll down. The further you drag from where you " +
                    "started, the faster it scrolls. Let go to start scrolling at that speed.\n" +
                    "\u2022 Scrolling pauses while you are touching the controls.\n" +
                    "\u2022 Add the Auto Scroll Quick Settings tile to turn it on or off quickly.",
                15f,
            ),
            top = 4f,
        )

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        masterSwitch.isChecked = prefs.enabled
        updateStatus()
        autoStopSwitch.isChecked = prefs.autoStop
        speed.set(prefs.speed * 10f)
        size.set(prefs.bubbleScale * 100f)
        opacity.set(prefs.idleAlpha * 100f)
        val mode = prefs.touchPauseMode
        touchModeGroup.check(if (mode == TouchPauseMode.STAY_PAUSED) stayPausedButton.id else resumeAfterButton.id)
        resumeDelay.set(prefs.touchResumeDelay)
        resumeDelay.enabled = mode == TouchPauseMode.RESUME_AFTER
        sameDirectionSwitch.isChecked = prefs.resumeOnSameDirection
        sameDirectionDistance.set(prefs.sameDirectionDistance * 100f)
        sameDirectionDistance.enabled = prefs.resumeOnSameDirection
        tabThickness.set(prefs.tabThicknessDp)
        tabLength.set(prefs.tabLengthDp)
        tabPadding.set(prefs.tabTouchPaddingDp)
    }

    private fun updateStatus() {
        val serviceOn = isServiceEnabled()
        statusText.text = when {
            !serviceOn ->
                "The auto scroll service is not set up yet. Turn on Auto Scroll in Accessibility settings " +
                    "so it can scroll other apps."
            prefs.enabled -> "Auto scroll is ON. Use the floating controls in any app."
            else -> "Auto scroll is OFF. The floating controls are hidden and nothing runs in the background."
        }
        enableButton.text = if (serviceOn) "Accessibility settings" else "Turn on auto scroll service"
    }

    private fun isServiceEnabled(): Boolean {
        if (ScrollService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, ScrollService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun add(view: View, top: Float = 0f) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dpInt(top)
        content.addView(view, lp)
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, secondary: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (secondary) alpha = 0.7f
        }

    private fun section(title: String) {
        add(text(title, 18f, bold = true), top = 28f)
    }

    private fun switch(title: String, onChange: (Boolean) -> Unit): Switch {
        val view = Switch(this).apply {
            text = title
            textSize = 15f
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        add(view, top = 12f)
        return view
    }

    /** Adds a labelled integer slider. */
    private fun slider(
        title: String,
        min: Int,
        max: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): Control {
        val label = text(title, 15f)
        val bar = SeekBar(this).apply {
            this.max = max
            this.min = min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    label.text = "$title: ${format(progress)}"
                    if (fromUser) onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        add(label, top = 16f)
        add(bar, top = 4f)
        return Control(listOf(label, bar)) { value ->
            val progress = value.roundToInt().coerceIn(min, max)
            bar.progress = progress
            label.text = "$title: ${format(progress)}"
        }
    }

    /**
     * Adds a slider paired with a text field for typing an exact value. Typed values are clamped
     * to [min]..[max] when the field is confirmed or loses focus; the slider moves in [step]s.
     */
    private fun numberInput(
        title: String,
        min: Float,
        max: Float,
        step: Float,
        onChange: (Float) -> Unit,
    ): Control {
        val label = text(title, 15f)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            textSize = 15f
            gravity = Gravity.END
            minEms = 3
        }
        val bar = SeekBar(this).apply {
            this.min = (min / step).roundToInt()
            this.max = (max / step).roundToInt()
        }
        var current = min
        fun show(value: Float) {
            current = value
            field.setText(String.format(Locale.US, "%.1f", value))
            bar.progress = (value / step).roundToInt()
        }
        fun commit() {
            val typed = field.text.toString().toFloatOrNull()
            if (typed == null) {
                show(current)
                return
            }
            val value = typed.coerceIn(min, max)
            show(value)
            onChange(value)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                current = progress * step
                field.setText(String.format(Locale.US, "%.1f", current))
                onChange(current)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        field.setOnEditorActionListener { view, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            commit()
            view.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            true
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add(row, top = 16f)
        add(bar, top = 4f)
        return Control(listOf(label, field, bar)) { show(it) }
    }
}
