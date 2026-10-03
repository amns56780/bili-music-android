package com.bilimusic.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WBI 签名的纯逻辑单测（不需要设备）。
 * 期望值是用一份独立实现（PowerShell + .NET MD5）算出来的，用来交叉验证重排表与拼接顺序。
 */
class WbiSignerTest {

    private val tab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    @Test
    fun `mixin_key 按重排表取前 32 位`() {
        val imgKey = "0123456789abcdefghijklmnopqrstuv"
        val subKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"
        val raw = imgKey + subKey
        assertEquals(64, raw.length)

        // 独立实现（PowerShell）算出的期望值
        val expected = "OPi2V8nAfSava3NDrL5RB9KjtseHcGJd"

        val actual = WbiSigner.deriveMixinKey(
            imgUrl = "https://i0.hdslb.com/bfs/wbi/$imgKey.png",
            subUrl = "https://i0.hdslb.com/bfs/wbi/$subKey.png",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `重排表是 0 到 63 的一个完整排列`() {
        assertEquals(64, tab.size)
        assertEquals((0..63).toList(), tab.sorted())
    }

    @Test
    fun `md5 结果与标准一致`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", WbiSigner.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", WbiSigner.md5("abc"))
    }

    @Test
    fun `参数值会过滤掉 感叹号 单引号 括号 星号`() {
        assertEquals("abcdef", WbiSigner.sanitize("a!b'c(d)e*f"))
        assertEquals("正常值123", WbiSigner.sanitize("正常值123"))
    }

    @Test
    fun `签名按 key 升序拼接并附加 w_rid`() {
        val signed = WbiSigner.signWith(
            params = mapOf("pn" to "1", "mid" to "123"),
            mixinKey = "abc",
            wtsSeconds = 1_700_000_000L,
        )
        assertEquals("1700000000", signed["wts"])
        // md5("mid=123&pn=1&wts=1700000000" + "abc")
        assertEquals("b2cb6a95025873d66bba79e23f63e831", signed["w_rid"])
    }

    @Test
    fun `没有 mixin_key 时只带 wts 不产生 w_rid`() {
        val signed = WbiSigner.signWith(
            params = mapOf("mid" to "123"),
            mixinKey = null,
            wtsSeconds = 1_700_000_000L,
        )
        assertEquals("1700000000", signed["wts"])
        assertEquals(null, signed["w_rid"])
    }
}
