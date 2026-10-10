package com.autoclicker.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 动作全集（P0 核心）。所有坐标为屏幕百分比，所有时间单位为毫秒。
 */
@Serializable
sealed interface Action

// ---------------- 点击类 ----------------

/** 单坐标点击；durationMs 决定按压时长（长按形态由 LongPressAction 表达）。 */
@Serializable
@SerialName("click")
data class ClickAction(
    val point: PercentPoint,
    val durationMs: Long = 60,
) : Action

/** 长按。 */
@Serializable
@SerialName("longPress")
data class LongPressAction(
    val point: PercentPoint,
    val durationMs: Long = 800,
) : Action

/** 连续点击（连点器）：对同一坐标以 intervalMs 间隔点击 count 次。 */
@Serializable
@SerialName("repeatClick")
data class RepeatClickAction(
    val point: PercentPoint,
    val intervalMs: Long = 50,
    val count: Int = 10,
    val durationMs: Long = 40,
) : Action

/** 区域随机点击：在矩形区域内取随机点点击（拟人化）。 */
@Serializable
@SerialName("areaRandomClick")
data class AreaRandomClickAction(
    val rect: PercentRect,
    val durationMs: Long = 60,
) : Action

// ---------------- 识别点击类 ----------------

/** 通过无障碍控件节点定位点击。 */
@Serializable
data class NodeSelector(
    val text: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val contentDesc: String? = null,
    /** 仅匹配可点击节点。 */
    val clickableOnly: Boolean = false,
    /** 命中多个时取第几个（从 0 开始）。 */
    val index: Int = 0,
)

/** 点击图片：截屏找模板图后点击命中点。 */
@Serializable
@SerialName("clickImage")
data class ClickImageAction(
    val templateId: String,
    val similarity: Float = 0.85f,
    val region: PercentRect? = null,
    /** 命中点附近的随机偏移（像素），用于防检测。 */
    val randomOffset: Int = 0,
    /** 检测次数（失败按 intervalMs 重试）。 */
    val detectCount: Int = 1,
    val detectIntervalMs: Long = 500,
) : Action

/** 点击颜色：在区域内找指定颜色点并点击。 */
@Serializable
@SerialName("clickColor")
data class ClickColorAction(
    /** ARGB 颜色值。 */
    val color: Int,
    val tolerance: Int = 12,
    val region: PercentRect = PercentRect.FULL,
    val randomOffset: Int = 0,
    val detectCount: Int = 1,
    val detectIntervalMs: Long = 500,
) : Action

/** 点击文字（P0 通过无障碍节点文本匹配实现，OCR 引擎留待 P1 接入）。 */
@Serializable
@SerialName("clickText")
data class ClickTextAction(
    val text: String,
    val useRegex: Boolean = false,
    val region: PercentRect? = null,
    val detectCount: Int = 1,
    val detectIntervalMs: Long = 500,
) : Action

/** 点击节点：按控件 text/id/类名/描述定位点击。 */
@Serializable
@SerialName("clickNode")
data class ClickNodeAction(
    val selector: NodeSelector,
    val detectCount: Int = 1,
    val detectIntervalMs: Long = 500,
) : Action

// ---------------- 手势类 ----------------

/** 单条手势轨迹（多点坐标序列）。多指手势由多条轨迹组成。 */
@Serializable
data class GestureStroke(
    val points: List<PercentPoint>,
    val startTimeMs: Long = 0,
    val durationMs: Long = 300,
)

@Serializable
@SerialName("gesture")
data class GestureAction(
    val strokes: List<GestureStroke>,
) : Action

/** 定长滑动。 */
@Serializable
@SerialName("swipe")
data class SwipeAction(
    val from: PercentPoint,
    val to: PercentPoint,
    val durationMs: Long = 300,
) : Action

// ---------------- 手机控制（全局键） ----------------

enum class GlobalKeyName { BACK, HOME, RECENTS }

@Serializable
@SerialName("globalKey")
data class GlobalKeyAction(val key: GlobalKeyName) : Action

// ---------------- 打开/关闭应用 ----------------

@Serializable
@SerialName("openApp")
data class OpenAppAction(
    val packageName: String,
    val activity: String? = null,
) : Action

@Serializable
@SerialName("closeApp")
data class CloseAppAction(val packageName: String) : Action

// ---------------- 内容处理 ----------------

enum class PickMode { SEQUENCE, RANDOM }
enum class NumberGenMode { INCREMENT, RANDOM }

