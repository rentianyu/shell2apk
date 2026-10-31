package cc.xbta.shell2apk

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 自动更新：拿到最新版本号 → 按线路拼下载地址。
 *
 * 线路池（默认「自动」）= 内置公共 GitHub 加速线路**并发**尝试，先成功的胜出；
 * 全部失败时才走 GitHub 直链。只用公共线路，不用自建域名。
 *
 * 注意各线路能力不同（2026-10 实测）：
 *  · gh-proxy.com  只代理资源下载，抓网页会返回 403「for resource downloads only」
 *  · ghproxy.net   网页与下载都能用
 *  · ghfast.top    下载可用（国内通常快）
 *  · ghproxy.link  是「最新地址发布页」而不是代理，会返回一个公告 HTML
 * 所以下载完必须校验文件头是 PK（zip/APK），防止把公告页当 APK 装。
 *
 * 取版本两条通道：
 *  1. 主通道：抓 releases/latest 页面的 HTML，正则提 tag（不吃 GitHub API 的 60 次/小时限流）。
 *  2. 兜底：GitHub API /releases/latest（拿 assets 里的真实下载地址）。
 *
 * asset 命名约定：shell2apk-<tag>.apk（CI 里固定，改名会破坏自动更新）。
 */
object UpdateChecker {

    const val SOURCE_AUTO = "auto"
    const val SOURCE_DIRECT = "direct"
    const val SOURCE_CUSTOM = "custom"

    /** 内置公共 GitHub 加速线路 */
    val BUILTIN_PROXIES = listOf(
        "https://gh-proxy.com",
        "https://ghfast.top",
        "https://ghproxy.net",
        "https://ghproxy.link"
    )

    private const val REPO = "rentianyu/shell2apk"
    private const val SP = "shell2apk"
    private const val CHECK_TIMEOUT_SEC = 22L

    data class Release(
        val tag: String,
        val versionName: String,
        val downloadUrl: String,
        val altUrls: List<String>,
        val pageUrl: String,
        val size: Long,
        val via: String
    )

    fun source(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString("update_source", SOURCE_AUTO) ?: SOURCE_AUTO

    fun customPrefix(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString("update_prefix", "") ?: ""

    fun setSource(ctx: Context, source: String, prefix: String) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString("update_source", source)
            .putString("update_prefix", prefix)
            .apply()
    }

    fun sourceLabel(ctx: Context): String = when (source(ctx)) {
        SOURCE_DIRECT -> "GitHub 直链"
        SOURCE_CUSTOM -> "自定义：" + customPrefix(ctx)
        else -> "自动（内置 " + BUILTIN_PROXIES.size + " 条线路并发，直链兜底）"
    }

    /** 本次要尝试的前缀；空串代表直链 */
    private fun bases(ctx: Context): List<String> = when (source(ctx)) {
        SOURCE_DIRECT -> listOf("")
        SOURCE_CUSTOM -> {
            val p = customPrefix(ctx).trim()
            if (p.isEmpty()) BUILTIN_PROXIES + "" else listOf(p)
        }
        else -> BUILTIN_PROXIES + ""
    }

    fun label(base: String): String =
        if (base.isEmpty()) "GitHub 直链" else base.replace("https://", "").trimEnd('/')

    /** 把 GitHub 原始地址拼到某个线路前缀上；空串=直链 */
    fun wrap(base: String, githubUrl: String): String {
        if (base.isEmpty()) return githubUrl
        val p = if (base.endsWith("/")) base else "$base/"
        return p + githubUrl
    }

    fun localVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (t: Throwable) {
        "0"
    }

    fun check(ctx: Context): Release {
        val list = bases(ctx)
        var pageErr: Throwable? = null
        try {
            return race(list) { b -> checkByPage(ctx, b) }
        } catch (t: Throwable) {
            pageErr = t
        }
        try {
            return race(list) { b -> checkByApi(ctx, b) }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "页面通道失败：" + (pageErr?.message ?: "?") + "\nAPI 通道失败：" + (t.message ?: "?")
            )
        }
    }

