package com.bilimusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bilimusic.app.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM song WHERE playlistId = :playlistId ORDER BY sortOrder ASC, id ASC")
    fun observeByPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM song WHERE playlistId = :playlistId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByPlaylist(playlistId: Long): List<SongEntity>

    @Query("SELECT * FROM song WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM song WHERE bvid = :bvid AND cid = :cid AND playlistId = :playlistId LIMIT 1")
    suspend fun find(bvid: String, cid: Long, playlistId: Long): SongEntity?

    @Query("SELECT * FROM song WHERE collectionKey = :collectionKey AND playlistId = :playlistId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByCollection(playlistId: Long, collectionKey: String): List<SongEntity>

    /** 去重插入：同歌单内 (bvid, cid) 已存在则整行忽略，返回值 -1 表示被跳过 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(songs: List<SongEntity>): List<Long>

    @Update
    suspend fun update(song: SongEntity)

    @Query("UPDATE song SET audioQualityId = :qualityId WHERE id = :id")
    suspend fun updateQuality(id: Long, qualityId: Int?)

    @Query("UPDATE song SET isInvalid = :invalid WHERE id = :id")
    suspend fun markInvalid(id: Long, invalid: Boolean)

    @Query("UPDATE song SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM song WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM song WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)

    @Query("SELECT COUNT(*) FROM song WHERE playlistId = :playlistId")
    suspend fun countInPlaylist(playlistId: Long): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM song WHERE playlistId = :playlistId")
    suspend fun maxSortOrder(playlistId: Long): Int

    @Query("SELECT * FROM song WHERE bvid = :bvid AND cid = :cid")
    suspend fun getByMediaKey(bvid: String, cid: Long): List<SongEntity>

    @Query(
        """
        SELECT s.* FROM song s
        INNER JOIN playlist p ON p.id = s.playlistId
        WHERE s.bvid = :bvid AND s.cid = :cid
        ORDER BY s.id ASC LIMIT 1
        """,
    )
    suspend fun getFirstByMediaKey(bvid: String, cid: Long): SongEntity?
}
