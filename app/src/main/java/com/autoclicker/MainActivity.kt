package com.autoclicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.autoclicker.ui.AppNavHost
import com.autoclicker.ui.Routes
import com.autoclicker.ui.theme.AutoClickerTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 唯一 Activity，承载 Compose 导航入口。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 当前请求的路由（由 Intent 的 open_route extra 驱动，供 Compose 观察）。 */
    private val routeRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeRequest.value = intent.getStringExtra(EXTRA_OPEN_ROUTE)
        setContent {
            AutoClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val route by routeRequest.collectAsState()
                    AppNavHost(startRoute = resolveRoute(route))
                }
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeRequest.value = intent.getStringExtra(EXTRA_OPEN_ROUTE)
        // Activity 已在栈中时不会重建，直接重建以便 AppNavHost 用新的 startRoute。
        recreate()
    }

    /** 把 open_route 的值映射为起始路由。 */
    private fun resolveRoute(route: String?): String =
        if (route == VALUE_VISION) Routes.VISION else Routes.HOME

    /** API 33+ 需要运行时申请通知权限，未授予时不影响主流程。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** 打开指定路由的 Intent extra 键；供悬浮窗等服务与 [MainActivity] 共用，避免字面量重复。 */
        const val EXTRA_OPEN_ROUTE = "open_route"
        private const val VALUE_VISION = "vision"
    }
}