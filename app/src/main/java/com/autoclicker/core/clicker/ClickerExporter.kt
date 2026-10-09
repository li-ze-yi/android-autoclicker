package com.autoclicker.core.clicker

import com.autoclicker.core.script.Script
import com.autoclicker.core.script.Step
import com.autoclicker.core.script.newId

/** 把点击器配置导出为可复用的 [Script]，与已有脚本库 / 执行引擎 / 定时触发打通。 */
object ClickerExporter {

    /** 一轮 = 每个点击点一个步骤；循环参数映射到 Script 的循环字段。 */
    fun toScript(config: ClickerConfig, name: String): Script {
        val steps = config.points.map { point ->
            if (point.touchDurationMs > 60L) {
                Step.LongPress(
                    x = point.x,
                    y = point.y,
                    durationMs = point.touchDurationMs,
                    delayBeforeMs = point.delayBeforeMs
                )
            } else {
                Step.Tap(
                    x = point.x,
                    y = point.y,
                    delayBeforeMs = point.delayBeforeMs
                )
            }
        }
        return Script(
            id = newId(),
            name = name,
            steps = steps,
            loopCount = config.loopCount,
            loopInfinite = config.loopInfinite,
            loopIntervalMs = config.loopIntervalMs
        )
    }
}