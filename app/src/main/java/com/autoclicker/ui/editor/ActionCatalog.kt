package com.autoclicker.ui.editor

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.AreaRandomClickAction
import com.autoclicker.domain.model.CallFunctionAction
import com.autoclicker.domain.model.ClickAction
import com.autoclicker.domain.model.ClickColorAction
import com.autoclicker.domain.model.ClickImageAction
import com.autoclicker.domain.model.ClickNodeAction
import com.autoclicker.domain.model.ClickTextAction
import com.autoclicker.domain.model.CloseAppAction
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.DelayAction
import com.autoclicker.domain.model.EmptyAction
import com.autoclicker.domain.model.ExtractContentAction
import com.autoclicker.domain.model.GestureAction
import com.autoclicker.domain.model.GlobalKeyAction
import com.autoclicker.domain.model.GlobalKeyName
import com.autoclicker.domain.model.InputTextAction
import com.autoclicker.domain.model.JumpAction
import com.autoclicker.domain.model.LongPressAction
import com.autoclicker.domain.model.OpenAppAction
import com.autoclicker.domain.model.PercentPoint
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PopupAction
import com.autoclicker.domain.model.RepeatClickAction
import com.autoclicker.domain.model.SpeakAction
import com.autoclicker.domain.model.SwipeAction
import com.autoclicker.domain.model.ToastAction
import com.autoclicker.domain.model.VariableOpAction

/** 动作目录项：标签 + 默认动作工厂。 */
data class ActionEntry(val label: String, val create: () -> Action)

/** 动作分类。 */
data class ActionCategory(val title: String, val entries: List<ActionEntry>)

/** P0 动作全集，按分类组织。 */
val ACTION_CATEGORIES: List<ActionCategory> = listOf(
    ActionCategory(
        "普通点击",
        listOf(
            ActionEntry("点击坐标") { ClickAction(PercentPoint(0.5f, 0.5f)) },
            ActionEntry("长按") { LongPressAction(PercentPoint(0.5f, 0.5f)) },
            ActionEntry("连续点击") { RepeatClickAction(PercentPoint(0.5f, 0.5f)) },
            ActionEntry("区域随机点击") { AreaRandomClickAction(PercentRect(0.4f, 0.4f, 0.6f, 0.6f)) },
        ),
    ),
    ActionCategory(
        "识别点击",
        listOf(
            ActionEntry("点击图片") { ClickImageAction(templateId = "") },
            ActionEntry("点击颜色") { ClickColorAction(color = 0xFFFF0000.toInt()) },
            ActionEntry("点击文字") { ClickTextAction(text = "") },
            ActionEntry("点击节点") { ClickNodeAction(selector = com.autoclicker.domain.model.NodeSelector()) },
        ),
    ),
    ActionCategory(
        "手势",
        listOf(
            ActionEntry("手势轨迹") { GestureAction(strokes = emptyList()) },
            ActionEntry("滑动") { SwipeAction(from = PercentPoint(0.5f, 0.8f), to = PercentPoint(0.5f, 0.2f)) },
        ),
    ),
    ActionCategory(
        "手机控制",
        listOf(
            ActionEntry("全局键") { GlobalKeyAction(GlobalKeyName.BACK) },
        ),
    ),
    ActionCategory(
        "打开关闭应用",
        listOf(
            ActionEntry("打开应用") { OpenAppAction(packageName = "") },
            ActionEntry("关闭应用") { CloseAppAction(packageName = "") },
        ),
    ),
    ActionCategory(
        "内容处理",
        listOf(
            ActionEntry("输入文字") { InputTextAction() },
            ActionEntry("内容提取") { ExtractContentAction(targetVar = "") },
            ActionEntry("变量操作") { VariableOpAction(varName = "") },
        ),
    ),
    ActionCategory(
        "逻辑控制",
        listOf(
            ActionEntry("条件判断") { ConditionAction() },
            ActionEntry("跳转") { JumpAction() },
            ActionEntry("调用函数包") { CallFunctionAction(packageId = "") },
            ActionEntry("延时") { DelayAction() },
            ActionEntry("占位注释") { EmptyAction() },
        ),
    ),
    ActionCategory(
        "提示",
        listOf(
            ActionEntry("Toast 提示") { ToastAction(message = "") },
            ActionEntry("弹窗提示") { PopupAction(message = "") },
            ActionEntry("语音播报") { SpeakAction(message = "") },
        ),
    ),
)

/** 动作在列表中的简短描述。 */
fun actionSummary(action: Action): String = when (action) {
    is ClickAction -> "点击 (${formatPercent(action.point.x)}%, ${formatPercent(action.point.y)}%)"
    is LongPressAction -> "长按 (${formatPercent(action.point.x)}%, ${formatPercent(action.point.y)}%) ${action.durationMs}ms"
    is RepeatClickAction -> "连续点击 x${action.count}"
    is AreaRandomClickAction -> "区域随机点击"
    is ClickImageAction -> "点击图片"
    is ClickColorAction -> "点击颜色 ${colorToHex(action.color)}"
    is ClickTextAction -> "点击文字「${action.text}」"
    is ClickNodeAction -> "点击节点"
    is GestureAction -> "手势轨迹（${action.strokes.size} 条）"
    is SwipeAction -> "滑动"
    is GlobalKeyAction -> "全局键 ${action.key}"
    is OpenAppAction -> "打开应用 ${action.packageName}"
    is CloseAppAction -> "关闭应用 ${action.packageName}"
    is InputTextAction -> "输入文字"
    is ExtractContentAction -> "内容提取 -> ${action.targetVar}"
    is VariableOpAction -> "变量操作 ${action.varName}"
    is ConditionAction -> "条件判断（${action.clauses.size} 条）"
    is JumpAction -> "跳转"
    is CallFunctionAction -> "调用函数包"
    is DelayAction -> "延时 ${action.ms}ms"
    is EmptyAction -> "注释 ${action.note}"
    is ToastAction -> "Toast 提示"
    is PopupAction -> "弹窗提示"
    is SpeakAction -> "语音播报"
    else -> "动作"
}

/** 动作类型标题。 */
fun actionTitle(action: Action): String = when (action) {
    is ClickAction -> "点击坐标"
    is LongPressAction -> "长按"
    is RepeatClickAction -> "连续点击"
    is AreaRandomClickAction -> "区域随机点击"
    is ClickImageAction -> "点击图片"
    is ClickColorAction -> "点击颜色"
    is ClickTextAction -> "点击文字"
    is ClickNodeAction -> "点击节点"
    is GestureAction -> "手势轨迹"
    is SwipeAction -> "滑动"
    is GlobalKeyAction -> "全局键"
    is OpenAppAction -> "打开应用"
    is CloseAppAction -> "关闭应用"
    is InputTextAction -> "输入文字"
    is ExtractContentAction -> "内容提取"
    is VariableOpAction -> "变量操作"
    is ConditionAction -> "条件判断"
    is JumpAction -> "跳转"
    is CallFunctionAction -> "调用函数包"
    is DelayAction -> "延时"
    is EmptyAction -> "占位注释"
    is ToastAction -> "Toast 提示"
    is PopupAction -> "弹窗提示"
    is SpeakAction -> "语音播报"
    else -> "动作"
}