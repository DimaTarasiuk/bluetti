package dev.eb3a.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.core.content.ContextCompat

class PowerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        StationStore.state = Prefs.load(context)
        val views = WidgetRenderer.build(context, StationStore.state)
        manager.updateAppWidget(ids, views)
        if (Prefs.isEnabled(context) && !Prefs.mac(context).isNullOrBlank()) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    android.content.Intent(context, BleService::class.java),
                )
            } catch (_: Exception) {
                // Periodic widget update is not allowed to start a service on newer Android.
            }
        }
    }
}
