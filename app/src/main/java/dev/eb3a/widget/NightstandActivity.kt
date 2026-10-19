package dev.eb3a.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * Режим «тумбочка» / nightstand: горизонтальная заставка
 * с крупными ваттами и зарядом, экран не гаснет.
 */
class NightstandActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var lastWatts = Int.MIN_VALUE

    private val listener: (Snapshot) -> Unit = { snap ->
        runOnUiThread { render(snap) }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            tickClock()
            val snap = StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state }
                ?: Prefs.load(this@NightstandActivity)
            // refresh relative status line lightly
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_nightstand)
        hideSystemUi()

        findViewById<View>(R.id.nightRoot).setOnClickListener { finish() }

        // чуть приглушить яркость для ночного режима
        val lp = window.attributes
        if (lp.screenBrightness < 0f || lp.screenBrightness > 0.55f) {
            lp.screenBrightness = 0.45f
            window.attributes = lp
        }

        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
        tickClock()
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
            watts.setTextColor(0xFFEAF3EE.toInt())
            ac.text = "${snap.acOut} Вт"
            dc.text = "${snap.dcOut} Вт"
            if (snap.battery in 0..100) {
                battery.text = "${snap.battery}%"
                bar.progress = snap.battery
                val color = batteryColor(snap.battery)
                battery.setTextColor(color)
                bar.progressTintList = ColorStateList.valueOf(color)
            } else {
                battery.text = "—%"
                bar.progress = 0
            }
            status.text = "на связи"
            setPulse(true, pulse)
        } else {
            watts.text = "—"
            watts.setTextColor(0xFF6B7A72.toInt())
            ac.text = "—"
            dc.text = "—"
            battery.text = "—%"
            battery.setTextColor(0xFF6B7A72.toInt())
            bar.progress = 0
            status.text = snap.status.ifBlank { "Нет связи" }
            setPulse(false, pulse)
            lastWatts = Int.MIN_VALUE
        }
    }

    private fun setPulse(on: Boolean, pulse: View) {
        pulseAnimator?.cancel()
        pulse.setBackgroundResource(if (on) R.drawable.pulse_dot else R.drawable.pulse_dot_off)
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
        battery >= 50 -> 0xFF1F8F5C.toInt()
        battery >= 20 -> 0xFFFFB84D.toInt()
        else -> 0xFFFF5C5C.toInt()
    }
}
