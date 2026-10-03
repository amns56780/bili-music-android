package com.bilimusic.app.ui.player

import androidx.lifecycle.ViewModel
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.player.PlaybackUiState
import com.bilimusic.app.data.player.QueueItem
import com.bilimusic.app.data.player.SleepTimer
import com.bilimusic.app.data.player.SleepTimerState
import com.bilimusic.app.domain.model.PlayMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = playbackConnection.state

    /** FR-4：当前真实播放顺序 */
    val queue: StateFlow<List<QueueItem>> = playbackConnection.queue

    /** FR-5：定时关闭状态 */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    /** FR-10：音质降级等一次性提示 */
    val notice: StateFlow<String?> = playbackConnection.notice

    fun consumeNotice() = playbackConnection.consumeNotice()

    private val _queueSheetOpen = MutableStateFlow(false)
    val queueSheetOpen: StateFlow<Boolean> = _queueSheetOpen.asStateFlow()

    private val _timerSheetOpen = MutableStateFlow(false)
    val timerSheetOpen: StateFlow<Boolean> = _timerSheetOpen.asStateFlow()

    init {
        playbackConnection.connect()
    }

    /** 幂等连接播放服务 */
    fun connect() = playbackConnection.connect()

    fun togglePlayPause() = playbackConnection.togglePlayPause()

    fun next() = playbackConnection.next()

    fun previous() = playbackConnection.previous()

    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)

    fun setPlayMode(mode: PlayMode) = playbackConnection.setPlayMode(mode)

    /** 模式按钮：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序 */
    fun cyclePlayMode() {
        val next = when (playbackConnection.state.value.playMode) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        playbackConnection.setPlayMode(next)
    }

    fun dismissError() = playbackConnection.clearError()

    // ---------------- 队列 ----------------

    fun openQueueSheet() {
        _queueSheetOpen.value = true
    }

    fun closeQueueSheet() {
        _queueSheetOpen.value = false
    }

    fun jumpToQueueItem(index: Int) = playbackConnection.jumpToQueueItem(index)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) =
        playbackConnection.moveQueueItem(fromIndex, toIndex)

    fun removeQueueItem(index: Int) = playbackConnection.removeQueueItem(index)

    // ---------------- 定时关闭 ----------------

    fun openSleepTimerSheet() {
        _timerSheetOpen.value = true
    }

    fun closeSleepTimerSheet() {
        _timerSheetOpen.value = false
    }

    fun startSleepTimer(minutes: Int, stopAfterCurrent: Boolean) {
        sleepTimer.start(minutes, stopAfterCurrent)
        _timerSheetOpen.value = false
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun consumeSleepNotice() {
        sleepTimer.consumeNotice()
    }
}
