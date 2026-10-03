package com.bilimusic.app.data.player

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * FR-8：通知栏里加一行「定时关闭剩余时间」（只有设了定时才显示）。
 *
 * Media3 默认的 MediaStyle 通知不带自定义辅助行，所以这里包一层默认实现，
 * 拿到通知后用平台的 Notification.Builder.recoverBuilder 补一个 subText。
 * 任何一步失败都直接返回原通知，保证不会因为加这一行而丢通知。
 */
class BiliMediaNotificationProvider(
    private val context: Context,
    private val sleepTimer: SleepTimer,
) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(context)

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val base = delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            onNotificationChangedCallback,
        )
        val label = sleepTimer.notificationLabel() ?: return base
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(context, base.notification)
            builder.setSubText(label)
            MediaNotification(base.notificationId, builder.build())
        }.getOrDefault(base)
    }

    /** 我们没有自定义命令，交给默认实现 */
    override fun handleCustomCommand(
        mediaSession: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(mediaSession, action, extras)
}
