package com.autoclicker.core.bus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * AutomationBus 状态中枢单元测试。
 *
 * 仅使用 JUnit4 + kotlinx-coroutines-test，直接断言 StateFlow.value，不引入 Turbine。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationBusTest {

    // ---------- 默认状态 ----------

    @Test
    fun 默认状态_空闲且无脚本_普通录制模式() = runTest {
        val bus = AutomationBus()

        assertEquals(EngineState.Idle, bus.engineState.value)
        assertNull(bus.activeScriptId.value)
        assertEquals(RecordMode.Normal, bus.recordingMode.value)
    }

    // ---------- 合法迁移链 ----------

    @Test
    fun 合法迁移链_启动暂停继续停止_脚本标识正确设置与清空() = runTest {
        val bus = AutomationBus()

        bus.start("script-001")
        assertEquals(EngineState.Running, bus.engineState.value)
        assertEquals("script-001", bus.activeScriptId.value)

        bus.pause()
        assertEquals(EngineState.Paused, bus.engineState.value)
        assertEquals("暂停期间脚本标识应保留", "script-001", bus.activeScriptId.value)

        bus.resume()
        assertEquals(EngineState.Running, bus.engineState.value)
        assertEquals("继续后脚本标识应保留", "script-001", bus.activeScriptId.value)

        bus.stop()
        assertEquals(EngineState.Idle, bus.engineState.value)
        assertNull("停止后脚本标识应清空", bus.activeScriptId.value)
    }

    @Test
    fun 停止后可再次启动新脚本() = runTest {
        val bus = AutomationBus()

        bus.start("script-A")
        bus.stop()
        bus.start("script-B")

        assertEquals(EngineState.Running, bus.engineState.value)
        assertEquals("script-B", bus.activeScriptId.value)
    }

    // ---------- 录制链 ----------

    @Test
    fun 录制链_精确模式_结束后回到空闲并复位模式() = runTest {
        val bus = AutomationBus()

        bus.startRecording(RecordMode.Precise)
        assertEquals(EngineState.Recording, bus.engineState.value)
        assertEquals(RecordMode.Precise, bus.recordingMode.value)
        assertNull("录制期间不携带回放脚本标识", bus.activeScriptId.value)

        bus.stopRecording()
        assertEquals(EngineState.Idle, bus.engineState.value)
        assertEquals("结束录制后模式复位为普通", RecordMode.Normal, bus.recordingMode.value)
    }

    @Test
    fun 普通模式录制_模式为Normal() = runTest {
        val bus = AutomationBus()

        bus.startRecording(RecordMode.Normal)

        assertEquals(EngineState.Recording, bus.engineState.value)
        assertEquals(RecordMode.Normal, bus.recordingMode.value)

        bus.stopRecording()
        assertEquals(EngineState.Idle, bus.engineState.value)
    }

    // ---------- 非法迁移：Idle 时 pause/resume/stop ----------

    @Test
    fun 空闲时_暂停_被拒绝() = runTest {
        val bus = AutomationBus()
        assertIllegalCommand(bus, EngineState.Idle) { bus.pause() }
    }

    @Test
    fun 空闲时_继续_被拒绝() = runTest {
        val bus = AutomationBus()
        assertIllegalCommand(bus, EngineState.Idle) { bus.resume() }
    }

    @Test
    fun 空闲时_停止_被拒绝() = runTest {
        val bus = AutomationBus()
        assertIllegalCommand(bus, EngineState.Idle) { bus.stop() }
    }

    // ---------- 非法迁移：Running 时 start/startRecording ----------

    @Test
    fun 运行时_再次启动_被拒绝且脚本标识不变() = runTest {
        val bus = AutomationBus()
        bus.start("script-running")

        assertIllegalCommand(bus, EngineState.Running) { bus.start("script-other") }

        assertEquals("script-running", bus.activeScriptId.value)
    }

    @Test
    fun 运行时_开始录制_被拒绝且脚本标识不变() = runTest {
        val bus = AutomationBus()
        bus.start("script-running")

        assertIllegalCommand(bus, EngineState.Running) { bus.startRecording(RecordMode.Precise) }

        assertEquals("script-running", bus.activeScriptId.value)
    }

    // ---------- 非法迁移：Recording 时 start/pause ----------

    @Test
    fun 录制时_启动脚本_被拒绝() = runTest {
        val bus = AutomationBus()
        bus.startRecording(RecordMode.Precise)

        assertIllegalCommand(bus, EngineState.Recording) { bus.start("script-1") }

        assertEquals(RecordMode.Precise, bus.recordingMode.value)
    }

    @Test
    fun 录制时_暂停_被拒绝() = runTest {
        val bus = AutomationBus()
        bus.startRecording(RecordMode.Precise)

        assertIllegalCommand(bus, EngineState.Recording) { bus.pause() }
    }

    // ---------- 非法迁移：Paused 时 startRecording ----------

    @Test
    fun 暂停时_开始录制_被拒绝且脚本标识保留() = runTest {
        val bus = AutomationBus()
        bus.start("script-paused")
        bus.pause()

        assertIllegalCommand(bus, EngineState.Paused) { bus.startRecording(RecordMode.Normal) }

        assertEquals("script-paused", bus.activeScriptId.value)
    }

    // ---------- 参数校验 ----------

    @Test
    fun 启动_空白脚本标识_抛出非法参数异常且状态不变() = runTest {
        val bus = AutomationBus()

        try {
            bus.start("   ")
            fail("预期抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("脚本标识不能为空", expected.message)
        }

        assertEquals(EngineState.Idle, bus.engineState.value)
        assertNull(bus.activeScriptId.value)
    }

    // ---------- 一次性事件流（预留能力） ----------

    @Test
    fun 发布事件_UI可接收一次性提示() = runTest {
        val bus = AutomationBus()
        // 使用立即调度的测试调度器，保证订阅在发布前就绪
        val deferred = async(UnconfinedTestDispatcher(testScheduler)) {
            bus.eventMessages.first()
        }

        bus.publishEvent("脚本已保存")

        assertEquals("脚本已保存", deferred.await())
    }

    // ---------- 辅助断言 ----------

    /**
     * 断言挂起命令抛出 [AutomationCommandException]（消息为非空中文），
     * 且命令失败后引擎状态保持为 [expectedState] 不变。
     */
    private suspend fun assertIllegalCommand(
        bus: AutomationBus,
        expectedState: EngineState,
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("预期抛出 AutomationCommandException，但命令被执行")
        } catch (expected: AutomationCommandException) {
            check(!expected.message.isNullOrBlank()) { "非法迁移异常必须携带中文提示消息" }
        }
        assertEquals("非法命令不得改变引擎状态", expectedState, bus.engineState.value)
    }
}
