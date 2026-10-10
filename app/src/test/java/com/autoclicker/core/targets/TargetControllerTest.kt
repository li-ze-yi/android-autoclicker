package com.autoclicker.core.targets

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.TargetScriptMapper
import com.autoclicker.domain.model.TargetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TargetController] 与 [TargetScriptMapper] 单元测试。
 */
class TargetControllerTest {

    @Test
    fun 添加移动更新点击目标() {
        val c = TargetController()
        val id = c.addTap(100, 200)
        c.moveTap(id, 300, 400)
        val t = c.targets.value.single() as TargetSpec.TapTarget
        assertEquals(300, t.x); assertEquals(400, t.y)

        c.updateTap(id, intervalMs = 500, holdMs = 80, repeats = 2)
        val u = c.targets.value.single() as TargetSpec.TapTarget
        assertEquals(500L, u.intervalMs); assertEquals(80L, u.holdMs); assertEquals(2, u.repeats)
    }

    @Test
    fun 触摸时长大于间隔_拒绝() {
        val c = TargetController()
        val id = c.addTap(1, 1)
        runCatching { c.updateTap(id, intervalMs = 10, holdMs = 50, repeats = 1) }
            .also { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test
    fun 添加滑动目标_移动控制点_更新时长() {
        val c = TargetController()
        val id = c.addSwipe(0, 100, 0, 500)
        c.moveSwipePoint(id, which = 2, x = 20, y = 600)
        val s = c.targets.value.single() as TargetSpec.SwipeTarget
        assertEquals(20, s.x2); assertEquals(600, s.y2)
        c.updateSwipeDuration(id, 400)
        assertEquals(400L, (c.targets.value.single() as TargetSpec.SwipeTarget).durationMs)
    }

    @Test
    fun 删除清空与隐藏() {
        val c = TargetController()
        val id1 = c.addTap(1, 1); c.addTap(2, 2)
        c.remove(id1)
        assertEquals(1, c.targets.value.size)
        c.setHidden(true); assertTrue(c.hidden.value)
        c.clear()
        assertTrue(c.targets.value.isEmpty()); assertTrue(!c.hidden.value)
    }

    @Test
    fun 映射脚本_步骤与参数正确() {
        val targets = listOf<com.autoclicker.domain.model.TargetSpec>(
            TargetSpec.TapTarget("t1", 10, 20, intervalMs = 100, holdMs = 1, repeats = 2),
            TargetSpec.SwipeTarget("t2", 0, 100, 0, 500, durationMs = 400),
        )
        val script = TargetScriptMapper.toScript("s", "n", targets, RepeatPolicy.Count(1))
        // t1：2×(点击+延时)=4 步；t2：滑动+延时=2 步，共 6
        assertEquals(6, script.steps.size)
        val firstAction = (script.steps[0] as com.autoclicker.domain.model.ScriptStep.BasicStep).action
        assertEquals(Action.Tap(10, 20), firstAction)
        val swipeAction = (script.steps[4] as com.autoclicker.domain.model.ScriptStep.BasicStep).action
        assertEquals(Action.Swipe(0, 100, 0, 500, 400), swipeAction)
    }
}
