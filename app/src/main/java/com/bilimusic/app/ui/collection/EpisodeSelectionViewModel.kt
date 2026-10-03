package com.bilimusic.app.ui.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.player.PlaybackConnection
import com.bilimusic.app.data.remote.ApiResult
import com.bilimusic.app.data.repository.AddSongsResult
import com.bilimusic.app.data.repository.EpisodeRepository
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.Episode
import com.bilimusic.app.domain.model.Playlist
import com.bilimusic.app.domain.model.Song
import com.bilimusic.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EpisodeSelectionUiState(
    val loading: Boolean = true,
    val title: String = "",
    val upperName: String = "",
    val coverUrl: String? = null,
    val episodes: List<Episode> = emptyList(),
    val error: String? = null,
    val playlistPickerOpen: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val addingToPlaylist: Boolean = false,
) {
    val selectedCount: Int get() = episodes.count { it.selected }

    val totalCount: Int get() = episodes.size

    val hasSelection: Boolean get() = selectedCount > 0

    val allSelected: Boolean get() = episodes.isNotEmpty() && selectedCount == episodes.size

    val selectedDurationMs: Long get() = episodes.filter { it.selected }.sumOf { it.durationMs }
}

sealed interface EpisodeSelectionEvent {
    data class Message(val text: String) : EpisodeSelectionEvent
    data object PlayStarted : EpisodeSelectionEvent
}

/**
 * FR-3 选集页：分P / 合集逐条勾选，勾选状态持久记忆，播放只取勾选项且保持原始顺序。
 */
@HiltViewModel
class EpisodeSelectionViewModel @Inject constructor(
    private val episodeRepository: EpisodeRepository,
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<Long>(Routes.ARG_PLAYLIST_ID) ?: -1L
    private val collectionKey: String = savedStateHandle.get<String>(Routes.ARG_COLLECTION_KEY).orEmpty()

    private val _uiState = MutableStateFlow(EpisodeSelectionUiState())
    val uiState: StateFlow<EpisodeSelectionUiState> = _uiState.asStateFlow()

    private val _events = Channel<EpisodeSelectionEvent>(Channel.BUFFERED)
    val events: Flow<EpisodeSelectionEvent> = _events.receiveAsFlow()

    init {
        load()
        // 勾选状态直接由数据库驱动，改完立刻反映到 UI（也保证重启后一致）
        viewModelScope.launch {
            episodeRepository.observeGroup(playlistId, collectionKey).collect { list ->
                if (list.isNotEmpty()) {
                    _uiState.update { it.copy(episodes = list, loading = false, error = null) }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = episodeRepository.loadGroup(playlistId, collectionKey)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        title = result.data.title,
                        upperName = result.data.upperName,
                        coverUrl = result.data.coverUrl,
                        episodes = result.data.episodes,
                        error = null,
                    )
                }

                is ApiResult.Error -> _uiState.update {
                    it.copy(loading = false, error = result.failure.userMessage)
                }
            }
        }
    }

    // ---------------- 勾选 ----------------

    fun toggleEpisode(episode: Episode) {
        viewModelScope.launch {
            episodeRepository.setSelected(playlistId, collectionKey, episode.epCid, !episode.selected)
        }
    }

    fun selectAll() {
        viewModelScope.launch { episodeRepository.setAllSelected(playlistId, collectionKey, true) }
    }

    fun clearAll() {
        viewModelScope.launch { episodeRepository.setAllSelected(playlistId, collectionKey, false) }
    }

    fun invertSelection() {
        viewModelScope.launch { episodeRepository.invertSelection(playlistId, collectionKey) }
    }

    // ---------------- 播放选中 ----------------

    fun playSelected() {
        val state = _uiState.value
        val selected = state.episodes.filter { it.selected }.sortedBy { it.pageIndex }
        if (selected.isEmpty()) {
            viewModelScope.launch { _events.send(EpisodeSelectionEvent.Message("请至少选择 1 集")) }
            return
        }
        // 队列只包含勾选项，且保持合集/分P的原始相对顺序
        val songs = selected.map { episode ->
            Song(
                id = 0L,
                bvid = episode.epBvid,
                cid = episode.epCid,
                title = episode.epTitle,
                upperName = state.upperName.ifBlank { "未知UP主" },
                coverUrl = state.coverUrl,
                durationMs = episode.durationMs,
                playlistId = playlistId,
                audioQualityId = null,
                isInvalid = false,
                addedAt = 0L,
                sortOrder = episode.pageIndex,
                collectionKey = collectionKey,
                episodeCount = state.totalCount,
                pageIndex = episode.pageIndex,
            )
        }
        playbackConnection.playSongs(songs, 0)
        viewModelScope.launch { _events.send(EpisodeSelectionEvent.PlayStarted) }
    }

    // ---------------- 加入歌单 ----------------

    fun openPlaylistPicker() {
        if (!_uiState.value.hasSelection) {
            viewModelScope.launch { _events.send(EpisodeSelectionEvent.Message("请至少选择 1 集")) }
            return
        }
        _uiState.update { it.copy(playlistPickerOpen = true) }
        viewModelScope.launch {
            val list = playlistRepository.observePlaylists().first()
            _uiState.update { it.copy(playlists = list) }
        }
    }

    fun closePlaylistPicker() {
        _uiState.update { it.copy(playlistPickerOpen = false) }
    }

    fun addSelectedToPlaylist(targetPlaylistId: Long, targetTitle: String) {
        if (_uiState.value.addingToPlaylist) return
        viewModelScope.launch {
            _uiState.update { it.copy(addingToPlaylist = true) }
            when (
                val result = episodeRepository.addSelectedToPlaylist(
                    targetPlaylistId = targetPlaylistId,
                    playlistId = playlistId,
                    collectionKey = collectionKey,
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(addingToPlaylist = false, playlistPickerOpen = false) }
                    _events.send(EpisodeSelectionEvent.Message(describeAdd(result.data, targetTitle)))
                }

                is ApiResult.Error -> {
                    _uiState.update { it.copy(addingToPlaylist = false) }
                    _events.send(EpisodeSelectionEvent.Message(result.failure.userMessage))
                }
            }
        }
    }

    private fun describeAdd(result: AddSongsResult, targetTitle: String): String = buildString {
        append("已加入「$targetTitle」：新增 ${result.inserted} 集")
        if (result.skippedAsDuplicate > 0) append("，重复跳过 ${result.skippedAsDuplicate} 集")
    }
}
