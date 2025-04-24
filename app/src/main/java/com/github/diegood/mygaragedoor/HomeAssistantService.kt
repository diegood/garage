package com.github.diegood.mygaragedoor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class HomeAssistantService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "HomeAssistantServiceChannel"
        private const val NOTIFICATION_ID = 1
        private var isGeofenceAdded = false // Bandera para añadir geovalla solo una vez
    }

    private lateinit var geofenceHelper: GeofenceHelper // Instancia del helper

    override fun onCreate() {
        super.onCreate()
        Log.d("HAService", "Servicio onCreate")
        createNotificationChannel()
        HomeAssistantWS.init(this)
        geofenceHelper = GeofenceHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("HAService", ">>> Servicio onStartCommand INICIO <<<") // <-- Log añadido

        // Crear y mostrar la notificación para el servicio en primer plano
        val notification = createNotification()
        // Usar FOREGROUND_SERVICE_TYPE_DATA_SYNC si aplica (Android 14+)
        // o 0 si no aplica o versiones anteriores
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
             android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
             0 // O el tipo más apropiado si no es solo dataSync
        }

        try {
             startForeground(NOTIFICATION_ID, notification/*, foregroundServiceType*/) // Comentado temporalmente si causa problemas en API < 34
             Log.d("HAService", "Servicio iniciado en primer plano.")
        } catch (e: Exception) {
             Log.e("HAService", "Error al iniciar en primer plano: ${e.message}", e)
             // Considerar iniciar como servicio normal si startForeground falla
             // return START_STICKY
        }


        // Conectar el WebSocket
        Log.d("HAService", "Intentando conectar WebSocket...")
        HomeAssistantWS.connect()
        Log.d("HAService", "Llamada a connect() realizada.")


        // Añadir la geovalla si aún no se ha hecho
        Log.d("HAService", "Comprobando si añadir geovalla. isGeofenceAdded = $isGeofenceAdded") // <-- Log añadido
        if (!isGeofenceAdded) {
            Log.i("HAService", ">>> Llamando a geofenceHelper.addGeofences()... <<<") // <-- Log añadido
            geofenceHelper.addGeofences() // Intentará añadirla
            isGeofenceAdded = true // Marcar como intentado
            Log.d("HAService", "Llamada a addGeofences() realizada. isGeofenceAdded establecido a true.")
        } else {
             Log.d("HAService", "La geovalla ya se intentó añadir previamente.")
        }

        Log.d("HAService", "onStartCommand finalizando. Retornando START_STICKY.")
        // START_STICKY asegura que el servicio se reinicie si el sistema lo mata
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
}