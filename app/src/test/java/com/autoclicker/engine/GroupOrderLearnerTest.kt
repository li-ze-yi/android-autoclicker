package com.autoclicker.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupOrderLearnerTest {

    private val members = listOf("a", "b", "c")

    @Test
    fun keepsUserOrderBelowThreshold() {
        val learner = GroupOrderLearner()
        assertEquals(members, learner.orderFor("g", members))
        learner.onHit("g", "G", "b", "B")
        learner.onHit("g", "G", "b", "B")
        // 未达阈值：顺序不变
        assertEquals(members, learner.orderFor("g", members))
    }

    @Test
    fun promotesToFrontAtThreshold() {
        val learner = GroupOrderLearner()
        repeat(3) { learner.onHit("g", "G", "c", "C") }
        assertEquals(listOf("c", "a", "b"), learner.orderFor("g", members))
    }

    @Test
    fun clearsCountersOnPromotion() {
        val learner = GroupOrderLearner()
        repeat(3) { learner.onHit("g", "G", "c", "C") }
        assertEquals(listOf("c", "a", "b"), learner.orderFor("g", members))
        // 计数已清零：被挤下首位的模板必须重新累计满阈值才能夺回，避免「谁命中谁置顶」
        learner.onHit("g", "G", "a", "A")
        learner.onHit("g", "G", "a", "A")
        assertEquals(listOf("c", "a", "b"), learner.orderFor("g", members))
        learner.onHit("g", "G", "a", "A")
        assertEquals(listOf("a", "c", "b"), learner.orderFor("g", members))
    }

    @Test
    fun orderForTracksMemberEdits() {
        val learner = GroupOrderLearner()
        repeat(3) { learner.onHit("g", "G", "b", "B") }
        assertEquals(listOf("b", "a", "c"), learner.orderFor("g", members))
        // 运行期用户删掉了 a、新增 d：a 被剔除，d 追加到末尾
        assertEquals(listOf("b", "c", "d"), learner.orderFor("g", listOf("b", "c", "d")))
    }

    @Test
    fun resetRestoresUserOrder() {
        val learner = GroupOrderLearner()
        repeat(3) { learner.onHit("g", "G", "c", "C") }
        learner.reset()
        assertEquals(members, learner.orderFor("g", members))
    }

    @Test
    fun independentPerGroup() {
        val learner = GroupOrderLearner()
        repeat(3) { learner.onHit("g1", "G1", "c", "C") }
        assertEquals(listOf("c", "a", "b"), learner.orderFor("g1", members))
        // 另一个组不受影响
        assertEquals(members, learner.orderFor("g2", members))
    }
}
