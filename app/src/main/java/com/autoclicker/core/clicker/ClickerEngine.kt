package com.autoclicker.core.clicker

import android.content.Context
import com.autoclicker.core.accessibility.AutoAccessService
import com.autoclicker.core.accessibility.GestureExecutor
import com.autoclicker.core.script.newId
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 点击器核心引擎：管理点击点配置，并按顺序 / 循环自动连点。
 * 全局单例，状态与配置通过 StateFlow 暴露给 UI。
 */
object ClickerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    private var store: ClickerStore? = null

    /** 手动停止标记，用于在取消回调中区分「手动停止」与「其他取消」。 */
    @Volatile
    private var stopRequested = false

    private val _state = MutableStateFlow<ClickerState>(ClickerState.Idle)
    val state: StateFlow<ClickerState> = _state.asStateFlow()

    private val _config = MutableStateFlow(ClickerConfig())
    val config: StateFlow<ClickerConfig> = _config.asStateFlow()

    /** 是否正在执行。 */
    val isRunning: Boolean
        get() = job?.isActive == true

    /** 绑定 Context 以启用持久化；仅首次调用时加载磁盘配置，避免覆盖内存中的修改。 */
    fun attach(context: Context) {
        if (store == null) {
            val created = ClickerStore(context)
            store = created
            _config.value = created.load()
        }
    }

    /** 追加一个点击点并持久化。 */
    fun addPoint(x: Float, y: Float): ClickerPoint {
        val point = ClickerPoint(
            id = newId(),
            x = x.coerceAtLeast(0f),
            y = y.coerceAtLeast(0f)
        )
        val updated = _config.value.copy(points = _config.value.points + point)
        _config.value = updated
        store?.save(updated)
        return point
    }

    /** 按 id 替换点击点并持久化；id 不存在时原配置不变。 */
    fun updatePoint(point: ClickerPoint) {
        val updated = _config.value.copy(
            points = _config.value.points.map { if (it.id == point.id) point else it }
        )
        _config.value = updated
        store?.save(updated)
    }

    /** 删除指定点击点并持久化。 */
    fun removePoint(id: String) {
        val updated = _config.value.copy(
            points = _config.value.points.filterNot { it.id == id }
        )
        _config.value = updated
        store?.save(updated)
    }

    /** 清空全部点击点并持久化。 */
    fun clearPoints() {
        val updated = _config.value.copy(points = emptyList())
        _config.value = updated
        store?.save(updated)
    }

    /** 更新循环参数并持久化。 */
    fun updateLoop(loopInfinite: Boolean, loopCount: Int, loopIntervalMs: Long) {
        val updated = _config.value.copy(
            loopInfinite = loopInfinite,
            loopCount = loopCount,
            loopIntervalMs = loopIntervalMs
        )
        _config.value = updated
        store?.save(updated)
    }

    /**
     * 开始连点。
     * 无障碍服务未连接、没有点击点或已在运行时返回 false。
     */
    fun start(): Boolean {
        if (job?.isActive == true) {
            return false
        }
        val cfg = _config.value
        val points = cfg.points
        if (points.isEmpty()) {
            return false
        }
        if (!AutoAccessService.isConnected) {
            return false
        }
        stopRequested = false
        job = scope.launch { runLoop(points, cfg) }
        return true
    }

    /** 停止连点并取消协程；当前处于 Running 时置为已完成（已手动停止）。 */
    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        if (_state.value is ClickerState.Running) {
            _state.value = ClickerState.Finished("已手动停止")
        }
    }

    private suspend fun runLoop(points: List<ClickerPoint>, cfg: ClickerConfig) {
        try {
            val totalLoops = if (cfg.loopInfinite) -1 else maxOf(1, cfg.loopCount)
            var loopIndex = 0
            while (true) {
                points.forEachIndexed { index, point ->
                    coroutineContext.ensureActive()

                    _state.value = ClickerState.Running(
                        loopIndex = loopIndex,
                        totalLoops = totalLoops,
                        pointIndex = index,
                        totalPoints = points.size
                    )

                    val delayMs = point.delayBeforeMs.coerceAtLeast(0L)
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }

                    val ok = if (point.touchDurationMs > 60L) {
                        GestureExecutor.longPress(point.x, point.y, point.touchDurationMs)
                    } else {
                        GestureExecutor.click(point.x, point.y)
                    }
                    if (!ok) {
                        _state.value = ClickerState.Finished("点击失败（无障碍服务未连接或手势被拒绝）")
                        return
                    }
                }

                if (totalLoops != -1 && loopIndex + 1 >= totalLoops) {
                    _state.value = ClickerState.Finished(null)
                    return
                }
                if (cfg.loopIntervalMs > 0L) {
                    delay(cfg.loopIntervalMs)
                }
                loopIndex++
            }
        } catch (e: CancellationException) {
            _state.value = ClickerState.Finished(if (stopRequested) "已手动停止" else "已停止")
        } catch (e: Exception) {
            _state.value = ClickerState.Finished(e.message ?: "执行异常")
        } finally {
            stopRequested = false
        }
    }
}