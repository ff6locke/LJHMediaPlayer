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
    private val defaultSource = "http://fty.xxooo.cf/tv"
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
            setText(prefs.getString("source", null).let { saved -> if (saved.isNullOrBlank() || saved == "http://www.饭太硬.cc/tv") defaultSource else saved })
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
                val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
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


    private fun downloadSpiderJar() {
        val spiderUrl = currentSpiderUrl
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
                val target = java.io.File(directory, "spider.jar")
                val temp = java.io.File(directory, "spider.jar.tmp")
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
                    appendLine("本地文件：${target.absolutePath}")
                    appendLine("大小：${target.length()} bytes")
                    appendLine("SHA-256：$hash")
                    appendLine("JAR 条目数：${entries.size}")
                    appendLine("DEX 文件：${if (dex.isEmpty()) "未发现" else dex.joinToString()}")
                    appendLine("普通 class 文件数：$classes")
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
            output.text = "站点：${site.name}\n站点类型：${site.type}\nSpider 类名：$rawApi\n\n诊断：这是 TVBox Spider 类名，不是网址；不能把它作为 HTTP 主机名请求。\n\n当前版本尚未执行 spider.jar，因此该站点暂不可用。请先在配置检测结果中确认 Spider 解析地址是否显示为完整的 http(s) URL。若地址为空或不是 URL，需要修正配置中的 spider 字段。\n\n注意：仅下载 JAR 仍不够，还需要确认它的 CatVod/TVBox 接口、依赖和调用方法，之后才能接入执行。"
            showStatus("Spider 引擎未接入：$rawApi")
            return
        }

        // TVBox site types are not interchangeable. Do not treat XPath/Spider sites as CMS APIs.
        if (site.type != 0) {
            val typeDescription = when (site.type) {
                1 -> "XPath 网页解析站点"
                3 -> "Spider/爬虫引擎站点"
                else -> "非标准 CMS JSON 站点"
            }
            output.text = "站点：${site.name}\n站点类型：${site.type}（$typeDescription）\nAPI：${site.api}\n\n当前版本的点播加载器只支持 type=0 的标准 JSON API，因此不会错误地向此地址追加 ?ac=list。\n\n下一步需要为该类型接入对应解析器；如果是 Spider 站点，还需要兼容其 Spider 引擎及配置。"
            showStatus("暂不支持此站点类型：${site.type}")
            return
        }

        val base = when {
            rawApi.startsWith("https://", ignoreCase = true) || rawApi.startsWith("http://", ignoreCase = true) -> rawApi
            rawApi.startsWith("//") -> "https:$rawApi"
            rawApi.startsWith("www.", ignoreCase = true) -> "https://$rawApi"
            rawApi.contains(".") && !rawApi.contains(" ") && !rawApi.startsWith("csp_", ignoreCase = true) -> "https://$rawApi"
            else -> {
                output.text = "站点：${site.name}\n站点类型：${site.type}\nAPI：$rawApi\n\nAPI 值看起来不是完整网址。它可能是 Spider 标识符或特殊协议，不能自动补成 HTTPS。"
                showStatus("API 地址格式无法识别")
                return
            }
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
                    val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
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
                    VodPageResult(categories, movies, json.optString("msg", ""))
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
                output.text = "站点：${site.name}\n分类：${parsed.categories.size} 个\n影片：${parsed.movies.size} 部\n选择分类筛选影片，选择影片可查看基本信息。"
                parsed.categories.forEach { cat ->
                    itemList.addView(actionButton("分类：${cat.name}") {
                        loadVodPage(site, base, cat, 1)
                    })
                }
                parsed.movies.forEach { movie ->
                    itemList.addView(actionButton("${movie.name}${if (movie.remarks.isNotBlank()) " · ${movie.remarks}" else ""}") {
                        output.text = "影片：${movie.name}\n分类：${category?.name ?: "全部"}\n年份：${movie.year}\n备注：${movie.remarks}\n影片 ID：${movie.id}\n图片：${movie.pic}\n\n下一步将接入详情、播放线路和视频播放器。"
                        showStatus("已选择影片：${movie.name}")
                    })
                }
                if (parsed.movies.isEmpty() && parsed.categories.isEmpty()) {
                    output.text = output.text.toString() + "\n\n接口返回 JSON，但没有识别到 class/list 字段。请检查响应结构。"
                }
                showStatus("已加载 ${parsed.categories.size} 个分类、${parsed.movies.size} 部影片")
            }
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
        val separator = if (base.contains("?")) "&" else "?"
        val encoded = params.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        return base + separator + encoded
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
        val error: Boolean = false
    )

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
                    val trimmed = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
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
