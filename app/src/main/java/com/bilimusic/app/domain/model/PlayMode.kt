package com.bilimusic.app.domain.model

/**
 * FR-4 四种播放模式。
 * 映射关系（任务书 7.3）：
 * 顺序 = repeatMode OFF + shuffle off；列表循环 = REPEAT_MODE_ALL；
 * 单曲循环 = REPEAT_MODE_ONE；随机 = shuffleModeEnabled = true（固定 seed）。
 */
enum class PlayMode(val displayName: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ALL("列表循环"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放"),
    ;

    companion object {
        fun fromNameOrSequential(name: String?): PlayMode =
            entries.firstOrNull { it.name == name } ?: SEQUENTIAL
    }
}
