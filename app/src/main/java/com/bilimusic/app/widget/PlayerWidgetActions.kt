package com.bilimusic.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.bilimusic.app.data.player.PlaybackConnection
import dagger.hilt.android.EntryPointAccessors

/**
 * FR-11 小组件按钮：三个回调都通过 MediaController（PlaybackConnection）真的控制播放。
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val connection = connectionOf(context)
        connection.connect()
        connection.togglePlayPause()
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val connection = connectionOf(context)
        connection.connect()
        connection.previous()
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val connection = connectionOf(context)
        connection.connect()
        connection.next()
    }
}

private fun connectionOf(context: Context): PlaybackConnection =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        PlayerWidgetEntryPoint::class.java,
    ).playbackConnection()
