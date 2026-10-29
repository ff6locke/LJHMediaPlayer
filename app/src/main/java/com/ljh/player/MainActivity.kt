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
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ljhplayer", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sourceInput: EditText
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var itemList: LinearLayout
    private val defaultSource = "https://raw.giteeusercontent.com/gaoops404/tvbox-config/raw/main/tvbox.json"
    private var currentSpiderUrl: String = ""

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
            setText(prefs.getString("source", null).let { saved -> if (saved.isNullOrBlank() || saved == "http://www.饭太硬.cc/tv" || saved == "http://fty.xxooo.cf/tv") defaultSource else saved })
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
        buttons.addView(actionButton("下载/检查 Spider") { downloadSpiderJar() })
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
                    "搜索" -> { showStatus("正在加载可搜索站点…"); loadConfig() }
                    "点播" -> loadConfig()
                    "直播" -> loadLiveChannels()
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
                val connection = openConnection(source)
                val code: Int
                val contentType: String
                val body: String
                try {
                    code = connection.responseCode
                    contentType = connection.contentType ?: "(未提供)"
                    val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                    body = stream?.use {
                        String(it.readBytes().take(1024 * 1024).toByteArray(), Charset.forName("UTF-8"))
                    } ?: ""
                } finally {
                    connection.disconnect()
                }
                if (code !in 200..399) error("HTTP 状态码：" + code + "；Content-Type：" + contentType)
                    val trimmed = body.trim().removePrefix("\uFEFF")
                if (trimmed.startsWith("<!DOCTYPE", true) || trimmed.startsWith("<html", true)) {
                    error("服务器返回 HTML 网页，不是 JSON 配置。Content-Type：" + contentType)
                }
                if (trimmed.startsWith("ÿØ") || contentType.contains("image/", true)) {
                    error("服务器返回图片而不是 JSON 配置。Content-Type：" + contentType)
                }
                val fields: List<String>
                val sites: JSONArray
                val configSummary: String
                var spiderRef = ""
                when {
                    trimmed.startsWith("{") -> {
                        val json = JSONObject(trimmed)
                        fields = json.keys().asSequence().toList()
                        sites = json.optJSONArray("sites")
                            ?: json.optJSONArray("data")
                            ?: error("JSON 对象中没有 sites/data 数组。顶层字段：" + fields.joinToString())
                        spiderRef = json.opt("spider").safeString("")
                        val configKeys = listOf("spider", "wallpaper", "lives", "parses", "flags", "ads", "ijk", "doh")
                        configSummary = configKeys.mapNotNull { key ->
                            if (!json.has(key) || json.isNull(key)) null
                            else {
                                val value = json.opt(key)
                                key + "：" + when (value) {
                                    is JSONArray -> "数组 " + value.length() + " 项"
                                    is JSONObject -> "对象"
                                    else -> value.toString().take(120)
                                }
                            }
                        }.joinToString("\n").ifBlank { "未发现 spider/wallpaper/lives 等附加字段" }
                    }
                    trimmed.startsWith("[") -> {
                        sites = JSONArray(trimmed)
                        fields = listOf("(顶层 JSON 数组)")
                        configSummary = "顶层直接返回站点数组"
                    }
                    else -> error("响应不是 JSON 对象或数组。Content-Type：" + contentType + "；响应开头：" + trimmed.take(180))
                }
                val siteData = (0 until sites.length()).mapNotNull { index ->
                    val site = sites.optJSONObject(index) ?: return@mapNotNull null
                    SiteInfo(
                        name = site.opt("name").safeString("未命名站点"),
                        api = site.opt("api").safeString(""),
                        type = site.opt("type").safeInt(0),
                        jar = site.opt("jar").safeString(""),
                        ext = when (val extValue = site.opt("ext")) {
                            null, JSONObject.NULL -> ""
                            is JSONObject, is JSONArray -> extValue.toString()
                            else -> extValue.toString().take(300)
                        },
                        searchable = site.opt("searchable").safeInt(0) == 1,
                        quickSearch = site.opt("quickSearch").safeInt(0) == 1
                    )
                }
                if (siteData.isEmpty()) error("JSON 已读取，但 sites/data 数组中没有可识别的站点对象。")
                ConfigResult(code, fields, siteData, configSummary = configSummary, spiderUrl = resolveReference(source, spiderRef))
            }.fold(
                onSuccess = { it },
                onFailure = { ConfigResult(-1, emptyList(), emptyList(), it.message ?: it.javaClass.simpleName) }
            )
            runOnUiThread {
                if (result.error != null) {
                    output.text = "读取失败：${result.error}\n\n请确认这个地址直接返回 TVBox JSON，而不是导航网页。"
                    showStatus("配置读取失败")
                } else {
                    currentSpiderUrl = result.spiderUrl
                    output.text = "HTTP：${result.httpCode}\n顶层字段：${result.fields.joinToString()}\n识别到点播站点：${result.sites.size}\n\n附加配置：\n${result.configSummary}" + if (result.spiderUrl.isNotBlank()) "\n\nSpider 解析地址：\n${result.spiderUrl}\n\n注意：当前版本尚未执行 Spider JAR；这里只解析出地址，下一步需要检查 JAR 的兼容接口和依赖。" else "\n\n未配置 spider 地址。" + "\n\n选择站点可查看其 API 信息。"
                    result.sites.forEach { site ->
                        itemList.addView(actionButton("${site.name}  ·  type=${site.type}  ·  ${if (site.searchable) "支持搜索" else "搜索未标记"}") {
                            inspectSiteApi(site)
                        })
                    }
                    showStatus(if (result.sites.isEmpty()) "配置已读取，但 sites 为空" else "配置读取成功：${result.sites.size} 个站点")
                }
            }
        }
    }



    private fun loadLiveChannels() {
        val source = currentSource()
        if (!source.startsWith("http://", true) && !source.startsWith("https://", true)) {
            showStatus("请先设置有效的 TVBox JSON 地址")
            return
        }
        showStatus("正在读取直播配置…")
        output.text = "正在读取配置中的 lives 字段和直播源…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val (code, body) = getText(source)
                if (code !in 200..399) error("配置请求 HTTP $code")
                val root = JSONObject(body.trim().removePrefix("\uFEFF"))
                val lives = root.optJSONArray("lives") ?: JSONArray()
                if (lives.length() == 0) error("配置中没有 lives 数组；请换用包含直播配置的 TVBox JSON")
                (0 until lives.length()).mapNotNull { index ->
                    val item = lives.optJSONObject(index)
                    when {
                        item != null -> LiveSource(
                            name = item.optString("name", "直播源 ${index + 1}"),
                            url = resolveReference(source, item.optString("url", "")),
                            inlineChannels = item.optJSONArray("channels")
                        )
                        lives.opt(index) is String -> LiveSource(
                            name = "直播源 ${index + 1}",
                            url = resolveReference(source, lives.optString(index)),
                            inlineChannels = null
                        )
                        else -> null
                    }
                }
            }
            runOnUiThread {
                val sources = result.getOrNull()
                if (sources == null) {
                    output.text = "直播配置读取失败：${result.exceptionOrNull()?.message ?: "未知错误"}\n\n支持 TVBox 配置中的 lives 数组和远程 M3U 地址。"
                    showStatus("未能读取直播源")
                    return@runOnUiThread
                }
                output.text = "找到 ${sources.size} 个直播源。选择一个源加载频道。"
                sources.forEach { live ->
                    itemList.addView(actionButton(live.name) { openLiveSource(live) })
                }
                showStatus("直播源读取完成")
            }
        }
    }

    private fun openLiveSource(source: LiveSource) {
        showStatus("正在加载直播源：${source.name}")
        output.text = "直播源：${source.name}\n${source.url.ifBlank { "内嵌频道" }}"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val channels = mutableListOf<LiveChannel>()
                val inline = source.inlineChannels
                if (inline != null) {
                    for (i in 0 until inline.length()) {
                        val item = inline.optJSONObject(i) ?: continue
                        val name = item.optString("name", item.optString("title", "频道 ${i + 1}"))
                        val urlValue = item.opt("url") ?: item.opt("urls")
                        val url = when (urlValue) {
                            is JSONArray -> urlValue.optString(0, "")
                            else -> urlValue?.toString().orEmpty()
                        }.substringBefore("|||").trim()
                        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                            channels.add(LiveChannel(name, url, item.optString("group", item.optString("group-title", "直播"))))
                        }
                    }
                }
                if (channels.isEmpty() && source.url.isNotBlank()) {
                    val (code, body) = getText(source.url)
                    if (code !in 200..399) error("直播源 HTTP $code")
                    val trimmed = body.trim().removePrefix("\uFEFF")
                    if (trimmed.startsWith("#EXTM3U", true) || trimmed.contains("#EXTINF", true)) {
                        var pendingName = ""
                        var pendingGroup = "直播"
                        var pendingReferer = ""
                        var pendingUserAgent = ""
                        body.lineSequence().forEach { rawLine ->
                            val line = rawLine.trim()
                            when {
                                line.startsWith("#EXTINF", true) -> {
                                    pendingName = line.substringAfter(',', "").trim().ifBlank { "未命名频道" }
                                    pendingGroup = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.ifBlank { "直播" } ?: "直播"
                                }
                                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> pendingReferer = line.substringAfter('=').trim()
                                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> pendingUserAgent = line.substringAfter('=').trim()
                                line.startsWith("#") || line.isBlank() -> Unit
                                !line.startsWith("#") -> {
                                    val resolvedUrl = resolveReference(source.url, line)
                                    if (resolvedUrl.startsWith("http://", true) || resolvedUrl.startsWith("https://", true)) {
                                        channels.add(LiveChannel(pendingName.ifBlank { line.substringAfterLast('/').substringBefore('?') }, resolvedUrl, pendingGroup, pendingReferer, pendingUserAgent))
                                    }
                                    pendingName = ""
                                    pendingReferer = ""
                                    pendingUserAgent = ""
                                }
                            }
                        }
                    } else {
                        val jsonObject = runCatching { JSONObject(trimmed) }.getOrNull()
                        val array = jsonObject?.optJSONArray("channels")
                            ?: jsonObject?.optJSONArray("data")
                            ?: jsonObject?.optJSONArray("lives")
                            ?: runCatching { JSONArray(trimmed) }.getOrNull()
                            ?: error("直播源不是 M3U，且 JSON 中没有 channels/data/lives 数组")
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val name = item.optString("name", item.optString("title", item.optString("tvg-name", "频道 ${i + 1}")))
                            val rawUrl = item.opt("url") ?: item.opt("play") ?: item.opt("urls")
                            val url = when (rawUrl) {
                                is JSONArray -> rawUrl.optString(0, "")
                                else -> rawUrl?.toString().orEmpty()
                            }.substringBefore("|||").trim()
                            val resolvedUrl = resolveReference(source.url, url)
                            if (resolvedUrl.startsWith("http://", true) || resolvedUrl.startsWith("https://", true)) {
                                channels.add(LiveChannel(
                                    name,
                                    resolvedUrl,
                                    item.optString("group", item.optString("group-title", item.optString("groupTitle", "直播"))),
                                    item.optString("referer", item.optString("http-referrer", "")),
                                    item.optString("user-agent", item.optString("userAgent", ""))
                                ))
                            }
                        }
                    }
                }
                if (channels.isEmpty()) error("直播源已读取，但没有找到可播放的 HTTP(S) 频道")
                channels
            }
            runOnUiThread {
                val channels = result.getOrNull()
                if (channels == null) {
                    output.text = "直播源加载失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    showStatus("直播频道加载失败")
                    return@runOnUiThread
                }
                val groups = channels.groupBy { it.group.ifBlank { "直播" } }
                val playlistJson = JSONArray()
                channels.forEach { entry ->
                    val headers = JSONObject()
                    if (entry.referer.isNotBlank()) headers.put("Referer", entry.referer)
                    if (entry.userAgent.isNotBlank()) headers.put("User-Agent", entry.userAgent)
                    playlistJson.put(JSONObject()
                        .put("title", entry.name)
                        .put("url", entry.url)
                        .put("headers", headers))
                }
                val playlistFile = java.io.File(cacheDir, "live-playlist.json")
                playlistFile.writeText(playlistJson.toString(), Charsets.UTF_8)
                output.text = "直播源：${source.name}\n频道：${channels.size} 个\n分组：${groups.size} 个"
                itemList.addView(actionButton("返回直播源列表") { loadLiveChannels() })
                groups.forEach { (group, entries) ->
                    itemList.addView(TextView(this).apply {
                        text = "$group（${entries.size}）"
                        textSize = 18f
                        setTextColor(0xFF66D9C2.toInt())
                        setPadding(4, 12, 4, 4)
                    })
                    entries.forEach { channel ->
                        itemList.addView(actionButton(channel.name) {
                            startActivity(android.content.Intent(this@MainActivity, PlayerActivity::class.java)
                                .putExtra("title", channel.name)
                                .putExtra("url", channel.url)
                                .putExtra("headers", playlistJson.optJSONObject(channels.indexOf(channel))?.optJSONObject("headers")?.toString() ?: "{}")
                                .putExtra("playlist_file", playlistFile.absolutePath)
                                .putExtra("playlist_index", channels.indexOf(channel))
                                .putExtra("playlist_label", "频道"))
                        })
                    }
                }
                showStatus("直播频道已加载")
            }
        }
    }

    private fun downloadSpiderJar(urlOverride: String? = null, fileName: String = "spider.jar", expectedClass: String? = null) {
        val spiderUrl = urlOverride?.takeIf { it.isNotBlank() } ?: currentSpiderUrl
        if (spiderUrl.isBlank()) {
            showStatus("请先加载配置，并确认配置包含 spider 地址")
            output.text = "尚未取得 Spider 下载地址。先点击“加载点播站点”，检查配置摘要中的 spider 字段。"
            return
        }
        if (!spiderUrl.startsWith("http://", true) && !spiderUrl.startsWith("https://", true)) {
            showStatus("Spider 地址不是 HTTP(S) URL")
            output.text = "无法下载 Spider：$spiderUrl"
            return
        }
        showStatus("正在下载 Spider JAR…")
        output.text = "Spider 地址：$spiderUrl\n正在下载并检查文件结构…"
        executor.execute {
            val result = runCatching {
                val directory = java.io.File(filesDir, "spider").apply { mkdirs() }
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "spider.jar" }
                val target = java.io.File(directory, safeName)
                val temp = java.io.File(directory, safeName + ".tmp")
                val connection = openConnection(spiderUrl)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val declared = connection.contentLengthLong
                    if (declared > 80L * 1024 * 1024) error("文件过大：$declared bytes（上限 80 MiB）")
                    connection.inputStream.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 80L * 1024 * 1024) error("下载文件超过 80 MiB 上限")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (temp.length() < 4) error("下载内容过短，不像有效 JAR")
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = temp.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val entries = ZipFile(temp).use { zip ->
                    zip.entries().asSequence().map { it.name }.toList()
                }
                if (entries.isEmpty()) error("ZIP/JAR 中没有条目，文件可能不是有效 JAR")
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                val dex = entries.filter { it.matches(Regex("classes(\\d*)\\.dex")) }
                val classes = entries.count { it.endsWith(".class", true) }
                val nativeLibs = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }
                buildString {
                    appendLine("Spider JAR 下载成功")
                    appendLine("来源：$spiderUrl")
                    appendLine("缓存文件名：$safeName")
                    appendLine("本地文件：${target.absolutePath}")
                    appendLine("大小：${target.length()} bytes")
                    appendLine("SHA-256：$hash")
                    appendLine("JAR 条目数：${entries.size}")
                    appendLine("DEX 文件：${if (dex.isEmpty()) "未发现" else dex.joinToString()}")
                    appendLine("普通 class 文件数：$classes")
                    if (!expectedClass.isNullOrBlank()) {
                        val classEntry = expectedClass.replace('.', '/') + ".class"
                        val dexOnly = dex.isNotEmpty() && classes == 0
                        val classPresent = entries.any { it == classEntry }
                        appendLine("目标 Spider 类：$expectedClass")
                        appendLine("JAR 中找到目标 .class：${if (classPresent) "是" else if (dexOnly) "无法从 DEX 条目直接确认" else "否"}")
                        if (!classPresent && !dexOnly) appendLine("提示：目标类不在 JAR 的普通 class 条目中，可能类名不匹配或 JAR 使用了不同打包方式。")
                    }
                    appendLine("原生 so 文件数：${nativeLibs.size}")
                    appendLine()
                    appendLine("说明：下载与 ZIP 结构检查已完成，但这不代表 Spider 可执行。下一步仍需核对该 JAR 的 Spider 基类 ABI、依赖库和初始化方式。")
                    if (dex.isEmpty() && classes == 0) appendLine("警告：未发现 classes.dex 或 .class，文件可能不是可执行 Spider JAR。")
                    if (nativeLibs.isNotEmpty()) appendLine("注意：JAR 含原生库，运行时还需验证设备 ABI 与加载路径。")
                }
            }.getOrElse { "Spider 下载/检查失败：${it.javaClass.simpleName}: ${it.message ?: "未知错误"}" }
            runOnUiThread {
                output.text = result
                showStatus(if (result.startsWith("Spider JAR 下载成功")) "Spider 已缓存；尚未执行" else "Spider 下载或检查失败")
            }
        }
    }

    private fun inspectSiteApi(site: SiteInfo) {
        if (site.api.isBlank()) {
            output.text = "站点：${site.name}\n站点类型：${site.type}\n这个站点没有提供 api 地址，可能依赖 Spider、扩展配置或其他特殊引擎。\n\n当前版本只直接请求标准 CMS JSON API，暂不支持执行 Spider。"
            showStatus("该站点没有可直接请求的 API")
            return
        }

        val rawApi = site.api.trim()

        // Values such as csp_Douban/csp_douban are Spider class identifiers, not hostnames.
        // Explain the missing runtime instead of attempting an HTTP request to that fake host.
        if (rawApi.startsWith("csp_", ignoreCase = true)) {
            val siteJarUrl = if (site.jar.isNotBlank()) resolveReference(currentSource(), site.jar) else currentSpiderUrl
            output.text = "站点：${site.name}\n站点类型：${site.type}\nSpider 类名：$rawApi\n站点 jar：${site.jar.ifBlank { "(未单独指定，使用顶层 spider)" }}\n解析后的 JAR 地址：${siteJarUrl.ifBlank { "(未配置)" }}\n扩展 ext：${site.ext.ifBlank { "(未提供)" }}\n\nSpider 插件是第三方代码，只运行你信任的配置和 JAR。" 
            itemList.removeAllViews()
            if (siteJarUrl.isNotBlank()) {
                itemList.addView(actionButton("运行此 Spider 站点") { runSpiderSite(site, siteJarUrl, rawApi.removePrefix("csp_")) })
                val safeName = site.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "site" } + ".jar"
                itemList.addView(actionButton("仅下载/检查 JAR") { downloadSpiderJar(siteJarUrl, safeName, rawApi.removePrefix("csp_")) })
            }
            showStatus("可尝试运行 Spider：$rawApi")
            return
        }

        // TVBox site types are not interchangeable. Do not treat XPath/Spider sites as CMS APIs.
        if (site.type != 0) {
            val typeDescription = when (site.type) {
                1 -> "XPath 网页解析站点"
                3 -> "Spider/爬虫引擎站点"
                else -> "非标准 CMS JSON 站点"
            }
            val siteJarUrl = if (site.jar.isNotBlank()) resolveReference(currentSource(), site.jar) else currentSpiderUrl
            output.text = "站点：${site.name}\n站点类型：${site.type}（$typeDescription）\nAPI：${site.api}\n站点 jar：${site.jar.ifBlank { "(未单独指定)" }}\n解析后的 JAR 地址：${siteJarUrl.ifBlank { "(未配置)" }}\n扩展 ext：${site.ext.ifBlank { "(未提供)" }}\n\n当前版本的点播加载器只支持 type=0 的标准 JSON API，因此不会错误地向此地址追加 ?ac=list。\n\n下一步需要为该类型接入对应解析器；Spider 站点还需兼容其运行接口。"
            showStatus("暂不支持此站点类型：${site.type}")
            return
        }

        val base = when {
            rawApi.startsWith("https://", ignoreCase = true) || rawApi.startsWith("http://", ignoreCase = true) -> rawApi
            rawApi.startsWith("//") -> "https:$rawApi"
            rawApi.startsWith("www.", ignoreCase = true) -> "https://$rawApi"
            rawApi.startsWith("/") || rawApi.startsWith("./") || rawApi.startsWith("../") ||
                (rawApi.contains("/") && !rawApi.substringBefore('/').contains(".")) -> resolveReference(currentSource(), rawApi)
            rawApi.contains(".") && !rawApi.contains(" ") && !rawApi.startsWith("csp_", ignoreCase = true) -> "https://$rawApi"
            else -> {
                output.text = "站点：${site.name}\n站点类型：${site.type}\nAPI：$rawApi\n\nAPI 值看起来不是完整网址。它可能是 Spider 标识符或特殊协议，不能自动补成 HTTPS。"
                showStatus("API 地址格式无法识别")
                return
            }
        }
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            output.text = "站点：${site.name}\n无法将 API 地址解析为 HTTP(S)：$rawApi"
            showStatus("API 地址格式无法识别")
            return
        }
        loadVodPage(site, base, null, 1)
    }

    private fun loadVodPage(site: SiteInfo, base: String, category: VodCategory?, page: Int) {
        val params = linkedMapOf("ac" to "list", "pg" to page.toString())
        if (category != null) params["t"] = category.id
        val requestUrl = appendApiParams(base, params)
        showStatus(if (category == null) "正在加载分类和影片…" else "正在加载“${category.name}”影片…")
        output.text = "站点：${site.name}\n请求：$requestUrl\n正在读取影片数据…"
        itemList.removeAllViews()
        executor.execute {
            val parsed = runCatching {
                val connection = openConnection(requestUrl)
                try {
                    val code = connection.responseCode
                    val contentType = connection.contentType ?: "(未提供)"
                    val body = (if (code in 200..399) connection.inputStream else connection.errorStream)
                        ?.use { stream -> String(stream.readBytes().take(1024 * 1024).toByteArray(), Charset.forName("UTF-8")) } ?: ""
                    if (code !in 200..399) error("HTTP $code；Content-Type：$contentType")
                    val trimmed = body.trim().removePrefix("\uFEFF")
                    val json = runCatching { JSONObject(trimmed) }.getOrNull()
                        ?: error("接口没有返回 JSON 对象。Content-Type：$contentType；响应开头：${trimmed.take(160)}")
                    val data = json.optJSONObject("data") ?: json
                    val classArray = data.optJSONArray("class") ?: json.optJSONArray("class") ?: JSONArray()
                    val listArray = data.optJSONArray("list") ?: json.optJSONArray("list") ?: JSONArray()
                    val categories = (0 until classArray.length()).mapNotNull { i ->
                        val item = classArray.optJSONObject(i) ?: return@mapNotNull null
                        val id = item.opt("type_id").safeString("")
                        val name = item.opt("type_name").safeString("")
                        if (id.isBlank() || name.isBlank()) null else VodCategory(id, name)
                    }
                    val movies = (0 until listArray.length()).mapNotNull { i ->
                        val item = listArray.optJSONObject(i) ?: return@mapNotNull null
                        val id = item.opt("vod_id").safeString("")
                        val name = item.opt("vod_name").safeString("未命名影片")
                        if (id.isBlank()) null else VodMovie(
                            id = id,
                            name = name,
                            remarks = item.opt("vod_remarks").safeString(""),
                            year = item.opt("vod_year").safeString(""),
                            pic = item.opt("vod_pic").safeString("")
                        )
                    }
                    val pageCount = data.opt("pagecount").safeInt(json.opt("pagecount").safeInt(1)).coerceAtLeast(1)
                    VodPageResult(categories, movies, json.optString("msg", ""), pageCount = pageCount)
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { VodPageResult(emptyList(), emptyList(), it.message ?: "未知错误", error = true) }
            runOnUiThread {
                if (parsed.error) {
                    output.text = "站点：${site.name}\n加载失败：${parsed.message}\n\n该站点可能不是标准苹果 CMS JSON API，或需要 Spider/专用适配。"
                    showStatus("影片加载失败")
                    return@runOnUiThread
                }
                output.text = "站点：${site.name}\n分类：${parsed.categories.size} 个\n影片：${parsed.movies.size} 部\n当前页：$page / ${parsed.pageCount}\n可输入关键词搜索，或选择分类筛选影片。"
                val keywordInput = EditText(this).apply {
                    hint = "搜索影片关键词"
                    isSingleLine = true
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    setHintTextColor(0xFF8B96A9.toInt())
                }
                itemList.addView(keywordInput)
                itemList.addView(actionButton("搜索") {
                    val keyword = keywordInput.text.toString().trim()
                    if (keyword.isBlank()) showStatus("请输入搜索关键词") else loadVodSearch(site, base, keyword)
                })
                if (category != null) {
                    itemList.addView(actionButton("返回全部影片") { loadVodPage(site, base, null, 1) })
                }
                parsed.categories.forEach { cat ->
                    itemList.addView(actionButton("分类：${cat.name}") {
                        loadVodPage(site, base, cat, 1)
                    })
                }
                parsed.movies.forEach { movie ->
                    itemList.addView(actionButton("${movie.name}${if (movie.remarks.isNotBlank()) " · ${movie.remarks}" else ""}") {
                        loadVodDetail(site, base, movie)
                    })
                }
                if (parsed.pageCount > 1) {
                    val pageButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    if (page > 1) pageButtons.addView(actionButton("上一页") { loadVodPage(site, base, category, page - 1) })
                    if (page < parsed.pageCount) pageButtons.addView(actionButton("下一页") { loadVodPage(site, base, category, page + 1) })
                    itemList.addView(pageButtons)
                }
                if (parsed.movies.isEmpty() && parsed.categories.isEmpty()) {
                    output.text = output.text.toString() + "\n\n接口返回 JSON，但没有识别到 class/list 字段。请检查响应结构。"
                }
                showStatus("已加载第 $page 页：${parsed.movies.size} 部影片")
            }
        }
    }

    private fun loadVodSearch(site: SiteInfo, base: String, keyword: String) {
        val requestUrl = appendApiParams(base, linkedMapOf("ac" to "videolist", "wd" to keyword, "pg" to "1"))
        showStatus("正在搜索：$keyword")
        output.text = "站点：${site.name} 搜索：$keyword 请求：$requestUrl"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val connection = openConnection(requestUrl)
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes().take(1024 * 1024).toByteArray().toString(Charsets.UTF_8) } ?: ""
                    if (code !in 200..399) error("HTTP $code")
                    val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: error("搜索接口未返回 JSON 对象")
                    val data = json.optJSONObject("data") ?: json
                    val list = data.optJSONArray("list") ?: json.optJSONArray("list") ?: JSONArray()
                    (0 until list.length()).mapNotNull { i ->
                        val item = list.optJSONObject(i) ?: return@mapNotNull null
                        val id = item.opt("vod_id").safeString("")
                        if (id.isBlank()) null else VodMovie(id, item.opt("vod_name").safeString("未命名影片"), item.opt("vod_remarks").safeString(""), item.opt("vod_year").safeString(""), item.opt("vod_pic").safeString(""))
                    }
                } finally { connection.disconnect() }
            }
            runOnUiThread {
                val movies = result.getOrNull()
                if (movies == null) { output.text = "搜索失败：${result.exceptionOrNull()?.message ?: "未知错误"}"; showStatus("搜索失败"); return@runOnUiThread }
                output.text = "站点：${site.name}\n关键词：$keyword\n结果：${movies.size} 部"
                movies.forEach { movie -> itemList.addView(actionButton(movie.name) { loadVodDetail(site, base, movie) }) }
                showStatus("搜索完成：${movies.size} 部影片")
            }
        }
    }
    private fun loadVodDetail(site: SiteInfo, base: String, movie: VodMovie) {
        val requestUrl = appendApiParams(base, linkedMapOf("ac" to "detail", "ids" to movie.id))
        showStatus("正在加载影片详情…")
        output.text = "影片：${movie.name}\n正在读取播放线路…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val connection = openConnection(requestUrl)
                try {
                    val code = connection.responseCode
                    val body = (if (code in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes().take(1024 * 1024).toByteArray().toString(Charsets.UTF_8) } ?: ""
                    if (code !in 200..399) error("HTTP $code")
                    val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: error("详情接口未返回 JSON 对象")
                    val data = json.optJSONObject("data") ?: json
                    val list = data.optJSONArray("list") ?: json.optJSONArray("list")
                    val detail = list?.optJSONObject(0) ?: data.optJSONObject("vod") ?: json.optJSONObject("vod") ?: error("详情响应中没有影片对象")
                    val playFrom = detail.opt("vod_play_from").safeString("")
                    val playUrl = detail.opt("vod_play_url").safeString("")
                    val sources = playFrom.split("$" + "$" + "$").map { it.trim() }
                    val groups = playUrl.split("$" + "$" + "$")
                    val episodes = mutableListOf<Pair<String, String>>()
                    groups.forEachIndexed { index, group ->
                        val sourceName = sources.getOrNull(index)?.ifBlank { "线路 ${index + 1}" } ?: "线路 ${index + 1}"
                        group.split("#").forEach { entry ->
                            val splitAt = entry.indexOf("$")
                            if (splitAt > 0 && splitAt < entry.length - 1) episodes.add(Pair("$sourceName · ${entry.substring(0, splitAt).trim()}", entry.substring(splitAt + 1).trim()))
                        }
                    }
                    Triple(detail.opt("vod_name").safeString(movie.name), detail.opt("vod_content").safeString(""), episodes)
                } finally { connection.disconnect() }
            }
            runOnUiThread {
                val detail = result.getOrNull()
                if (detail == null) { output.text = "影片：${movie.name}\n详情加载失败：${result.exceptionOrNull()?.message ?: "未知错误"}"; showStatus("详情加载失败"); return@runOnUiThread }
                output.text = "影片：${detail.first}\n简介：${detail.second.take(500)}\n可播放集数：${detail.third.size}"
                val episodePlaylist = JSONArray()
                detail.third.forEach { episode ->
                    episodePlaylist.put(JSONObject()
                        .put("title", "${detail.first} · ${episode.first}")
                        .put("url", episode.second)
                        .put("headers", JSONObject()))
                }
                val episodePlaylistFile = java.io.File(cacheDir, "vod-playlist.json")
                episodePlaylistFile.writeText(episodePlaylist.toString(), Charsets.UTF_8)
                detail.third.forEachIndexed { index, episode ->
                    itemList.addView(actionButton(episode.first) {
                        val intent = android.content.Intent(this, PlayerActivity::class.java)
                        intent.putExtra("title", "${detail.first} · ${episode.first}")
                        intent.putExtra("url", episode.second)
                        intent.putExtra("playlist_file", episodePlaylistFile.absolutePath)
                        intent.putExtra("playlist_index", index)
                        intent.putExtra("playlist_label", "集数")
                        startActivity(intent)
                    })
                }
                if (detail.third.isEmpty()) output.text = output.text.toString() + "\n未解析到标准 CMS 播放线路。"
                showStatus(if (detail.third.isEmpty()) "没有可识别的播放线路" else "已加载 ${detail.third.size} 个播放项")
            }
        }
    }

    private fun runSpiderSite(site: SiteInfo, jarUrl: String, classShortName: String) {
        showStatus("正在准备 Spider 插件…")
        output.text = "站点：${site.name}\n正在下载并初始化插件。仅运行你信任的第三方 JAR。"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val dir = java.io.File(filesDir, "spider").apply { mkdirs() }
                val safeName = site.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "site" }
                val jarFile = java.io.File(dir, "$safeName.jar")
                // Refresh the downloaded plugin on each explicit run so updated JAR content
                // is not silently shadowed by a stale per-site cache.
                run {
                    val connection = openConnection(jarUrl)
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) error("JAR 下载 HTTP $code")
                        if (connection.contentLengthLong > 80L * 1024 * 1024) error("JAR 超过 80 MiB 限制")
                        val tmp = java.io.File(dir, "$safeName.jar.tmp")
                        connection.inputStream.use { input -> tmp.outputStream().buffered().use { out ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > 80L * 1024 * 1024) error("JAR 超过 80 MiB 限制"); out.write(buffer, 0, n) }
                        } }
                        java.util.zip.ZipFile(tmp).use { zip -> if (!zip.entries().hasMoreElements()) error("下载内容不是有效 JAR/ZIP") }
                        if (!tmp.renameTo(jarFile)) { tmp.copyTo(jarFile, overwrite = true); tmp.delete() }
                    } finally { connection.disconnect() }
                }
                val optimized = java.io.File(dir, "optimized").apply { mkdirs() }
                val libs = java.io.File(dir, "libs").apply { mkdirs() }
                val loader = dalvik.system.DexClassLoader(jarFile.absolutePath, optimized.absolutePath, libs.absolutePath, javaClass.classLoader)

                // Match the CatVod/OKTV host bootstrap sequence: initialize the Spider runtime
                // from the downloaded JAR before loading a guarded Spider class. Some guarded
                // providers expect this Context hook to have run before DexNative is initialized.
                val spiderBootstrap = runCatching {
                    val initClass = loader.loadClass("com.github.catvod.spider.Init")
                    val initMethod = initClass.getMethod("init", android.content.Context::class.java)
                    initMethod.invoke(null, this@MainActivity)
                    "Spider Init.init(Context) 已调用"
                }.fold(
                    onSuccess = { it },
                    onFailure = { "Spider Init bootstrap 未提供或失败：${describeSpiderError(it)}" }
                )

                val normalizedName = classShortName.removePrefix("csp_").removePrefix("CSP_").trim()
                val candidates = listOf(
                    "com.github.catvod.spider.$normalizedName",
                    "com.github.catvod.spider.${normalizedName.replaceFirstChar { it.uppercase() }}",
                    normalizedName,
                    classShortName
                ).distinct()
                val loadErrors = mutableListOf<String>()
                var loadedClass: Class<*>? = null
                for (candidate in candidates) {
                    try {
                        loadedClass = loader.loadClass(candidate)
                        break
                    } catch (t: Throwable) {
                        loadErrors.add("$candidate -> ${describeSpiderError(t)}")
                    }
                }
                val spiderClass = loadedClass ?: run {
                    val discovered = runCatching {
                        java.util.zip.ZipFile(jarFile).use { zip ->
                            val e = zip.entries()
                            val names = mutableListOf<String>()
                            while (e.hasMoreElements()) {
                                val entry = e.nextElement()
                                if (entry.name.endsWith(".class") && !entry.name.contains("\\$")) {
                                    names.add(entry.name.removeSuffix(".class").replace('/', '.'))
                                }
                                if (names.size >= 80) break
                            }
                            names.joinToString("\n").ifBlank { "(未发现普通 .class；可能是 DEX/JAR 格式或非可执行文件)" }
                        }
                    }.getOrDefault("(读取 JAR 类清单失败)")
                    throw IllegalStateException(
                        "无法加载 Spider 类。尝试类名：${candidates.joinToString()}\n" +
                            "类加载错误：${loadErrors.joinToString(" | ")}\n" +
                            "JAR 内部类样例：\n$discovered"
                    )
                }
                val constructor = runCatching { spiderClass.getDeclaredConstructor() }.getOrElse {
                    throw IllegalStateException("类 ${spiderClass.name} 没有无参构造函数：${describeSpiderError(it)}")
                }
                constructor.isAccessible = true
                val spider = constructor.newInstance()
                val methods = spiderClass.methods.toList()
                val initMethods = methods.filter { it.name == "init" }.sortedBy { it.parameterTypes.size }
                val init2 = initMethods.firstOrNull {
                    it.parameterTypes.size == 2 &&
                        it.parameterTypes[0].isAssignableFrom(this@MainActivity.javaClass) &&
                        (it.parameterTypes[1] == String::class.java || it.parameterTypes[1] == JSONObject::class.java ||
                            java.util.Map::class.java.isAssignableFrom(it.parameterTypes[1]))
                }
                val init1 = initMethods.firstOrNull {
                    it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(this@MainActivity.javaClass)
                }
                when {
                    init2 != null -> {
                        val second = when (init2.parameterTypes[1]) {
                            String::class.java -> site.ext
                            JSONObject::class.java -> runCatching { JSONObject(site.ext.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                            else -> runCatching {
                                val obj = JSONObject(site.ext.ifBlank { "{}" })
                                HashMap<String, String>().apply {
                                    val keys = obj.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        put(key, obj.opt(key).safeString(""))
                                    }
                                }
                            }.getOrElse { HashMap<String, String>() }
                        }
                        invokeSpiderMethod(init2, spider, this@MainActivity, second)
                    }
                    init1 != null -> invokeSpiderMethod(init1, spider, this@MainActivity)
                    initMethods.isNotEmpty() -> throw IllegalStateException(
                        "发现 init 方法，但参数不兼容：${initMethods.joinToString { m -> m.parameterTypes.joinToString(prefix = "init(", postfix = ")") { it.simpleName } }}"
                    )
                }
                val home = methods.firstOrNull {
                    it.name == "homeContent" && it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Boolean::class.javaPrimitiveType || it.parameterTypes[0] == Boolean::class.java)
                } ?: throw IllegalStateException(
                    "类 ${spiderClass.name} 缺少 homeContent(boolean)。可用方法：${methods.filter { it.name in listOf("init", "homeContent", "categoryContent", "detailContent", "playerContent") }.joinToString { m -> m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" }.ifBlank { "(未找到常见 Spider 方法)" }}"
                )
                val homeResult = invokeSpiderMethod(home, spider, true)?.toString().orEmpty()
                if (homeResult.isBlank()) throw IllegalStateException("homeContent(true) 返回空内容")
                val json = runCatching { JSONObject(homeResult) }.getOrElse {
                    throw IllegalStateException("homeContent 返回的不是 JSON 对象：${homeResult.take(240)}；错误：${describeSpiderError(it)}")
                }
                Triple(spider, json, spiderClass.name + "（" + spiderBootstrap + "）")
            }
            runOnUiThread {
                val loaded = result.getOrNull()
                if (loaded == null) {
                    val failure = result.exceptionOrNull()
                    val details = failure?.let { describeSpiderError(it) } ?: "未捕获到异常对象"
                    val guardHint = if (details.contains("com.github.catvod.spider.DexNative") && details.contains("YGPGuard")) {
                        "\n\n诊断结论：检测到 YGPGuard 在 DexNative 静态初始化阶段失败。此类加固 Spider 通常依赖与其匹配的 CatVod/TVBox 宿主运行时、加固资源或原生库；当前应用的通用 DexClassLoader 不能保证兼容。请优先换用未加固且兼容 CatVod Spider ABI 的 JAR，或集成该 JAR 对应版本的官方宿主运行时。仅修改站点 api/ext 通常无法修复此错误。"
                    } else ""
                    output.text = "Spider 启动失败：\n$details$guardHint\n\n请检查异常中的类名、依赖、初始化参数或 Spider ABI。"
                    showStatus("Spider 启动失败（已输出诊断建议）")
                    return@runOnUiThread
                }
                val spider = loaded.first
                val json = loaded.second
                output.text = "Spider：${loaded.third}\n分类：${json.optJSONArray("class")?.length() ?: 0}\n影片：${json.optJSONArray("list")?.length() ?: 0}"
                itemList.removeAllViews()
                val spiderSearchInput = EditText(this).apply {
                    hint = "搜索此 Spider 站点"
                    isSingleLine = true
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    setHintTextColor(0xFF8B96A9.toInt())
                }
                itemList.addView(spiderSearchInput)
                itemList.addView(actionButton("搜索 Spider 站点") {
                    val keyword = spiderSearchInput.text.toString().trim()
                    if (keyword.isBlank()) showStatus("请输入搜索关键词")
                    else loadSpiderSearch(spider, site, keyword)
                })
                val categories = json.optJSONArray("class") ?: JSONArray()
                for (i in 0 until categories.length()) {
                    val cat = categories.optJSONObject(i) ?: continue
                    val id = cat.opt("type_id").safeString("")
                    val name = cat.opt("type_name").safeString(id)
                    if (id.isNotBlank()) itemList.addView(actionButton("分类：$name") { loadSpiderCategory(spider, site, id) })
                }
                addSpiderVideoButtons(spider, site, json.optJSONArray("list") ?: JSONArray())
                showStatus("Spider 已初始化")
            }
        }
    }

    private fun invokeSpiderMethod(method: java.lang.reflect.Method, receiver: Any, vararg args: Any?): Any? {
        return try {
            method.invoke(receiver, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun describeSpiderError(error: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && parts.size < 8) {
            val node = current
            val message = node.message?.takeIf { it.isNotBlank() } ?: "(无消息)"
            val frames = node.stackTrace.take(10).joinToString("\n") { "    at $it" }
            parts.add("${node.javaClass.name}: $message" + if (frames.isNotBlank()) "\n$frames" else "")
            current = when (node) {
                is java.lang.reflect.InvocationTargetException -> node.targetException ?: node.cause
                else -> node.cause
            }
        }
        return parts.joinToString("\nCaused by: ")
    }

    private fun loadSpiderSearch(spider: Any, site: SiteInfo, keyword: String) {
        showStatus("正在搜索 Spider 站点：$keyword")
        output.text = "站点：${site.name}\n关键词：$keyword\n正在调用 Spider 搜索接口…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val candidates = spider.javaClass.methods.filter {
                    it.name == "searchContent" && it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes.size in 2..3
                }.sortedBy { it.parameterTypes.size }
                val method = candidates.firstOrNull() ?: error("此 Spider 没有兼容的 searchContent(String, …) 方法")
                val args = when (method.parameterTypes.size) {
                    2 -> arrayOf<Any?>(keyword, false)
                    3 -> {
                        val third = method.parameterTypes[2]
                        val ext = runCatching {
                            val obj = JSONObject(site.ext.ifBlank { "{}" })
                            HashMap<String, String>().apply {
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    put(key, obj.opt(key).safeString(""))
                                }
                            }
                        }.getOrElse { HashMap<String, String>() }
                        arrayOf(keyword, false, when { java.util.Map::class.java.isAssignableFrom(third) -> ext; third == JSONObject::class.java -> runCatching { JSONObject(site.ext.ifBlank { "{}" }) }.getOrElse { JSONObject() }; else -> site.ext })
                    }
                    else -> error("不支持的 Spider 搜索接口")
                }
                val response = invokeSpiderMethod(method, spider, *args)?.toString().orEmpty()
                val json = JSONObject(response)
                val data = json.optJSONObject("data") ?: json
                val videos = data.optJSONArray("list") ?: json.optJSONArray("list") ?: JSONArray()
                json to videos
            }
            runOnUiThread {
                val value = result.getOrNull()
                if (value == null) {
                    output.text = "Spider 搜索失败：${result.exceptionOrNull()?.let { describeSpiderError(it) } ?: "未知错误"}"
                    showStatus("Spider 搜索失败")
                    return@runOnUiThread
                }
                val videos = value.second
                output.text = "站点：${site.name}\n关键词：$keyword\n搜索结果：${videos.length()} 部"
                addSpiderVideoButtons(spider, site, videos)
                showStatus("Spider 搜索完成：${videos.length()} 部")
            }
        }
    }

    private fun loadSpiderCategory(spider: Any, site: SiteInfo, categoryId: String, page: Int = 1) {
        showStatus("正在加载 Spider 分类（第 ${page} 页）…")
        output.text = "站点：${site.name}\n分类：$categoryId\n页码：$page\n正在请求 Spider…"
        itemList.removeAllViews()
        executor.execute {
            val result = runCatching {
                val method = spider.javaClass.methods.firstOrNull { it.name == "categoryContent" && it.parameterTypes.size == 4 } ?: error("缺少 categoryContent 方法")
                val extend = runCatching { JSONObject(site.ext).let { obj -> HashMap<String, String>().apply { obj.keys().forEach { key -> put(key, obj.opt(key).safeString("")) } } } }.getOrElse { HashMap() }
                JSONObject(invokeSpiderMethod(method, spider, categoryId, page.toString(), true, extend)?.toString().orEmpty())
            }
            runOnUiThread {
                val json = result.getOrNull()
                if (json == null) {
                    output.text = "分类加载失败：${result.exceptionOrNull()?.let { describeSpiderError(it) } ?: "未知错误"}"
                    showStatus("分类加载失败")
                    return@runOnUiThread
                }
                val videos = json.optJSONArray("list") ?: JSONArray()
                val pageCount = json.opt("pagecount").safeInt(json.opt("page_count").safeInt(page))
                output.text = "站点：${site.name}\n分类：$categoryId\n当前页：$page / ${pageCount.coerceAtLeast(page)}\n影片：${videos.length()}"
                if (page > 1) itemList.addView(actionButton("上一页") { loadSpiderCategory(spider, site, categoryId, page - 1) })
                if (page < pageCount) itemList.addView(actionButton("下一页") { loadSpiderCategory(spider, site, categoryId, page + 1) })
                addSpiderVideoButtons(spider, site, videos)
                showStatus("Spider 分类加载完成")
            }
        }
    }
    private fun addSpiderVideoButtons(spider: Any, site: SiteInfo, videos: JSONArray) {
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val id = video.opt("vod_id").safeString("")
            val name = video.opt("vod_name").safeString("未命名影片")
            if (id.isBlank()) continue
            itemList.addView(actionButton(name) {
                showStatus("正在加载影片详情…")
                executor.execute {
                    val result = runCatching {
                        val method = spider.javaClass.methods.firstOrNull { it.name == "detailContent" && it.parameterTypes.size == 1 } ?: error("缺少 detailContent 方法")
                        val detailJson = JSONObject(invokeSpiderMethod(method, spider, listOf(id))?.toString().orEmpty())
                        val detail = detailJson.optJSONArray("list")?.optJSONObject(0) ?: detailJson.optJSONObject("vod") ?: error("详情中没有影片")
                        val flags = detail.opt("vod_play_from").safeString("").split("$" + "$" + "$")
                        val groups = detail.opt("vod_play_url").safeString("").split("$" + "$" + "$")
                        val episodes = mutableListOf<Triple<String, String, String>>()
                        groups.forEachIndexed { index, group -> group.split("#").forEach { entry -> val pos = entry.indexOf("$"); if (pos > 0 && pos < entry.length - 1) episodes.add(Triple(flags.getOrNull(index) ?: "线路 ${index + 1}", entry.substring(0, pos).trim(), entry.substring(pos + 1).trim())) } }
                        Triple(detail.opt("vod_name").safeString(name), detail.opt("vod_content").safeString(""), episodes)
                    }
                    runOnUiThread {
                        val detail = result.getOrNull()
                        if (detail == null) { output.text = "详情失败：${result.exceptionOrNull()?.cause?.message ?: result.exceptionOrNull()?.message}"; showStatus("详情加载失败"); return@runOnUiThread }
                        output.text = "影片：${detail.first}\n简介：${detail.second.take(400)}\n剧集：${detail.third.size}"
                        itemList.removeAllViews()
                        detail.third.forEach { ep -> itemList.addView(actionButton("${ep.first} · ${ep.second}") {
                            executor.execute {
                                val playResult = runCatching {
                                    val method = spider.javaClass.methods.firstOrNull { it.name == "playerContent" && it.parameterTypes.size == 3 } ?: error("缺少 playerContent 方法")
                                    val playJson = JSONObject(invokeSpiderMethod(method, spider, ep.first, ep.third, emptyList<String>())?.toString().orEmpty())
                                    val url = playJson.opt("url").safeString(ep.third)
                                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) error("无效播放地址：$url")
                                    val headers = playJson.optJSONObject("header") ?: playJson.optJSONObject("headers") ?: JSONObject()
                                    Triple(url, headers.toString(), playJson.opt("parse").safeString(""))
                                }
                                runOnUiThread {
                                    val playback = playResult.getOrNull()
                                    if (playback == null) { output.text = "解析播放地址失败：${playResult.exceptionOrNull()?.cause?.message ?: playResult.exceptionOrNull()?.message}"; showStatus("播放解析失败") }
                                    else if (playback.third.isNotBlank() && playback.third != "0") { output.text = "该 Spider 返回了需要额外解析器的播放标记：${playback.third}\n当前播放器仅支持 Spider 已返回的直链或可直接播放的媒体地址。"; showStatus("需要外部解析器") }
                                    else { startActivity(android.content.Intent(this@MainActivity, PlayerActivity::class.java).putExtra("title", "${detail.first} · ${ep.second}").putExtra("url", playback.first).putExtra("headers", playback.second)); showStatus("正在播放") }
                                }
                            }
                        }) }
                        showStatus("已解析剧集")
                    }
                }
            })
        }
    }

    private fun resolveReference(baseUrl: String, reference: String): String {
        val value = reference.trim()
        if (value.isBlank()) return ""
        return runCatching {
            java.net.URI(baseUrl).resolve(value).toString()
        }.getOrElse { value }
    }

    private fun appendApiParams(base: String, params: Map<String, String>): String {
        // Keep fragments out of the HTTP request and replace existing values for keys
        // such as ac/pg/t instead of sending duplicate query parameters.
        val fragmentIndex = base.indexOf('#')
        val fragment = if (fragmentIndex >= 0) base.substring(fragmentIndex) else ""
        val address = if (fragmentIndex >= 0) base.substring(0, fragmentIndex) else base
        val queryIndex = address.indexOf('?')
        val path = if (queryIndex >= 0) address.substring(0, queryIndex) else address
        val existingQuery = if (queryIndex >= 0) address.substring(queryIndex + 1) else ""
        val keysToReplace = params.keys
        val queryParts = existingQuery.split('&').filter { part ->
            if (part.isBlank()) false else {
                val rawKey = part.substringBefore('=')
                val decodedKey = runCatching { java.net.URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
                decodedKey !in keysToReplace
            }
        }.toMutableList()
        queryParts += params.entries.map {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        return path + if (queryParts.isEmpty()) fragment else "?" + queryParts.joinToString("&") + fragment
    }

    private fun Any?.safeString(fallback: String): String = when (this) {
        null, JSONObject.NULL -> fallback
        is String -> this
        else -> toString()
    }

    private fun Any?.safeInt(fallback: Int): Int = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: fallback
        is Boolean -> if (this) 1 else 0
        else -> fallback
    }

    private data class LiveSource(val name: String, val url: String, val inlineChannels: JSONArray?)
    private data class LiveChannel(val name: String, val url: String, val group: String = "直播", val referer: String = "", val userAgent: String = "")

    private data class VodCategory(val id: String, val name: String)

    private data class VodMovie(
        val id: String,
        val name: String,
        val remarks: String,
        val year: String,
        val pic: String
    )

    private data class VodPageResult(
        val categories: List<VodCategory>,
        val movies: List<VodMovie>,
        val message: String = "",
        val error: Boolean = false,
        val pageCount: Int = 1
    )

    private data class SiteInfo(
        val name: String,
        val api: String,
        val type: Int,
        val jar: String,
        val ext: String,
        val searchable: Boolean,
        val quickSearch: Boolean
    )

    private data class ConfigResult(
        val httpCode: Int,
        val fields: List<String>,
        val sites: List<SiteInfo>,
        val error: String? = null,
        val configSummary: String = "",
        val spiderUrl: String = ""
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
                    val trimmed = body.trim().removePrefix("\uFEFF")
                    val jsonObject = runCatching { JSONObject(trimmed) }.getOrNull()
                    val jsonArray = if (jsonObject == null) runCatching { JSONArray(trimmed) }.getOrNull() else null
                    val fields = jsonObject?.keys()?.asSequence()?.take(40)?.toList().orEmpty()
                    buildString {
                        appendLine("请求地址：$source")
                        appendLine("HTTP 状态：$code")
                        appendLine("Content-Type：$contentType")
                        appendLine("响应长度（最多读取 64 KiB）：${body.toByteArray().size} bytes")
                        appendLine("响应类型：")
                        appendLine(when {
                            jsonObject?.has("sites") == true -> "TVBox 风格 JSON 对象（发现 sites 字段）"
                            jsonObject?.has("data") == true -> "JSON 对象（发现 data 字段）"
                            jsonObject != null -> "有效 JSON 对象，但未发现 sites/data 字段"
                            jsonArray != null -> "有效 JSON 数组（共 ${jsonArray.length()} 项）"
                            trimmed.startsWith("<!DOCTYPE", true) || trimmed.startsWith("<html", true) -> "HTML 网页，不是直接可识别的 TVBox JSON"
                            trimmed.isBlank() -> "空响应"
                            trimmed.startsWith("ÿØ") || contentType.contains("image/jpeg", true) -> "服务器返回 JPEG 图片（JFIF/JPEG），不是 JSON 配置"
                            else -> "非 JSON 或未知格式"
                        })
                        if (fields.isNotEmpty()) appendLine("JSON 顶层字段：${fields.joinToString()}")
                        appendLine()
                        appendLine("响应内容开头（用于判断真实格式）：")
                        appendLine(trimmed.take(500).ifBlank { "(空)" })
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
