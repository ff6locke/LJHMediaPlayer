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
import org.json.JSONArray
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
    private lateinit var itemList: LinearLayout
    private val defaultSource = "http://www.饭太硬.cc/tv"

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
            text = "Android TV · TVBox 配置读取测试"
            textSize = 16f
            setTextColor(0xFFCFD6E4.toInt())
            setPadding(0, 4, 0, 12)
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
                showStatus("已保存接口")
            }
        })
        buttons.addView(actionButton("检测接口") { probeSource() })
        buttons.addView(actionButton("加载点播站点") { loadConfig() })
        root.addView(buttons)
        status = TextView(this).apply {
            text = "默认源已填写。点击“加载点播站点”读取配置。"
            textSize = 15f
            setTextColor(0xFF66D9C2.toInt())
            setPadding(0, 10, 0, 8)
        }
        root.addView(status)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("点播", "直播", "搜索", "接口设置").forEach { label ->
            nav.addView(actionButton(label) {
                when (label) {
                    "接口设置" -> sourceInput.requestFocus()
                    "搜索" -> showStatus("请先加载配置；跨站搜索将在确认各站点 API 格式后接入。")
                    "点播" -> loadConfig()
                    "直播" -> showStatus("直播列表解析尚未接入；请先验证配置中的 lives 字段或 M3U 地址。")
                }
            })
        }
        root.addView(nav)
        output = TextView(this).apply {
            text = "这里会显示配置检测结果和站点信息。"
            textSize = 15f
            setTextColor(0xFFE4E9F2.toInt())
            setPadding(0, 12, 0, 8)
        }
        itemList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(output)
            addView(itemList)
        }
        val scroll = ScrollView(this)
        scroll.addView(inner)
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

    private fun currentSource(): String = sourceInput.text.toString().trim()

    private fun openConnection(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LJHPlayer/0.2 AndroidTV")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }

    private fun getText(address: String): Pair<Int, String> {
        val connection = openConnection(address)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val bytes = it.readBytes()
                String(bytes.take(1024 * 1024).toByteArray(), Charset.forName("UTF-8"))
            } ?: ""
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun loadConfig() {
        val source = currentSource()
        if (!(source.startsWith("http://") || source.startsWith("https://"))) {
            showStatus("地址必须以 http:// 或 https:// 开头")
            return
        }
        prefs.edit().putString("source", source).apply()
        showStatus("正在读取 TVBox 配置…")
        output.text = "请求地址：$source\n正在请求并解析 JSON…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val (code, body) = getText(source)
                if (code !in 200..399) error("HTTP 状态码：$code")
                val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                val json = JSONObject(trimmed)
                val sites = json.optJSONArray("sites")
                    ?: error("响应是 JSON，但没有 sites 数组；这可能不是 TVBox 点播配置。顶层字段：${json.keys().asSequence().toList().joinToString()}")
                val siteData = (0 until sites.length()).mapNotNull { index ->
                    sites.optJSONObject(index)?.let { site ->
                        SiteInfo(
                            name = site.optString("name", "未命名站点"),
                            api = site.optString("api", ""),
                            type = site.optInt("type", 0),
                            searchable = site.optInt("searchable", 0) == 1,
                            quickSearch = site.optInt("quickSearch", 0) == 1
                        )
                    }
                }
                ConfigResult(code, json.keys().asSequence().toList(), siteData)
            }.fold(
                onSuccess = { it },
                onFailure = { ConfigResult(-1, emptyList(), emptyList(), it.message ?: it.javaClass.simpleName) }
            )
            runOnUiThread {
                if (result.error != null) {
                    output.text = "读取失败：${result.error}\n\n请确认这个地址直接返回 TVBox JSON，而不是导航网页。"
                    showStatus("配置读取失败")
                } else {
                    output.text = "HTTP：${result.httpCode}\n顶层字段：${result.fields.joinToString()}\n识别到点播站点：${result.sites.size}\n\n选择站点可查看其 API 信息。"
                    result.sites.forEach { site ->
                        itemList.addView(actionButton("${site.name}  ·  ${if (site.searchable) "支持搜索" else "搜索未标记"}") {
                            output.text = "站点：${site.name}\nAPI：${site.api}\n类型：${site.type}\n可搜索：${site.searchable}\n快速搜索：${site.quickSearch}\n\n说明：已成功读取站点配置。不同站点 API 格式不同，影片分类、列表与播放解析还需按该站点接口适配。"
                            showStatus("已选择站点：${site.name}")
                        })
                    }
                    showStatus(if (result.sites.isEmpty()) "配置已读取，但 sites 为空" else "配置读取成功：${result.sites.size} 个站点")
                }
            }
        }
    }

    private data class SiteInfo(
        val name: String,
        val api: String,
        val type: Int,
        val searchable: Boolean,
        val quickSearch: Boolean
    )

    private data class ConfigResult(
        val httpCode: Int,
        val fields: List<String>,
        val sites: List<SiteInfo>,
        val error: String? = null
    )

    private fun probeSource() {
        val source = currentSource()
        if (!(source.startsWith("http://") || source.startsWith("https://"))) {
            showStatus("地址必须以 http:// 或 https:// 开头")
            return
        }
        showStatus("正在检测接口…")
        output.text = "请求地址：$source\n正在检测 HTTP 状态、Content-Type 和 JSON 结构…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val connection = openConnection(source)
                try {
                    val code = connection.responseCode
                    val contentType = connection.contentType ?: "(未提供)"
                    val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                    val body = stream?.use {
                        val bytes = it.readBytes()
                        String(bytes.take(64 * 1024).toByteArray(), Charset.forName("UTF-8"))
                    } ?: ""
                    val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    val json = runCatching { JSONObject(trimmed) }.getOrNull()
                    val fields = json?.keys()?.asSequence()?.take(40)?.toList().orEmpty()
                    buildString {
                        appendLine("请求地址：$source")
                        appendLine("HTTP 状态：$code")
                        appendLine("Content-Type：$contentType")
                        appendLine("响应长度（最多读取 64 KiB）：${body.toByteArray().size} bytes")
                        appendLine("响应类型：")
                        appendLine(when {
                            json != null && json.has("sites") -> "TVBox 风格 JSON（发现 sites 字段）"
                            json != null -> "有效 JSON，但未发现 sites 字段"
                            trimmed.startsWith("<!DOCTYPE", true) || trimmed.startsWith("<html", true) -> "HTML 网页，不是直接可识别的 TVBox JSON"
                            trimmed.isBlank() -> "空响应"
                            else -> "非 JSON 或未知格式"
                        })
                        if (fields.isNotEmpty()) appendLine("JSON 顶层字段：${fields.joinToString()}")
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { "接口检测失败：${it.javaClass.simpleName}: ${it.message ?: "未知错误"}" }
            runOnUiThread {
                output.text = result
                showStatus("检测结束，请查看详细结果")
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
