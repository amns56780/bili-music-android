package com.bilimusic.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Coil 复用同一个 OkHttpClient：封面图（i0.hdslb.com 等）同样需要
 * Referer / User-Agent，否则部分图片会 403。
 */
@HiltAndroidApp
class BiliMusicApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { okHttpClient }
        .crossfade(true)
        .build()
}
