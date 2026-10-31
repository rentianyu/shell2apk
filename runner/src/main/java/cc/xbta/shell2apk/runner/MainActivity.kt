package cc.xbta.shell2apk.runner

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 被 shell2apk 生成出来的 APK 的入口。
 * 配置来自 assets/config.json，由生成器在打包时写入。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = loadConfig()
        val kind = cfg.optString("kind", "shell")
        try {
            when (kind) {
                "url" -> {
                    openUrl(cfg.optString("value"))
                    finish()
                }
                "app" -> {
                    openApp(cfg.optString("value"))
                    finish()
                }
                "intent" -> {
                    fireIntent(cfg)
                    finish()
                }
                "none" -> finish()
                else -> runShell(cfg)
            }
        } catch (t: Throwable) {
            toast("执行失败：" + (t.message ?: t.javaClass.simpleName))
            finish()
        }
    }

    private fun loadConfig(): JSONObject {
        return try {
            val text = assets.open("config.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    // ---------------- shell ----------------

    private fun runShell(cfg: JSONObject) {
        val cmd = cfg.optString("value", "")
        val timeout = cfg.optInt("timeoutSec", 20).coerceIn(1, 600)
        val showOutput = cfg.optBoolean("showOutput", true)
        val label = cfg.optString("name", "Shell2APK")
        Thread {
            val sb = StringBuilder()
            var exit = -1
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val readerThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = r.read(buf)
                                if (n <= 0) break
                                synchronized(sb) {
                                    if (sb.length < 200000) sb.append(buf, 0, n)
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                readerThread.start()
                val finished = p.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    synchronized(sb) { sb.append("\n[超时 ").append(timeout).append("s，进程已终止]") }
                }
                readerThread.join(2000)
                exit = try {
                    p.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
            } catch (t: Throwable) {
                synchronized(sb) { sb.append("执行异常: ").append(t.toString()) }
            }
            val out = synchronized(sb) { sb.toString() }
            runOnUiThread {
                if (showOutput) {
                    showOutputDialog(label, out, exit)
                } else {
                    toast("\u5B8C\u6210 (exit=$exit) " + summary(out))
                    finish()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun summary(out: String): String {
        val s = out.trim()
        return if (s.isEmpty()) "无输出" else if (s.length <= 120) s else s.substring(0, 120) + "…"
    }

    private fun showOutputDialog(label: String, out: String, exit: Int) {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val tv = TextView(this).apply {
            text = if (out.isEmpty()) "（无输出）" else out
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#E6E9EC"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14191E"))
            addView(
                tv,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), 0)
            addView(
                sv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (dm.heightPixels * 0.55f).toInt()
                )
            )
        }
        val d = AlertDialog.Builder(this)
            .setTitle("$label  ·  exit=$exit")
            .setView(wrap)
            .setPositiveButton("关闭") { _, _ -> finish() }
            .create()
        d.setCancelable(false)
        d.show()
    }

    // ---------------- url ----------------

    private fun openUrl(value: String) {
        val raw = value.trim()
        if (raw.isEmpty()) {
            toast("未配置 URL")
            return
        }
        val uri = if (raw.contains("://") || raw.startsWith("mailto:") || raw.startsWith("tel:") ||
            raw.startsWith("sms:") || raw.startsWith("geo:") || raw.startsWith("market:")
        ) Uri.parse(raw) else Uri.parse("https://$raw")
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------------- app ----------------

    private fun openApp(pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) {
            toast("未配置包名")
            return
        }
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i == null) {
            toast("未找到应用：$p")
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    // ---------------- intent ----------------

    private fun fireIntent(cfg: JSONObject) {
        val i = Intent()
        val action = cfg.optString("action", "").trim()
        if (action.isNotEmpty()) i.action = action
        val uri = cfg.optString("uri", cfg.optString("value", "")).trim()
        if (uri.isNotEmpty()) i.data = Uri.parse(uri)
        val type = cfg.optString("mimeType", "").trim()
        if (type.isNotEmpty() && uri.isNotEmpty()) i.setDataAndType(Uri.parse(uri), type)

        val component = cfg.optString("component", "").trim()
        if (component.isNotEmpty()) {
            val sep = if (component.contains("/")) "/" else null
            if (sep != null) {
                val p = component.substringBefore("/")
                var c = component.substringAfter("/")
                if (c.startsWith(".")) c = p + c
                i.component = ComponentName(p, c)
            }
        }
        val pkg = cfg.optString("package", "").trim()
        if (pkg.isNotEmpty() && i.component == null) i.setPackage(pkg)

        val extrasRaw = cfg.optString("extras", "").trim()
        if (extrasRaw.isNotEmpty()) {
            try {
                val obj = JSONObject(extrasRaw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (val v = obj.get(k)) {
                        is Boolean -> i.putExtra(k, v)
                        is Int -> i.putExtra(k, v)
                        is Long -> i.putExtra(k, v)
                        is Double -> i.putExtra(k, v)
                        else -> {
                            val s = v.toString()
                            when {
                                s.startsWith("i:") -> i.putExtra(k, s.substring(2).toInt())
                                s.startsWith("l:") -> i.putExtra(k, s.substring(2).toLong())
                                s.startsWith("b:") -> i.putExtra(k, s.substring(2).toBoolean())
                                s.startsWith("f:") -> i.putExtra(k, s.substring(2).toFloat())
                                else -> i.putExtra(k, s)
                            }
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------------- helper ----------------

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) {
        }
    }
}
