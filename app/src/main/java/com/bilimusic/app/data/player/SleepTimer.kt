package com.bilimusic.app.data.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.bilimusic.app.data.local.prefs.PlaybackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** FR-5 定时关闭的 UI 状态 */
data class SleepTimerState(
    val enabled: Boolean = false,
    /** 剩余毫秒（enabled 时有效） */
    val remainingMs: Long = 0L,
    /** 已到点、正在等「本曲播完」 */
    val waitingForTrackEnd: Boolean = false,
    /** 用户开关：播完当前歌曲后停止（默认开） */
    val stopAfterCurrentEnabled: Boolean = true,
    /** 一次性提示（取消定时 / 定时已停止播放等） */
    val notice: String? = null,
) {
    val remainingText: String get() = formatRemaining(remainingMs)

    /** 播放页 / 通知栏上显示的小标签 */
    val labelText: String
        get() = when {
            waitingForTrackEnd -> "本曲播完后停止"
            enabled -> "⏱ 剩余 $remainingText"
            else -> ""
        }

    companion object {
        /** mm:ss；超过 1 小时用 h:mm:ss */
        fun formatRemaining(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}

/**
 * FR-5 定时关闭。
 *
 * 行为要点（任务书原文要求，这里逐条实现）：
 * - 用 `SystemClock.elapsedRealtime()` 计时，**不用** `System.currentTimeMillis()`（防止用户改系统时间）
 * - 到点后分两种情况：
 *   - 开关开启（默认）：**不立刻停**，只设 `waitingForTrackEnd`，UI 标签变成「本曲播完后停止」，
 *     等当前歌曲自然播完时才真正停止
 *   - 开关关闭：到点立刻暂停并停掉前台服务
 * - 到点判定挂在播放器回调上：`onMediaItemTransition(reason == AUTO)` 与 `onPlaybackStateChanged(STATE_ENDED)`
 * - 用户在等待状态下重新点播放 → 取消定时停止，并提示「已取消定时停止」
 * - 到期时刻与等待状态持久化，App 被杀后能恢复
 */
@Singleton
class SleepTimer @Inject constructor(
    private val playbackPrefs: PlaybackPrefs,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var player: Player? = null
    private var onStopPlayback: (() -> Unit)? = null
    private var onRefreshNotification: (() -> Unit)? = null

    /** 到期时刻（elapsedRealtime），0 = 未计时 */
    private var deadlineElapsedMs = 0L
    private var tickJob: Job? = null
    private var lastNotifiedSecond = -1L

    /** 上一次进入「非播放」的时刻，用于区分「用户真的暂停过」和「切歌瞬间的缓冲」 */
    private var pausedSinceElapsed = 0L

    /** 上一次曲目切换的时刻 */
    private var lastTransitionElapsed = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastTransitionElapsed = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "onMediaItemTransition reason=$reason waiting=${_state.value.waitingForTrackEnd} " +
                    "item=${mediaItem?.mediaMetadata?.title}",
            )
            if (_state.value.waitingForTrackEnd &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                Log.i(TAG, "定时到点 + 本曲自然播完 → 停止播放")
                stopNow("定时到点，本曲播完已停止播放")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(
                TAG,
                "onPlaybackStateChanged state=$playbackState waiting=${_state.value.waitingForTrackEnd}",
            )
            // 最后一首歌自然播完：STATE_ENDED 也要触发停止，否则通知栏会一直挂着
            if (_state.value.waitingForTrackEnd && playbackState == Player.STATE_ENDED) {
                Log.i(TAG, "定时到点 + 最后一曲播完 → 停止播放")
                stopNow("定时到点，最后一曲播完已停止播放")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!isPlaying) {
                pausedSinceElapsed = now
                return
            }
            // 重新开始播放：只有「用户真的暂停过」（暂停超过 1.5 秒、且不是刚切歌导致的缓冲）
            // 才算用户主动重新播放，这时才取消「本曲播完后停止」。
            // 否则切歌/拖动进度条造成的瞬时缓冲会把定时状态误取消掉。
            val pausedFor = if (pausedSinceElapsed > 0L) now - pausedSinceElapsed else 0L
            val sinceTransition = now - lastTransitionElapsed
            if (_state.value.waitingForTrackEnd && pausedFor > 1_500L && sinceTransition > 3_000L) {
                Log.i(TAG, "用户在等待停止状态下重新播放 → 取消定时停止")
                cancelInternal(notice = "已取消定时停止")
            }
            pausedSinceElapsed = 0L
        }
    }

    /**
     * FR-5：用户在「本曲播完后停止」状态下重新点了播放 → 取消定时停止。
     * 由 UI 的播放按钮直接调用（比监听播放状态更准确）。
     */
    fun cancelByUserResume() {
        if (_state.value.waitingForTrackEnd) {
            Log.i(TAG, "用户点击播放 → 取消定时停止")
            cancelInternal(notice = "已取消定时停止")
        }
    }

    /** 由 PlaybackService 在创建播放器后调用 */
    fun attach(
        player: Player,
        onStopPlayback: () -> Unit,
        onRefreshNotification: () -> Unit,
    ) {
        this.player = player
        this.onStopPlayback = onStopPlayback
        this.onRefreshNotification = onRefreshNotification
        player.addListener(listener)
        restoreFromPrefs()
    }

    fun detach() {
        tickJob?.cancel()
        player?.removeListener(listener)
        player = null
        onStopPlayback = null
        onRefreshNotification = null
    }

    /** 启动定时（minutes 分钟，1~720） */
    fun start(minutes: Int, stopAfterCurrent: Boolean) {
        val safeMinutes = minutes.coerceIn(1, 720)
        deadlineElapsedMs = SystemClock.elapsedRealtime() + safeMinutes * 60_000L
        lastNotifiedSecond = -1L
        _state.value = SleepTimerState(
            enabled = true,
            remainingMs = safeMinutes * 60_000L,
            waitingForTrackEnd = false,
            stopAfterCurrentEnabled = stopAfterCurrent,
        )
        scope.launch {
            playbackPrefs.setSleepTimerDeadlineElapsed(deadlineElapsedMs)
            playbackPrefs.setSleepTimerStopAfterCurrent(stopAfterCurrent)
            playbackPrefs.setSleepTimerWaitingForTrackEnd(false)
            playbackPrefs.setSleepTimerDefaultMinutes(safeMinutes)
        }
        startTicker()
        onRefreshNotification?.invoke()
    }

    /** 取消定时（含已进入「本曲播完后停止」的状态） */
    fun cancel() = cancelInternal(notice = "已取消定时")

    private fun cancelInternal(notice: String?) {
        tickJob?.cancel()
        deadlineElapsedMs = 0L
        lastNotifiedSecond = -1L
        _state.value = SleepTimerState(
            enabled = false,
            stopAfterCurrentEnabled = _state.value.stopAfterCurrentEnabled,
            notice = notice,
        )
        scope.launch {
            playbackPrefs.setSleepTimerDeadlineElapsed(0L)
            playbackPrefs.setSleepTimerWaitingForTrackEnd(false)
        }
        onRefreshNotification?.invoke()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** 通知栏辅助行用：没定时返回 null */
    fun notificationLabel(): String? = _state.value.labelText.takeIf { it.isNotBlank() }

    // ---------------- 内部 ----------------

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val remain = deadlineElapsedMs - SystemClock.elapsedRealtime()
                if (remain <= 0L) {
                    onDeadlineReached()
                    break
                }
                _state.value = _state.value.copy(enabled = true, remainingMs = remain)
                // 通知栏按秒刷新太费，按「显示出来的秒数变化」刷新
                val shownSecond = remain / 1000L
                if (shownSecond != lastNotifiedSecond) {
                    lastNotifiedSecond = shownSecond
                    onRefreshNotification?.invoke()
                }
                delay(500L)
            }
        }
    }

    private fun onDeadlineReached() {
        if (_state.value.stopAfterCurrentEnabled) {
            // 不立刻停：只设标志，等当前歌曲自然播完
            Log.i(TAG, "定时到点：等本曲播完再停")
            _state.value = _state.value.copy(enabled = false, remainingMs = 0L, waitingForTrackEnd = true)
            scope.launch { playbackPrefs.setSleepTimerWaitingForTrackEnd(true) }
        } else {
            Log.i(TAG, "定时到点：立即暂停并停止前台服务")
            stopNow("定时到点，已停止播放")
        }
        onRefreshNotification?.invoke()
    }

    private fun stopNow(notice: String) {
        tickJob?.cancel()
        deadlineElapsedMs = 0L
        _state.value = SleepTimerState(
            enabled = false,
            stopAfterCurrentEnabled = _state.value.stopAfterCurrentEnabled,
            notice = notice,
        )
        scope.launch {
            playbackPrefs.setSleepTimerDeadlineElapsed(0L)
            playbackPrefs.setSleepTimerWaitingForTrackEnd(false)
        }
        player?.pause()
        onStopPlayback?.invoke()
    }

    /** App/服务被杀后恢复：到期时刻 + 等待状态都从 DataStore 读回来 */
    private fun restoreFromPrefs() {
        scope.launch {
            val deadline = playbackPrefs.sleepTimerDeadlineElapsed.first()
            val waiting = playbackPrefs.sleepTimerWaitingForTrackEnd.first()
            val stopAfter = playbackPrefs.sleepTimerStopAfterCurrent.first()
            when {
                waiting -> {
                    Log.i(TAG, "恢复定时状态：等本曲播完")
                    _state.value = SleepTimerState(
                        waitingForTrackEnd = true,
                        stopAfterCurrentEnabled = stopAfter,
                    )
                }

                deadline > 0L -> {
                    val remain = deadline - SystemClock.elapsedRealtime()
                    if (remain > 0L) {
                        Log.i(TAG, "恢复定时：还剩 ${remain / 1000}s")
                        deadlineElapsedMs = deadline
                        _state.value = SleepTimerState(
                            enabled = true,
                            remainingMs = remain,
                            stopAfterCurrentEnabled = stopAfter,
                        )
                        startTicker()
                    } else {
                        // 过期了：按用户开关决定是否直接停
                        if (stopAfter) {
                            _state.value = SleepTimerState(waitingForTrackEnd = true)
                        } else {
                            stopNow("定时已到点，停止播放")
                        }
                    }
                }

                else -> _state.value = SleepTimerState(stopAfterCurrentEnabled = stopAfter)
            }
        }
    }

    private companion object {
        const val TAG = "SleepTimer"
    }
}
