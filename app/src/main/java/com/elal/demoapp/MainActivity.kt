package com.elal.demoapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.elal.demoapp.ui.journey.JourneyDemoScreen
import com.elal.demoapp.ui.theme.ElAlDemoTheme
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted or denied — UI will reflect state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissions()

        // TODO: Debug Only firebase — remove before release
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Log.d("FCM_TOKEN", token)
        }

        setContent {
            ElAlDemoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JourneyDemoScreen()
                }
            }
        }
    }

    private fun requestNotificationPermissions() {
        if (Build.VERSION.SDK_INT < 36) return
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // POST_PROMOTED_NOTIFICATIONS is required for Live Updates on Android 16
        try {
            val promotedPermission = "android.permission.POST_PROMOTED_NOTIFICATIONS"
            if (checkSelfPermission(promotedPermission) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(promotedPermission)
            }
        } catch (_: Exception) {
            // Permission may not exist on older platform versions
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
