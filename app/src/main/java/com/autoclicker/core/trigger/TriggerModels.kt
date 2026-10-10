package com.autoclicker.core.trigger

import kotlinx.serialization.Serializable

/**
 * 定时任务条目（FR-9 定时触发）。
 *
 * 一个条目表示「在 [triggerAtMs] 时刻提醒执行脚本 [scriptId]」；
 * [repeatIntervalMs] > 0 时触发后按该间隔（毫秒）自动排下一次，
 * 等于 0 时为单次任务，触发后条目自动清除。
 *
 * @property id 条目稳定 ID（同时作为 AlarmManager requestCode 的哈希源）
 * @property scriptId 关联脚本 ID
 * @property triggerAtMs 下次触发的绝对时间（System.currentTimeMillis 基准，UTC 毫秒）
 * @property repeatIntervalMs 重复间隔毫秒；0 = 单次
 * @property enabled 是否启用；关闭期间不保留系统闹钟，重新打开时再注册
 */
@Serializable
data class Trigger(
    val id: String,
    val scriptId: String,
    val triggerAtMs: Long,
    val repeatIntervalMs: Long = 0L,
    val enabled: Boolean = true,
) {
    /** 是否为重复条目 */
    val isRepeating: Boolean get() = repeatIntervalMs > 0L
}

/**
 * 定时触发 → MainActivity 的启动约定（供主 agent 在 MainActivity 接入自动运行）。
 *
 * 到点发出的触发通知被点击后，会打开 MainActivity 并在 Intent extras 中携带
 * [EXTRA_RUN_SCRIPT_ID]（值为脚本 ID 字符串）。MainActivity 读取到该 extra
 * 后即可经 AutomationCoordinator.startScript 自动启动对应脚本。
 */
object TriggerLaunch {
    /** MainActivity Intent extra：待运行脚本 ID（String） */
    const val EXTRA_RUN_SCRIPT_ID = "com.autoclicker.extra.RUN_SCRIPT_ID"
}
