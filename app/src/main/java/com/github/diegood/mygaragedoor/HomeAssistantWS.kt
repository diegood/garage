package com.github.diegood.mygaragedoor

import android.content.Context
import android.content.Intent
import android.os.Handler // Importar Handler
import android.os.Looper // Importar Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import org.json.JSONArray
// import java.util.concurrent.CopyOnWriteArrayList // No parece usarse, se puede quitar si no es necesario

object HomeAssistantWS {
    private var webSocket: WebSocket? = null
    private var lastKnownState: Boolean? = null 
    private var isConnecting = false 
    private var isConnected = false 
    private var appContext: Context? = null 
    private var messageIdCounter = GET_STATES_ID + 1 // Asegurar que los comandos empiecen después de los IDs fijos
    private const val SUBSCRIBE_ID = 1
    private const val GET_STATES_ID = 2

    // ++ Reconnection Logic ++
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private const val MAX_RECONNECT_ATTEMPTS = 10 // Limitar intentos
    private const val INITIAL_RECONNECT_DELAY_MS = 5000L // 5 segundos iniciales
    private const val MAX_RECONNECT_DELAY_MS = 60000L // 1 minuto máximo
    private var isManualDisconnect = false // Para evitar reconexión si desconectamos manualmente

    private val reconnectRunnable = Runnable {
        Log.d("HA_WS_Reconnect", "Ejecutando intento de reconexión #$reconnectAttempts")
        connect() // Intentar conectar de nuevo
    }
    // -- End Reconnection Logic --

