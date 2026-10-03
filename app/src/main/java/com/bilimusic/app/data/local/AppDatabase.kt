package com.bilimusic.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bilimusic.app.data.local.dao.CollectionSelectionDao
import com.bilimusic.app.data.local.dao.DownloadDao
import com.bilimusic.app.data.local.dao.PlaylistDao
import com.bilimusic.app.data.local.dao.SongDao
import com.bilimusic.app.data.local.entity.CollectionSelectionEntity
import com.bilimusic.app.data.local.entity.DownloadRecordEntity
import com.bilimusic.app.data.local.entity.PlaylistEntity
import com.bilimusic.app.data.local.entity.SongEntity

@Database(
    entities = [
        PlaylistEntity::class,
        SongEntity::class,
        CollectionSelectionEntity::class,
        DownloadRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao

    abstract fun songDao(): SongDao

    abstract fun collectionSelectionDao(): CollectionSelectionDao

    abstract fun downloadDao(): DownloadDao

    companion object {
        const val NAME = "bilimusic.db"
    }
}
