package com.netproxy.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class TransparentProxyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleIntent(intent)
        }
    }

    private fun handleIntent(incomingIntent: Intent) {
        if (Intent.ACTION_VIEW == incomingIntent.action) {
            val uri = incomingIntent.data
            if (uri != null && uri.scheme == "proxy") {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val portStr = prefs.getString(MainActivity.KEY_PORT, MainActivity.DEFAULT_PORT) ?: MainActivity.DEFAULT_PORT
                val password = prefs.getString(MainActivity.KEY_PASSWORD, MainActivity.DEFAULT_PASSWORD) ?: MainActivity.DEFAULT_PASSWORD
                
                val port = try {
                    val p = portStr.toInt()
                    if (p in 1..65535) p else MainActivity.DEFAULT_PORT.toInt()
                } catch (e: Exception) {
                    MainActivity.DEFAULT_PORT.toInt()
                }

                var startParam = uri.getQueryParameter("start")
                if (startParam == null) {
                    val hostOrPath = uri.host ?: uri.schemeSpecificPart
                    if (hostOrPath != null && hostOrPath.contains("start=")) {
                        val parts = hostOrPath.replace("//", "").split("=")
                        if (parts.size > 1) {
                            startParam = parts[1]
                        }
                    }
                }

                val serviceIntent = Intent(this, ProxyService::class.java).apply {
                    putExtra("PORT", port)
                    putExtra("PASSWORD", password)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                val minutes = startParam?.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        stopService(Intent(this, ProxyService::class.java))
                    }, minutes * 60 * 1000L)
                }
            }
        }
        // Odmah zatvori aktivnost bez ikakvog iskakanja i gubitka fokusa pozivaoca
        finish()
    }
}
