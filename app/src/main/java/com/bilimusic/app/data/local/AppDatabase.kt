package com.bilimusic.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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

        /**
         * v1 → v2：曲目表新增 `localUri`，用来记录本地歌曲的文件 URI（本地播放器功能）。
         * 只是加一个可空列，老数据不受影响。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song ADD COLUMN localUri TEXT DEFAULT NULL")
            }
        }
    }
}
