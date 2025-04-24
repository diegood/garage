package com.github.diegood.mygaragedoor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
// import android.appwidget.AppWidgetManager // Ya no se necesita aquí
// import android.content.ComponentName // Ya no se necesita aquí
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
// import android.os.Handler // Ya no se necesita aquí
// import android.os.Looper // Ya no se necesita aquí

// Ya no implementa HomeAssistantWS.WSListener
class HomeAssistantService : Service() {

    private val NOTIFICATION_CHANNEL_ID = "HomeAssistantServiceChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        Log.i("HomeAssistantService", "Servicio CREADO (onCreate).")
        createNotificationChannel()
        // Ya no registramos listener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("HomeAssistantService", "Servicio INICIADO (onStartCommand). Intent: $intent, Flags: $flags")

        // Asegurar conexión si queremos que el servicio la controle
        // Si la conexión se inicia en Application, esta línea podría no ser necesaria
        // o podría servir como un reintento si falló antes.
        HomeAssistantWS.connect()

        val notification = createNotification("Servicio Garaje Activo") // Mensaje genérico
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY // Intentar mantener el servicio vivo
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w("HomeAssistantService", "****** SERVICIO DESTRUIDO (onDestroy) ******")
        // Ya no desregistramos listener
        // Considera si quieres desconectar el WS cuando el servicio muere.
        // Si la conexión la maneja Application, quizás no quieras desconectar aquí.
        // HomeAssistantWS.disconnect()
        stopForeground(STOP_FOREGROUND_DETACH) // O true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // --- onStateChanged eliminado ---

    // --- Métodos de Notificación (sin cambios) ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Home Assistant Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d("HomeAssistantService", "Canal de notificación creado.")
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Servicio Garaje")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_garage_unknown) // Podrías querer un icono genérico aquí
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    // updateNotification probablemente ya no sea necesario aquí
    /*
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d("HomeAssistantService", "Notificación actualizada: $contentText")
    }
    */
}