package com.github.diegood.mygaragedoor

//import GarageWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList // Add this import

// Convert class to object (singleton)
object HomeAssistantWS {
    private var webSocket: WebSocket? = null
    // private val listeners = CopyOnWriteArrayList<WSListener>() // <-- Eliminado
    private var lastKnownState: Boolean? = null // Store the last known state
    private var isConnecting = false // Flag to prevent multiple connection attempts
    private var isConnected = false // Flag to track connection status
    private var appContext: Context? = null // <-- Añadido para el contexto
    private var messageIdCounter = 2 // Start command IDs from 2 (1 is used for subscription)

    // Acción para el Broadcast
    const val ACTION_GARAGE_STATE_CHANGED = "com.github.diegood.mygaragedoor.ACTION_GARAGE_STATE_CHANGED"
    const val EXTRA_IS_OPEN = "com.github.diegood.mygaragedoor.EXTRA_IS_OPEN"


    // Método para inicializar con el contexto de la aplicación
    fun init(context: Context) {
        appContext = context.applicationContext // Guardar contexto de aplicación
        Log.d("HA_WS", "HomeAssistantWS inicializado con contexto.")
        // Podrías iniciar la conexión aquí si quieres que siempre esté activa
        // connect()
    }


    // --- Métodos de Listener eliminados ---
    // interface WSListener { ... }
    // fun registerListener(...) { ... }
    // fun unregisterListener(...) { ... }

    fun getCurrentState(): Boolean? {
        return lastKnownState
    }

    fun connect() {
        if (appContext == null) {
             Log.e("HA_WS_Connect", "Error: Intento de conexión antes de llamar a init().")
             return
        }
        // Log al intentar conectar y evitar múltiples conexiones simultáneas
        Log.d("HA_WS_Connect", "Intento de conexión. isConnected: $isConnected, isConnecting: $isConnecting")
        if (isConnected || isConnecting) {
            Log.d("HA_WS", "Connection attempt skipped: Already connected or connecting.")
            return
        }
        isConnecting = true // Marcar como conectando
        Log.d("HA_WS_Connect", "Estableciendo nueva conexión WebSocket...")


        val client = OkHttpClient()
        // Ensure BuildConfig values are accessible
        val url = try {
             BuildConfig.HA_URL.replace("https", "wss") + "/api/websocket"
        } catch (e: Exception) {
            Log.e("HA_WS", "Error accessing BuildConfig.HA_URL. Make sure it's defined.", e)
            isConnecting = false
            return // Stop connection attempt if URL is invalid
        }
        val token = try {
            BuildConfig.HA_TOKEN
        } catch (e: Exception) {
             Log.e("HA_WS", "Error accessing BuildConfig.HA_TOKEN. Make sure it's defined.", e)
             isConnecting = false
             return // Stop connection attempt if Token is invalid
        }


        val request = Request.Builder()
            .url(url)
            // .header("Authorization", "Bearer $token") // Auth header might not be needed if using token in message
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false // Conexión establecida
                Log.d("HA_WS", "Conexión WebSocket establecida con Home Assistant")
                // Send auth message using the token from BuildConfig
                 webSocket.send("{\"type\": \"auth\", \"access_token\": \"$token\"}")

                // Subscribe to state changes for the specific entity
                val subscriptionMessage = """
                {
                    "id": 1,
                    "type": "subscribe_events",
                    "event_type": "state_changed"
                }
                """.trimIndent()
                 // We will filter by entity_id in onMessage

                Log.d("HA_WS", "Enviando subscripción general a state_changed: $subscriptionMessage")
                webSocket.send(subscriptionMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
               // Log.d("HA_WS", "Mensaje recibido: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "auth_required" -> Log.d("HA_WS", "Autenticación requerida")
                        "auth_ok" -> Log.d("HA_WS", "Autenticación exitosa")
                        "event" -> {
                            if (json.optInt("id") == 1 && json.has("event")) {
                                val event = json.getJSONObject("event")
                                if (event.has("data")) {
                                    val eventData = event.getJSONObject("data")
                                    val entityId = eventData.optString("entity_id")
                                    if (entityId == "input_boolean.garage_prueba") {
                                        if (eventData.has("new_state")) {

                                            val newState = eventData.getJSONObject("new_state")
                                            val state = newState.optString("state")
                                            val isOpen = state == "on"
                                            Log.d("HA_WS", "Cambio detectado en $entityId - Nuevo estado: $state.")

                                            // Comprobar si el estado realmente cambió
                                            if (lastKnownState != isOpen) {
                                                lastKnownState = isOpen
                                                // Enviar broadcast en lugar de notificar listeners
                                                broadcastState(isOpen)
                                            } else {
                                                Log.d("HA_WS", "Estado no cambió ($isOpen), no se envía broadcast.")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                         "result" -> {
                             if (json.optInt("id") == 1) { // Check if it's the result for our subscription
                                 if (json.optBoolean("success", false)) {
                                     Log.d("HA_WS", "Suscripción a state_changed confirmada.")
                                     // Optional: Request initial state after successful subscription?
                                     // You might need another message type like 'get_states' if HA supports it
                                 } else {
                                     val error = json.optJSONObject("error")
                                     Log.e("HA_WS", "Error en la suscripción a state_changed: ${error?.toString()}")
                                 }
                             }
                         }
                        else -> Log.d("HA_WS", "Tipo de mensaje no manejado: ${json.optString("type")}")
                    }
                } catch (e: Exception) {
                    Log.e("HA_WS", "Error parsing message: ${e.message}", e)
                }
            }

             override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                 Log.d("HA_WS", "WebSocket closing: $code / $reason")
                 isConnected = false
                 isConnecting = false
                 HomeAssistantWS.webSocket = null // Clear the reference
             }


            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("HA_WS", "Error en WebSocket: ${t.message}", t)
                isConnected = false
                isConnecting = false
                HomeAssistantWS.webSocket = null // Clear the reference on failure too
            }
        })
    }

