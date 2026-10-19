package dev.eb3a.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, String>()
    private var scanning = false
    private var lastWatts = Int.MIN_VALUE
    private var lastBattery = Int.MIN_VALUE
    private var pulseAnimator: ObjectAnimator? = null
    private var entered = false
    private var themeIndex = 0
    private lateinit var theme: ColorTheme

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllPermissions()) startScan() else toast("Без этих разрешений станцию не увидеть")
        }

    private val listener: (Snapshot) -> Unit = { snap ->
        runOnUiThread { render(snap) }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val snap = StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this@MainActivity)
            findViewById<TextView>(R.id.updatedLine).text = updatedText(snap)
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        themeIndex = Prefs.colorTheme(this)
        applyColorTheme(animate = false)
        findViewById<TextView>(R.id.serialLine).text = Target.SERIAL
        findViewById<TextView>(R.id.wattsValue).setOnClickListener { cycleColorTheme() }
        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener {
            if (hasAllPermissions()) startScan() else permissionLauncher.launch(neededPermissions())
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            stopScan()
            BleService.stop(this)
        }
        findViewById<MaterialButton>(R.id.optButton).setOnClickListener { askBatteryExemption() }
        findViewById<MaterialButton>(R.id.nightstandButton).setOnClickListener {
            startActivity(Intent(this, NightstandActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.macButton).setOnClickListener { connectManualMac() }
        setupTransparencyControl()
        render(Prefs.load(this), animateEntrance = true)
        if (Prefs.isEnabled(this) && !Prefs.mac(this).isNullOrBlank()) {
            BleService.start(this)
        }
    }

    private fun setupTransparencyControl() {
        val seek = findViewById<SeekBar>(R.id.transparencySeek)
        val label = findViewById<TextView>(R.id.transparencyValue)
        val initial = Prefs.widgetTransparency(this)
        seek.progress = initial
        label.text = "$initial%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$progress%"
                if (!fromUser) return
                Prefs.setWidgetTransparency(this@MainActivity, progress)
                WidgetRenderer.push(this@MainActivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                Prefs.setWidgetTransparency(this@MainActivity, progress)
                WidgetRenderer.push(this@MainActivity)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        StationStore.listen(listener)
        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
        main.removeCallbacks(clockTick)
        main.post(clockTick)
    }

    override fun onStop() {
        StationStore.unlisten(listener)
        stopScan()
        main.removeCallbacks(clockTick)
        pulseAnimator?.cancel()
        super.onStop()
    }

    private fun neededPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += android.Manifest.permission.BLUETOOTH_SCAN
            list += android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += android.Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return neededPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Включите Bluetooth")
            try {
                startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            toast("На этом телефоне нет BLE")
            return
        }
        found.clear()
        renderDevices()
        scanning = true
        findViewById<TextView>(R.id.statusLine).text = "Ищем EB3A рядом…"
        setConnectedUi(false)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            toast("Нет разрешения на поиск Bluetooth")
            return
        }
        main.removeCallbacks(stopScanRunnable)
        main.postDelayed(stopScanRunnable, 12_000)
    }

    private val stopScanRunnable = Runnable {
        stopScan()
        if (found.isEmpty()) {
            findViewById<TextView>(R.id.statusLine).text =
                "Рядом нет EB3A. Включите станцию и поднесите телефон, либо введите MAC."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(stopScanRunnable)
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val recordName = result.scanRecord?.deviceName
            val name = result.device.name ?: recordName ?: return
            if (!name.uppercase(Locale.US).startsWith("EB3A")) return
            val mac = result.device.address ?: return
            runOnUiThread {
                val fresh = !found.containsKey(mac)
                found[mac] = name
                if (fresh) renderDevices()
                if (name.equals(Target.SERIAL, ignoreCase = true) && Prefs.mac(this@MainActivity) != mac) {
                    choose(mac, name, silent = true)
                }
            }
        }
    }

    private fun renderDevices() {
        val list = findViewById<LinearLayout>(R.id.deviceList)
        list.removeAllViews()
        if (found.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Пока пусто. Станция должна быть включена и в паре метров."
            empty.setTextColor(if (::theme.isInitialized) theme.muted else 0xFF8B9A91.toInt())
            empty.textSize = 14f
            empty.typeface = resources.getFont(R.font.outfit)
            list.addView(empty)
            return
        }
        val density = resources.displayMetrics.density
        found.forEach { (mac, name) ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val pad = (10 * density).toInt()
            row.setPadding(0, pad, 0, pad)

            val label = TextView(this)
            val yours = name.equals(Target.SERIAL, ignoreCase = true)
            label.text = if (yours) "$name\nваша станция" else "$name\n$mac"
            label.setTextColor(if (::theme.isInitialized) theme.fg else 0xFFEAF3EE.toInt())
            label.textSize = 15f
            label.typeface = resources.getFont(R.font.outfit)
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val button = MaterialButton(this)
            button.text = if (Prefs.mac(this) == mac) "Выбрана" else "Подключить"
            button.setOnClickListener { choose(mac, name, silent = false) }

            row.addView(label)
            row.addView(button)
            list.addView(row)
            row.alpha = 0f
            row.translationY = 16f
            row.animate().alpha(1f).translationY(0f).setDuration(280).start()
        }
    }

    private fun choose(mac: String, name: String, silent: Boolean = false) {
        Prefs.setMac(this, mac)
        Prefs.setEnabled(this, true)
        BleService.start(this)
        renderDevices()
        if (!silent) toast("Подключаюсь к $name")
        stopScan()
    }

    private fun connectManualMac() {
        val raw = findViewById<android.widget.EditText>(R.id.macInput).text?.toString()?.trim()?.uppercase(Locale.US)
            ?: ""
        val mac = if (raw.matches(Regex("^[0-9A-F]{12}$"))) {
            raw.chunked(2).joinToString(":")
        } else {
            raw
        }
        if (!mac.matches(Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$"))) {
            toast("MAC в виде AA:BB:CC:DD:EE:FF")
            return
        }
        if (!hasAllPermissions()) {
            permissionLauncher.launch(neededPermissions())
            return
        }
        choose(mac, mac, silent = false)
    }

    private fun render(snap: Snapshot, animateEntrance: Boolean = false) {
        val watts = findViewById<TextView>(R.id.wattsValue)
        val battery = findViewById<TextView>(R.id.batteryValue)
        val bar = findViewById<ProgressBar>(R.id.batteryBar)
        val status = findViewById<TextView>(R.id.statusLine)
        val updated = findViewById<TextView>(R.id.updatedLine)
        val fresh = snap.fresh()

        if (fresh) {
            val nextWatts = snap.loadW
            watts.text = nextWatts.toString()
            if (::theme.isInitialized) watts.setTextColor(theme.fg)
            if (lastWatts != nextWatts && lastWatts != Int.MIN_VALUE) punch(watts)
            lastWatts = nextWatts
            findViewById<TextView>(R.id.acOut).text = snap.acOut.toString()
            findViewById<TextView>(R.id.dcOut).text = snap.dcOut.toString()
            findViewById<TextView>(R.id.acIn).text = snap.acIn.toString()
            findViewById<TextView>(R.id.dcIn).text = snap.dcIn.toString()
            if (snap.battery in 0..100) {
                battery.text = "${snap.battery}%"
                animateProgress(bar, snap.battery)
                battery.setTextColor(batteryColor(snap.battery))
                tintBatteryBar(bar, snap.battery)
                lastBattery = snap.battery
            } else {
                battery.text = "—"
                animateProgress(bar, 0)
            }
            status.text = "На связи · нагрузка ${snap.loadW} Вт"
            setConnectedUi(true)
        } else {
            watts.text = "—"
            if (::theme.isInitialized) watts.setTextColor(theme.muted)
            findViewById<TextView>(R.id.acOut).text = "—"
            findViewById<TextView>(R.id.dcOut).text = "—"
            findViewById<TextView>(R.id.acIn).text = "—"
            findViewById<TextView>(R.id.dcIn).text = "—"
            battery.text = "—"
            if (::theme.isInitialized) battery.setTextColor(theme.muted)
            animateProgress(bar, 0)
            status.text = snap.status
            setConnectedUi(false)
            lastWatts = Int.MIN_VALUE
        }
        updated.text = updatedText(snap)

        if (animateEntrance && !entered) {
            entered = true
            playEntrance()
        }
    }

    private fun updatedText(snap: Snapshot): String {
        return if (snap.at == 0L) {
            "Показаний ещё не было"
        } else {
            val sec = ((System.currentTimeMillis() - snap.at) / 1000).coerceAtLeast(0)
            "Обновлено ${sec} с назад"
        }
    }

    private fun batteryColor(battery: Int): Int = when {
        battery >= 50 -> theme.batteryOk
        battery >= 20 -> theme.batteryMid
        else -> theme.batteryLow
    }

    private fun tintBatteryBar(bar: ProgressBar, battery: Int) {
        val color = batteryColor(battery)
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressDrawable?.setTint(color)
    }

    private fun setConnectedUi(connected: Boolean) {
        val pulse = findViewById<View>(R.id.statusPulse)
        pulseAnimator?.cancel()
        val color = if (::theme.isInitialized) {
            if (connected) theme.accent else theme.muted
        } else {
            if (connected) 0xFF3DDC97.toInt() else 0xFF8B9A91.toInt()
        }
        pulse.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        if (connected) {
            pulseAnimator = ObjectAnimator.ofFloat(pulse, View.ALPHA, 1f, 0.25f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulse.alpha = 1f
        }
    }

    private fun cycleColorTheme() {
        themeIndex = ColorThemes.nextIndex(themeIndex)
        Prefs.setColorTheme(this, themeIndex)
        applyColorTheme(animate = true)
        toast(theme.name)
        render(StationStore.state.at.takeIf { it > 0 }?.let { StationStore.state } ?: Prefs.load(this))
    }

    private fun applyColorTheme(animate: Boolean) {
        theme = ColorThemes.get(themeIndex)
        val root = findViewById<View>(R.id.rootScroll)
        root.setBackgroundResource(theme.mainBackground)

        fun c(id: Int, color: Int) {
            findViewById<TextView>(id).setTextColor(color)
        }
        c(R.id.brandLabel, theme.brand)
        c(R.id.titleLabel, theme.fg)
        c(R.id.serialLine, theme.muted)
        c(R.id.wattsUnit, theme.muted)
        c(R.id.wattsCaption, theme.muted)
        c(R.id.statusLine, theme.fg)
        c(R.id.updatedLine, theme.muted)
        c(R.id.batteryLabel, theme.muted)
        c(R.id.metricsHeader, theme.muted)
        c(R.id.acOutLabel, theme.ac)
        c(R.id.dcOutLabel, theme.dc)
        c(R.id.acInLabel, theme.muted)
        c(R.id.dcInLabel, theme.accent)
        c(R.id.connectHeader, theme.muted)
        c(R.id.connectTitle, theme.fg)
        c(R.id.transparencyTitle, theme.fg)
        c(R.id.transparencyValue, theme.accent)
        c(R.id.footerTip, theme.muted)

        findViewById<TextView>(R.id.acOut).setTextColor(theme.fg)
        findViewById<TextView>(R.id.dcOut).setTextColor(theme.fg)
        findViewById<TextView>(R.id.acIn).setTextColor(theme.fg)
        findViewById<TextView>(R.id.dcIn).setTextColor(theme.fg)

        tintSurface(R.id.connectBlock)
        tintSurface(R.id.transparencyBlock)
        listOf(
            findViewById<View>(R.id.acOut).parent as View,
            findViewById<View>(R.id.dcOut).parent as View,
            findViewById<View>(R.id.acIn).parent as View,
            findViewById<View>(R.id.dcIn).parent as View,
        ).forEach { tile ->
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * resources.displayMetrics.density
                setColor(theme.surface)
            }
        }

        val scan = findViewById<MaterialButton>(R.id.scanButton)
        scan.backgroundTintList = ColorStateList.valueOf(theme.accent)
        scan.setTextColor(if (themeIndex == 2) 0xFFFFF8F0.toInt() else 0xFF070B0A.toInt())
        val seek = findViewById<SeekBar>(R.id.transparencySeek)
        seek.progressTintList = ColorStateList.valueOf(theme.accent)
        seek.thumbTintList = ColorStateList.valueOf(theme.accent)

        if (animate) {
            root.alpha = 0.9f
            root.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun tintSurface(id: Int) {
        val view = findViewById<View>(id)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22 * resources.displayMetrics.density
            setColor(theme.surface)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), theme.line)
        }
    }

    private fun punch(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(120)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            .start()
    }

    private fun animateProgress(bar: ProgressBar, target: Int) {
        val start = bar.progress
        if (start == target) return
        ValueAnimator.ofInt(start, target).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
    }

    private fun playEntrance() {
        val blocks = listOf(
            R.id.heroBlock,
            R.id.batteryBlock,
            R.id.metricsBlock,
            R.id.connectBlock,
            R.id.transparencyBlock,
            R.id.footerTip,
        )
        blocks.forEachIndexed { index, id ->
            val view = findViewById<View>(id)
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 70).toLong())
                .setDuration(480)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
        val watts = findViewById<View>(R.id.wattsValue)
        watts.scaleX = 0.92f
        watts.scaleY = 0.92f
        watts.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(120)
            .setDuration(520)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Экономия батареи уже не мешает")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
