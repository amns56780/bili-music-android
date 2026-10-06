package com.bilimusic.app.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时关闭的「重启检测」。
 *
 * 背景（用户反馈「随机播放时，某首歌放完会莫名暂停」）：
 * 定时截止点用的是 `SystemClock.elapsedRealtime()`（开机以来毫秒数），**重启会归零**。
 * 只存截止点的话，手机重启后那个数会变成"未来某个时刻"，App 一启动就恢复出一个幽灵定时，
 * 几小时后"到点" → 设「等本曲播完」→ 下一首歌自然播完就暂停。
 *
 * 这里守住：跨重启的定时记录一律丢弃。
 */
class SleepTimerRestoreTest {

    private val bootWall = 1_700_000_000_000L // 某次开机的墙上时刻

    @Test
    fun 同一开机周期内的定时可以恢复() {
        val savedAt = 10L * 60_000 // 开机 10 分钟时设的定时
        val marker = bootWall // 该次开机的标记
        // 20 分钟后检查：开机时长增加、开机标记不变
        assertTrue(
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = savedAt + 30L * 60_000,
                savedAtElapsed = savedAt,
                savedBootMarker = marker,
                nowElapsedMs = 20L * 60_000,
                nowWallMs = bootWall + 20L * 60_000,
            ),
        )
    }

    @Test
    fun 重启后舍弃重启前存的定时() {
        // 重启前：开机 10 小时后设的定时（截止点 ≈ 10.5 小时）
        val savedAt = 10L * 3600_000
        val marker = bootWall
        // 重启后：当前开机才 5 分钟，但"截止点"看起来还有 10 小时 → 这就是幽灵定时
        assertFalse(
            "开机时长变小 ⇒ 重启过，必须丢弃",
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = savedAt + 30L * 60_000,
                savedAtElapsed = savedAt,
                savedBootMarker = marker,
                nowElapsedMs = 5L * 60_000,
                nowWallMs = bootWall + 3L * 3600_000 + 5L * 60_000,
            ),
        )
    }

    @Test
    fun 开机标记变化过大也要丢弃() {
        // 开机时长侥幸"没变小"，但开机时刻标记对不上（说明中间重启过）
        assertFalse(
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = 100L * 60_000,
                savedAtElapsed = 60L * 60_000,
                savedBootMarker = bootWall,
                nowElapsedMs = 70L * 60_000,
                nowWallMs = bootWall + 12L * 3600_000 + 70L * 60_000,
            ),
        )
    }

    @Test
    fun 旧版本数据没有开机信息时保守丢弃() {
        assertFalse(
            "没有开机标记的旧记录不可信，宁可丢弃也不要幽灵定时",
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = 100L * 60_000,
                savedAtElapsed = 0L,
                savedBootMarker = 0L,
                nowElapsedMs = 5L * 60_000,
                nowWallMs = bootWall + 5L * 60_000,
            ),
        )
    }

    @Test
    fun 没有定时就不用恢复() {
        assertFalse(
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = 0L,
                savedAtElapsed = 10L * 60_000,
                savedBootMarker = bootWall,
                nowElapsedMs = 20L * 60_000,
                nowWallMs = bootWall + 20L * 60_000,
            ),
        )
    }

    @Test
    fun 墙钟被校正一点不影响恢复() {
        // NTP 校正了 2 分钟：开机标记偏移 2 分钟，仍在 10 分钟容差内
        assertTrue(
            canRestoreSleepTimerDeadline(
                savedDeadlineElapsed = 40L * 60_000,
                savedAtElapsed = 10L * 60_000,
                savedBootMarker = bootWall,
                nowElapsedMs = 20L * 60_000,
                nowWallMs = bootWall + 20L * 60_000 + 2L * 60_000,
            ),
        )
    }
}