    const val ACTION_GARAGE_STATE_CHANGED = "com.github.diegood.mygaragedoor.ACTION_GARAGE_STATE_CHANGED"
    const val EXTRA_IS_OPEN = "com.github.diegood.mygaragedoor.EXTRA_IS_OPEN"

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d("HA_WS", "HomeAssistantWS inicializado con contexto.")
    }

    fun getCurrentState(): Boolean? {
        return lastKnownState
    }

    fun connect() {
        if (appContext == null) {
             Log.e("HA_WS_Connect", "Error: Intento de conexión antes de llamar a init().")
             return
        }
        // Evitar múltiples llamadas concurrentes a connect()
        if (isConnecting) {
             Log.d("HA_WS_Connect", "Intento de conexión omitido: ya se está conectando.")
             return
        }
         if (isConnected) {
             Log.d("HA_WS_Connect", "Intento de conexión omitido: ya está conectado.")
             // Si ya está conectado, reseteamos los intentos por si acaso
             resetReconnectAttempts()
             return
         }

        isConnecting = true
        isManualDisconnect = false // Resetear al intentar conectar
        Log.d("HA_WS_Connect", "Estableciendo nueva conexión WebSocket...")

        val client = OkHttpClient()
        val url = try {
             BuildConfig.HA_URL.replace("https", "wss") + "/api/websocket"
        } catch (e: Exception) {
            Log.e("HA_WS", "Error accessing BuildConfig.HA_URL. Make sure it's defined.", e)
            isConnecting = false
            // ++ Programar reintento si falla al obtener URL ++
            scheduleReconnect()
            return
        }
        val token = try {
            BuildConfig.HA_TOKEN
        } catch (e: Exception) {
             Log.e("HA_WS", "Error accessing BuildConfig.HA_TOKEN. Make sure it's defined.", e)
             isConnecting = false
             // ++ Programar reintento si falla al obtener Token ++
             scheduleReconnect()
             return
        }

        val request = Request.Builder()
            .url(url)
            .build()

        // Cancelar cualquier reintento pendiente antes de crear un nuevo socket
        reconnectHandler.removeCallbacks(reconnectRunnable)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false
                // ++ Resetear intentos al conectar exitosamente ++
                resetReconnectAttempts()
                Log.d("HA_WS", "Conexión WebSocket establecida.")

                webSocket.send("{\"type\": \"auth\", \"access_token\": \"$token\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "auth_required" -> Log.d("HA_WS", "Autenticación requerida")
                        "auth_ok" -> {
                            Log.d("HA_WS", "Autenticación exitosa")
                            // Asegurarse de resetear intentos aquí también por si acaso
                            resetReconnectAttempts()

                            val subscriptionMessage = """
                            {
                                "id": $SUBSCRIBE_ID,
                                "type": "subscribe_events",
                                "event_type": "state_changed"
                            }
                            """.trimIndent()
                            Log.d("HA_WS", "Enviando subscripción a state_changed (ID: $SUBSCRIBE_ID)")
                            webSocket.send(subscriptionMessage)


                            val getStatesMessage = """
                            {
                                "id": $GET_STATES_ID,
                                "type": "get_states"
                            }
                            """.trimIndent()
                            Log.d("HA_WS", "Solicitando estados actuales (ID: $GET_STATES_ID)")
                            webSocket.send(getStatesMessage)
                        }
                        "event" -> {
                            if (json.optInt("id") == SUBSCRIBE_ID && json.has("event")) {
                                val event = json.getJSONObject("event")
                                if (event.optString("event_type") == "state_changed" && event.has("data")) {
                                    val eventData = event.getJSONObject("data")
                                    val entityId = eventData.optString("entity_id")
                                    if (entityId == BuildConfig.HA_STATE_ENTITY_ID) {
                                        if (eventData.has("new_state")) {
                                            val newState = eventData.getJSONObject("new_state")
                                            val stateStr = newState.optString("state", "unknown")
                                            val newStateBool = stateStr == "on" 
                                            Log.d("HA_WS_Event", "Cambio de estado para $entityId recibido: $stateStr ($newStateBool)")
                                            updateStateAndNotify(newStateBool)
                                        }
                                    }
                                }
                            }
                        }
                        "result" -> {
                            val resultId = json.optInt("id")
                            val success = json.optBoolean("success", false)
                            // Log.d("HA_WS_Result", "Resultado recibido para ID: $resultId, Success: $success") // Log menos verboso

                            if (resultId == GET_STATES_ID && success && json.has("result")) {
                                Log.d("HA_WS_Result", "Procesando resultado de get_states (ID: $GET_STATES_ID)")
                                val results = json.getJSONArray("result")
                                var initialStateFound = false
                                for (i in 0 until results.length()) {
                                    val entityState = results.getJSONObject(i)
                                    val entityId = entityState.optString("entity_id")
                                    if (entityId == BuildConfig.HA_STATE_ENTITY_ID) {
                                        val stateStr = entityState.optString("state", "unknown")
                                        val initialStateBool = stateStr == "on"
                                        Log.d("HA_WS_Result", "Estado inicial encontrado para $entityId: $stateStr ($initialStateBool)")
                                        updateStateAndNotify(initialStateBool)
                                        initialStateFound = true
                                        break
                                    }
                                }
                                if (!initialStateFound) {
                                     Log.w("HA_WS_Result", "No se encontró el estado inicial para ${BuildConfig.HA_STATE_ENTITY_ID} en la respuesta get_states.")
                                }
                            } else if (!success) {
                                val error = json.optJSONObject("error")
                                Log.e("HA_WS_Result", "Comando fallido (ID: $resultId): ${error?.optString("message", "Unknown error")}")
                            }
                        }
                        "pong" -> {
                            Log.d("HA_WS", "Pong recibido (ID: ${json.optInt("id")})")
                        }
                        else -> Log.d("HA_WS", "Tipo de mensaje no manejado: ${json.optString("type")}")
                    }
                } catch (e: Exception) {
                    Log.e("HA_WS", "Error procesando mensaje: ${e.message}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isConnecting = false
                Log.e("HA_WS", "Error en conexión WebSocket: ${t.message}", t)
                // ++ Programar reintento en caso de fallo ++
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting = false
                Log.d("HA_WS", "WebSocket cerrándose: $code / $reason")
                webSocket.close(1000, null) // Asegurar cierre limpio
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting = false
                Log.d("HA_WS", "WebSocket cerrado: $code / $reason")
                // ++ Programar reintento si se cierra inesperadamente ++
                scheduleReconnect()
            }
        })
        // Limpiar referencia al cliente OkHttp para evitar fugas
        // client.dispatcher.executorService.shutdown() // Considera si esto es necesario o si OkHttp lo maneja bien
    }

    // Función auxiliar para actualizar estado y notificar
    private fun updateStateAndNotify(newState: Boolean?) {
        if (lastKnownState != newState) {
            lastKnownState = newState
            Log.i("HA_WS_State", "Estado actualizado a: $newState. Enviando broadcast.")
            val intent = Intent(ACTION_GARAGE_STATE_CHANGED).apply {
                putExtra(EXTRA_IS_OPEN, newState)
                `package` = appContext?.packageName
            }
             appContext?.sendBroadcast(intent)
        } else {
             // Log.d("HA_WS_State", "El estado recibido ($newState) es el mismo que el actual. No se envía broadcast.") // Log menos verboso
        }
    }


    fun sendToggleCommand() {
        if (!isConnected || webSocket == null) {
            Log.w("HA_WS_Command", "No se puede enviar comando toggle: WebSocket no conectado.")
            // Opcional: Intentar conectar si no está conectado y no se está reconectando ya
            if (!isConnecting && reconnectAttempts == 0) {
                 Log.d("HA_WS_Command", "Intentando conectar antes de enviar comando...")
                 connect()
            }
            return
        }
        val commandId = messageIdCounter++
        val entityId = BuildConfig.HA_OPENER_ENTITY_ID
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

        Log.d("HA_WS_Command", "Enviando comando toggle para $entityId (ID: $commandId)")
        webSocket?.send(toggleCommand)
    }

    fun disconnect() {
        Log.d("HA_WS", "Solicitando desconexión manual del WebSocket.")
        isManualDisconnect = true // Marcar como desconexión manual
        reconnectHandler.removeCallbacks(reconnectRunnable) // Cancelar reintentos pendientes
        webSocket?.close(1000, "Client requested disconnect")
        webSocket = null
        isConnected = false
        isConnecting = false
        reconnectAttempts = 0 // Resetear intentos al desconectar manualmente
        lastKnownState = null
    }

    // ++ Reconnection Helper Methods ++
    private fun scheduleReconnect() {
        // No reintentar si nos desconectamos manualmente o si ya estamos conectados/conectando
        if (isManualDisconnect || isConnected || isConnecting) {
            Log.d("HA_WS_Reconnect", "Reconexión omitida. ManualDisconnect: $isManualDisconnect, Connected: $isConnected, Connecting: $isConnecting")
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w("HA_WS_Reconnect", "Se alcanzó el máximo número de intentos de reconexión ($MAX_RECONNECT_ATTEMPTS).")
            reconnectAttempts = 0 // Resetear para futuros intentos si es necesario
            return
        }

        reconnectAttempts++
        // Calcular delay con backoff exponencial
        val delay = (INITIAL_RECONNECT_DELAY_MS * Math.pow(2.0, (reconnectAttempts - 1).toDouble())).toLong()
        val cappedDelay = delay.coerceAtMost(MAX_RECONNECT_DELAY_MS) // Limitar al máximo

        Log.d("HA_WS_Reconnect", "Programando intento de reconexión #$reconnectAttempts en ${cappedDelay / 1000} segundos.")
        reconnectHandler.postDelayed(reconnectRunnable, cappedDelay)
    }

    private fun resetReconnectAttempts() {
        if (reconnectAttempts > 0) {
            Log.d("HA_WS_Reconnect", "Reseteando intentos de reconexión.")
            reconnectAttempts = 0
            reconnectHandler.removeCallbacks(reconnectRunnable) // Cancelar cualquier reintento pendiente
        }
    }
    // -- End Reconnection Helper Methods --
}