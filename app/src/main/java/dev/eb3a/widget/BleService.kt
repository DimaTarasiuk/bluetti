package dev.eb3a.widget

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class BleService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val buffer = ArrayList<Byte>(160)
    private var ready = false
    private var commandOutstanding = false
    private var failStreak = 0
    private var servicesHooked = false
    private var activeMac: String? = null
    private var session = 0
    private var watchdog: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StationStore.state = Prefs.load(this).copy(connected = false)
        ensureChannel()
        promote("Подключение к EB3A…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            val idle = StationStore.state.copy(
                connected = false,
                status = "Отключено",
                at = System.currentTimeMillis(),
            )
            StationStore.publish(this, idle)
            WidgetRenderer.push(this)
            closeGatt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setEnabled(this, true)
        main.removeCallbacks(connectRunnable)
        main.post(connectRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        closeGatt()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.isEnabled(this)) {
            val restart = Intent(this, BleService::class.java)
            val pi = PendingIntent.getService(
                this,
                1,
                restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            try {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pi)
            } catch (e: Exception) {
                Log.w(TAG, "restart alarm failed", e)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private val connectRunnable = Runnable { connect() }
    private val pollRunnable = Runnable { sendRead() }
    private var discoverFallback: Runnable? = null

    private val responseWatchdog = Runnable {
        if (!commandOutstanding) return@Runnable
        commandOutstanding = false
        buffer.clear()
        failStreak += 1
        Log.w(TAG, "response timeout streak=$failStreak")
        if (failStreak >= 3) dropAndRetry("Станция не отвечает", 4000) else armPoll(700)
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (!Prefs.isEnabled(this)) return
        val mac = Prefs.mac(this)
        if (mac.isNullOrBlank()) {
            showIdle("Откройте приложение и выберите станцию")
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            showIdle("Bluetooth выключен")
            scheduleConnect(8000)
            return
        }
        if (ready && activeMac == mac && gatt != null) return

        closeGatt()
        val token = session
        activeMac = mac
        showIdle("Подключение…")
        try {
            val device = adapter.getRemoteDevice(mac)
            gatt = device.connectGatt(this, false, newCallback(token), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt", e)
            showIdle("Не удалось начать подключение")
            scheduleConnect(8000)
            return
        }
        val watch = Runnable {
            if (token == session && !ready) dropAndRetry("Нет связи, повтор…", 4000)
        }
        watchdog = watch
        main.postDelayed(watch, 18_000)
        val discover = Runnable {
            if (token != session || ready) return@Runnable
            try {
                gatt?.discoverServices()
            } catch (e: SecurityException) {
                dropAndRetry("Нет разрешения Bluetooth", 8000)
            }
        }
        discoverFallback = discover
        main.postDelayed(discover, 1600)
    }

    private fun scheduleConnect(delayMs: Long) {
        main.removeCallbacks(connectRunnable)
        main.postDelayed(connectRunnable, delayMs)
    }

    private fun armPoll(delayMs: Long) {
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, delayMs)
    }

    private fun showIdle(status: String) {
        val next = StationStore.state.copy(
            connected = false,
            status = status,
            at = System.currentTimeMillis(),
        )
        StationStore.publish(this, next)
        WidgetRenderer.push(this)
        updateNotification(status)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        session += 1
        ready = false
        servicesHooked = false
        commandOutstanding = false
        buffer.clear()
        writeChar = null
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(responseWatchdog)
        watchdog?.let { main.removeCallbacks(it) }
        discoverFallback?.let { main.removeCallbacks(it) }
        val current = gatt
        gatt = null
        try {
            current?.disconnect()
        } catch (_: Exception) {
        }
        try {
            current?.close()
        } catch (_: Exception) {
        }
    }

    private fun dropAndRetry(status: String, delayMs: Long) {
        showIdle(status)
        closeGatt()
        if (Prefs.isEnabled(this)) scheduleConnect(delayMs)
    }

    private fun newCallback(token: Int) = object : BluetoothGattCallback() {
        private fun onMain(block: () -> Unit) {
            main.post {
                if (token != session) return@post
                block()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onMain { handleConnection(gatt, status, newState) }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onMain {
                if (ready) return@onMain
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    dropAndRetry("Нет разрешения Bluetooth", 8000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onMain {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    dropAndRetry("Службы станции не найдены", 5000)
                    return@onMain
                }
                hook(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            onMain {
                if (descriptor.uuid != CCCD_UUID) return@onMain
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    dropAndRetry("Не удалось подписаться на данные", 5000)
                    return@onMain
                }
                markReady()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            onMain { ingest(copy) }
        }

        @Deprecated("Legacy callback for API 26–32")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val copy = value.copyOf()
            onMain { ingest(copy) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnection(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            ready = false
            commandOutstanding = false
            dropAndRetry(if (status == BluetoothGatt.GATT_SUCCESS) "Связь прервана" else "Связь оборвалась ($status)", 3500)
            return
        }
        if (newState != BluetoothProfile.STATE_CONNECTED) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            dropAndRetry("Ошибка Bluetooth $status", 5000)
            return
        }
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (!gatt.requestMtu(247)) gatt.discoverServices()
        } catch (e: SecurityException) {
            dropAndRetry("Нет разрешения Bluetooth", 8000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun hook(gatt: BluetoothGatt) {
        discoverFallback?.let { main.removeCallbacks(it) }
        if (servicesHooked) return
        servicesHooked = true
        val service = gatt.services.firstOrNull { it.uuid == SERVICE_UUID }
        if (service == null) {
            Log.w(TAG, "services=" + gatt.services.joinToString { it.uuid.toString() })
            dropAndRetry("Это не классический протокол EB3A", 8000)
            return
        }
        val notify = service.getCharacteristic(NOTIFY_UUID)
        writeChar = service.getCharacteristic(WRITE_UUID)
        if (notify == null || writeChar == null) {
            dropAndRetry("У станции нет канала данных", 8000)
            return
        }
        val props = notify.properties
        val notifyOk = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!notifyOk) {
            dropAndRetry("Канал станции без уведомлений", 8000)
            return
        }
        gatt.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            markReady()
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        if (!ok) dropAndRetry("Не удалось подписаться на данные", 5000)
    }

    private fun markReady() {
        ready = true
        failStreak = 0
        watchdog?.let { main.removeCallbacks(it) }
        showIdle("Connected, reading load…")
        armPoll(350)
    }

    @SuppressLint("MissingPermission")
    private fun sendRead() {
        val current = gatt
        val ch = writeChar
        if (!ready || current == null || ch == null) return
        if (commandOutstanding) return
        val packet = Modbus.readHolding()
        val canNoResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (canNoResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        commandOutstanding = true
        buffer.clear()
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(ch, packet, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                ch.value = packet
                @Suppress("DEPRECATION")
                current.writeCharacteristic(ch)
            }
        } catch (e: SecurityException) {
            dropAndRetry("Нет разрешения Bluetooth", 8000)
            return
        }
        if (!ok) {
            commandOutstanding = false
            failStreak += 1
            if (failStreak >= 3) dropAndRetry("Команда не ушла", 4000) else armPoll(800)
            return
        }
        main.removeCallbacks(responseWatchdog)
        main.postDelayed(responseWatchdog, 5000)
    }

    private fun ingest(value: ByteArray) {
        if (!commandOutstanding) return
        if (value.size >= 2 && value[0] == 'A'.code.toByte() && value[1] == 'T'.code.toByte()) {
            dropAndRetry("Станция сбросила связь", 4000)
            return
        }
        for (b in value) buffer.add(b)
        if (buffer.size >= 2 && (buffer[1].toInt() and 0xFF) == 0x83) {
            if (buffer.size >= 5) {
                commandOutstanding = false
                buffer.clear()
                failStreak += 1
                if (failStreak >= 4) dropAndRetry("Станция отклонила запрос", 4000) else armPoll(1200)
            }
            return
        }
        if (buffer.size < Modbus.RESPONSE_SIZE) return
        val frame = ByteArray(Modbus.RESPONSE_SIZE) { buffer[it] }
        commandOutstanding = false
        buffer.clear()
        main.removeCallbacks(responseWatchdog)
        val sample = Modbus.parseCore(frame)
        if (sample == null) {
            failStreak += 1
            Log.w(TAG, "bad frame streak=$failStreak hex=${frame.take(8).joinToString("") { "%02x".format(it) }}")
            if (failStreak >= 4) dropAndRetry("Не удалось разобрать ответ", 4000) else armPoll(800)
            return
        }
        failStreak = 0
        val snap = Snapshot(
            connected = true,
            status = "Connected",
            acOut = sample.acOut,
            dcOut = sample.dcOut,
            acIn = sample.acIn,
            dcIn = sample.dcIn,
            battery = sample.battery,
            at = System.currentTimeMillis(),
        )
        StationStore.publish(this, snap)
        WidgetRenderer.push(this)
        updateNotification()
        armPoll(4000)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "EB3A link", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Load and battery on lock screen and Always On"
        channel.setShowBadge(false)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)
    }

    private fun promote(text: String) {
        val notification = buildNotification(status = text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(status: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(status = status))
    }

    /**
     * Ватты и % в заголовке — так их чаще видно на Always On / lock screen.
     * Свой экран AOD Android сторонним приложениям не даёт.
     */
    private fun buildNotification(status: String? = null): android.app.Notification {
        val snap = StationStore.state
        val fresh = snap.fresh()
        val battery = if (snap.battery in 0..100) "${snap.battery}%" else "—%"
        val title = if (fresh) {
            "${snap.loadW} Вт · $battery"
        } else {
            status ?: snap.status.ifBlank { "Нет связи" }
        }
        val body = if (fresh) {
            "Bluetti EB3A · connected"
        } else {
            "Bluetti EB3A"
        }

        val compact = RemoteViews(packageName, R.layout.notification_live).apply {
            setTextViewText(R.id.notif_watts, if (fresh) "${snap.loadW} Вт" else "— Вт")
            setTextViewText(R.id.notif_battery, if (fresh) battery else "—%")
            setTextColor(
                R.id.notif_battery,
                if (fresh && snap.battery in 0..100) {
                    when {
                        snap.battery >= 50 -> 0xFF1F8F5C.toInt()
                        snap.battery >= 20 -> 0xFFFFB84D.toInt()
                        else -> 0xFFFF5C5C.toInt()
                    }
                } else {
                    0xFF8B9A91.toInt()
                },
            )
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(if (fresh) battery else null)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(compact)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "EB3A"
        private const val CHANNEL = "eb3a_link"
        private const val NOTIF_ID = 41
        const val ACTION_STOP = "dev.eb3a.widget.STOP"

        private val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BleService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BleService::class.java).setAction(ACTION_STOP))
        }
    }
}
