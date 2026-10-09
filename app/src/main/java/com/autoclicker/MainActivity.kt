package com.autoclicker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.autoclicker.ui.AppNavHost
import com.autoclicker.ui.Routes
import com.autoclicker.ui.theme.AutoClickerTheme

/**
 * 唯一 Activity，承载 Compose 导航入口。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialRoute = if (intent.getStringExtra("open_route") == "vision") {
            Routes.VISION
        } else {
            Routes.HOME
        }
        setContent {
            AutoClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(startRoute = initialRoute)
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    /** API 33+ 需要运行时申请通知权限，未授予时不影响主流程。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}