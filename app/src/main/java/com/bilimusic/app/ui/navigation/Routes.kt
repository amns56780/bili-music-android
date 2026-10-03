package com.bilimusic.app.ui.navigation

/**
 * 全局路由表。参数用 navArgument 声明，页面之间不传对象，只传 id，避免序列化负担。
 */
object Routes {
    /** 登录页（FR-1），未登录时的起始页 */
    const val LOGIN = "login"

    /** 歌单列表（首页，底部导航第一项） */
    const val PLAYLISTS = "playlists"

    /** 设置页（底部导航第二项） */
    const val SETTINGS = "settings"

    /** 导入页（FR-2：收藏夹 / 稍后再看 / UP主投稿 / 合集） */
    const val IMPORT = "import"

    /** 全屏播放页（FR-4 / FR-7 页面 6） */
    const val PLAYER = "player"

    /** FR-3 选集页：playlistId + collectionKey（season:{id} 或 pages:{bvid}） */
    const val EPISODE_SELECTION = "collection/{playlistId}/{collectionKey}"
    const val ARG_COLLECTION_KEY = "collectionKey"

    fun episodeSelection(playlistId: Long, collectionKey: String): String =
        "collection/$playlistId/${android.net.Uri.encode(collectionKey)}"

    /** 歌单详情 */
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val ARG_PLAYLIST_ID = "playlistId"

    fun playlistDetail(playlistId: Long): String = "playlist/$playlistId"
}
