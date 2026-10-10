package com.autoclicker.core.engine

import com.autoclicker.domain.model.Action
import com.autoclicker.domain.model.FunctionPackage
import com.autoclicker.domain.model.RepeatPolicy
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.model.ScriptStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScriptPlayer] 单元测试（TR-5.1 ~ TR-5.5）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptPlayerTest {

    // ---------- 假依赖 ----------

    /** 记录所有手势指令的假执行器 */
    private class Recorder : GestureExecutor {
        val commands = mutableListOf<String>()

        override suspend fun tap(x: Int, y: Int, holdMs: Long): GestureResult {
            commands += "tap($x,$y)"
            return GestureResult.Completed
        }

        override suspend fun longPress(x: Int, y: Int, durationMs: Long): GestureResult {
            commands += "long($x,$y,$durationMs)"
            return GestureResult.Completed
        }

        override suspend fun swipe(
            x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long,
        ): GestureResult {
            commands += "swipe($x1,$y1,$x2,$y2,$durationMs)"
            return GestureResult.Completed
        }
    }

    /** 统计脚本遍数的监听器 */
    private class PassCounter : PlaybackListener {
        var passes = 0
        override fun onPassCompleted(pass: Int) {
            passes = pass
        }
    }

    private fun tapStep(id: String, x: Int, y: Int): ScriptStep =
        ScriptStep.BasicStep(id, Action.Tap(x, y))

    private fun delayStep(id: String, ms: Long): ScriptStep =
        ScriptStep.BasicStep(id, Action.Delay(ms))

    private fun callStep(id: String, packageId: String): ScriptStep =
        ScriptStep.PackageCall(id, packageId)

    // ---------- TR-5.1：多目标顺序 ×2 循环 ----------

    @Test
    fun 三个目标_循环2次_收到6次顺序指令() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(tapStep("a", 10, 10), tapStep("b", 20, 20), tapStep("c", 30, 30)),
            repeatPolicy = RepeatPolicy.Count(2),
        )
        player.play(script)
        assertEquals(
            listOf(
                "tap(10,10)", "tap(20,20)", "tap(30,30)",
                "tap(10,10)", "tap(20,20)", "tap(30,30)",
            ),
            recorder.commands,
        )
    }

    // ---------- TR-5.2：无限循环取消后无新指令 ----------

    @Test
    fun 无限循环_取消协程后_不再有新指令() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        // 每遍包含 100ms 延时，防止虚拟时间下死循环
        val script = Script(
            id = "s", name = "n",
            steps = listOf(tapStep("a", 1, 1), delayStep("d", 100)),
            repeatPolicy = RepeatPolicy.UntilStopped,
        )
        val job = launch { player.play(script) }
        advanceTimeBy(250)
        job.cancel()
        job.join()
        val afterCancel = recorder.commands.toList()
        assertTrue("取消前应已执行至少 2 遍，实际 ${afterCancel.size}", afterCancel.size >= 2)
        // 再推进虚拟时间，不应出现任何新指令
        advanceTimeBy(1000)
        assertEquals(afterCancel, recorder.commands)
    }

    // ---------- TR-5.3：总时长策略 ----------

    @Test
    fun 总时长250ms_每遍100ms_恰好执行3遍() = runTest {
        val recorder = Recorder()
        // 虚拟时钟：返回测试调度器当前虚拟时间
        val virtualClock = Clock { currentTime }
        val player = ScriptPlayer(recorder, PackageResolver { null }, clock = virtualClock)
        val counter = PassCounter()
        val script = Script(
            id = "s", name = "n",
            steps = listOf(delayStep("d", 100)),
            repeatPolicy = RepeatPolicy.UntilTime(250),
        )
        player.play(script, listener = counter)
        assertEquals(3, counter.passes)
    }

    // ---------- TR-5.4：循环段执行序列 ----------

    @Test
    fun 循环段3次_执行序列正确() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        val group = ScriptStep.LoopGroup(
            id = "g", name = "段", count = 3,
            steps = listOf(tapStep("b", 2, 2), tapStep("c", 3, 3)),
        )
        val script = Script(
            id = "s", name = "n",
            steps = listOf(tapStep("a", 1, 1), group, tapStep("d", 4, 4)),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        player.play(script)
        assertEquals(
            listOf(
                "tap(1,1)",
                "tap(2,2)", "tap(3,3)",
                "tap(2,2)", "tap(3,3)",
                "tap(2,2)", "tap(3,3)",
                "tap(4,4)",
            ),
            recorder.commands,
        )
    }

    @Test
    fun 嵌套循环段_计数正确() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        val inner = ScriptStep.LoopGroup(
            id = "gi", name = "内", count = 2,
            steps = listOf(tapStep("f", 6, 6)),
        )
        val outer = ScriptStep.LoopGroup(
            id = "go", name = "外", count = 2,
            steps = listOf(tapStep("e", 5, 5), inner),
        )
        val script = Script(
            id = "s", name = "n",
            steps = listOf(outer),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        player.play(script)
        assertEquals(
            listOf(
                "tap(5,5)", "tap(6,6)", "tap(6,6)",
                "tap(5,5)", "tap(6,6)", "tap(6,6)",
            ),
            recorder.commands,
        )
    }

    // ---------- TR-5.5：函数包调用与安全 ----------

    @Test
    fun 函数包调用_执行包内步骤() = runTest {
        val recorder = Recorder()
        val pkg = FunctionPackage("p1", "包", listOf(tapStep("p", 9, 9)))
        val player = ScriptPlayer(recorder, PackageResolver { pkg })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(callStep("c", "p1")),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        player.play(script)
        assertEquals(listOf("tap(9,9)"), recorder.commands)
    }

    @Test
    fun 调用深度超3层_抛中文异常() = runTest {
        val recorder = Recorder()
        // p1→p2→p3→p4，脚本调用 p1，进入 p4 时为第 4 层，应拒绝
        fun chainPkg(id: String, next: String) = FunctionPackage(
            id = id, name = id,
            steps = listOf(callStep("call-$id", next)),
        )
        val packages = mapOf(
            "p1" to chainPkg("p1", "p2"),
            "p2" to chainPkg("p2", "p3"),
            "p3" to chainPkg("p3", "p4"),
            "p4" to FunctionPackage("p4", "p4", listOf(tapStep("end", 1, 1))),
        )
        val player = ScriptPlayer(recorder, PackageResolver { packages[it] })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(callStep("c", "p1")),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        val ex = runCatching { player.play(script) }.exceptionOrNull()
        assertTrue("应抛 PlaybackException，实际 $ex", ex is PlaybackException)
        assertTrue("消息应说明深度，实际 ${ex.message}", ex.message!!.contains("深度"))
        // 深层步骤不应被执行
        assertTrue(recorder.commands.isEmpty())
    }

    @Test
    fun 环调用_抛中文异常() = runTest {
        val recorder = Recorder()
        val pa = FunctionPackage("pa", "pa", listOf(callStep("ca", "pb")))
        val pb = FunctionPackage("pb", "pb", listOf(callStep("cb", "pa")))
        val packages = mapOf("pa" to pa, "pb" to pb)
        val player = ScriptPlayer(recorder, PackageResolver { packages[it] })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(callStep("c", "pa")),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        val ex = runCatching { player.play(script) }.exceptionOrNull()
        assertTrue(ex is PlaybackException)
        assertTrue("消息应说明环，实际 ${ex.message}", ex.message!!.contains("环"))
    }

    @Test
    fun 函数包缺失_抛中文异常() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(callStep("c", "missing")),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        val ex = runCatching { player.play(script) }.exceptionOrNull()
        assertTrue(ex is PlaybackException)
        assertTrue("消息应说明不存在，实际 ${ex.message}", ex.message!!.contains("不存在"))
    }

    @Test
    fun 未注入识图等待器_遇到WaitImage报错() = runTest {
        val recorder = Recorder()
        val player = ScriptPlayer(recorder, PackageResolver { null })
        val script = Script(
            id = "s", name = "n",
            steps = listOf(
                ScriptStep.BasicStep("w", Action.WaitImage(templateId = "t", timeoutMs = 1000)),
            ),
            repeatPolicy = RepeatPolicy.Count(1),
        )
        val ex = runCatching { player.play(script) }.exceptionOrNull()
        assertTrue(ex is PlaybackException)
        assertTrue(ex.message!!.contains("识图"))
    }
}
