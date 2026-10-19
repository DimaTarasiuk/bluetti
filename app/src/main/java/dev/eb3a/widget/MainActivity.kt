package dev.eb3a.widget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, String>()
    private var scanning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllPermissions()) startScan() else toast("Без этих разрешений станцию не увидеть")
        }

    private val listener: (Snapshot) -> Unit = { snap ->
        runOnUiThread { render(snap) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.serialLine).text = Target.SERIAL
        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener {
            if (hasAllPermissions()) startScan() else permissionLauncher.launch(neededPermissions())
        }
        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            stopScan()
            BleService.stop(this)
        }
        findViewById<MaterialButton>(R.id.optButton).setOnClickListener { askBatteryExemption() }
        findViewById<MaterialButton>(R.id.macButton).setOnClickListener { connectManualMac() }
        setupTransparencyControl()
        render(Prefs.load(this))
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
    }

    override fun onStop() {
        StationStore.unlisten(listener)
        stopScan()
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
            empty.setTextColor(0xFF8EA097.toInt())
            empty.textSize = 14f
            list.addView(empty)
            return
        }
        val density = resources.displayMetrics.density
        found.forEach { (mac, name) ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            row.setPadding(0, pad, 0, pad)

            val label = TextView(this)
            val yours = name.equals(Target.SERIAL, ignoreCase = true)
            label.text = if (yours) "$name\nваша станция" else "$name\n$mac"
            label.setTextColor(0xFFE7F0EA.toInt())
            label.textSize = 15f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val button = MaterialButton(this)
            button.text = if (Prefs.mac(this) == mac) "Выбрана" else "Подключить"
            button.setOnClickListener { choose(mac, name, silent = false) }

            row.addView(label)
            row.addView(button)
            list.addView(row)
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

    private fun render(snap: Snapshot) {
        val watts = findViewById<TextView>(R.id.wattsValue)
        val battery = findViewById<TextView>(R.id.batteryValue)
        val bar = findViewById<android.widget.ProgressBar>(R.id.batteryBar)
        val status = findViewById<TextView>(R.id.statusLine)
        val updated = findViewById<TextView>(R.id.updatedLine)
        if (snap.fresh()) {
            watts.text = snap.loadW.toString()
            findViewById<TextView>(R.id.acOut).text = snap.acOut.toString()
            findViewById<TextView>(R.id.dcOut).text = snap.dcOut.toString()
            findViewById<TextView>(R.id.acIn).text = snap.acIn.toString()
            findViewById<TextView>(R.id.dcIn).text = snap.dcIn.toString()
            if (snap.battery in 0..100) {
                battery.text = "${snap.battery}%"
                bar.progress = snap.battery
            } else {
                battery.text = "—"
                bar.progress = 0
            }
            status.text = "На связи · нагрузка ${snap.loadW} Вт"
        } else {
            watts.text = "—"
            findViewById<TextView>(R.id.acOut).text = "—"
            findViewById<TextView>(R.id.dcOut).text = "—"
            findViewById<TextView>(R.id.acIn).text = "—"
            findViewById<TextView>(R.id.dcIn).text = "—"
            battery.text = "—"
            bar.progress = 0
            status.text = snap.status
        }
        updated.text = if (snap.at == 0L) {
            "Показаний ещё не было"
        } else {
            val sec = ((System.currentTimeMillis() - snap.at) / 1000).coerceAtLeast(0)
            "Обновлено ${sec} с назад"
        }
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
