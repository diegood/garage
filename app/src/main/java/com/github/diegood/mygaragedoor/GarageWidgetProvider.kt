package com.github.diegood.mygaragedoor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build // Add this import
import android.util.Log
import android.widget.RemoteViews

// Asegúrate de importar las constantes de HomeAssistantWS
import com.github.diegood.mygaragedoor.HomeAssistantWS.ACTION_GARAGE_STATE_CHANGED
import com.github.diegood.mygaragedoor.HomeAssistantWS.EXTRA_IS_OPEN

class GarageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d("WidgetProvider", "onUpdate llamado. IDs: ${appWidgetIds.joinToString()}")
        // Actualizar todos los widgets con el último estado conocido (si existe) o un estado por defecto
        val lastState = HomeAssistantWS.getCurrentState() // Obtener el último estado conocido
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId, lastState) // Usar null si no hay estado conocido
        }
        // Asegurarse de que el servicio esté corriendo
        startServiceIfNeeded(context)
    }

    override fun onEnabled(context: Context) {
        Log.d("WidgetProvider", "onEnabled llamado. Iniciando servicio.")
        // Iniciar el servicio cuando se añade el primer widget
        startServiceIfNeeded(context)
    }

    override fun onDisabled(context: Context) {
        Log.d("WidgetProvider", "onDisabled llamado. Deteniendo servicio.")
        // Detener el servicio cuando se elimina el último widget
        context.stopService(Intent(context, HomeAssistantService::class.java))
        // Opcional: Desconectar WebSocket si ya no se necesita
        // HomeAssistantWS.disconnect()
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("WidgetReceive", "onReceive llamado. Acción: ${intent.action}")
        super.onReceive(context, intent) // Importante llamar al super

        // Comprobar si es nuestro broadcast de cambio de estado
        if (ACTION_GARAGE_STATE_CHANGED == intent.action) {
            val isOpen = intent.getBooleanExtra(EXTRA_IS_OPEN, false) // Obtener el estado del extra
            Log.d("WidgetReceive", "Broadcast de estado recibido. isOpen: $isOpen")

            // Actualizar todos los widgets existentes
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisAppWidget = ComponentName(context.packageName, javaClass.name)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
            appWidgetIds.forEach { appWidgetId ->
                updateAppWidget(context, appWidgetManager, appWidgetId, isOpen)
            }
        } else if ("com.github.diegood.ACTION_TOGGLE_GARAGE" == intent.action) {
             Log.d("WidgetReceive", "Acción TOGGLE recibida. Enviando comando a WS.")
             HomeAssistantWS.sendToggleCommand() // Llama al método en HomeAssistantWS
        }
    }

    companion object {
        // Mueve la lógica de actualización a un método estático o dentro de onReceive/onUpdate
        // para evitar duplicación y facilitar las llamadas.
        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager,
                                     appWidgetId: Int, isOpen: Boolean?) { // isOpen ahora es nullable

            Log.d("WidgetProvider", "Actualizando widget ID $appWidgetId. Estado isOpen: $isOpen")
            val views = RemoteViews(context.packageName, R.layout.widget_garage_door)

            // Determinar el icono y texto a mostrar
            val iconResId: Int
            val statusText: String

            when (isOpen) {
                true -> {
                    iconResId = R.drawable.ic_garage_open // Asegúrate que este drawable existe
                    statusText = context.getString(R.string.garage_status_open) // Asegúrate que este string existe
                }
                false -> {
                    iconResId = R.drawable.ic_garage_closed // Asegúrate que este drawable existe
                    statusText = context.getString(R.string.garage_status_closed) // Asegúrate que este string existe
                }
                null -> {
                    // Estado desconocido o inicial
                    iconResId = R.drawable.ic_garage_unknown // Asegúrate que este drawable existe
                    statusText = context.getString(R.string.garage_status_unknown) // Asegúrate que este string existe
                }
            }

            views.setImageViewResource(R.id.btn_garage, iconResId)
            // Si quieres mostrar texto también, necesitarías añadir un TextView al layout
            // views.setTextViewText(R.id.widget_status_text, statusText)

            // Configurar el PendingIntent para el clic en el botón/imagen
            val intent = Intent(context, GarageWidgetProvider::class.java)
            intent.action = "com.github.diegood.ACTION_TOGGLE_GARAGE" // Acción para el clic
            // Es importante que cada PendingIntent sea único si tiene extras diferentes,
            // pero para una acción simple como esta, está bien.
            // Usamos FLAG_UPDATE_CURRENT para que el PendingIntent se actualice si ya existe.
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.btn_garage, pendingIntent)

            // Actualizar el widget específico
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // Método para iniciar el servicio si es necesario
        private fun startServiceIfNeeded(context: Context) {
            // Iniciar el servicio en primer plano para mantenerlo vivo
            val serviceIntent = Intent(context, HomeAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Now Build should be resolved
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}