package com.bilimusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bilimusic.app.data.local.entity.CollectionSelectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionSelectionDao {

    @Query(
        """
        SELECT * FROM collection_selection
        WHERE playlistId = :playlistId AND collectionKey = :collectionKey
        ORDER BY pageIndex ASC, id ASC
        """,
    )
    fun observeGroup(playlistId: Long, collectionKey: String): Flow<List<CollectionSelectionEntity>>

    @Query(
        """
        SELECT * FROM collection_selection
        WHERE playlistId = :playlistId AND collectionKey = :collectionKey
        ORDER BY pageIndex ASC, id ASC
        """,
    )
    suspend fun getGroup(playlistId: Long, collectionKey: String): List<CollectionSelectionEntity>

    @Query("SELECT * FROM collection_selection WHERE playlistId = :playlistId")
    suspend fun getAllInPlaylist(playlistId: Long): List<CollectionSelectionEntity>

    @Query(
        """
        SELECT * FROM collection_selection
        WHERE playlistId = :playlistId AND collectionKey = :collectionKey AND selected = 1
        ORDER BY pageIndex ASC, id ASC
        """,
    )
    suspend fun getSelected(playlistId: Long, collectionKey: String): List<CollectionSelectionEntity>

    @Query("SELECT DISTINCT collectionKey FROM collection_selection WHERE playlistId = :playlistId")
    suspend fun getCollectionKeys(playlistId: Long): List<String>

    /** 以 (playlistId, collectionKey, epCid) 唯一索引做覆盖写入，实现勾选记忆持久化 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CollectionSelectionEntity>)

    @Query("UPDATE collection_selection SET selected = :selected WHERE id = :id")
    suspend fun setSelected(id: Long, selected: Boolean)

    @Query("UPDATE collection_selection SET selected = :selected WHERE playlistId = :playlistId AND collectionKey = :collectionKey")
    suspend fun setAllSelected(playlistId: Long, collectionKey: String, selected: Boolean)

    @Query("DELETE FROM collection_selection WHERE playlistId = :playlistId AND collectionKey = :collectionKey")
    suspend fun deleteGroup(playlistId: Long, collectionKey: String)

    @Query("DELETE FROM collection_selection WHERE playlistId = :playlistId AND epCid = :epCid")
    suspend fun deleteEpisode(playlistId: Long, epCid: Long)
}
