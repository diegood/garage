package com.github.diegood.mygaragedoor

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceHelper(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceHelper"
        private const val GEOFENCE_ID = "GARAGE_AREA"
        private const val GEOFENCE_LATITUDE = 37.83710650207569
        private const val GEOFENCE_LONGITUDE = -0.7961820680549196
        private const val GEOFENCE_RADIUS_METERS = 150f // 150 metros
        private const val GEOFENCE_EXPIRATION_MS = Geofence.NEVER_EXPIRE // No expira
        // Transiciones que queremos detectar
        private const val GEOFENCE_TRANSITIONS = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
    }

    private val geofencingClient = LocationServices.getGeofencingClient(context)

    // Crea el PendingIntent que se disparará cuando ocurra una transición de geovalla
    internal val geofencePendingIntent: PendingIntent by lazy { // Changed from private to internal
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        Log.d(TAG, "Intent creado: $intent")
        // Usamos FLAG_UPDATE_CURRENT para que el mismo PendingIntent se reutilice si ya existe,
        // y FLAG_IMMUTABLE por seguridad y requisitos de Android S+
        PendingIntent.getBroadcast(
            context,
            0, // requestCode - 0 debería ser suficiente si solo hay un tipo de PendingIntent para geofence
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    // Construye el objeto Geofence
    internal fun buildGeofence(): Geofence? { // Changed from private to internal
        return Geofence.Builder()
            // Establece el ID único para esta geovalla
            .setRequestId(GEOFENCE_ID)
            // Define la región circular
            .setCircularRegion(
                GEOFENCE_LATITUDE,
                GEOFENCE_LONGITUDE,
                GEOFENCE_RADIUS_METERS
            )
            // Establece la duración de la geovalla (NEVER_EXPIRE significa que permanece activa hasta que se elimine)
            .setExpirationDuration(GEOFENCE_EXPIRATION_MS)
            // Establece los tipos de transición a monitorear (entrada y salida)
            .setTransitionTypes(GEOFENCE_TRANSITIONS)
            // Opcional: Define un retraso en milisegundos antes de que se notifique una transición DWELL (no la usamos aquí)
            // .setLoiteringDelay(int)
            .build()
    }

    // Construye la solicitud de geovalla
    internal fun buildGeofencingRequest(geofence: Geofence): GeofencingRequest { // Changed from private to internal
        return GeofencingRequest.Builder().apply {
            // Especifica cómo se deben activar las notificaciones de geovalla
            // INITIAL_TRIGGER_ENTER indica que se debe activar GEOFENCE_TRANSITION_ENTER si el dispositivo ya está dentro al añadirla
            setInitialTrigger(
              GeofencingRequest.INITIAL_TRIGGER_ENTER or
              GeofencingRequest.INITIAL_TRIGGER_EXIT
            )
        
            // Añade la geovalla (o lista de geovallas) a la solicitud
            addGeofence(geofence)
        }.build()
    }

    // Añade la geovalla al sistema
    fun addGeofences() {
        Log.i(TAG, ">>> addGeofences() INICIO <<<") // <-- Log añadido

        val geofence = buildGeofence() ?: run {
            Log.e(TAG, "No se pudo construir la geovalla.")
            return
        }
        Log.d(TAG, "Geovalla construida: $geofence")

        val geofencingRequest = buildGeofencingRequest(geofence)
        Log.d(TAG, "Solicitud de geovalla construida: $geofencingRequest")

        // Verificar permisos ANTES de intentar añadir la geovalla
        Log.d(TAG, "Comprobando permiso ACCESS_FINE_LOCATION...")
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permiso ACCESS_FINE_LOCATION no concedido. No se puede añadir geovalla.")
            return
        }
        Log.d(TAG, "Permiso ACCESS_FINE_LOCATION concedido.")

        // En Android Q (API 29) y superior, también se necesita ACCESS_BACKGROUND_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Comprobando permiso ACCESS_BACKGROUND_LOCATION...")
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permiso ACCESS_BACKGROUND_LOCATION no concedido en Android Q+. Geofencing puede no funcionar en segundo plano.")
                // No retornamos aquí para intentar añadirla de todas formas, pero registramos la advertencia.
            } else {
                 Log.d(TAG, "Permiso ACCESS_BACKGROUND_LOCATION concedido.")
            }
        }

        Log.d(TAG, "Intentando añadir geovallas al cliente...")
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Geovalla añadida exitosamente: $GEOFENCE_ID")
            }
            addOnFailureListener { exception ->
                Log.e(TAG, "Error al añadir la geovalla: ${exception.message}", exception)
            }
        }
        Log.i(TAG, ">>> addGeofences() FIN <<<") // <-- Log añadido
    }

    // Opcional: Método para eliminar geovallas (si necesitas detener el monitoreo)
    fun removeGeofences() {
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Geovalla eliminada exitosamente.")
            }
            addOnFailureListener { exception ->
                Log.e(TAG, "Error al eliminar la geovalla: ${exception.message}", exception)
            }
        }
    }
}