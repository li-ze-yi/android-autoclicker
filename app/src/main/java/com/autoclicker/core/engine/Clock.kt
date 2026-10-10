package com.autoclicker.core.engine

/**
 * 毫秒级时钟：用于脚本 [com.autoclicker.domain.model.RepeatPolicy.UntilTime] 的截止判断。
 *
 * 默认实现取系统墙钟；测试可注入返回虚拟时间的实现，保证确定性。
 */
fun interface Clock {
    /** 当前时间（毫秒），只需保证单调递增语义即可 */
    fun nowMs(): Long
}

/** 系统时钟默认实现 */
val SystemClock: Clock = Clock { System.currentTimeMillis() }
