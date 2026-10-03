package com.bilimusic.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilimusic.app.data.repository.PlaylistRepository
import com.bilimusic.app.domain.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlaylistListUiState {
    data object Loading : PlaylistListUiState
    data class Ready(val playlists: List<Playlist>) : PlaylistListUiState
}

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistListUiState> = repository.observePlaylists()
        .map<List<Playlist>, PlaylistListUiState> { PlaylistListUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistListUiState.Loading,
        )

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun createPlaylist(rawTitle: String) {
        val title = rawTitle.trim()
        viewModelScope.launch {
            when {
                title.isEmpty() -> _messages.send("歌单名不能为空")
                repository.playlistTitleExists(title) -> _messages.send("已存在同名歌单「$title」")
                else -> {
                    repository.createPlaylist(title)
                    _messages.send("已创建歌单「$title」")
                }
            }
        }
    }

    fun renamePlaylist(playlistId: Long, rawTitle: String) {
        val title = rawTitle.trim()
        viewModelScope.launch {
            if (title.isEmpty()) {
                _messages.send("歌单名不能为空")
                return@launch
            }
            val current = repository.getPlaylist(playlistId)
            if (current == null) {
                _messages.send("歌单不存在或已被删除")
                return@launch
            }
            if (current.title == title) {
                _messages.send("歌单名没有变化")
                return@launch
            }
            if (repository.playlistTitleExists(title)) {
                _messages.send("已存在同名歌单「$title」")
                return@launch
            }
            repository.renamePlaylist(playlistId, title)
            _messages.send("已重命名为「$title」")
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id)
            _messages.send("已删除歌单「${playlist.title}」")
        }
    }
}
