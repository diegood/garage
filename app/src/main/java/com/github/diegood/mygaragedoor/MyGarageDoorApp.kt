package com.github.diegood.mygaragedoor

import android.app.Application
import android.util.Log

class MyGarageDoorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MyGarageDoorApp", "Application onCreate.")
        // Inicializar HomeAssistantWS con el contexto de la aplicación
        HomeAssistantWS.init(this)
        // Opcional: Iniciar la conexión WebSocket aquí si quieres que esté siempre activa
        // mientras la app esté viva (incluso en segundo plano sin servicio foreground)
        // HomeAssistantWS.connect()
    }
}