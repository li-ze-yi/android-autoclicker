package com.autoclicker.domain.model

/**
 * 目标列表 → 可执行脚本映射。
 *
 * 生成规则（一遍）：
 * - 点击目标：按 repeats 连续点击（每两次之间插入其 intervalMs 延时）；
 * - 滑动目标：执行一次滑动；
 * - 每个目标处理完后插入该目标的 intervalMs 延时（滑动目标取固定 [SWIPE_TARGET_GAP_MS]）。
 *
 * 脚本级全局循环由调用方通过 [RepeatPolicy] 控制。
 */
object TargetScriptMapper {

    /** 滑动目标之间的默认间隔（毫秒） */
    const val SWIPE_TARGET_GAP_MS: Long = 100L

    /**
     * @param scriptId 生成脚本的 ID
     * @param name 脚本名称
     * @param targets 按执行顺序排列的目标
     * @param policy 脚本级重复策略
     */
    fun toScript(
        scriptId: String,
        name: String,
        targets: List<TargetSpec>,
        policy: RepeatPolicy = RepeatPolicy.UntilStopped,
    ): Script {
        val steps = mutableListOf<ScriptStep>()
        var seq = 0L
        fun nextId(): String = "mapped-${seq++}"

        targets.forEach { target ->
            when (target) {
                is TargetSpec.TapTarget -> {
                    val repeats = target.repeats.coerceAtLeast(1)
                    repeat(repeats) { i ->
                        steps += ScriptStep.BasicStep(nextId(), Action.Tap(target.x, target.y))
                        // 每次点击后按间隔等待（最后一次点击也等待，与下一个目标拉开间隔）
                        steps += ScriptStep.BasicStep(nextId(), Action.Delay(target.intervalMs.coerceAtLeast(0)))
                    }
                }

                is TargetSpec.SwipeTarget -> {
                    steps += ScriptStep.BasicStep(
                        nextId(),
                        Action.Swipe(target.x1, target.y1, target.x2, target.y2, target.durationMs),
                    )
                    steps += ScriptStep.BasicStep(nextId(), Action.Delay(SWIPE_TARGET_GAP_MS))
                }
            }
        }
        return Script(id = scriptId, name = name, steps = steps, repeatPolicy = policy)
    }
}
