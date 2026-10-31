package cc.xbta.shell2apk.apk

import android.content.Context
import cc.xbta.shell2apk.Task
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 核心：把 assets/runner.apk（模板）改造成用户要的那个 APK。
 *
 * 改造点：
 *  1. AndroidManifest.xml 的包名 + 应用名（重建字符串池）
 *  2. resources.arsc 里的包名（等长字节替换）
 *  3. assets/config.json 换成任务配置
 *  4. 各密度 mipmap 的 ic_launcher.png 换成生成的图标
 *  5. 丢掉模板自带的 v1 签名文件，最后用内置 keystore 重新签名
 */
object ApkBuilder {

    const val TPL_PKG = "cc.xbta.shell2apk.tpl"
    const val TPL_LABEL = "S2ATEMPLATELABELPLACEHOLDER0000"

    fun build(ctx: Context, task: Task, outFile: File) {
        val tpl = File(ctx.cacheDir, "runner-template.apk")
        if (!tpl.exists() || tpl.length() == 0L) {
            ctx.assets.open("runner.apk").use { input ->
                tpl.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val entries = ZipKit.read(tpl)
        val pkg = resolvePackage(task)
        val label = task.name.trim().ifBlank { "Shell2APK" }
        val cfg = task.toRunnerConfig().toByteArray(Charsets.UTF_8)
        val iconText = task.iconText.trim().ifBlank { label.take(1) }

        val result = ArrayList<ZipEntryData>(entries.size)
        // 正常情况下按 res/mipmap-xxx/ic_launcher.png 匹配；
        // 若模板被 AGP 做了资源路径缩短（res/9w.png 这种），退化成「按体积排序的 png」兜底。
        val hasNamedIcon = entries.any { isIconEntry(it.name) }
        val fallbackMap = HashMap<String, Int>()
        if (!hasNamedIcon) {
            val sizes = intArrayOf(48, 72, 96, 144, 192)
            entries.filter { it.name.matches(Regex("^res/[^/]+\\.png$")) && !it.name.endsWith(".9.png") }
                .sortedBy { it.data.size }
                .forEachIndexed { i, en -> fallbackMap[en.name] = sizes[minOf(i, sizes.size - 1)] }
        }

        for (e in entries) {
            val n = e.name
            val fpx = fallbackMap[n]
            when {
                n == "AndroidManifest.xml" -> {
                    val edited = AxmlEditor.replaceStrings(e.data, mapOf(TPL_PKG to pkg, TPL_LABEL to label))
                    result.add(ZipEntryData(n, 8, edited))
                }
                n == "resources.arsc" -> result.add(ZipEntryData(n, 0, setArscPackage(e.data, pkg)))
                n == "assets/config.json" -> result.add(ZipEntryData(n, 8, cfg))
                isTemplateSignatureFile(n) -> {
                    // 丢掉模板的 v1 签名，后面统一重新签
                }
                n.startsWith("res/mipmap-anydpi") -> {
                    // 丢掉自适应图标 XML，避免遮住我们生成的 PNG
                }
                isIconEntry(n) -> {
                    val dir = n.substringBeforeLast("/")
                    result.add(ZipEntryData(n, 0, IconGen.render(iconText, task.kind, IconGen.sizeFor(dir))))
                }
                fpx != null -> result.add(ZipEntryData(n, 0, IconGen.render(iconText, task.kind, fpx)))
                else -> result.add(e)
            }
        }

        val unsigned = File(ctx.cacheDir, "unsigned.apk")
        ZipKit.write(result, unsigned)
        KeystoreProvider.sign(ctx, unsigned, outFile)
        unsigned.delete()
    }

    /**
     * 生成唯一包名，**长度必须与 TPL_PKG 完全一致（21 字符）**，
     * 因为 resources.arsc 里的包名是按字节等长替换的。
     * TPL_PKG = "cc.xbta.shell2apk.tpl" → 21；这里 "cc.xbta.s2a.a" (13) + 8 位 hex = 21。
     */
    fun newPackageName(seed: String): String {
        val h = seed.replace("-", "").lowercase()
        val hex = (h + "00000000").substring(0, 8)
        return "cc.xbta.s2a.a$hex"
    }

    /** 用户自定义优先，留空则自动生成 */
    fun resolvePackage(task: Task): String {
        val c = task.packageName.trim()
        return if (c.isEmpty()) newPackageName(task.id) else c
    }

    /** 返回 null 表示合法 */
    fun validatePackage(pkg: String): String? {
        if (pkg.isEmpty()) return null
        if (pkg.length > 100) return "包名太长（最多 100 个字符）"
        if (!pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
            return "需字母开头、至少两段、以点分隔，例如 com.example.myapp"
        }
        return null
    }

    /**
     * 改 resources.arsc 里 package chunk 的 name 字段（第 12 字节起 256 字节的 UTF-16 定长数组）。
     * 定长写入 → 支持任意长度的自定义包名，不用等长替换。
     */
    private fun setArscPackage(data: ByteArray, newPkg: String): ByteArray {
        val out = data.copyOf()
        var pos = if (data.size >= 8) u16(data, 2) else 0
        while (pos + 8 <= out.size) {
            val type = u16(out, pos)
            val size = i32(out, pos + 4)
            if (size <= 0 || pos + size > out.size) break
            if (type == 0x0200) {
                val nameBytes = newPkg.toByteArray(Charsets.UTF_16LE)
                val n = minOf(nameBytes.size, 254)
                System.arraycopy(nameBytes, 0, out, pos + 12, n)
                for (i in pos + 12 + n until pos + 12 + 256) out[i] = 0
                return out
            }
            pos += size
        }
        return replacePackageEquals(out, newPkg)
    }

    private fun u16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)

    private fun i32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xff) or
            ((d[o + 1].toInt() and 0xff) shl 8) or
            ((d[o + 2].toInt() and 0xff) shl 16) or
            ((d[o + 3].toInt() and 0xff) shl 24)

    private fun isIconEntry(n: String): Boolean =
        n.startsWith("res/mipmap-") && n.endsWith("/ic_launcher.png")

    private fun isTemplateSignatureFile(n: String): Boolean {
        if (!n.startsWith("META-INF/")) return false
        return n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") ||
            n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF", ignoreCase = true)
    }

    private fun replacePackageEquals(data: ByteArray, newPkg: String): ByteArray {
        var out = replaceAll(data, TPL_PKG.toByteArray(Charsets.UTF_8), newPkg.toByteArray(Charsets.UTF_8))
        out = replaceAll(out, TPL_PKG.toByteArray(Charsets.UTF_16LE), newPkg.toByteArray(Charsets.UTF_16LE))
        return out
    }

    private fun replaceAll(src: ByteArray, from: ByteArray, to: ByteArray): ByteArray {
        if (from.isEmpty() || from.size != to.size) return src
        val out = ByteArrayOutputStream(src.size + 16)
        var i = 0
        while (i < src.size) {
            if (i + from.size <= src.size && matches(src, i, from)) {
                out.write(to)
                i += from.size
            } else {
                out.write(src[i].toInt() and 0xff)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun matches(d: ByteArray, at: Int, pat: ByteArray): Boolean {
        for (j in pat.indices) {
            if (d[at + j] != pat[j]) return false
        }
        return true
    }
}
