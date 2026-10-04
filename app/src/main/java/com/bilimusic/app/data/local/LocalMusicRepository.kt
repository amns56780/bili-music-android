package com.bilimusic.app.data.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 手机里的一个音频文件（来自系统媒体库 MediaStore） */
data class LocalSong(
    /** MediaStore 里的 id，导入歌单时当作 cid 用，保证同一文件不会重复导入 */
    val id: Long,
    /** 播放用的 content:// URI */
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    /** 专辑封面（部分机型/文件没有，取不到就是 null，UI 显示占位图） */
    val artworkUri: String?
        get() = if (albumId > 0L) "content://media/external/audio/albumart/$albumId" else null
}

/**
 * 本地音乐：扫描系统媒体库（MediaStore.Audio）。
 *
 * 权限：Android 13+ 用 `READ_MEDIA_AUDIO`，12 及以下用 `READ_EXTERNAL_STORAGE`（都只读音频，
 * 不碰照片/视频）。App 不申请任何存储写入权限 —— 本地播放只读文件，不改动用户的东西。
 */
@Singleton
class LocalMusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 当前该申请哪个权限（按系统版本） */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) ==
            PackageManager.PERMISSION_GRANTED

    /** 扫描本地音频；没权限时返回空列表（由 UI 引导授权） */
    suspend fun scan(): List<LocalSong> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        // IS_MUSIC != 0 排除铃声/通知音；太短的（<15 秒）也跳过，多半是音效
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= 15000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val result = mutableListOf<LocalSong>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        result += LocalSong(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            title = cursor.getString(titleCol).orEmpty().ifBlank { "未知曲目" },
                            artist = cursor.getString(artistCol)
                                .orEmpty()
                                .takeIf { it.isNotBlank() && it != "<unknown>" }
                                ?: "未知歌手",
                            album = cursor.getString(albumCol).orEmpty(),
                            albumId = cursor.getLong(albumIdCol),
                            durationMs = cursor.getLong(durationCol),
                            sizeBytes = cursor.getLong(sizeCol),
                        )
                    }
                }
        }
        result
    }
}
