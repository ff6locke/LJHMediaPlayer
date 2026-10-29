package com.ljh.player

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private var playlist = JSONArray()
    private var playlistIndex = -1
    private var currentTitle = "LJHPlayer"
    private var playlistItemLabel = "频道"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            keepScreenOn = true
        }
        message = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA10141D.toInt())
            setPadding(24, 14, 24, 14)
        }
        val root = FrameLayout(this)
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            message,
            FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP)
        )

        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: "LJHPlayer"
        val url = intent.getStringExtra("url")?.trim().orEmpty()
        currentTitle = title
        playlist = runCatching {
            val path = intent.getStringExtra("playlist_file")
            when {
                !path.isNullOrBlank() -> JSONArray(java.io.File(path).readText(Charsets.UTF_8))
                else -> JSONArray(intent.getStringExtra("playlist").orEmpty())
            }
        }.getOrDefault(JSONArray())
        playlistIndex = intent.getIntExtra("playlist_index", -1)
        playlistItemLabel = intent.getStringExtra("playlist_label")?.takeIf { it.isNotBlank() } ?: "频道"
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            message.text = "播放地址无效：\n$url"
            setContentView(root)
            return
        }

        if (playlist.length() > 1) {
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xAA10141D.toInt())
                gravity = android.view.Gravity.CENTER
            }
            controls.addView(Button(this).apply {
                text = "上一$playlistItemLabel"
                setOnClickListener { playPlaylistOffset(-1) }
            })
            controls.addView(Button(this).apply {
                text = "下一$playlistItemLabel"
                setOnClickListener { playPlaylistOffset(1) }
            })
            root.addView(controls, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        }
        setContentView(root)
        initializePlayer(url, title, parseHeaders(intent.getStringExtra("headers").orEmpty()))
    }

    private fun initializePlayer(url: String, title: String, headers: Map<String, String>) {
        currentTitle = title
        playerView.player = null
        player?.release()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 LJHPlayer/0.3")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
        player = exo
        playerView.player = exo
        message.text = "正在缓冲…\n$title"
        message.visibility = View.VISIBLE
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val causes = generateSequence<Throwable>(error) { it.cause }
                    .take(8)
                    .map { cause -> "${cause.javaClass.simpleName}: ${cause.message ?: "(无详细信息)"}" }
                    .distinct()
                    .joinToString("\n")
                val hint = when {
                    causes.contains("UnknownHostException", true) -> "无法解析域名：检查网络或播放链接是否过期。"
                    causes.contains("SSL", true) || causes.contains("CertPath", true) -> "HTTPS 证书校验失败，检查设备时间或服务器证书。"
                    causes.contains("403", true) || causes.contains("401", true) -> "服务器拒绝访问：该链接可能需要 Referer、Cookie 或登录。"
                    causes.contains("404", true) -> "媒体地址不存在或已过期。"
                    causes.contains("Cleartext HTTP traffic", true) -> "设备阻止了 HTTP 明文请求。"
                    causes.contains("ParserException", true) || causes.contains("UnrecognizedInputFormatException", true) -> "返回内容可能不是视频流；请使用直链 MP4、M3U8 或 MPD，而不是网页播放页。"
                    else -> "请确认链接是可直接访问的媒体流，而不是网站播放页面。"
                }
                message.text = "播放失败（${error.errorCodeName}）\n$title\n\n${hint}\n\n错误详情：\n${causes.ifBlank { error.message ?: "没有更多错误信息" }}"
                message.visibility = View.VISIBLE
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        message.text = "正在缓冲…\n$currentTitle"
                        message.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> message.visibility = View.GONE
                    Player.STATE_ENDED -> {
                        if (playlistIndex in 0 until playlist.length() - 1) {
                            message.text = "本集播放结束，正在切换下一集…"
                            message.visibility = View.VISIBLE
                            playerView.postDelayed({ playPlaylistOffset(1) }, 350)
                        } else {
                            message.text = "播放结束\n$currentTitle"
                            message.visibility = View.VISIBLE
                        }
                    }
                }
            }
        })
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        inferMimeType(url)?.let(mediaItemBuilder::setMimeType)
        exo.setMediaItem(mediaItemBuilder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun playPlaylistOffset(offset: Int) {
        if (playlist.length() <= 1 || playlistIndex !in 0 until playlist.length()) return
        val nextIndex = playlistIndex + offset
        if (nextIndex !in 0 until playlist.length()) {
            message.text = if (offset > 0) "已经是最后一集" else "已经是第一集"
            message.visibility = View.VISIBLE
            return
        }
        val item = playlist.optJSONObject(nextIndex) ?: return
        val title = item.optString("title", "频道 ${nextIndex + 1}")
        val url = item.optString("url", "").trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            message.text = "频道地址无效：\n$url"
            message.visibility = View.VISIBLE
            return
        }
        playlistIndex = nextIndex
        val headers = parseHeaders(item.optJSONObject("headers")?.toString().orEmpty())
        initializePlayer(url, title, headers)
    }

    private fun parseHeaders(raw: String): Map<String, String> = runCatching {
        val json = JSONObject(raw)
        val headers = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL && value !is JSONObject) {
                val text = value.toString()
                if (key.isNotBlank() && text.isNotBlank()) headers[key] = text
            }
        }
        headers
    }.getOrDefault(emptyMap())

    private fun inferMimeType(url: String): String? {
        val path = runCatching { android.net.Uri.parse(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#'))
            .lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    override fun onStop() {
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
