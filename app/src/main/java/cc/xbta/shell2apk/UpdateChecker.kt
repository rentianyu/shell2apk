package cc.xbta.shell2apk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新：拿到最新版本号 → 按用户选的源（直链 / 代理 / 自定义前缀）拼下载地址。
 *
 * 两条通道：
 *  1. 主通道：抓 releases/latest 页面的 HTML，正则提 tag（不吃 GitHub API 的 60 次/小时限流；
 *     实测 p.xbta.cc 代理会跟随 302 并返回页面，页面里带 /releases/tag/vX.Y.Z）。
 *  2. 兜底：GitHub API /releases/latest（去拿 assets 里的真实下载地址）。
 *
 * 下载文件名约定：shell2apk-<versionName>.apk（CI 里固定，改名会破坏自动更新）。
 */
object UpdateChecker {

    const val SOURCE_DIRECT = "direct"
    const val SOURCE_PROXY = "proxy"
    const val SOURCE_CUSTOM = "custom"

    const val DEFAULT_PROXY = "https://p.xbta.cc/"
    private const val REPO = "rentianyu/shell2apk"
    private const val SP = "shell2apk"

    data class Release(
        val tag: String,
        val versionName: String,
        val downloadUrl: String,
        val pageUrl: String,
        val size: Long
    )

    fun source(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString("update_source", SOURCE_PROXY) ?: SOURCE_PROXY

    fun customPrefix(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString("update_prefix", DEFAULT_PROXY) ?: DEFAULT_PROXY

    fun setSource(ctx: Context, source: String, prefix: String) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString("update_source", source)
            .putString("update_prefix", prefix)
            .apply()
    }

    fun sourceLabel(ctx: Context): String = when (source(ctx)) {
        SOURCE_DIRECT -> "GitHub 直链"
        SOURCE_CUSTOM -> "自定义：" + customPrefix(ctx)
        else -> "GitHub 代理（p.xbta.cc）"
    }

    /** 把 GitHub 原始地址按当前源改写 */
    fun wrap(ctx: Context, githubUrl: String): String = when (source(ctx)) {
        SOURCE_DIRECT -> githubUrl
        SOURCE_CUSTOM -> {
            val p = customPrefix(ctx).trim().let { if (it.endsWith("/")) it else "$it/" }
            p + githubUrl
        }
        else -> DEFAULT_PROXY + githubUrl
    }

    fun localVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (t: Throwable) {
        "0"
    }

    fun check(ctx: Context): Release {
        var first: Throwable? = null
        try {
            return checkByPage(ctx)
        } catch (t: Throwable) {
            first = t
        }
        try {
            return checkByApi(ctx)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "页面通道：" + (first?.message ?: "?") + "；API 通道：" + (t.message ?: "?")
            )
        }
    }

    /** 主通道：releases/latest 页面 → 提 tag */
    private fun checkByPage(ctx: Context): Release {
        val pageUrl = "https://github.com/$REPO/releases/latest"
        val html = httpGet(wrap(ctx, pageUrl), "text/html")
        // 页面里出现的形式可能是 "…/releases/tag/v1.0.8"、"releases/tag/v1.0.8&quot;"、
        // 甚至 "releases/tag/*name"（模板），所以不能要求前导斜杠，且必须以数字版本开头。
        val m = Regex("releases/tag/(v?[0-9][A-Za-z0-9._-]*)").find(html)
            ?: throw IllegalStateException("页面里没找到 release tag")
        val tag = m.groupValues[1]
        val ver = tag.removePrefix("v")
        if (ver.isEmpty() || !ver[0].isDigit()) throw IllegalStateException("tag 解析失败：$tag")
        // asset 命名规则与 CI 一致：shell2apk-<tag>.apk，即 shell2apk-v1.0.8.apk
        val dl = "https://github.com/$REPO/releases/download/$tag/shell2apk-$tag.apk"
        return Release(
            tag = tag,
            versionName = ver,
            downloadUrl = wrap(ctx, dl),
            pageUrl = wrap(ctx, "https://github.com/$REPO/releases/tag/$tag"),
            size = 0
        )
    }

    /** 兜底通道：GitHub API */
    private fun checkByApi(ctx: Context): Release {
        val api = "https://api.github.com/repos/$REPO/releases/latest"
        val o = JSONObject(httpGet(wrap(ctx, api), "application/vnd.github+json"))
        val tag = o.optString("tag_name", "")
        if (tag.isBlank()) throw IllegalStateException("没有取到 tag_name")
        var url = ""
        var size = 0L
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    url = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0)
                    break
                }
            }
        }
        if (url.isBlank()) throw IllegalStateException("该 release 里没有 apk")
        return Release(
            tag = tag,
            versionName = tag.removePrefix("v"),
            downloadUrl = wrap(ctx, url),
            pageUrl = wrap(ctx, "https://github.com/$REPO/releases"),
            size = size
        )
    }

    /** 1.0.10 > 1.0.9，按数字段比较 */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split(".", "-").mapNotNull { it.toIntOrNull() }
        val b = local.split(".", "-").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun download(url: String, dest: File, onProgress: (Int) -> Unit): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Shell2APK-Android")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String, accept: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Shell2APK")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
