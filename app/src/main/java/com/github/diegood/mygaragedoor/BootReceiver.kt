package com.github.diegood.mygaragedoor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "BOOT_COMPLETED recibido. Iniciando servicio...")
            startServiceIfNeeded(context)
        }
    }

    // Puedes copiar esta función de GarageWidgetProvider o crear una común
    private fun startServiceIfNeeded(context: Context) {
        val serviceIntent = Intent(context, HomeAssistantService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d("BootReceiver", "Llamado a startForegroundService.")
            } else {
                context.startService(serviceIntent)
                Log.d("BootReceiver", "Llamado a startService.")
            }
        } catch (e: Exception) {
            // Capturar excepciones, especialmente IllegalStateException en Android 8+ si la app está en segundo plano
            Log.e("BootReceiver", "Error al iniciar el servicio desde BootReceiver: ${e.message}", e)
            // Considera mostrar una notificación al usuario si el inicio falla persistentemente.
        }
    }
}