package dev.eb3a.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

abstract class BaseWidget : AppWidgetProvider() {
    abstract val kind: WidgetKind

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (StationStore.state.at == 0L) StationStore.state = Prefs.load(context)
        manager.updateAppWidget(ids, WidgetRenderer.build(context, StationStore.state, kind))
        if (Prefs.isEnabled(context) && !Prefs.mac(context).isNullOrBlank()) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BleService::class.java),
                )
            } catch (_: Exception) {
                // Periodic widget update is not allowed to start a service on newer Android.
            }
        }
    }
}

/** Карточка 3×2: нагрузка, AC/DC, заряд. */
class PowerWidget : BaseWidget() {
    override val kind = WidgetKind.POWER
}

/** Компактный 2×1: ватты и процент батареи. */
class CompactWidget : BaseWidget() {
    override val kind = WidgetKind.COMPACT
}

/** Кольцо 2×2: батарея по кругу, нагрузка в центре. */
class RingWidget : BaseWidget() {
    override val kind = WidgetKind.RING
}


