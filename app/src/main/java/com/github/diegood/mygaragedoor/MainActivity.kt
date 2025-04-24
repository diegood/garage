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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivPuerta = findViewById(R.id.ivPuerta)
        tvEstado = findViewById(R.id.tvEstado)

        // Connect using the singleton object (if not already connected by Application or Service)
        // Consider if this connect call is still needed here or if it's handled elsewhere
        HomeAssistantWS.connect()
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
         updateUI(HomeAssistantWS.getCurrentState())
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