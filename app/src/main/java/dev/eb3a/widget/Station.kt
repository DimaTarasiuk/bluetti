package dev.eb3a.widget

import android.content.Context

object Target {
    const val SERIAL = "EB3A2247002180751"
}

data class Snapshot(
    val connected: Boolean,
    val status: String,
    val acOut: Int,
    val dcOut: Int,
    val acIn: Int,
    val dcIn: Int,
    val battery: Int,
    val at: Long,
) {
    val loadW: Int get() = (acOut.coerceAtLeast(0) + dcOut.coerceAtLeast(0))
    val chargeW: Int get() = (acIn.coerceAtLeast(0) + dcIn.coerceAtLeast(0))

    fun fresh(now: Long = System.currentTimeMillis()): Boolean {
        return connected && at > 0 && now - at < 20_000
    }
}

object StationStore {
    @Volatile
    var state: Snapshot = Snapshot(
        connected = false,
        status = "Не подключено",
        acOut = 0,
        dcOut = 0,
        acIn = 0,
        dcIn = 0,
        battery = -1,
        at = 0L,
    )

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun publish(context: Context, next: Snapshot) {
        state = next
        Prefs.save(context, next)
        listeners.forEach { it(next) }
    }

    fun listen(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
    }

    fun unlisten(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }
}

object Prefs {
    private const val FILE = "eb3a"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mac(context: Context): String? = sp(context).getString("mac", null)

    fun setMac(context: Context, mac: String) {
        sp(context).edit().putString("mac", mac).apply()
    }

    fun isEnabled(context: Context): Boolean = sp(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("enabled", enabled).apply()
    }

    /** 0 = непрозрачный, 100 = полностью прозрачный фон виджета. */
    fun widgetTransparency(context: Context): Int =
        sp(context).getInt("widget_transparency", 0).coerceIn(0, 100)

    fun setWidgetTransparency(context: Context, value: Int) {
        sp(context).edit().putInt("widget_transparency", value.coerceIn(0, 100)).apply()
    }

    fun nightstandTheme(context: Context): Int =
        sp(context).getInt("nightstand_theme", 0).coerceIn(0, 2)

    fun setNightstandTheme(context: Context, theme: Int) {
        sp(context).edit().putInt("nightstand_theme", theme.coerceIn(0, 2)).apply()
    }

    fun save(context: Context, snap: Snapshot) {
        sp(context).edit()
            .putBoolean("connected", snap.connected)
            .putString("status", snap.status)
            .putInt("acOut", snap.acOut)
            .putInt("dcOut", snap.dcOut)
            .putInt("acIn", snap.acIn)
            .putInt("dcIn", snap.dcIn)
            .putInt("battery", snap.battery)
            .putLong("at", snap.at)
            .apply()
    }

    fun load(context: Context): Snapshot {
        val sp = sp(context)
        return Snapshot(
            connected = sp.getBoolean("connected", false),
            status = sp.getString("status", "Не подключено") ?: "Не подключено",
            acOut = sp.getInt("acOut", 0),
            dcOut = sp.getInt("dcOut", 0),
            acIn = sp.getInt("acIn", 0),
            dcIn = sp.getInt("dcIn", 0),
            battery = sp.getInt("battery", -1),
            at = sp.getLong("at", 0L),
        )
    }
}
