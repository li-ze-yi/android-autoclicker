package com.autoclicker.engine

import com.autoclicker.domain.model.LiteralValue
import com.autoclicker.domain.model.RandomNumber
import com.autoclicker.domain.model.TimeValue
import com.autoclicker.domain.model.ValueExpr
import com.autoclicker.domain.model.VariableRef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** 求值一个取值表达式（字面量 / 变量 / 随机数 / 时间）。 */
fun resolveValueExpr(expr: ValueExpr, ctx: ExecutionContext): String = when (expr) {
    is LiteralValue -> expr.text
    is VariableRef -> ctx.get(expr.name) ?: ""
    is RandomNumber -> {
        val from = minOf(expr.from, expr.to)
        val to = maxOf(expr.from, expr.to)
        if (to <= from) from.toString() else Random.nextLong(from, to + 1).toString()
    }
    is TimeValue -> try {
        SimpleDateFormat(expr.format, Locale.getDefault()).format(Date())
    } catch (t: Throwable) {
        Date().toString()
    }
}

/** 宽松数字解析：非数字/空返回 0。 */
fun String?.toLongOrZero(): Long = this?.trim()?.toLongOrNull() ?: 0L

/**
 * 运行期执行上下文：全局变量表 + 函数调用栈。
 *
 * 变量查找顺序为「当前帧局部 → 当前帧参数 → 全局」；
 * 赋值时优先写回已存在的参数/局部变量，其次已存在的全局变量，最后新建全局变量。
 */
class ExecutionContext {

    private class Frame {
        val params = LinkedHashMap<String, String>()
        val locals = LinkedHashMap<String, String>()
        val returns = LinkedHashMap<String, String>()
    }

    private val global = LinkedHashMap<String, String>()
    private val stack = ArrayDeque<Frame>()

    /** 当前函数调用深度（栈帧数）。 */
    val callDepth: Int get() = stack.size

    /** 全局变量快照。 */
    val variables: Map<String, String> get() = global.toMap()

    fun get(name: String): String? {
        val key = normalize(name)
        val frame = stack.lastOrNull()
        if (frame != null) {
            frame.locals[key]?.let { return it }
            frame.params[key]?.let { return it }
        }
        return global[key]
    }

    fun set(name: String, value: String) {
        val key = normalize(name)
        val frame = stack.lastOrNull()
        if (frame != null) {
            if (frame.params.containsKey(key)) {
                frame.params[key] = value
                return
            }
            if (frame.locals.containsKey(key)) {
                frame.locals[key] = value
                return
            }
            if (!global.containsKey(key)) {
                frame.locals[key] = value
                return
            }
        }
        global[key] = value
    }

    /** 直接写入全局变量（忽略调用栈隔离）。 */
    fun putGlobal(name: String, value: String) {
        global[normalize(name)] = value
    }

    /** 进入函数调用：压入携带参数的帧。 */
    fun pushFrame(params: Map<String, String>) {
        val frame = Frame()
        params.forEach { (k, v) -> frame.params[normalize(k)] = v }
        stack.addLast(frame)
    }

    /** 退出函数调用：弹出帧并返回其返回值集合。 */
    fun popFrame(): Map<String, String> {
        val frame = stack.removeLastOrNull() ?: return emptyMap()
        return frame.returns.toMap()
    }

    /** 记录函数返回值（写入当前帧）。 */
    fun setReturn(name: String, value: String) {
        stack.lastOrNull()?.returns?.put(normalize(name), value)
    }

    private fun normalize(raw: String): String {
        val n = raw.trim()
        return if (n.length > 3 && n.startsWith("\${") && n.endsWith("}")) n.substring(2, n.length - 1) else n
    }
}