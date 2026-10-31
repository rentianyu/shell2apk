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
 *  4. res/mipmap-*/ic_launcher.png 换成生成的图标
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
        val pkg = newPackageName(task.id)
        val label = task.name.trim().ifBlank { "Shell2APK" }
        val cfg = task.toRunnerConfig().toByteArray(Charsets.UTF_8)
        val iconText = task.iconText.trim().ifBlank { label.take(1) }

        val result = ArrayList<ZipEntryData>(entries.size)
        for (e in entries) {
            val n = e.name
            when {
                n == "AndroidManifest.xml" -> {
                    val edited = AxmlEditor.replaceStrings(e.data, mapOf(TPL_PKG to pkg, TPL_LABEL to label))
                    result.add(ZipEntryData(n, 8, edited))
                }
                n == "resources.arsc" -> result.add(ZipEntryData(n, 0, replacePackageEquals(e.data, pkg)))
                n == "assets/config.json" -> result.add(ZipEntryData(n, 8, cfg))
                isTemplateSignatureFile(n) -> {
                    // 丢掉模板的 v1 签名，后面统一重新签
                }
                n.startsWith("res/mipmap-anydpi") -> {
                    // 丢掉自适应图标 XML，避免遮住我们生成的 PNG
                }
                n.startsWith("res/mipmap-") && n.endsWith("/ic_launcher.png") -> {
                    val dpi = n.removePrefix("res/mipmap-").substringBefore("/")
                    result.add(ZipEntryData(n, 0, IconGen.render(iconText, task.kind, IconGen.sizeFor(dpi))))
                }
                else -> result.add(e)
            }
        }

        val unsigned = File(ctx.cacheDir, "unsigned.apk")
        ZipKit.write(result, unsigned)
        KeystoreProvider.sign(ctx, unsigned, outFile)
        unsigned.delete()
    }

    /** 生成固定 22 字符的唯一包名，与模板包名等长，便于 arsc 原地替换 */
    fun newPackageName(seed: String): String {
        val h = seed.replace("-", "").lowercase()
        val hex = (h + "0000000000000").substring(0, 9)
        return "cc.xbta.s2a.a$hex"
    }

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
