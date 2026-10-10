package com.autoclicker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.autoclicker.core.data.scripts.ScriptFileRepository
import com.autoclicker.core.trigger.TriggerLaunch
import com.autoclicker.ui.AppNavigation
import kotlinx.coroutines.launch

/**
 * 单 Activity 入口：承载 Compose Navigation
 * （首页 / 脚本库 / 函数包 / 权限）。
 *
 * 定时通知点击会带 [TriggerLaunch.EXTRA_RUN_SCRIPT_ID]，
 * 在 [consumeTriggerIntent] 中加载脚本并自动启动；消费后清空 extra，
 * 避免旋转/重建时重复启动。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.autoclicker.ui.theme.AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
        consumeTriggerIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTriggerIntent(intent)
    }

    /** 处理定时通知 extra：加载脚本→启动；脚本不存在给中文提示 */
    private fun consumeTriggerIntent(source: Intent?) {
        val scriptId = source?.getStringExtra(TriggerLaunch.EXTRA_RUN_SCRIPT_ID)
            ?: return
        // 消费即移除，防止重复启动
        source.removeExtra(TriggerLaunch.EXTRA_RUN_SCRIPT_ID)

        val app = applicationContext as MyApplication
        lifecycleScope.launch {
            val repository = ScriptFileRepository(app)
            val script = repository.get(scriptId)
            if (script == null) {
                app.bus.publishEvent("定时任务对应的脚本不存在，可能已被删除")
                return@launch
            }
            app.coordinator.startScript(script)
        }
    }
}
