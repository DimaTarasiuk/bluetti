package dev.eb3a.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Режим «тумбочка»: горизонтальная заставка.
 * Нажатие на ватты переключает цветовую гамму.
 */
class NightstandActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var lastWatts = Int.MIN_VALUE
    private var themeIndex = 0
    private lateinit var theme: ColorTheme

    private var windowBrightness: Float? = null
    private var touchStartY = 0f
    private var touchStartX = 0f
    private var brightAtGestureStart = 0.45f
    private var movedEnough = false
    private var adjustingBrightness = false

    private val hideBrightHint = Runnable {
        findViewById<View>(R.id.nightBrightHint).animate().alpha(0f).setDuration(220).withEndAction {
            findViewById<View>(R.id.nightBrightHint).visibility = View.GONE
            findViewById<View>(R.id.nightBrightHint).alpha = 1f
        }.start()
    }

    private val listener: (Snapshot) -> Unit = { snap ->
        runOnUiThread { render(snap) }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            tickClock()
            val snap = StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state }
                ?: Prefs.load(this@NightstandActivity)
            if (!snap.fresh()) {
                findViewById<TextView>(R.id.nightStatus).text = snap.status.ifBlank { "Нет связи" }
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_nightstand)
        hideSystemUi()

        themeIndex = Prefs.colorTheme(this)
        applyTheme(animate = false)
        setupTouch()

        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
        tickClock()
    }

    private fun cycleTheme() {
        themeIndex = ColorThemes.nextIndex(themeIndex)
        Prefs.setColorTheme(this, themeIndex)
        applyTheme(animate = true)
        showThemeHint(theme.name)
        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
    }

    private fun applyTheme(animate: Boolean) {
        theme = ColorThemes.get(themeIndex)
        val root = findViewById<View>(R.id.nightRoot)
        root.setBackgroundResource(theme.nightBackground)
        findViewById<TextView>(R.id.nightBrand).setTextColor(theme.brand)
        findViewById<TextView>(R.id.nightUnit).setTextColor(theme.muted)
        findViewById<TextView>(R.id.nightCaption).setTextColor(theme.muted)
        findViewById<TextView>(R.id.nightBatLabel).setTextColor(theme.muted)
        findViewById<TextView>(R.id.nightAcLabel).setTextColor(theme.ac)
        findViewById<TextView>(R.id.nightDcLabel).setTextColor(theme.dc)
        findViewById<TextView>(R.id.nightHint).setTextColor((theme.muted and 0x00FFFFFF) or 0x66000000)
        findViewById<TextView>(R.id.nightDate).setTextColor(theme.muted)
        findViewById<TextView>(R.id.nightClock).setTextColor(theme.fg)
        findViewById<TextView>(R.id.nightStatus).setTextColor(theme.muted)
        if (animate) {
            root.alpha = 0.88f
            root.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun showThemeHint(name: String) {
        val hint = findViewById<TextView>(R.id.nightBrightHint)
        hint.text = name
        hint.visibility = View.VISIBLE
        hint.alpha = 1f
        main.removeCallbacks(hideBrightHint)
        main.postDelayed(hideBrightHint, 1100)
    }

    private fun setupTouch() {
        val root = findViewById<View>(R.id.nightRoot)
        val watts = findViewById<View>(R.id.nightWatts)
        val density = resources.displayMetrics.density
        val tapSlop = 24f * density
        val fullSwipe = 280f * density

        // Нажатие на ватты — смена темы (не закрывает экран)
        watts.setOnClickListener { cycleTheme() }

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    touchStartX = event.x
                    movedEnough = false
                    adjustingBrightness = false
                    brightAtGestureStart = currentBrightness()
                    // если палец на ваттах — не перехватываем, пусть сработает click
                    val onWatts = isInView(watts, event.rawX, event.rawY)
                    !onWatts
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = touchStartY - event.y
                    val dx = event.x - touchStartX
                    if (!movedEnough && (abs(dy) > tapSlop || abs(dx) > tapSlop)) {
                        movedEnough = true
                        adjustingBrightness = abs(dy) > abs(dx)
                    }
                    if (adjustingBrightness) {
                        val next = (brightAtGestureStart + dy / fullSwipe).coerceIn(0.02f, 1f)
                        applyBrightness(next)
                        showBrightHint(next)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!movedEnough && event.actionMasked == MotionEvent.ACTION_UP) {
                        finish()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun isInView(view: View, rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = rawX.toInt()
        val y = rawY.toInt()
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun currentBrightness(): Float {
        windowBrightness?.let { return it }
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.4f
        }.coerceIn(0.02f, 1f)
    }

    private fun applyBrightness(value: Float) {
        windowBrightness = value
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun showBrightHint(value: Float) {
        val hint = findViewById<TextView>(R.id.nightBrightHint)
        hint.text = "Яркость ${(value * 100).toInt()}%"
        hint.visibility = View.VISIBLE
        hint.alpha = 1f
        main.removeCallbacks(hideBrightHint)
        main.postDelayed(hideBrightHint, 900)
    }

    override fun onStart() {
        super.onStart()
        StationStore.listen(listener)
        main.removeCallbacks(clockTick)
        main.post(clockTick)
    }

    override fun onStop() {
        StationStore.unlisten(listener)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(hideBrightHint)
        pulseAnimator?.cancel()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun tickClock() {
        val now = Date()
        findViewById<TextView>(R.id.nightClock).text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        findViewById<TextView>(R.id.nightDate).text =
            SimpleDateFormat("d MMMM, EEEE", Locale("ru")).format(now)
    }

    private fun render(snap: Snapshot) {
        if (!::theme.isInitialized) return
        val watts = findViewById<TextView>(R.id.nightWatts)
        val battery = findViewById<TextView>(R.id.nightBattery)
        val bar = findViewById<ProgressBar>(R.id.nightBatteryBar)
        val status = findViewById<TextView>(R.id.nightStatus)
        val pulse = findViewById<View>(R.id.nightPulse)
        val ac = findViewById<TextView>(R.id.nightAc)
        val dc = findViewById<TextView>(R.id.nightDc)

        if (snap.fresh()) {
            val next = snap.loadW
            watts.text = next.toString()
            if (lastWatts != next && lastWatts != Int.MIN_VALUE) {
                watts.animate().cancel()
                watts.scaleX = 1f
                watts.scaleY = 1f
                watts.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction {
                    watts.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
            }
            lastWatts = next
            watts.setTextColor(theme.fg)
            ac.text = "${snap.acOut} Вт"
            dc.text = "${snap.dcOut} Вт"
            ac.setTextColor(theme.fg)
            dc.setTextColor(theme.fg)
            if (snap.battery in 0..100) {
                battery.text = "${snap.battery}%"
                bar.progress = snap.battery
                val color = batteryColor(snap.battery)
                battery.setTextColor(color)
                bar.progressTintList = ColorStateList.valueOf(color)
            } else {
                battery.text = "—%"
                battery.setTextColor(theme.muted)
                bar.progress = 0
            }
            status.text = "на связи"
            status.setTextColor(theme.muted)
            setPulse(true, pulse)
        } else {
            watts.text = "—"
            watts.setTextColor(theme.muted)
            ac.text = "—"
            dc.text = "—"
            ac.setTextColor(theme.muted)
            dc.setTextColor(theme.muted)
            battery.text = "—%"
            battery.setTextColor(theme.muted)
            bar.progress = 0
            status.text = snap.status.ifBlank { "Нет связи" }
            status.setTextColor(theme.muted)
            setPulse(false, pulse)
            lastWatts = Int.MIN_VALUE
        }
    }

    private fun setPulse(on: Boolean, pulse: View) {
        pulseAnimator?.cancel()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) theme.accent else theme.muted)
        }
        pulse.background = dot
        if (on) {
            pulseAnimator = ObjectAnimator.ofFloat(pulse, View.ALPHA, 1f, 0.25f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulse.alpha = 1f
        }
    }

    private fun batteryColor(battery: Int): Int = when {
        battery >= 50 -> theme.batteryOk
        battery >= 20 -> theme.batteryMid
        else -> theme.batteryLow
    }
}
