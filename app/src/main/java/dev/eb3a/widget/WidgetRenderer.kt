package dev.eb3a.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews

enum class WidgetKind(val provider: Class<out BaseWidget>) {
    POWER(PowerWidget::class.java),
    COMPACT(CompactWidget::class.java),
    RING(RingWidget::class.java),
}

object WidgetRenderer {
    private val FG = 0xFFE7F0EA.toInt()
    private val MUTED = 0xFF8EA097.toInt()
    private val ACCENT = 0xFF3DDC97.toInt()
    private val BATTERY_OK = 0xFF1F8F5C.toInt()
    private val AMBER = 0xFFFFB84D.toInt()
    private val BLUE = 0xFF4DA3FF.toInt()
    private val VIOLET = 0xFFB28DFF.toInt()
    private val DANGER = 0xFFFF5C5C.toInt()
    private val TRACK = 0xFF2A3832.toInt()

    fun push(context: Context) {
        val live = StationStore.state
        val snap = if (live.at > 0) live else Prefs.load(context)
        val manager = AppWidgetManager.getInstance(context)
        for (kind in WidgetKind.values()) {
            val ids = manager.getAppWidgetIds(ComponentName(context, kind.provider))
            if (ids.isEmpty()) continue
            manager.updateAppWidget(ids, build(context, snap, kind))
        }
    }

    fun build(context: Context, snap: Snapshot, kind: WidgetKind = WidgetKind.POWER): RemoteViews {
        val views = when (kind) {
            WidgetKind.POWER -> power(context, snap)
            WidgetKind.COMPACT -> compact(context, snap)
            WidgetKind.RING -> ring(context, snap)
        }
        chrome(context, views)
        return views
    }

    /** Общая прозрачность фона и открытие приложения по тапу. */
    private fun chrome(context: Context, views: RemoteViews) {
        val alpha = (100 - Prefs.widgetTransparency(context)) * 255 / 100
        views.setInt(R.id.widget_bg, "setImageAlpha", alpha)
        val open = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
    }

    private fun batteryColor(battery: Int): Int = when {
        battery < 0 -> MUTED
        battery >= 50 -> BATTERY_OK
        battery >= 20 -> AMBER
        else -> DANGER
    }

    private fun batteryText(snap: Snapshot): String =
        if (snap.battery in 0..100) "${snap.battery}%" else "—%"

    private fun pct(value: Int, max: Int): Int =
        (value.coerceAtLeast(0) * 100 / max).coerceIn(0, 100)

    private fun power(context: Context, snap: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_power)
        if (snap.fresh()) {
            views.setTextViewText(R.id.widget_watts, snap.loadW.toString())
            views.setTextColor(R.id.widget_watts, FG)
            views.setTextViewText(R.id.widget_unit, "Вт")
            views.setTextViewText(R.id.widget_battery, batteryText(snap))
            views.setTextColor(R.id.widget_battery, batteryColor(snap.battery))
            views.setTextViewText(R.id.widget_split, "AC ${snap.acOut}   ·   DC ${snap.dcOut}")
            val foot = if (snap.chargeW > 0) "заряд ${snap.chargeW} Вт" else "без заряда"
            views.setTextViewText(R.id.widget_status, foot)
        } else {
            views.setTextViewText(R.id.widget_watts, "—")
            views.setTextColor(R.id.widget_watts, MUTED)
            views.setTextViewText(R.id.widget_unit, "Вт")
            views.setTextViewText(R.id.widget_battery, "—%")
            views.setTextColor(R.id.widget_battery, MUTED)
            views.setTextViewText(R.id.widget_split, "AC —   ·   DC —")
            views.setTextViewText(R.id.widget_status, snap.status.ifBlank { "Нет связи" })
        }
        return views
    }

    private fun compact(context: Context, snap: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact)
        if (snap.fresh()) {
            views.setTextViewText(R.id.compact_caption, "AC ${snap.acOut}  ·  DC ${snap.dcOut}")
            views.setTextViewText(R.id.compact_watts, snap.loadW.toString())
            views.setTextColor(R.id.compact_watts, FG)
            views.setTextViewText(R.id.compact_battery, batteryText(snap))
            views.setTextColor(R.id.compact_battery, batteryColor(snap.battery))
            views.setTextViewText(
                R.id.compact_charge,
                if (snap.chargeW > 0) "+${snap.chargeW} Вт" else "",
            )
        } else {
            views.setTextViewText(R.id.compact_caption, snap.status.ifBlank { "Нет связи" })
            views.setTextViewText(R.id.compact_watts, "—")
            views.setTextColor(R.id.compact_watts, MUTED)
            views.setTextViewText(R.id.compact_battery, "—%")
            views.setTextColor(R.id.compact_battery, MUTED)
            views.setTextViewText(R.id.compact_charge, "")
        }
        return views
    }

    private fun ring(context: Context, snap: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_ring)
        val fresh = snap.fresh()
        val battery = if (fresh) snap.battery else -1
        val load = if (fresh) snap.loadW else 0
        views.setImageViewBitmap(R.id.ring_img, ringBitmap(context, battery, load))
        views.setTextViewText(R.id.ring_watts, if (fresh) snap.loadW.toString() else "—")
        views.setTextColor(R.id.ring_watts, if (fresh) FG else MUTED)
        views.setTextViewText(R.id.ring_battery, if (fresh) batteryText(snap) else "—%")
        views.setTextColor(R.id.ring_battery, batteryColor(battery))
        return views
    }



    /** Внешнее кольцо — батарея, тонкое внутреннее — нагрузка (до 600 Вт). */
    private fun ringBitmap(context: Context, battery: Int, load: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = (160 * d).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val outerW = 12 * d
        val outer = RectF(outerW / 2, outerW / 2, size - outerW / 2, size - outerW / 2)
        paint.strokeWidth = outerW
        paint.color = TRACK
        canvas.drawArc(outer, 0f, 360f, false, paint)
        if (battery > 0) {
            paint.color = batteryColor(battery)
            canvas.drawArc(outer, -90f, 360f * battery.coerceIn(0, 100) / 100f, false, paint)
        }

        val innerW = 5 * d
        val inset = outerW + 7 * d + innerW / 2
        val inner = RectF(inset, inset, size - inset, size - inset)
        paint.strokeWidth = innerW
        paint.color = TRACK
        canvas.drawArc(inner, 0f, 360f, false, paint)
        if (load > 0) {
            paint.color = BLUE
            canvas.drawArc(inner, -90f, 360f * pct(load, 600) / 100f, false, paint)
        }
        return bitmap
    }
}
