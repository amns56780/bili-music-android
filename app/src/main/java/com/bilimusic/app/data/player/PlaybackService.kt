package com.bilimusic.app.data.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bilimusic.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlin.random.Random

/**
 * FR-6 / FR-8 播放服务：MediaSessionService + 唯一的 ExoPlayer 实例。
 *
 * ★ 音频焦点：`setAudioAttributes(audioAttributes, handleAudioFocus = false)`
 *   —— 完全不接管音频焦点，所以抖音/微信在放声音时本 App 的音量不降、不暂停。
 *   **绝对不要**调用 AudioManager.requestAudioFocus()。
 * ★ `setHandleAudioBecomingNoisy(false)`：拔耳机也不暂停（刻意设置）。
 * ★ `setWakeMode(C.WAKE_MODE_NETWORK)`：息屏后仍能持续缓冲，防止后台断流。
 *
 * MediaSession 与音频焦点是两套独立机制，不申请焦点不影响通知栏/锁屏/线控。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerDataSources: PlayerDataSources

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var sleepTimer: SleepTimer

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val dataSourceFactory = playerDataSources.create(okHttpClient)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(BiliLoadErrorHandlingPolicy())

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // 固定 seed 的洗牌顺序：同一次会话内随机顺序稳定，切歌不会重排（任务书 FR-4）。
        // MediaController 不支持 setShuffleOrder，所以只能在这里设置。
        // 注意第一个参数是**长度**不是种子：长度给 0，播放列表变长时 ExoPlayer 会用同一个
        // Random（seed 固定）扩展出顺序，因此本次会话内顺序随机但稳定。
        player.setShuffleOrder(
            ShuffleOrder.DefaultShuffleOrder(0, Random.nextInt().toLong()),
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppPendingIntent())
            .build()

        // 通知栏带上「定时关闭剩余时间」辅助行（FR-8）
        setMediaNotificationProvider(BiliMediaNotificationProvider(this, sleepTimer))

        // FR-5：定时关闭挂在播放器回调上，到点后由它决定何时真正停止
        sleepTimer.attach(
            player = player,
            // 任务书要求：停止 = pause() + 停止前台服务。
            // 注意**不要 stopSelf()**：服务被杀掉会让 UI 的 MediaController 断连，
            // 播放页就会停在「正在播放」的旧状态上（进度条冻住）。暂停 + 让 Media3
            // 收掉前台通知即可，服务留着，UI 能正确显示「已暂停」。
            onStopPlayback = {
                player.pause()
                refreshPlaybackNotification()
            },
            onRefreshNotification = { refreshPlaybackNotification() },
        )
    }

    /** 定时状态变化 / 每秒倒计时时刷新通知栏 */
    fun refreshPlaybackNotification() {
        val session = mediaSession ?: return
        runCatching { onUpdateNotification(session, false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户在最近任务里划掉 App：
     * 正在播放就继续播（后台播放不能被误杀）；没在播就停掉服务，避免通知栏一直挂着。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val playing = player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        sleepTimer.detach()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 点通知回到 App */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