    // Método para enviar el broadcast
    private fun broadcastState(isOpen: Boolean) {
        if (appContext == null) {
            Log.e("HA_WS", "Error: Intento de enviar broadcast sin contexto.")
            return
        }
        Log.d("HA_WS_Broadcast", "Enviando broadcast: $ACTION_GARAGE_STATE_CHANGED, isOpen: $isOpen")
        val intent = Intent(ACTION_GARAGE_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_OPEN, isOpen)
            // Importante: Hacer el intent explícito para el receptor si es posible
            // o añadir FLAG_INCLUDE_STOPPED_PACKAGES si el receptor puede no estar activo
            // Por ahora, usamos un broadcast implícito estándar
            setPackage(appContext!!.packageName) // Hace el broadcast más seguro y eficiente
        }
        appContext!!.sendBroadcast(intent)
    }

    fun disconnect() {
         // Only close if connected and potentially check if listeners exist
         if (isConnected) {
            Log.d("HA_WS", "Disconnecting WebSocket...")
            webSocket?.close(1000, "Client requested disconnect")
            webSocket = null
            isConnected = false
            isConnecting = false
            Log.d("HA_WS_Connect", "WebSocket desconectado y variables reseteadas.")
        } else {
             Log.d("HA_WS_Connect", "Desconexión solicitada pero no estaba conectado.")
         }
    }

    // ++ Add this function ++
    fun sendToggleCommand() {
        if (!isConnected || webSocket == null) {
            Log.w("HA_WS_Command", "No se puede enviar el comando toggle: WebSocket no conectado.")
            // Optional: Attempt to reconnect?
            // connect()
            return
        }

        val commandId = messageIdCounter++ // Increment ID for each command
        val entityId = "input_boolean.garage_prueba" // Make sure this is your correct entity ID
        val toggleCommand = """
        {
            "id": $commandId,
            "type": "call_service",
            "domain": "input_boolean",
            "service": "toggle",
            "target": {
                "entity_id": "$entityId"
            }
        }
        """.trimIndent()

        Log.d("HA_WS_Command", "Enviando comando toggle para $entityId (ID: $commandId): $toggleCommand")
        webSocket?.send(toggleCommand)
    }
    // -- End of added function --
}