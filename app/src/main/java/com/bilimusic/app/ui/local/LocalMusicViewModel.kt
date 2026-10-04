package com.bilimusic.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.local.LocalMusicRepository
import com.bilimusic.app.data.local.LocalSong
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.domain.model.SongDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalMusicUiState(
    val permissionGranted: Boolean = false,
    val scanning: Boolean = false,
    val songs: List<LocalSong> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val pickerOpen: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
) {
    val selectedCount: Int get() = selectedIds.size

    val allSelected: Boolean get() = songs.isNotEmpty() && selectedIds.size == songs.size

    val totalDurationMs: Long get() = songs.filter { it.id in selectedIds }.sumOf { it.durationMs }
}

/**
 * 本地音乐（把手机里的音频当成本地播放器用）。
 *
 * 数据来自系统媒体库 MediaStore：可以**直接播放**，也可以**导入到某个歌单**
 * 跟 B 站曲目混在一个队列里。
 */
@HiltViewModel
class LocalMusicViewModel @Inject constructor(
    private val localMusicRepository: LocalMusicRepository,
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalMusicUiState())
    val uiState: StateFlow<LocalMusicUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val requiredPermission: String get() = localMusicRepository.requiredPermission

    init {
        refreshPermissionAndScan()
    }

    /** 进页面 / 授权回来后调用：先看权限，有权限就扫描 */
    fun refreshPermissionAndScan() {
        val granted = localMusicRepository.hasPermission()
        _uiState.value = _uiState.value.copy(permissionGranted = granted)
        if (granted) scan()
    }

    fun scan() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(scanning = true)
            val list = localMusicRepository.scan()
            _uiState.value = _uiState.value.copy(
                scanning = false,
                permissionGranted = true,
                songs = list,
                selectedIds = _uiState.value.selectedIds.filter { id -> list.any { it.id == id } }.toSet(),
            )
            if (list.isEmpty()) {
                _messages.send("媒体库里没有找到音频文件（可能还没被系统扫描，或确实没有本地音乐）")
            }
        }
    }

    fun toggleSelect(song: LocalSong) {
        val current = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(
            selectedIds = if (song.id in current) current - song.id else current + song.id,
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedIds = _uiState.value.songs.map { it.id }.toSet(),
        )
    }

    fun invertSelection() {
        val all = _uiState.value.songs.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedIds = all - _uiState.value.selectedIds)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    // ---------------- 直接播放 ----------------

    /** 播放选中（没选就播全部），进的是同一套播放器：队列 / 随机 / 定时 / 小组件都能用 */
    fun playSelected() {
        val state = _uiState.value
        val chosen = if (state.selectedIds.isEmpty()) state.songs else state.songs.filter { it.id in state.selectedIds }
        if (chosen.isEmpty()) {
            viewModelScope.launch { _messages.send("没有可播放的本地音乐") }
            return
        }
        playbackConnection.playSongs(chosen.map { it.toSong() }, 0)
        viewModelScope.launch { _messages.send("开始播放本地音乐：${chosen.size} 首") }
    }

    /**
     * 点某一首直接播放（把它后面的本地歌一起排进队列，这样能连续听）。
     * 本地页既当"导入源"也当"播放器"用。
     */
    fun playFrom(song: LocalSong) {
        val state = _uiState.value
        val index = state.songs.indexOfFirst { it.id == song.id }
        if (index < 0) return
        playbackConnection.playSongs(state.songs.map { it.toSong() }, index)
    }

    // ---------------- 导入歌单 ----------------

    fun openPlaylistPicker() {
        if (_uiState.value.selectedIds.isEmpty()) {
            viewModelScope.launch { _messages.send("请先选择要导入的曲目") }
            return
        }
        _uiState.value = _uiState.value.copy(pickerOpen = true)
        viewModelScope.launch {
            val list = playlistRepository.observePlaylists().first()
            _uiState.value = _uiState.value.copy(playlists = list)
            if (list.isEmpty()) {
                _uiState.value = _uiState.value.copy(pickerOpen = false)
                _messages.send("还没有歌单，先去歌单页新建一个")
            }
        }
    }

    fun closePlaylistPicker() {
        _uiState.value = _uiState.value.copy(pickerOpen = false)
    }

    /** 把选中的本地曲目导入指定歌单（同一文件重复导入会被自动跳过） */
    fun importToPlaylist(targetPlaylistId: Long, targetTitle: String) {
        val state = _uiState.value
        val chosen = state.songs.filter { it.id in state.selectedIds }
        if (chosen.isEmpty()) return
        _uiState.value = state.copy(pickerOpen = false)
        viewModelScope.launch {
            val result = playlistRepository.addSongs(targetPlaylistId, chosen.map { it.toDraft() })
            clearSelection()
            _messages.send(
                buildString {
                    append("已导入 ${result.inserted} 首到「$targetTitle」")
                    if (result.skippedAsDuplicate > 0) {
                        append("；${result.skippedAsDuplicate} 首已存在，跳过重复")
                    }
                },
            )
        }
    }

    // ---------------- 映射 ----------------

    private fun LocalSong.toSong(): Song = Song(
        id = 0L,
        // 本地曲目统一用 "local" 作为 bvid，cid 用 MediaStore id：同文件唯一，重复导入会被判重
        bvid = LOCAL_BVID,
        cid = id,
        title = title,
        upperName = artist,
        coverUrl = artworkUri,
        durationMs = durationMs,
        playlistId = -1L,
        audioQualityId = null,
        isInvalid = false,
        addedAt = 0L,
        sortOrder = 0,
        localUri = uri,
    )

    private fun LocalSong.toDraft(): SongDraft = SongDraft(
        bvid = LOCAL_BVID,
        cid = id,
        title = title,
        upperName = artist,
        coverUrl = artworkUri,
        durationMs = durationMs,
        localUri = uri,
    )

    private companion object {
        const val LOCAL_BVID = "local"
    }
}
