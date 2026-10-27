package com.github.catvod.crawler

import android.content.Context
import org.json.JSONObject

/**
 * Minimal CatVod Spider ABI surface for dynamically loaded compatible spider JARs.
 * Keep method names/signatures aligned with the CatVod Spider base class.
 */
abstract class Spider {
    open fun init(context: Context) {}
    open fun init(context: Context, extend: String) { init(context) }
    open fun homeContent(filter: Boolean): String = ""
    open fun homeVideoContent(): String = ""
    open fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>?): String = ""
    open fun detailContent(ids: List<String>): String = ""
    open fun searchContent(key: String, quick: Boolean): String = ""
    open fun playerContent(flag: String, id: String, vipFlags: List<String>): String = ""
    open fun isVideoFormat(url: String): Boolean = false
    open fun manualVideoCheck(): Boolean = false

    companion object {
        @JvmField val empty = JSONObject()
    }
}
