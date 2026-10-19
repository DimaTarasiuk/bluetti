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

private data class NightPalette(
    val name: String,
    val background: Int,
    val brand: Int,
    val watts: Int,
    val muted: Int,
    val batteryOk: Int,
    val batteryMid: Int,
    val batteryLow: Int,
    val ac: Int,
    val dc: Int,
    val pulseOn: Int,
    val hint: Int,
)

/**
 * Режим «тумбочка»: горизонтальная заставка.
 * Нажатие на ватты переключает цветовую гамму.
 */
class NightstandActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var lastWatts = Int.MIN_VALUE
    private var themeIndex = 0
    private lateinit var palette: NightPalette

    private var windowBrightness: Float? = null
    private var touchStartY = 0f
    private var touchStartX = 0f
    private var brightAtGestureStart = 0.45f
    private var movedEnough = false
    private var adjustingBrightness = false

    private val palettes = listOf(
        NightPalette(
            name = "Лес",
            background = R.drawable.bg_night_green,
            brand = 0xFF1F8F5C.toInt(),
            watts = 0xFFEAF3EE.toInt(),
            muted = 0xFF6B7A72.toInt(),
            batteryOk = 0xFF1F8F5C.toInt(),
            batteryMid = 0xFFFFB84D.toInt(),
            batteryLow = 0xFFFF5C5C.toInt(),
            ac = 0xFF5AB0FF.toInt(),
            dc = 0xFFFFB84D.toInt(),
            pulseOn = 0xFF3DDC97.toInt(),
            hint = 0x664E5A54,
        ),
        NightPalette(
            name = "Беж",
            background = R.drawable.bg_night_beige,
            brand = 0xFFC4A574.toInt(),
            watts = 0xFFF3E6D4.toInt(),
            muted = 0xFF9A8B76.toInt(),
            batteryOk = 0xFFB8955F.toInt(),
            batteryMid = 0xFFD4A05A.toInt(),
            batteryLow = 0xFFC46B5A.toInt(),
            ac = 0xFFC4A574.toInt(),
            dc = 0xFFE0C49A.toInt(),
            pulseOn = 0xFFC4A574.toInt(),
            hint = 0x669A8B76,
        ),
        NightPalette(
            name = "Новый год",
            background = R.drawable.bg_night_nye,
            brand = 0xFFE8C76B.toInt(),
            watts = 0xFFFFF8F0.toInt(),
            muted = 0xFFB8A090.toInt(),
            batteryOk = 0xFFE8C76B.toInt(),
            batteryMid = 0xFFE8A05A.toInt(),
            batteryLow = 0xFFE04B4B.toInt(),
            ac = 0xFFE85A5A.toInt(),
            dc = 0xFFE8C76B.toInt(),
            pulseOn = 0xFFE04B4B.toInt(),
            hint = 0x66B8A090,
        ),
    )

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

        themeIndex = Prefs.nightstandTheme(this)
        applyTheme(animate = false)
        setupTouch()

        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
        tickClock()
    }

    private fun cycleTheme() {
        themeIndex = (themeIndex + 1) % palettes.size
        Prefs.setNightstandTheme(this, themeIndex)
        applyTheme(animate = true)
        showThemeHint(palettes[themeIndex].name)
        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
    }

    private fun applyTheme(animate: Boolean) {
        palette = palettes[themeIndex]
        val root = findViewById<View>(R.id.nightRoot)
        root.setBackgroundResource(palette.background)
        findViewById<TextView>(R.id.nightBrand).setTextColor(palette.brand)
        findViewById<TextView>(R.id.nightUnit).setTextColor(palette.muted)
        findViewById<TextView>(R.id.nightCaption).setTextColor(palette.muted)
        findViewById<TextView>(R.id.nightBatLabel).setTextColor(palette.muted)
        findViewById<TextView>(R.id.nightAcLabel).setTextColor(palette.ac)
        findViewById<TextView>(R.id.nightDcLabel).setTextColor(palette.dc)
        findViewById<TextView>(R.id.nightHint).setTextColor(palette.hint)
        findViewById<TextView>(R.id.nightDate).setTextColor(palette.muted)
        findViewById<TextView>(R.id.nightClock).setTextColor(palette.watts)
        findViewById<TextView>(R.id.nightStatus).setTextColor(palette.muted)
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
        if (!::palette.isInitialized) return
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
            watts.setTextColor(palette.watts)
            ac.text = "${snap.acOut} Вт"
            dc.text = "${snap.dcOut} Вт"
            ac.setTextColor(palette.watts)
            dc.setTextColor(palette.watts)
            if (snap.battery in 0..100) {
                battery.text = "${snap.battery}%"
                bar.progress = snap.battery
                val color = batteryColor(snap.battery)
                battery.setTextColor(color)
                bar.progressTintList = ColorStateList.valueOf(color)
            } else {
                battery.text = "—%"
                battery.setTextColor(palette.muted)
                bar.progress = 0
            }
            status.text = "на связи"
            status.setTextColor(palette.muted)
            setPulse(true, pulse)
        } else {
            watts.text = "—"
            watts.setTextColor(palette.muted)
            ac.text = "—"
            dc.text = "—"
            ac.setTextColor(palette.muted)
            dc.setTextColor(palette.muted)
            battery.text = "—%"
            battery.setTextColor(palette.muted)
            bar.progress = 0
            status.text = snap.status.ifBlank { "Нет связи" }
            status.setTextColor(palette.muted)
            setPulse(false, pulse)
            lastWatts = Int.MIN_VALUE
        }
    }

    private fun setPulse(on: Boolean, pulse: View) {
        pulseAnimator?.cancel()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) palette.pulseOn else palette.muted)
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
        battery >= 50 -> palette.batteryOk
        battery >= 20 -> palette.batteryMid
        else -> palette.batteryLow
    }
}
