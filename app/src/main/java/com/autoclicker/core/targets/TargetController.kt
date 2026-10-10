package com.autoclicker.core.targets

import com.autoclicker.domain.model.TargetSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 多目标配置控制器：悬浮窗目标控件的唯一状态源（纯 Kotlin，可单测）。
 *
 * 持有当前目标列表与"控件是否隐藏"标志；悬浮窗渲染、脚本映射均读取此状态。
 * 所有变更为同步函数（StateFlow），非法参数抛 IllegalArgumentException（中文消息）。
 */
class TargetController {

    private val _targets = MutableStateFlow<List<TargetSpec>>(emptyList())
    /** 当前目标列表（按执行顺序） */
    val targets: StateFlow<List<TargetSpec>> = _targets.asStateFlow()

    private val _hidden = MutableStateFlow(false)
    /** 目标控件是否隐藏（"小眼睛"）；隐藏不影响配置与运行 */
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    private val idSeq = AtomicLong(0)

    /** 添加点击目标，返回新目标 ID */
    fun addTap(x: Int, y: Int): String {
        require(x >= 0 && y >= 0) { "目标坐标不能为负" }
        val id = "target-${idSeq.incrementAndGet()}"
        _targets.update { it + TargetSpec.TapTarget(id, x, y) }
        return id
    }

    /** 添加滑动目标（S1、S2），返回新目标 ID */
    fun addSwipe(x1: Int, y1: Int, x2: Int, y2: Int): String {
        require(x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) { "滑动坐标不能为负" }
        val id = "target-${idSeq.incrementAndGet()}"
        _targets.update { it + TargetSpec.SwipeTarget(id, x1, y1, x2, y2) }
        return id
    }

    /** 移动点击目标到新坐标 */
    fun moveTap(id: String, x: Int, y: Int) {
        require(x >= 0 && y >= 0) { "目标坐标不能为负" }
        updateTarget(id) { existing ->
            (existing as? TargetSpec.TapTarget)?.copy(x = x, y = y)
                ?: throw IllegalArgumentException("目标（$id）不是点击目标")
        }
    }

    /** 移动滑动目标的控制点：which=1 移动 S1，which=2 移动 S2 */
    fun moveSwipePoint(id: String, which: Int, x: Int, y: Int) {
        require(x >= 0 && y >= 0) { "滑动坐标不能为负" }
        updateTarget(id) { existing ->
            val swipe = existing as? TargetSpec.SwipeTarget
                ?: throw IllegalArgumentException("目标（$id）不是滑动目标")
            when (which) {
                1 -> swipe.copy(x1 = x, y1 = y)
                2 -> swipe.copy(x2 = x, y2 = y)
                else -> throw IllegalArgumentException("滑动控制点只能是 1(S1) 或 2(S2)")
            }
        }
    }

    /** 更新点击目标参数 */
    fun updateTap(id: String, intervalMs: Long, holdMs: Long, repeats: Int) {
        require(intervalMs >= 0) { "点击间隔不能为负" }
        require(holdMs in 0..TargetSpec.MAX_HOLD_MS) {
            "触摸时长需在 0~${TargetSpec.MAX_HOLD_MS}ms 之间"
        }
        require(holdMs <= intervalMs) { "建议触摸时长不大于点击间隔" }
        require(repeats >= 1) { "单点重复次数必须 >= 1" }
        updateTarget(id) { existing ->
            (existing as? TargetSpec.TapTarget)?.copy(
                intervalMs = intervalMs, holdMs = holdMs, repeats = repeats,
            ) ?: throw IllegalArgumentException("目标（$id）不是点击目标")
        }
    }

    /** 更新滑动时长 */
    fun updateSwipeDuration(id: String, durationMs: Long) {
        require(durationMs >= 1) { "滑动时长必须 >= 1ms" }
        updateTarget(id) { existing ->
            (existing as? TargetSpec.SwipeTarget)?.copy(durationMs = durationMs)
                ?: throw IllegalArgumentException("目标（$id）不是滑动目标")
        }
    }

    /** 删除目标 */
    fun remove(id: String) {
        _targets.update { list -> list.filterNot { it.id == id } }
    }

    /** 清空全部目标 */
    fun clear() {
        _targets.value = emptyList()
        _hidden.value = false
    }

    /** 设置控件隐藏/显示 */
    fun setHidden(value: Boolean) {
        _hidden.value = value
    }

    private inline fun updateTarget(id: String, transform: (TargetSpec) -> TargetSpec) {
        _targets.update { list ->
            var changed = false
            val result = list.map { t ->
                if (t.id == id) {
                    changed = true
                    transform(t)
                } else t
            }
            require(changed) { "目标（$id）不存在" }
            result
        }
    }

    private inline fun MutableStateFlow<List<TargetSpec>>.update(
        transform: (List<TargetSpec>) -> List<TargetSpec>,
    ) {
        value = transform(value)
    }
}
