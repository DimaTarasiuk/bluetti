package dev.eb3a.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.RemoteViews

object WidgetRenderer {
    fun push(context: Context) {
        val live = StationStore.state
        val snap = if (live.at > 0) live else Prefs.load(context)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PowerWidget::class.java))
        if (ids.isEmpty()) return
        val views = build(context, snap)
        manager.updateAppWidget(ids, views)
    }

    fun build(context: Context, snap: Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_power)
        val fresh = snap.fresh()
        val fg = 0xFFE7F0EA.toInt()
        val muted = 0xFF8EA097.toInt()
        val accent = 0xFF3DDC97.toInt()

        if (fresh) {
            views.setTextViewText(R.id.widget_watts, snap.loadW.toString())
            views.setTextColor(R.id.widget_watts, fg)
            views.setTextViewText(R.id.widget_unit, "Вт")
            val battery = if (snap.battery in 0..100) "${snap.battery}%" else "—%"
            views.setTextViewText(R.id.widget_battery, battery)
            views.setTextColor(R.id.widget_battery, accent)
            views.setTextViewText(
                R.id.widget_split,
                "AC ${snap.acOut}   ·   DC ${snap.dcOut}",
            )
            val foot = if (snap.chargeW > 0) "заряд ${snap.chargeW} Вт" else "без заряда"
            views.setTextViewText(R.id.widget_status, foot)
        } else {
            views.setTextViewText(R.id.widget_watts, "—")
            views.setTextColor(R.id.widget_watts, muted)
            views.setTextViewText(R.id.widget_unit, "Вт")
            views.setTextViewText(R.id.widget_battery, "—%")
            views.setTextColor(R.id.widget_battery, muted)
            views.setTextViewText(R.id.widget_split, "AC —   ·   DC —")
            views.setTextViewText(R.id.widget_status, snap.status.ifBlank { "Нет связи" })
        }

        views.setImageViewBitmap(
            R.id.widget_bg,
            makeBackground(context, Prefs.widgetTransparency(context)),
        )

        val open = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        return views
    }

    private fun makeBackground(context: Context, transparency: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (220 * density).toInt().coerceAtLeast(1)
        val height = (140 * density).toInt().coerceAtLeast(1)
        val alpha = ((100 - transparency.coerceIn(0, 100)) * 255 / 100)
        val fill = Color.argb(alpha, 0x0C, 0x10, 0x0F)
        val strokeAlpha = (alpha * 0.85f).toInt().coerceIn(0, 255)
        val stroke = Color.argb(strokeAlpha, 0x2A, 0x38, 0x32)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(fill)
            setStroke((1f * density).toInt().coerceAtLeast(1), stroke)
        }
        drawable.setBounds(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
