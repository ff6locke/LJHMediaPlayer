package com.ljh.player

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ljhplayer", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sourceInput: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private val defaultSource = "http://我不是.摸鱼儿.top"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            setBackgroundColor(0xFF10141D.toInt())
            isFocusableInTouchMode = true
        }
        root.addView(TextView(this).apply {
            text = "LJHPlayer"
            textSize = 30f
            setTextColor(0xFF66D9C2.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Android TV · 接口探测与媒体入口"
            textSize = 16f
            setTextColor(0xFFCFD6E4.toInt())
            setPadding(0, 4, 0, 18)
        })
        sourceInput = EditText(this).apply {
            isSingleLine = true
            textSize = 16f
            hint = "输入 TVBox 配置 URL"
            setText(prefs.getString("source", defaultSource))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8B96A9.toInt())
            setSelectAllOnFocus(true)
        }
        root.addView(sourceInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        buttons.addView(actionButton("保存接口") {
            val value = sourceInput.text.toString().trim()
            if (value.isBlank()) showStatus("请输入接口地址")
            else {
                prefs.edit().putString("source", value).apply()
                showStatus("已保存接口：$value")
            }
        })
        buttons.addView(actionButton("检测接口") { probeSource() })
        root.addView(buttons)
        status = TextView(this).apply {
            text = "默认接口尚未验证。点击“检测接口”开始探测。"
            textSize = 15f
            setTextColor(0xFF66D9C2.toInt())
            setPadding(0, 12, 0, 8)
        }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("点播", "直播", "搜索", "接口设置").forEach { label ->
            nav.addView(actionButton(label) {
                when (label) {
                    "接口设置" -> sourceInput.requestFocus()
                    "搜索" -> showStatus("搜索入口已就绪；接入真实 TVBox 配置后实现搜索请求。")
                    "点播" -> showStatus("点播入口已就绪；需要有效的 TVBox JSON 配置与站点规则。")
                    "直播" -> showStatus("直播入口已就绪；需要有效的直播列表或 M3U 地址。")
                }
            })
        }
        root.addView(nav)
        output = TextView(this).apply {
            text = "接口探测结果会显示在这里。\n\n说明：根地址可能是导航网页，不一定是 TVBox JSON。"
            textSize = 15f
            setTextColor(0xFFE4E9F2.toInt())
            setPadding(0, 16, 0, 8)
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.requestFocus()
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { action() }
        }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun probeSource() {
        val source = sourceInput.text.toString().trim()
        if (!(source.startsWith("http://") || source.startsWith("https://"))) {
            showStatus("地址必须以 http:// 或 https:// 开头")
            return
        }
        prefs.edit().putString("source", source).apply()
        showStatus("正在连接接口…")
        output.text = "请求地址：$source\n正在检测 HTTP 状态、Content-Type 和 JSON 结构…"
        executor.execute {
            val result = runCatching {
                val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LJHPlayer/0.1 AndroidTV")
                }
                try {
                    val code = connection.responseCode
                    val contentType = connection.contentType ?: "(未提供)"
                    val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                    val body = stream?.use { it.readBytes().take(64 * 1024).toByteArray().toString(Charset.forName("UTF-8")) } ?: ""
                    val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    val json = runCatching { JSONObject(trimmed) }.getOrNull()
                    val fields = json?.keys()?.asSequence()?.take(40)?.toList().orEmpty()
                    val looksLikeTvbox = fields.any { it in setOf("sites", "lives", "parses", "rules", "spider", "urls", "doh") }
                    buildString {
                        appendLine("请求地址：$source")
                        appendLine("HTTP 状态：$code")
                        appendLine("Content-Type：$contentType")
                        appendLine("响应长度（截取上限 64 KiB）：${body.toByteArray().size} bytes")
                        appendLine("响应类型：")
                        appendLine(when {
                            json != null && looksLikeTvbox -> "疑似 TVBox 配置 JSON（发现常见字段）"
                            json != null -> "有效 JSON，但尚未识别到常见 TVBox 顶层字段"
                            trimmed.startsWith("<!DOCTYPE", true) || trimmed.startsWith("<html", true) -> "HTML 网页，不是直接可确认的 TVBox JSON"
                            trimmed.isBlank() -> "空响应"
                            else -> "非 JSON 或未知格式"
                        })
                        if (fields.isNotEmpty()) appendLine("JSON 顶层字段：${fields.joinToString()}")
                        appendLine()
                        appendLine("注意：连通性检测不能证明点播、直播、搜索或视频播放一定可用。")
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { e ->
                "接口检测失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}\n\n请检查网络、域名、证书和接口地址。"
            }
            runOnUiThread {
                output.text = result
                showStatus(if (result.contains("HTTP 状态：200")) "检测完成：HTTP 200（仍需确认配置结构）" else "检测结束，请查看详细结果")
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
