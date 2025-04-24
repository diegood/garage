package com.github.diegood.mygaragedoor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName // Asegúrate que este import está
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences // Ensure this import exists
import android.os.Build // Add this import
import android.util.Log
import android.widget.RemoteViews // Asegúrate que este import está

import com.github.diegood.mygaragedoor.HomeAssistantWS.ACTION_GARAGE_STATE_CHANGED
import com.github.diegood.mygaragedoor.HomeAssistantWS.EXTRA_IS_OPEN

class GarageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d("WidgetProvider", "onUpdate llamado para IDs: ${appWidgetIds.joinToString()}")
        // Asegurarse de que el servicio esté corriendo
        startServiceIfNeeded(context)

        // ++ Obtener estado de Geofence ++
        val prefs = context.getSharedPreferences(GeofenceBroadcastReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val isInsideGeofence = prefs.getBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_GEOFENCE, true) // Default to true or false as needed
        Log.d("WidgetProvider", "onUpdate - Estado Geofence: ${if (isInsideGeofence) "DENTRO" else "FUERA"}")

        // Obtener el último estado conocido del WebSocket
        val lastState = HomeAssistantWS.getCurrentState()
        Log.d("WidgetProvider", "onUpdate - Último estado HA conocido: $lastState")

        // Actualizar cada widget afectado por esta actualización
        appWidgetIds.forEach { appWidgetId ->
            // Pasar el estado de geofence a updateAppWidget
            updateAppWidget(context, appWidgetManager, appWidgetId, lastState, isInsideGeofence) // Pass isInsideGeofence here
        }
    }

    override fun onEnabled(context: Context) {
        Log.d("WidgetProvider", "onEnabled llamado. Iniciando servicio.")
        startServiceIfNeeded(context)
    }

    override fun onDisabled(context: Context) {
        Log.d("WidgetProvider", "onDisabled llamado. Deteniendo servicio (si es necesario).")
        // Considera si quieres detener el servicio cuando no hay widgets.
        // Podrías necesitar contar los widgets activos.
        // context.stopService(Intent(context, HomeAssistantService::class.java))
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WidgetReceive", "onReceive llamado. Acción: ${intent.action}")
        super.onReceive(context, intent) // Importante llamar al super

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

        // ++ Obtener estado de Geofence ++
        val prefs = context.getSharedPreferences(GeofenceBroadcastReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        // Por defecto, asumimos que está dentro si no hay valor (o puedes elegir lo contrario)
        val isInsideGeofence = prefs.getBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_GEOFENCE, true)
        Log.d("WidgetReceive", "Estado Geofence actual: ${if (isInsideGeofence) "DENTRO" else "FUERA"}")

        when (intent.action) {
            HomeAssistantWS.ACTION_GARAGE_STATE_CHANGED -> { // Use constant from HomeAssistantWS
                val isOpen = intent.getBooleanExtra(HomeAssistantWS.EXTRA_IS_OPEN, false) // Use constant from HomeAssistantWS
                Log.d("WidgetReceive", "Broadcast de estado HA recibido. isOpen: $isOpen")
                lastKnownState = isOpen // Update local state if needed
                appWidgetIds.forEach { appWidgetId ->
                    // Pasar el estado de geofence a updateAppWidget
                    updateAppWidget(context, appWidgetManager, appWidgetId, isOpen, isInsideGeofence)
                }
                isProcessingToggle = false
                Log.d("WidgetReceive", "Bandera isProcessingToggle reseteada a false.")
            }
            "com.github.diegood.ACTION_TOGGLE_GARAGE" -> {
                Log.d("WidgetReceive", "Acción TOGGLE recibida.")

                // ++ Comprobar Geofence ANTES de procesar ++
                if (!isInsideGeofence) {
                    Log.w("WidgetReceive", "Acción TOGGLE ignorada: Fuera de la geovalla.")
                    // Opcional: Actualizar icono a 'forbidden' si aún no lo está (aunque updateAppWidget debería manejarlo)
                    appWidgetIds.forEach { appWidgetId ->
                         updateAppWidget(context, appWidgetManager, appWidgetId, lastKnownState, isInsideGeofence)
                    }
                    return // No hacer nada más si está fuera
                }
                // -- Fin comprobación Geofence --

                if (isProcessingToggle) {
                    Log.d("WidgetReceive", "Acción TOGGLE ignorada: ya se está procesando una.")
                    return
                }

                isProcessingToggle = true
                Log.d("WidgetReceive", "Bandera isProcessingToggle establecida a true.")

                Log.d("WidgetReceive", "Cambiando icono a loading para todos los widgets.")
                appWidgetIds.forEach { appWidgetId ->
                    val views = RemoteViews(context.packageName, R.layout.widget_garage_door)
                    views.setImageViewResource(R.id.btn_garage, R.drawable.ic_garage_loading)
                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                }

                Log.d("WidgetReceive", "Enviando comando a WS.")
                HomeAssistantWS.sendToggleCommand()
                // Consider adding a timeout to reset isProcessingToggle if no state update is received
            }
            // ++ Manejar actualización directa desde Geofence Receiver ++
            GeofenceBroadcastReceiver.ACTION_GEOFENCE_STATE_CHANGED -> {
                 Log.d("WidgetReceive", "Recibido broadcast de cambio de estado de Geofence.")
                 // Volver a leer el estado por si acaso y actualizar todos los widgets
                 val currentIsInside = prefs.getBoolean(GeofenceBroadcastReceiver.KEY_IS_INSIDE_GEOFENCE, true)
                 val lastState = HomeAssistantWS.getCurrentState() // Usar el último estado conocido de HA
                 appWidgetIds.forEach { appWidgetId ->
                    updateAppWidget(context, appWidgetManager, appWidgetId, lastState, currentIsInside)
                 }
            }
            // -- Fin manejo Geofence --
            // Manejar APPWIDGET_UPDATE también aquí para asegurar que se use el estado geofence
             AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                 Log.d("WidgetReceive", "Recibido broadcast de actualización estándar (APPWIDGET_UPDATE).")
                 val lastState = HomeAssistantWS.getCurrentState()
                 // Obtener los IDs específicos de este intent si están disponibles
                 val specificWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: appWidgetIds

                 specificWidgetIds.forEach { appWidgetId ->
                     updateAppWidget(context, appWidgetManager, appWidgetId, lastState, isInsideGeofence)
                 }
                 // Asegurarse de que el servicio esté corriendo (si la actualización viene del sistema)
                 startServiceIfNeeded(context)
             }
             // Handle potential BOOT_COMPLETED if the widget provider is registered for it (though BootReceiver is better)
             Intent.ACTION_BOOT_COMPLETED -> {
                 Log.d("WidgetReceive", "Recibido BOOT_COMPLETED (inesperado aquí, manejado por BootReceiver).")
                 startServiceIfNeeded(context)
             }
             else -> {
                 Log.w("WidgetReceive", "Acción desconocida recibida: ${intent.action}")
             }
        }
    }


    companion object {
        @Volatile
        private var isProcessingToggle = false
        // Variable para guardar el último estado conocido de HA (puede ser redundante con HomeAssistantWS.getCurrentState)
        private var lastKnownState: Boolean? = null

        // ++ Modificar updateAppWidget para aceptar y usar el estado de geofence ++
        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager,
                                     appWidgetId: Int, isOpen: Boolean?, isInsideGeofence: Boolean) {

            Log.d("WidgetProvider", "Actualizando widget ID $appWidgetId. Estado HA: $isOpen, Geofence: ${if (isInsideGeofence) "DENTRO" else "FUERA"}")
            val views = RemoteViews(context.packageName, R.layout.widget_garage_door)

            val iconResId: Int
            val statusText: String // Aunque no la uses, la dejamos por si acaso

            // ++ Priorizar el estado 'forbidden' si está fuera de la geovalla ++
            if (!isInsideGeofence) {
                iconResId = R.drawable.ic_garage_forbidden // Asegúrate que este drawable existe
                statusText = context.getString(R.string.garage_status_outside_geofence) // Añade este string
                Log.d("WidgetProvider", "Widget ID $appWidgetId: Estableciendo icono FORBIDDEN.")
            } else {
                // Si está dentro, usar la lógica normal basada en el estado de HA
                when (isOpen) {
                    true -> {
                        iconResId = R.drawable.ic_garage_open
                        statusText = context.getString(R.string.garage_status_open)
                    }
                    false -> {
                        iconResId = R.drawable.ic_garage_closed
                        statusText = context.getString(R.string.garage_status_closed)
                    }
                    null -> {
                        // Si está dentro pero el estado es desconocido (null), mostrar 'unknown'
                        iconResId = R.drawable.ic_garage_unknown
                        statusText = context.getString(R.string.garage_status_unknown)
                    }
                }
                 Log.d("WidgetProvider", "Widget ID $appWidgetId: Estableciendo icono según estado HA ($isOpen): $iconResId")
            }
            // -- Fin lógica de icono --

            views.setImageViewResource(R.id.btn_garage, iconResId)
            // views.setTextViewText(R.id.widget_status_text, statusText) // Si tuvieras un TextView

            // El PendingIntent no necesita cambiar, la lógica de bloqueo está en onReceive
            val intent = Intent(context, GarageWidgetProvider::class.java)
            intent.action = "com.github.diegood.ACTION_TOGGLE_GARAGE"
            // Asegurar que el requestCode sea único por widget ID si es necesario, o 0 si es global
            val pendingIntent = PendingIntent.getBroadcast(context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.btn_garage, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        // -- Fin modificación updateAppWidget --

        // Método para iniciar el servicio (ya lo tenías)
        private fun startServiceIfNeeded(context: Context) {
            val serviceIntent = Intent(context, HomeAssistantService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    Log.d("WidgetProvider", "Llamado a startForegroundService.")
                } else {
                    context.startService(serviceIntent)
                    Log.d("WidgetProvider", "Llamado a startService.")
                }
            } catch (e: Exception) {
                Log.e("WidgetProvider", "Error al iniciar el servicio: ${e.message}", e)
            }
        }
    }
}