/** 文本来源模式。 */
enum class TextMode { LITERAL, TEXT_GROUP, NUMBER_GENERATOR, VARIABLE }

/** 文本来源：字面量 / 文本组 / 数字产生器 / 变量。 */
@Serializable
data class TextSource(
    val mode: TextMode = TextMode.LITERAL,
    val literal: String = "",
    val groupId: String = "",
    val pickMode: PickMode = PickMode.SEQUENCE,
    val numberMode: NumberGenMode = NumberGenMode.INCREMENT,
    val numberFrom: Long = 0,
    val numberTo: Long = 100,
    val numberStep: Long = 1,
    /** 补零位数，0 表示不补。 */
    val numberLength: Int = 0,
    val variableName: String = "",
)

/** 输入文字。 */
@Serializable
@SerialName("inputText")
data class InputTextAction(
    val source: TextSource = TextSource(),
    val clearFirst: Boolean = true,
) : Action

enum class ExtractSource { OCR, NODE }

/** 内容提取：把 OCR / 节点文本提取到变量，支持正则。 */
@Serializable
@SerialName("extractContent")
data class ExtractContentAction(
    val source: ExtractSource = ExtractSource.NODE,
    val region: PercentRect? = null,
    val nodeSelector: NodeSelector? = null,
    val regex: String? = null,
    val replaceWith: String? = null,
    val targetVar: String,
) : Action

enum class VarOp { ASSIGN, ADD, SUB, MUL, DIV, APPEND }

/** 变量操作：赋值 / 算术 / 文本追加。 */
@Serializable
@SerialName("variableOp")
data class VariableOpAction(
    val varName: String,
    val op: VarOp = VarOp.ASSIGN,
    val operand: ValueExpr = LiteralValue(""),
) : Action

// ---------------- 逻辑控制 ----------------

enum class ConditionType { IMAGE, TEXT, COLOR, NODE, VARIABLE }
enum class BoolCombine { AND, OR }
enum class CompareOp { EQ, NE, GT, GE, LT, LE, CONTAINS, NOT_CONTAINS, REGEX }

/** 单个条件子句。 */
@Serializable
data class ConditionClause(
    val type: ConditionType,
    val templateId: String? = null,
    val similarity: Float = 0.85f,
    val text: String? = null,
    val useRegex: Boolean = false,
    val color: Int? = null,
    val tolerance: Int = 12,
    val region: PercentRect? = null,
    val nodeSelector: NodeSelector? = null,
    val varName: String? = null,
    val op: CompareOp = CompareOp.EQ,
    val compareTo: String = "",
    val negate: Boolean = false,
)

/**
 * 条件运行：满足/不满足时由宿主 StepNode 的 onSuccess/onFailure 跳转决定后续。
 */
@Serializable
@SerialName("condition")
data class ConditionAction(
    val clauses: List<ConditionClause> = emptyList(),
    val combine: BoolCombine = BoolCombine.AND,
    val detectCount: Int = 1,
    val detectIntervalMs: Long = 500,
    /** 超时等待，0 表示只检测一次不等待。 */
    val timeoutMs: Long = 0,
) : Action

enum class JumpMode { STEP, END_TASK }

/** 跳转：跳转到指定步骤，或结束任务。 */
@Serializable
@SerialName("jump")
data class JumpAction(
    val mode: JumpMode = JumpMode.STEP,
    val targetStepId: String? = null,
) : Action

/** 调用函数包。 */
@Serializable
@SerialName("callFunction")
data class CallFunctionAction(
    val packageId: String,
    val args: Map<String, ValueExpr> = emptyMap(),
    /** 返回值名 -> 存入的变量名。 */
    val resultVars: Map<String, String> = emptyMap(),
) : Action

/** 延时。randomMs>0 时在 [ms, ms+randomMs] 之间随机。 */
@Serializable
@SerialName("delay")
data class DelayAction(
    val ms: Long = 1000,
    val randomMs: Long = 0,
) : Action

/** 占位/注释节点。 */
@Serializable
@SerialName("empty")
data class EmptyAction(val note: String = "") : Action

// ---------------- 提示类 ----------------

@Serializable
@SerialName("toast")
data class ToastAction(val message: String) : Action

@Serializable
@SerialName("popup")
data class PopupAction(
    val title: String = "提示",
    val message: String,
) : Action

@Serializable
@SerialName("speak")
data class SpeakAction(val message: String) : Action