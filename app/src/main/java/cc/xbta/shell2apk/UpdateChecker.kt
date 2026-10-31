package cc.xbta.shell2apk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动更新：从 GitHub Release 查最新版本，按用户选的源（直链 / 代理 / 自定义前缀）改写地址。
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
        else -> "GitHub 代理"
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
        val api = "https://api.github.com/repos/$REPO/releases/latest"
        val json = httpGet(wrap(ctx, api))
        val o = JSONObject(json)
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
            pageUrl = o.optString("html_url", "https://github.com/$REPO/releases"),
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

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Shell2APK-Android")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
