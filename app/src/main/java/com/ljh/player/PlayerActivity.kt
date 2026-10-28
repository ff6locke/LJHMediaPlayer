package com.ljh.player

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        playerView = PlayerView(this)
        message = TextView(this).apply { textSize = 18f; setPadding(32, 24, 32, 24) }
        val root = android.widget.FrameLayout(this)
        root.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(message, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        setContentView(root)
        val title = intent.getStringExtra("title") ?: "LJHPlayer"
        val url = intent.getStringExtra("url")?.trim().orEmpty()
        title.let { message.text = it }
        if (!(url.startsWith("https://", true) || url.startsWith("http://", true))) {
            message.text = "播放地址无效或不是 HTTP(S) 地址：\n$url"
            return
        }
        val headers = runCatching {
            val raw = intent.getStringExtra("headers").orEmpty()
            val json = org.json.JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) { val key = keys.next(); map[key] = json.opt(key).toString() }
            map.toMap()
        }.getOrDefault(emptyMap())
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 LJHPlayer")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                message.text = "播放失败：${error.errorCodeName}\n${error.message ?: "媒体格式或网络不受支持"}"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) message.text = title
                if (playbackState == Player.STATE_BUFFERING) message.text = "正在缓冲…\n$title"
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onStop() {
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
