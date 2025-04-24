package com.github.diegood.mygaragedoor

import android.app.NotificationManager // <-- Añadir import
import android.app.PendingIntent // <-- Añadir import
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat // <-- Añadir import
import androidx.core.content.ContextCompat // <-- Añadir import
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val PREFS_NAME = "GeofencePrefs"
        const val KEY_IS_INSIDE_GEOFENCE = "isInsideGeofence"
        // Acción para notificar al widget provider directamente (opcional pero útil)
        const val ACTION_GEOFENCE_STATE_CHANGED = "com.github.diegood.mygaragedoor.ACTION_GEOFENCE_STATE_CHANGED"
        // Acción para el botón de la notificación "Abrir"
        const val ACTION_TOGGLE_FROM_NOTIFICATION = "com.github.diegood.mygaragedoor.ACTION_TOGGLE_FROM_NOTIFICATION"
        // ++ Acción para el botón de la notificación "No" ++
        const val ACTION_DISMISS_NOTIFICATION = "com.github.diegood.mygaragedoor.ACTION_DISMISS_NOTIFICATION"
        // ID para la notificación de geovalla
        internal const val GEOFENCE_NOTIFICATION_ID = 2
        // ID del canal de notificación
        private const val NOTIFICATION_CHANNEL_ID = "HomeAssistantServiceChannel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ++ Manejar la acción de descarte explícito ++
        if (intent.action == ACTION_DISMISS_NOTIFICATION) {
            Log.d("GeofenceReceiver", "Recibida acción DISMISS desde notificación.")
            val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            notificationManager?.cancel(GEOFENCE_NOTIFICATION_ID)
            return // No hay nada más que hacer para esta acción
        }
        // -- Fin manejo acción descarte --

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
             Log.e("GeofenceReceiver", "Error: GeofencingEvent is null")
             // Considerar si el intent podría ser para la acción de notificación "Abrir"
             if (intent.action == ACTION_TOGGLE_FROM_NOTIFICATION) {
                 Log.d("GeofenceReceiver", "Recibida acción TOGGLE desde notificación (manejada por el servicio).")
                 // El servicio se encargará de esto.
             }
             return
        }
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e("GeofenceReceiver", "Error de Geofencing: $errorMessage")
            return
        }

        // Obtener el tipo de transición
        val geofenceTransition = geofencingEvent.geofenceTransition

        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d("GeofenceReceiver", "Transición detectada: ENTRADA")
            val isInside = true

            // Guardar estado
            saveGeofenceState(context, isInside)
            // Notificar al widget
            notifyWidget(context)
            // Mostrar notificación accionable
            showEnterNotification(context)

        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("GeofenceReceiver", "Transición detectada: SALIDA")
            val isInside = false

            // Guardar estado
            saveGeofenceState(context, isInside)
            // Notificar al widget
            notifyWidget(context)
            // Opcional: Cancelar la notificación si el usuario sale de la zona sin interactuar
            val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            notificationManager?.cancel(GEOFENCE_NOTIFICATION_ID)

        } else {
            // Error o transición desconocida
            Log.e("GeofenceReceiver", "Transición de Geofencing desconocida: $geofenceTransition")
        }
    }

    private fun saveGeofenceState(context: Context, isInside: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Usar apply() es generalmente preferido a commit() ya que es asíncrono
        prefs.edit().putBoolean(KEY_IS_INSIDE_GEOFENCE, isInside).apply()
        Log.d("GeofenceReceiver", "Estado de geovalla guardado: isInside = $isInside")
    }

    private fun notifyWidget(context: Context) {
        val updateIntent = Intent(context, GarageWidgetProvider::class.java)
        updateIntent.action = ACTION_GEOFENCE_STATE_CHANGED // Usar una acción específica
        context.sendBroadcast(updateIntent)
        Log.d("GeofenceReceiver", "Broadcast enviado al WidgetProvider: $ACTION_GEOFENCE_STATE_CHANGED")
    }

    private fun showEnterNotification(context: Context) {
        val notificationManager = ContextCompat.getSystemService(context, NotificationManager::class.java)

        // --- Intent para la acción "Abrir" (sin cambios) ---
        val toggleIntent = Intent(context, HomeAssistantService::class.java).apply {
            action = ACTION_TOGGLE_FROM_NOTIFICATION
        }
        val togglePendingIntent: PendingIntent = PendingIntent.getService(
            context,
            1, // requestCode para Abrir
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // +++ Intent para la acción "No" (Descartar) +++
        val dismissIntent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_DISMISS_NOTIFICATION // Acción para que este Receiver la maneje
        }
        val dismissPendingIntent: PendingIntent = PendingIntent.getBroadcast(
            context,
            2, // requestCode diferente para Descartar
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // --- Fin Intent para "No" ---


        // Construir la notificación
        val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_garage_closed)
            .setContentTitle("Entrando en zona de garaje")
            .setContentText("¿Quieres abrir el garaje?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // Se cierra al tocar la notificación o una acción (generalmente)
            .addAction(R.drawable.ic_garage_open, "Sí, Abrir", togglePendingIntent) // Texto actualizado
            .addAction(R.drawable.ic_garage_closed, "No", dismissPendingIntent) // Necesitas un icono como ic_cancel

        // Mostrar la notificación
        notificationManager?.notify(GEOFENCE_NOTIFICATION_ID, notificationBuilder.build())
        Log.d("GeofenceReceiver", "Notificación de entrada mostrada con acciones Sí/No.")
    }
}