package com.bilimusic.app.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-4 / FR-5 / FR-9 / FR-10 的播放偏好。
 * 只做偏好读写，不含业务逻辑。
 */
@Singleton
class PlaybackPrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 播放模式（FR-4），对应 PlayMode 枚举名 */
    val playMode: Flow<String> = dataStore.data.map { it[KEY_PLAY_MODE] ?: DEFAULT_PLAY_MODE }

    /** 音质档位（FR-10），对应 AudioQualityOption 枚举名 */
    val audioQualityOption: Flow<String> =
        dataStore.data.map { it[KEY_QUALITY] ?: DEFAULT_QUALITY }

    /** 定时关闭默认档位（分钟） */
    val sleepTimerDefaultMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_MINUTES] ?: DEFAULT_SLEEP_MINUTES }

    /** 定时关闭：默认开启「播完当前歌曲后停止」（FR-5 要求默认开启） */
    val sleepTimerStopAfterCurrent: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_STOP_AFTER] ?: true }

    /**
     * FR-5：定时到期时刻（用 elapsedRealtime，防止用户改系统时间）。
     * 0 表示没有在计时。App 被杀后靠它恢复计时。
     */
    val sleepTimerDeadlineElapsed: Flow<Long> =
        dataStore.data.map { it[KEY_SLEEP_DEADLINE] ?: 0L }

    /** FR-5：已经到点、正在等「本曲播完」的状态，也要能跨进程恢复 */
    val sleepTimerWaitingForTrackEnd: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_WAITING] ?: false }

    /** 缓存上限（字节），默认 2GB（FR-9） */
    val cacheLimitBytes: Flow<Long> =
        dataStore.data.map { it[KEY_CACHE_LIMIT] ?: DEFAULT_CACHE_LIMIT_BYTES }

    /** 上次播放的曲目 id，用于 FR 恢复播放位置 */
    val lastPlayedSongId: Flow<Long?> =
        dataStore.data.map { it[KEY_LAST_SONG_ID]?.takeIf { id -> id > 0L } }

    /** 暂停后是否保留通知（FR-8 可选项，默认保留） */
    val keepNotificationWhenPaused: Flow<Boolean> =
        dataStore.data.map { it[KEY_KEEP_NOTIFICATION] ?: true }

    suspend fun setPlayMode(mode: String) = edit { it[KEY_PLAY_MODE] = mode }

    suspend fun setAudioQuality(option: String) = edit { it[KEY_QUALITY] = option }

    suspend fun setSleepTimerDefaultMinutes(minutes: Int) = edit { it[KEY_SLEEP_MINUTES] = minutes }

    suspend fun setSleepTimerStopAfterCurrent(enabled: Boolean) =
        edit { it[KEY_SLEEP_STOP_AFTER] = enabled }

    suspend fun setSleepTimerDeadlineElapsed(value: Long) = edit { it[KEY_SLEEP_DEADLINE] = value }

    suspend fun setSleepTimerWaitingForTrackEnd(value: Boolean) =
        edit { it[KEY_SLEEP_WAITING] = value }

    suspend fun setCacheLimitBytes(bytes: Long) = edit { it[KEY_CACHE_LIMIT] = bytes }

    suspend fun setLastPlayedSongId(songId: Long) = edit { it[KEY_LAST_SONG_ID] = songId }

    suspend fun setKeepNotificationWhenPaused(keep: Boolean) =
        edit { it[KEY_KEEP_NOTIFICATION] = keep }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    suspend fun snapshot(): Snapshot = Snapshot(
        playMode = playMode.first(),
        audioQualityOption = audioQualityOption.first(),
        sleepTimerDefaultMinutes = sleepTimerDefaultMinutes.first(),
        sleepTimerStopAfterCurrent = sleepTimerStopAfterCurrent.first(),
        cacheLimitBytes = cacheLimitBytes.first(),
        lastPlayedSongId = lastPlayedSongId.first(),
    )

    data class Snapshot(
        val playMode: String,
        val audioQualityOption: String,
        val sleepTimerDefaultMinutes: Int,
        val sleepTimerStopAfterCurrent: Boolean,
        val cacheLimitBytes: Long,
        val lastPlayedSongId: Long?,
    )

    companion object {
        /** 默认播放模式：列表循环（整单放完自动接着放，不会莫名停） */
        const val DEFAULT_PLAY_MODE = "REPEAT_ALL"
        const val DEFAULT_QUALITY = "AUTO"
        const val DEFAULT_SLEEP_MINUTES = 30
        const val DEFAULT_CACHE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024

        private val KEY_PLAY_MODE = stringPreferencesKey("play_mode")
        private val KEY_QUALITY = stringPreferencesKey("audio_quality")
        private val KEY_SLEEP_MINUTES = intPreferencesKey("sleep_timer_default_minutes")
        private val KEY_SLEEP_STOP_AFTER = booleanPreferencesKey("sleep_timer_stop_after_current")
        private val KEY_SLEEP_DEADLINE = longPreferencesKey("sleep_timer_deadline_elapsed")
        private val KEY_SLEEP_WAITING = booleanPreferencesKey("sleep_timer_waiting_for_track_end")
        private val KEY_CACHE_LIMIT = longPreferencesKey("cache_limit_bytes")
        private val KEY_LAST_SONG_ID = longPreferencesKey("last_played_song_id")
        private val KEY_KEEP_NOTIFICATION = booleanPreferencesKey("keep_notification_when_paused")
    }
}
