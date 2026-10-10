package com.autoclicker.core.record

import com.autoclicker.core.bus.RecordMode
import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.ScriptStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GestureAggregator] 与 [Recorder] 单元测试（TR-8.3、AC-4）。
 */
class GestureAggregatorTest {

    // ---------- 归并器 ----------

    @Test
    fun 未移动_归并为点击() {
        val a = GestureAggregator(tapSlopPx = 20)
        a.onDown(100, 200, timeMs = 0)
        a.onMove(105, 205)
        val r = a.onUp(105, 205, timeMs = 50)
        assertEquals(RecordedGesture.Tap(100, 200), r)
    }

    @Test
    fun 超过容差移动_归并为滑动() {
        val a = GestureAggregator(tapSlopPx = 20)
        a.onDown(100, 200, timeMs = 0)
        a.onMove(130, 260)
        val r = a.onUp(140, 300, timeMs = 200)
        assertEquals(RecordedGesture.Swipe(100, 200, 140, 300, 200), r)
    }

    @Test
    fun 未按下就抬起_抛异常() {
        val a = GestureAggregator()
        assertTrue(
            runCatching { a.onUp(1, 1, 0) }.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    fun 复位后可归并新手势() {
        val a = GestureAggregator()
        a.onDown(10, 10, 0); a.onUp(10, 10, 10)
        a.reset()
        a.onDown(20, 20, 20)
        assertEquals(RecordedGesture.Tap(20, 20), a.onUp(20, 20, 30))
    }

    // ---------- 录制器 ----------

    @Test
    fun 两条手势_每条后自动延时_中间用真实间隔() {
        val r = Recorder()
        r.begin(RecordMode.Precise)
        r.recordGesture(RecordedGesture.Tap(1, 1), timeMs = 100)
        r.recordGesture(RecordedGesture.Tap(2, 2), timeMs = 400)
        val steps = r.steps.value
        // Tap1、延时(真实间隔300，替换了默认尾随延时)、Tap2、延时(默认1000)
        assertEquals(4, steps.size)
        assertEquals(Action.Delay(300), (steps[1] as ScriptStep.BasicStep).action)
        assertEquals(Action.Delay(1000), (steps[3] as ScriptStep.BasicStep).action)
    }

    @Test
    fun 关闭自动延时_不插入任何延时() {
        val r = Recorder()
        r.autoInterval = false
        r.begin(RecordMode.Precise)
        r.recordGesture(RecordedGesture.Tap(1, 1), 100)
        r.recordGesture(RecordedGesture.Tap(2, 2), 200)
        assertEquals(2, r.steps.value.size)
    }

    @Test
    fun 删除动作_连带其后延时() {
        val r = Recorder()
        r.begin(RecordMode.Normal)
        r.recordGesture(RecordedGesture.Tap(1, 1), 0)
        val id = (r.steps.value[0] as ScriptStep.BasicStep).id
        r.removeStep(id)
        assertTrue(r.steps.value.isEmpty())
    }
}