    /** 并发跑同一动作，先成功者胜；全失败则抛错 */
    private fun race(list: List<String>, fn: (String) -> Release): Release {
        val latch = CountDownLatch(1)
        val ok = AtomicReference<Release>()
        val errs = Collections.synchronizedList(ArrayList<String>())
        val pending = AtomicInteger(list.size)
        for (b in list) {
            Thread {
                try {
                    val r = fn(b)
                    if (ok.compareAndSet(null, r)) latch.countDown()
                } catch (t: Throwable) {
                    errs.add(label(b) + "：" + (t.message ?: t.javaClass.simpleName))
                } finally {
                    if (pending.decrementAndGet() == 0) latch.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        latch.await(CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
        val r = ok.get()
        if (r != null) return r
        throw IllegalStateException(if (errs.isEmpty()) "超时（${CHECK_TIMEOUT_SEC}s）" else errs.joinToString("；"))
    }

    /** 主通道：releases/latest 页面 → 提 tag */
    private fun checkByPage(ctx: Context, base: String): Release {
        val pageUrl = "https://github.com/$REPO/releases/latest"
        val html = httpGet(wrap(base, pageUrl), "text/html")
        // 页面里的形式可能是 "…/releases/tag/v1.0.8"、"releases/tag/v1.0.8&quot;"、
        // 甚至 "releases/tag/*name"（模板），所以不能要求前导斜杠，且必须以数字版本开头。
        val m = Regex("releases/tag/(v?[0-9][A-Za-z0-9._-]*)").find(html)
            ?: throw IllegalStateException("页面里没找到 tag（可能是代理的公告页）")
        val tag = m.groupValues[1]
        val ver = tag.removePrefix("v")
        if (ver.isEmpty() || !ver[0].isDigit()) throw IllegalStateException("tag 解析失败：$tag")
        return buildRelease(ctx, tag, ver, base, 0L)
    }

    /** 兜底通道：GitHub API */
    private fun checkByApi(ctx: Context, base: String): Release {
        val api = "https://api.github.com/repos/$REPO/releases/latest"
        val o = JSONObject(httpGet(wrap(base, api), "application/vnd.github+json"))
        val tag = o.optString("tag_name", "")
        if (tag.isBlank()) throw IllegalStateException("没有取到 tag_name")
        var size = 0L
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    size = a.optLong("size", 0)
                    break
                }
            }
        }
        return buildRelease(ctx, tag, tag.removePrefix("v"), base, size)
    }

    /** 命中线路排在首位，其余线路作为备用下载地址 */
    private fun buildRelease(ctx: Context, tag: String, ver: String, hitBase: String, size: Long): Release {
        val rel = "https://github.com/$REPO/releases/download/$tag/shell2apk-$tag.apk"
        val others = bases(ctx).filter { it != hitBase }
        val urls = (listOf(hitBase) + others).map { wrap(it, rel) }.distinct()
        return Release(
            tag = tag,
            versionName = ver,
            downloadUrl = urls.first(),
            altUrls = urls.drop(1),
            pageUrl = wrap(hitBase, "https://github.com/$REPO/releases/tag/$tag"),
            size = size,
            via = label(hitBase)
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

    /** 依次尝试各线路下载，成功返回文件；每次都校验得到的是真 APK */
    fun downloadFirst(urls: List<String>, dest: File, onProgress: (Int) -> Unit): File {
        var last: Throwable? = null
        for (u in urls) {
            try {
                if (dest.exists()) dest.delete()
                download(u, dest, onProgress)
                if (!looksLikeApk(dest)) throw IllegalStateException("返回的不是 APK（可能是代理公告页）")
                return dest
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("所有下载线路都失败：" + (last?.message ?: "?"))
    }

    /** APK 就是 zip：文件头必须是 PK\x03\x04 */
    private fun looksLikeApk(f: File): Boolean {
        if (!f.exists() || f.length() < 100 * 1024) return false
        return try {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                val n = ins.read(b)
                n == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun download(url: String, dest: File, onProgress: (Int) -> Unit): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 45000
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
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) Shell2APK")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
