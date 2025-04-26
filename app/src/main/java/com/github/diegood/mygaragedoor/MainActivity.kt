package com.github.diegood.mygaragedoor

// Remove unused import if HomeAssistantWS is now an object
// import com.github.diegood.mygaragedoor.HomeAssistantWS
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.os.Build // Add this import
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest // <-- Añadir import
import android.content.pm.PackageManager // <-- Añadir import
import androidx.core.app.ActivityCompat // <-- Añadir import
import androidx.core.content.ContextCompat // <-- Añadir import

// Remove the implementation of HomeAssistantWS.WSListener
class MainActivity : AppCompatActivity() {
    private lateinit var ivPuerta: ImageView
    private lateinit var tvEstado: TextView
    // Remove the instance variable: private var haWS: HomeAssistantWS? = null

    // Define the BroadcastReceiver
    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HomeAssistantWS.ACTION_GARAGE_STATE_CHANGED) {
                val isOpen = intent.getBooleanExtra(HomeAssistantWS.EXTRA_IS_OPEN, false) // Default to false if extra not found
                Log.d("MainActivity", "Broadcast received: isOpen=$isOpen")
                // Ensure UI updates run on the main thread
                runOnUiThread {
                    updateUI(isOpen)
                }
            }
        }
    }

    // Define un código de solicitud para los permisos
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivPuerta = findViewById(R.id.ivPuerta)
        tvEstado = findViewById(R.id.tvEstado)

        // Verificar y solicitar permisos antes de iniciar el servicio o conectar WS
        checkAndRequestLocationPermissions()

        // Conectar WS (se moverá a después de obtener permisos si es necesario)
        // HomeAssistantWS.connect() // <- Comentado/movido temporalmente
    }

    private fun checkAndRequestLocationPermissions() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No se necesita en versiones anteriores a Q
        }

        val permissionsToRequest = mutableListOf<String>()
        if (!fineLocationGranted) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Solo pedir background si tenemos fine y estamos en Q+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationGranted && fineLocationGranted) {
             // Es recomendable pedir background por separado después de fine,
             // pero por simplicidad inicial, los pedimos juntos si fine ya está concedido
             // o lo pediremos después en onRequestPermissionsResult
             permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationGranted && !fineLocationGranted) {
             // Si no tenemos ni fine, pedimos fine primero. Background se pedirá después.
             // No añadir ACCESS_BACKGROUND_LOCATION aquí todavía.
        }


        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Solicitando permisos: ${permissionsToRequest.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d("MainActivity", "Todos los permisos de ubicación necesarios ya están concedidos.")
            // Si todos los permisos están concedidos, iniciar lógica dependiente (ej: servicio)
            startHomeAssistantService()
            // Conectar WS aquí si depende de permisos o servicio
            HomeAssistantWS.connect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            var fineLocationGranted = false
            var backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q // Asumir true si no aplica

            permissions.forEachIndexed { index, permission ->
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION && grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    fineLocationGranted = true
                    Log.d("MainActivity", "Permiso ACCESS_FINE_LOCATION concedido.")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION && grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    backgroundLocationGranted = true
                     Log.d("MainActivity", "Permiso ACCESS_BACKGROUND_LOCATION concedido.")
                }
            }

            if (fineLocationGranted) {
                // Si tenemos fine location, podemos iniciar el servicio
                startHomeAssistantService()
                HomeAssistantWS.connect() // Conectar WS

                // Si estamos en Q+ y aún no tenemos background, solicitarlo ahora (si es necesario)
                // Nota: La solicitud de background location a menudo requiere una justificación clara
                // y puede necesitar llevar al usuario a la configuración del sistema.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocationGranted) {
                     Log.w("MainActivity", "Permiso ACCESS_FINE_LOCATION concedido, pero ACCESS_BACKGROUND_LOCATION denegado o no solicitado aún.")
                     // Aquí podrías mostrar un diálogo explicando por qué necesitas el permiso en segundo plano
                     // y potencialmente guiar al usuario a la configuración.
                     // Por ahora, solo lo registramos. El geofencing podría no funcionar en segundo plano.
                     // Si decides pedirlo explícitamente:
                     // ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
                }

            } else {
                Log.e("MainActivity", "Permiso ACCESS_FINE_LOCATION denegado. Funcionalidad de ubicación limitada.")
                // Mostrar mensaje al usuario o deshabilitar funciones
                tvEstado.text = "Error: Permiso de ubicación necesario"
            }
        }
    }

    // Función para iniciar el servicio (reutilizable)
    private fun startHomeAssistantService() {
         Log.d("MainActivity", "Intentando iniciar HomeAssistantService...")
         val serviceIntent = Intent(this, HomeAssistantService::class.java)
         try {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 startForegroundService(serviceIntent)
                 Log.d("MainActivity", "Llamado a startForegroundService.")
             } else {
                 startService(serviceIntent)
                 Log.d("MainActivity", "Llamado a startService.")
             }
         } catch (e: Exception) {
             Log.e("MainActivity", "Error al iniciar el servicio: ${e.message}", e)
         }
    }


     override fun onResume() {
         super.onResume()
         // Register the BroadcastReceiver
         val filter = IntentFilter(HomeAssistantWS.ACTION_GARAGE_STATE_CHANGED)
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Build should now be resolved
             registerReceiver(stateUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
         } else {
             registerReceiver(stateUpdateReceiver, filter) // Older versions don't need the flag
         }
         Log.d("MainActivity", "BroadcastReceiver registered")

         // Update UI with current state immediately upon resuming
         // Solo actualiza si los permisos están concedidos
         if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
             updateUI(HomeAssistantWS.getCurrentState())
         } else {
             // Podrías mostrar un estado indicando que se necesitan permisos
             updateUI(null) // O un estado específico
             tvEstado.text = "Permiso de ubicación requerido"
         }
     }

     override fun onPause() {
         super.onPause()
         // Unregister the BroadcastReceiver
         unregisterReceiver(stateUpdateReceiver)
         Log.d("MainActivity", "BroadcastReceiver unregistered")
     }

     private fun updateUI(isOpen: Boolean?) {
         Log.d("MainActivity", "Updating UI with state: $isOpen")
         ivPuerta.setImageResource(
             when (isOpen) {
                 true -> R.drawable.ic_garage_open
                 false -> R.drawable.ic_garage_closed
                 null -> R.drawable.ic_garage_unknown // Add an unknown state drawable
             }
         )
         tvEstado.text = "Estado: ${
             when (isOpen) {
                 true -> "Abierta"
                 false -> "Cerrada"
                 null -> "Desconocido"
             }
         }"
     }


    override fun onDestroy() {
        // Optionally disconnect if MainActivity is the main controller,
        // but be careful if the widget should keep the connection alive.
        // If the widget needs the connection, don't disconnect here.
        // HomeAssistantWS.disconnect()
        super.onDestroy()
    }
}