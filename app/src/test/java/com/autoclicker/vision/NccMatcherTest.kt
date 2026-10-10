package com.autoclicker.vision

import com.autoclicker.domain.model.PixelPoint
import com.autoclicker.domain.model.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NccMatcherTest {

    /** 生成灰度 ARGB（R=G=B=v，alpha=255），保证亮度恰为 v。 */
    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** 低自相关的伪随机灰度场，用于构造模板与背景。 */
    private fun hash(x: Int, y: Int, seed: Int): Int {
        var h = x * 73856093 xor (y * 19349663) xor (seed * 83492791)
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return h and 0xFF
    }

    private fun template(tw: Int, th: Int, seed: Int): IntArray {
        val a = IntArray(tw * th)
        for (y in 0 until th) {
            for (x in 0 until tw) a[y * tw + x] = gray(hash(x, y, seed))
        }
        return a
    }

    /** 在伪随机背景上于 (px,py) 贴入模板。 */
    private fun scene(
        sw: Int,
        sh: Int,
        bgSeed: Int,
        tpl: IntArray,
        tw: Int,
        th: Int,
        px: Int,
        py: Int,
    ): IntArray {
        val a = IntArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) a[y * sw + x] = gray(hash(x, y, bgSeed))
        }
        for (y in 0 until th) {
            for (x in 0 until tw) a[(py + y) * sw + (px + x)] = tpl[y * tw + x]
        }
        return a
    }

    @Test
    fun findsExactCenterInLargeScene() {
        val tw = 24
        val th = 24
        val sw = 200
        val sh = 160
        val px = 50
        val py = 40
        val tpl = template(tw, th, seed = 7)
        val frame = scene(sw, sh, bgSeed = 1, tpl = tpl, tw = tw, th = th, px = px, py = py)

        val hit = NccMatcher.findBest(tpl, tw, th, frame, sw, sh, region = null, similarity = 0.9f)

        assertEquals(PixelPoint(px + tw / 2, py + th / 2), hit)
    }

    @Test
    fun regionSearchAccountsForOffset() {
        val tw = 20
        val th = 20
        val sw = 180
        val sh = 140
        val px = 60
        val py = 50
        val tpl = template(tw, th, seed = 21)
        val frame = scene(sw, sh, bgSeed = 3, tpl = tpl, tw = tw, th = th, px = px, py = py)
        val region = PixelRect(l = 40, t = 30, r = 180, b = 140)

        val hit = NccMatcher.findBest(tpl, tw, th, frame, sw, sh, region = region, similarity = 0.9f)

        // 命中点仍为绝对像素坐标
        assertEquals(PixelPoint(px + tw / 2, py + th / 2), hit)
    }

    @Test
    fun returnsNullWhenTemplateAbsent() {
        val tw = 24
        val th = 24
        val sw = 200
        val sh = 160
        val tpl = template(tw, th, seed = 7)
        // 背景中不含 tpl
        val frame = scene(sw, sh, bgSeed = 1, tpl = tpl, tw = tw, th = th, px = 50, py = 40)
        val absent = template(tw, th, seed = 999)

        val hit = NccMatcher.findBest(absent, tw, th, frame, sw, sh, region = null, similarity = 0.9f)

        assertNull(hit)
    }

    @Test
    fun returnsNullWhenTemplateOutsideRegion() {
        val tw = 24
        val th = 24
        val sw = 200
        val sh = 160
        val tpl = template(tw, th, seed = 7)
        val frame = scene(sw, sh, bgSeed = 1, tpl = tpl, tw = tw, th = th, px = 20, py = 20)
        // 区域仅覆盖右下，模板位于左上，导出外
        val region = PixelRect(l = 120, t = 100, r = 200, b = 160)

        val hit = NccMatcher.findBest(tpl, tw, th, frame, sw, sh, region = region, similarity = 0.9f)

        assertNull(hit)
    }
}