package com.bilimusic.app.data.local

import androidx.room.TypeConverter
import com.bilimusic.app.domain.model.PlaylistSourceType

/**
 * Room 类型转换：枚举以 name 存字符串，读取时做兜底（未知值归到 MANUAL，避免升级崩库）。
 */
class Converters {

    @TypeConverter
    fun fromSourceType(value: PlaylistSourceType): String = value.name

    @TypeConverter
    fun toSourceType(value: String?): PlaylistSourceType =
        value?.let { name -> PlaylistSourceType.entries.firstOrNull { it.name == name } }
            ?: PlaylistSourceType.MANUAL
}
