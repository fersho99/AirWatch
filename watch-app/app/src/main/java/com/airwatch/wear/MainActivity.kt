package com.airwatch.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.airwatch.wear.ui.AirWatchApp

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        handleIntent(intent)
        setContent { AirWatchApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Atajos para ADB (util si el descubrimiento mDNS falla en tu red):
     *   adb shell am start -n com.airwatch.wear/.MainActivity --es host 192.168.1.50
     *   adb shell am start -n com.airwatch.wear/.MainActivity --es host 192.168.1.50 --ez pair true
     */
    private fun handleIntent(i: Intent?) {
        val host = i?.getStringExtra("host")
        val pair = i?.getBooleanExtra("pair", false) == true
        if (host != null || pair) Engine.requestStart(this, pair, host)
    }
}
