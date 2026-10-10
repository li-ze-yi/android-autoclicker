package com.autoclicker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autoclicker.ui.AppNavigation
import com.autoclicker.ui.EditorRequest
import com.autoclicker.ui.theme.AppTheme

/**
 * 主界面。除正常启动外，还接收悬浮球控制台的「打开编辑器」请求
 * （[EXTRA_EDITOR_SCRIPT_ID] + [EXTRA_EDITOR_ACTION]），直接跳到对应任务的编辑页。
 */
class MainActivity : ComponentActivity() {

    private var editorRequest by mutableStateOf<EditorRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editorRequest = readEditorRequest(intent)
        setContent {
            AppTheme {
                AppNavigation(
                    editorRequest = editorRequest,
                    onEditorRequestHandled = { editorRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        editorRequest = readEditorRequest(intent)
    }

    private fun readEditorRequest(intent: Intent?): EditorRequest? {
        val scriptId = intent?.getStringExtra(EXTRA_EDITOR_SCRIPT_ID)
        if (scriptId.isNullOrBlank()) return null
        return EditorRequest(
            scriptId = scriptId,
            action = intent.getStringExtra(EXTRA_EDITOR_ACTION).orEmpty(),
        )
    }

    companion object {
        /** 要编辑的任务 id。 */
        const val EXTRA_EDITOR_SCRIPT_ID = "editor_script_id"

        /** 打开后直达的动作，见 com.autoclicker.ui.EditorAction。 */
        const val EXTRA_EDITOR_ACTION = "editor_action"
    }
}
