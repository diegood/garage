package com.github.diegood.mygaragedoor

import android.Manifest // Keep this if you added it
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager // Keep this if you added it
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat // <-- Add this import
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices // Keep this if you added it

class HomeAssistantService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "HomeAssistantServiceChannel"
        private const val NOTIFICATION_ID = 1
        // Mantenemos la bandera, pero su lógica cambia
        @Volatile // <- Añadir Volatile para asegurar visibilidad entre hilos si fuera necesario
        private var isGeofenceAdded = false
    }

    private lateinit var geofenceHelper: GeofenceHelper
    // Necesitamos acceso al GeofencingClient para añadir los listeners aquí
    private val geofencingClient by lazy { LocationServices.getGeofencingClient(this) }


    override fun onCreate() {
        super.onCreate()
        Log.d("HAService", "Servicio onCreate")
        createNotificationChannel()
        HomeAssistantWS.init(this)
        geofenceHelper = GeofenceHelper(this) // GeofenceHelper todavía se usa para construir la solicitud
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("HAService", ">>> Servicio onStartCommand INICIO <<<")

        // Crear y mostrar la notificación para el servicio en primer plano
        try {
             // Determinar el tipo de servicio en primer plano
             // Si la función principal es la ubicación, usar LOCATION. Si es sincronización de datos, DATA_SYNC.
             // Puedes combinar tipos si es necesario en APIs más recientes.
             // Dado que usamos Geofencing, LOCATION parece más apropiado.
             val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                     // En Android 14+, puedes especificar múltiples tipos si es relevante
                     // android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                     android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                 } else {
                     // En Android 10-13, solo puedes especificar un tipo principal aquí
                     android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                 }
             } else {
                 0 // No se especifica tipo antes de Android Q
             }

             val notification = createNotification()
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
             } else {
                 startForeground(NOTIFICATION_ID, notification)
             }
             Log.d("HAService", "Servicio iniciado en primer plano con tipo: $foregroundServiceType")
        } catch (e: Exception) {
             Log.e("HAService", "Error al iniciar en primer plano: ${e.message}", e)
             // Considerar iniciar como servicio normal si startForeground falla
             // return START_STICKY
        }


        // Conectar el WebSocket
        Log.d("HAService", "Intentando conectar WebSocket...")
        HomeAssistantWS.connect()
        Log.d("HAService", "Llamada a connect() realizada.")


        // Añadir la geovalla si aún no se ha hecho y los permisos están concedidos
        Log.d("HAService", "Comprobando si añadir geovalla. isGeofenceAdded = $isGeofenceAdded")
        if (!isGeofenceAdded) {
            // Verify permissions here too, in case the service restarts without going through MainActivity
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { // Now ActivityCompat should be resolved
                Log.i("HAService", "Permissions OK. Attempting to add geofence...")

                // Construir la solicitud y el PendingIntent usando el Helper
                val geofence = geofenceHelper.buildGeofence() // Necesitamos hacer públicas estas funciones o refactorizar
                val request = geofence?.let { geofenceHelper.buildGeofencingRequest(it) } // Necesitamos hacer públicas estas funciones o refactorizar
                val pendingIntent = geofenceHelper.geofencePendingIntent // Necesitamos hacer público el PendingIntent

                if (request != null && pendingIntent != null) {
                    geofencingClient.addGeofences(request, pendingIntent).run {
                        addOnSuccessListener {
                            Log.i("HAService", "Geovalla añadida correctamente")
                            isGeofenceAdded = true // <-- Marcar como añadida SOLO en caso de éxito
                        }
                        addOnFailureListener { e ->
                            Log.e("HAService", "No se pudo añadir la geovalla", e)
                            isGeofenceAdded = false // <-- Marcar como NO añadida en caso de fallo (para reintentar)
                            // Podrías añadir lógica de reintento aquí si es necesario
                        }
                    }
                } else {
                     Log.e("HAService", "No se pudo construir la solicitud o el PendingIntent para la geovalla.")
                     isGeofenceAdded = false // Asegurar que se pueda reintentar
                }
                // NO marcar isGeofenceAdded = true aquí fuera de los listeners
            } else {
                Log.e("HAService", "No se puede añadir geovalla: Permiso ACCESS_FINE_LOCATION denegado.")
                isGeofenceAdded = false // Asegurar que se pueda reintentar si se conceden permisos más tarde
                // Considerar detener el servicio o notificar al usuario si los permisos son cruciales
                // stopSelf()
            }
        } else {
             Log.d("HAService", "La geovalla ya fue añadida exitosamente previamente.")
        }

        Log.d("HAService", "onStartCommand finalizando. Retornando START_STICKY.")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("HAService", "Servicio onDestroy")
        // Opcional: Desconectar WebSocket y/o eliminar geovallas si es necesario al detener el servicio
        // HomeAssistantWS.disconnect(true) // Assuming disconnect takes a boolean for manual disconnect
        // geofenceHelper.removeGeofences()

        // Use the recommended way to stop foreground service and remove notification
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        Log.d("HAService", "Servicio detenido y notificación eliminada.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // No necesitamos binding para este servicio
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Home Assistant Service Channel", // Nombre visible para el usuario
                NotificationManager.IMPORTANCE_LOW // Baja importancia para que no sea intrusiva
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d("HAService", "Canal de notificación creado.")
        }
    }

    private fun createNotification(): Notification {
         // Aquí puedes añadir un PendingIntent para abrir la app si se toca la notificación
         // val notificationIntent = Intent(this, MainActivity::class.java)
         // val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Servicio Garaje Conectado")
            .setContentText("Manteniendo conexión y ubicación...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Usa un icono apropiado
            // .setContentIntent(pendingIntent) // Descomenta si añades el PendingIntent
            .setOngoing(true) // Hace que la notificación no se pueda descartar fácilmente
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridad baja
            .build()
    }

    // --- Refactorización necesaria en GeofenceHelper ---
    // Para que el código anterior funcione, necesitas hacer accesibles
    // buildGeofence(), buildGeofencingRequest(), y geofencePendingIntent desde HomeAssistantService.
    // Puedes hacerlos 'internal' o pasarlos como parámetros, o refactorizar
    // para que addGeofences() en GeofenceHelper acepte los listeners como parámetros.

    // Alternativa: Modificar GeofenceHelper.addGeofences para aceptar callbacks
    /*
    // En GeofenceHelper.kt
    fun addGeofences(onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        // ... (construir geofence y request) ...
        // ... (comprobar permisos) ...
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Geovalla añadida exitosamente: $GEOFENCE_ID")
                onSuccess() // Llamar al callback de éxito
            }
            addOnFailureListener { exception ->
                Log.e(TAG, "Error al añadir la geovalla: ${exception.message}", exception)
                onFailure(exception) // Llamar al callback de fallo
            }
        }
    }

    // En HomeAssistantService.onStartCommand
    if (!isGeofenceAdded) {
         if (ActivityCompat.checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED) {
             Log.i("HAService", "Intentando añadir geovalla...")
             geofenceHelper.addGeofences(
                 onSuccess = {
                     Log.i("HAService", "Callback onSuccess: Geovalla añadida.")
                     isGeofenceAdded = true
                 },
                 onFailure = { e ->
                     Log.e("HAService", "Callback onFailure: No se pudo añadir.", e)
                     isGeofenceAdded = false
                 }
             )
         } else { ... }
    }
    */
    // --- Fin Refactorización ---

}