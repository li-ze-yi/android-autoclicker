package com.autoclicker.engine

import com.autoclicker.domain.model.BoolCombine
import com.autoclicker.domain.model.CompareOp
import com.autoclicker.domain.model.ConditionAction
import com.autoclicker.domain.model.ConditionClause
import com.autoclicker.domain.model.ConditionType
import com.autoclicker.domain.model.PercentRect
import com.autoclicker.domain.model.PixelRect
import com.autoclicker.domain.rule.CoordinateMapper
import com.autoclicker.platform.ScreenFrame

/**
 * 条件求值器：[ConditionAction] 的 clauses（IMAGE/TEXT/COLOR/NODE/VARIABLE）
 * 按 [BoolCombine] 组合，并处理各子句的 negate 与整体重试/超时。
 */
class ConditionEvaluator(private val gate: PauseGate) {

    suspend fun evaluate(
        action: ConditionAction,
        ctx: ExecutionContext,
        mapper: () -> CoordinateMapper,
    ): Boolean {
        if (action.timeoutMs > 0) {
            val start = System.currentTimeMillis()
            val interval = if (action.detectIntervalMs > 0) action.detectIntervalMs else 200L
            while (true) {
                if (evaluateOnce(action, ctx, mapper)) return true
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= action.timeoutMs) return false
                gate.delay(minOf(interval, action.timeoutMs - elapsed))
            }
        }
        val attempts = action.detectCount.coerceAtLeast(1)
        var i = 0
        while (i < attempts) {
            if (evaluateOnce(action, ctx, mapper)) return true
            i++
            if (i < attempts) gate.delay(action.detectIntervalMs.coerceAtLeast(0))
        }
        return false
    }

    private suspend fun evaluateOnce(
        action: ConditionAction,
        ctx: ExecutionContext,
        mapper: () -> CoordinateMapper,
    ): Boolean {
        if (action.clauses.isEmpty()) return false
        val results = action.clauses.map { evaluateClause(it, ctx, mapper) }
        return when (action.combine) {
            BoolCombine.AND -> results.all { it }
            BoolCombine.OR -> results.any { it }
        }
    }

    private suspend fun evaluateClause(
        clause: ConditionClause,
        ctx: ExecutionContext,
        mapper: () -> CoordinateMapper,
    ): Boolean {
        val raw = when (clause.type) {
            ConditionType.IMAGE -> evalImage(clause)
            ConditionType.TEXT -> evalText(clause, mapper)
            ConditionType.COLOR -> evalColor(clause)
            ConditionType.NODE -> evalNode(clause)
            ConditionType.VARIABLE -> evalVariable(clause, ctx)
        }
        return if (clause.negate) !raw else raw
    }

    private suspend fun evalImage(clause: ConditionClause): Boolean {
        val id = clause.templateId ?: return false
        val frame = captureFrame() ?: return false
        val finder = Platform.images() ?: return false
        val region = clause.region?.let { CoordinateMapper(frame.width, frame.height).toPixel(it) }
        return finder.find(id, clause.similarity, region, frame) != null
    }

    private suspend fun evalText(clause: ConditionClause, mapper: () -> CoordinateMapper): Boolean {
        val query = clause.text ?: return false
        val locator = Platform.nodes() ?: return false
        val nodes = locator.readAllText()
        val region = clause.region?.let { mapper().toPixel(it) }
        for (info in nodes) {
            val text = info.text ?: continue
            val matched = if (clause.useRegex) {
                runCatching { Regex(query).containsMatchIn(text) }.getOrDefault(false)
            } else {
                text.contains(query)
            }
            if (matched && (region == null || inRegion(region, info.bounds))) return true
        }
        return false
    }

    private suspend fun evalColor(clause: ConditionClause): Boolean {
        val color = clause.color ?: return false
        val frame = captureFrame() ?: return false
        val finder = Platform.colors() ?: return false
        val region = CoordinateMapper(frame.width, frame.height).toPixel(clause.region ?: PercentRect.FULL)
        return finder.find(color, clause.tolerance, region, frame) != null
    }

    private suspend fun evalNode(clause: ConditionClause): Boolean {
        val selector = clause.nodeSelector ?: return false
        val locator = Platform.nodes() ?: return false
        return locator.findNodes(selector).isNotEmpty()
    }

    private fun evalVariable(clause: ConditionClause, ctx: ExecutionContext): Boolean {
        val name = clause.varName ?: return false
        val actual = ctx.get(name) ?: ""
        val expected = clause.compareTo
        return when (clause.op) {
            CompareOp.EQ -> actual == expected
            CompareOp.NE -> actual != expected
            CompareOp.GT -> actual.toLongOrZero() > expected.toLongOrZero()
            CompareOp.GE -> actual.toLongOrZero() >= expected.toLongOrZero()
            CompareOp.LT -> actual.toLongOrZero() < expected.toLongOrZero()
            CompareOp.LE -> actual.toLongOrZero() <= expected.toLongOrZero()
            CompareOp.CONTAINS -> actual.contains(expected)
            CompareOp.NOT_CONTAINS -> !actual.contains(expected)
            CompareOp.REGEX -> runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
        }
    }

    private suspend fun captureFrame(): ScreenFrame? {
        val source = Platform.screen() ?: return null
        if (!source.isReady()) return null
        return try {
            source.capture()
        } catch (t: Throwable) {
            null
        }
    }

    private fun inRegion(rect: PixelRect, bounds: PixelRect): Boolean {
        val cx = bounds.centerX
        val cy = bounds.centerY
        return cx >= rect.l && cx <= rect.r && cy >= rect.t && cy <= rect.b
    }
}