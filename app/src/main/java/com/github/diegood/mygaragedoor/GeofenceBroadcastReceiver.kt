package com.github.diegood.mygaragedoor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val PREFS_NAME = "GeofencePrefs"
        const val KEY_IS_INSIDE_GEOFENCE = "isInsideGeofence"
        // Acción para notificar al widget provider directamente (opcional pero útil)
        const val ACTION_GEOFENCE_STATE_CHANGED = "com.github.diegood.mygaragedoor.ACTION_GEOFENCE_STATE_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
             Log.e("GeofenceReceiver", "Error: GeofencingEvent is null")
             return
        }
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e("GeofenceReceiver", "Error de Geofencing: $errorMessage")
            return
        }

        // Obtener el tipo de transición
        val geofenceTransition = geofencingEvent.geofenceTransition
        
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {

            val isInside = geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
            Log.d("GeofenceReceiver", "Transición detectada: ${if (isInside) "ENTRADA" else "SALIDA"}")

            // Guardar el estado en SharedPreferences (cambiar apply por commit)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_INSIDE_GEOFENCE, isInside).commit() // <--- Cambiado a commit()

            // Notificar al WidgetProvider para que se actualice inmediatamente (opcional)
            val updateIntent = Intent(context, GarageWidgetProvider::class.java)
            updateIntent.action = ACTION_GEOFENCE_STATE_CHANGED // Usar una acción específica
            context.sendBroadcast(updateIntent)

        } else {
            // Error o transición desconocida
            Log.e("GeofenceReceiver", "Transición de Geofencing desconocida: $geofenceTransition")
        }
    }
}