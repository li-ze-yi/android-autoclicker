package com.autoclicker.engine

import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus

/**
 * 模板组识别顺序学习器（运行期内存态，按组共享，每次开始任务即清空）。
 *
 * 动机：组内是「按顺序匹配、先命中先停」。老是命中的那张图如果能排到最前，
 * 就能省掉它前面若干模板的扫描；同时每轮都从「上次学到的顺序」起步，而不是每次都从用户配置的第一张试起。
 *
 * 规则：累计每个成员的命中次数，**达到阈值且当前不在首位**时把它提升到首位，并**清空该组所有计数**。
 * 清零是必须的：不清零的话预热后人人达标，之后「谁命中谁就被置顶」，阈值会形同虚设（退化成命中即置顶）。
 * 清零后，被挤下首位的模板必须重新累计满阈值才能夺回首位。
 *
 * 代价：置顶模板在目标换成另一张图时会先白扫一次，直到另一张累计满阈值才夺回——这是阈值的固有成本，换来抗抖动。
 */
class GroupOrderLearner(private val promoteThreshold: Int = DEFAULT_PROMOTE_THRESHOLD) {

    private val lock = Any()

    /** groupId -> (templateId -> 本次学习周期内的命中次数)。 */
    private val counts = HashMap<String, MutableMap<String, Int>>()

    /** groupId -> 学习后的尝试顺序（按优先级排列，只包含仍存在的成员）。 */
    private val orders = HashMap<String, MutableList<String>>()

    /**
     * 返回该组当前应优先尝试的顺序：学习到的顺序在前，用户新增/未收录的成员按原顺序补在后。
     */
    fun orderFor(groupId: String, memberIds: List<String>): List<String> = synchronized(lock) {
        val learned = orders.getOrPut(groupId) { memberIds.toMutableList() }
        // 运行期间用户可能增删了组内成员：去掉已删除的，把新增的追加到末尾。
        learned.retainAll(memberIds.toSet())
        for (id in memberIds) if (id !in learned) learned.add(id)
        learned.toList()
    }

    /**
     * 记录一次命中；累计达到阈值且该模板不在首位时，把它提升到首位并清空该组计数。
     * [templateLabel] 仅用于日志展示（模板名 / id）。
     */
    fun onHit(groupId: String, groupName: String, templateId: String, templateLabel: String) {
        synchronized(lock) {
            // 已在首位：无需调整（也不必日志），计数继续累计无副作用。
            if (orders[groupId]?.firstOrNull() == templateId) return
            val groupCounts = counts.getOrPut(groupId) { HashMap() }
            val hits = (groupCounts[templateId] ?: 0) + 1
            groupCounts[templateId] = hits
            if (hits < promoteThreshold) return
            val order = orders.getOrPut(groupId) { mutableListOf(templateId) }
            order.remove(templateId)
            order.add(0, templateId)
            // 提升后清零：被挤下首位的模板需要重新累计满阈值才能夺回，避免「谁命中谁置顶」。
            groupCounts.clear()
            RuntimeBus.log(
                LogLevel.INFO,
                "模板组「$groupName」识别顺序调整：优先尝试「$templateLabel」（累计命中 $hits 次）",
            )
        }
    }

    /** 清空全部学习状态（每次开始任务时调用，保证从用户配置的顺序起步）。 */
    fun reset() {
        synchronized(lock) {
            counts.clear()
            orders.clear()
        }
    }

    companion object {
        /** 默认提升阈值：累计命中达到该次数才置顶。 */
        const val DEFAULT_PROMOTE_THRESHOLD = 3
    }
}
