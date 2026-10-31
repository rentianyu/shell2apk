package cc.xbta.shell2apk

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** 「试运行」：在生成器自己的进程里直接执行一次，方便验证配置是否正确。 */
object ExecEngine {

    fun run(ctx: Activity, task: Task, onDone: (String) -> Unit) {
        when (task.kind) {
            Task.KIND_URL -> {
                try {
                    openUrl(ctx, task.value)
                    onDone("已发送打开请求")
                } catch (t: Throwable) {
                    onDone("失败: " + (t.message ?: t.javaClass.simpleName))
                }
            }
            Task.KIND_APP -> {
                try {
                    val i = ctx.packageManager.getLaunchIntentForPackage(task.value.trim())
                    if (i == null) onDone("未找到应用：" + task.value) else {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                        onDone("已启动")
                    }
                } catch (t: Throwable) {
                    onDone("失败: " + (t.message ?: t.javaClass.simpleName))
                }
            }
            Task.KIND_INTENT -> {
                try {
                    fireIntent(ctx, task)
                    onDone("已发送 Intent")
                } catch (t: Throwable) {
                    onDone("失败: " + (t.message ?: t.javaClass.simpleName))
                }
            }
            else -> shell(task, onDone)
        }
    }

    private fun openUrl(ctx: Activity, raw0: String) {
        val raw = raw0.trim()
        val uri = if (raw.contains("://") || raw.startsWith("mailto:") || raw.startsWith("tel:") ||
            raw.startsWith("sms:") || raw.startsWith("geo:") || raw.startsWith("market:")
        ) Uri.parse(raw) else Uri.parse("https://$raw")
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun fireIntent(ctx: Activity, task: Task) {
        val i = Intent()
        if (task.action.isNotBlank()) i.action = task.action.trim()
        val uri = task.value.trim()
        if (uri.isNotBlank()) i.data = Uri.parse(uri)
        if (task.component.isNotBlank()) {
            val p = task.component.substringBefore("/")
            var c = task.component.substringAfter("/", "")
            if (c.startsWith(".")) c = p + c
            if (c.isNotEmpty()) i.component = ComponentName(p, c)
        }
        if (task.extras.isNotBlank()) {
            try {
                val obj = JSONObject(task.extras)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (val v = obj.get(k)) {
                        is Boolean -> i.putExtra(k, v)
                        is Int -> i.putExtra(k, v)
                        is Long -> i.putExtra(k, v)
                        is Double -> i.putExtra(k, v)
                        else -> i.putExtra(k, v.toString())
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun shell(task: Task, onDone: (String) -> Unit) {
        val cmd = task.value
        val timeout = task.timeoutSec.coerceIn(1, 600)
        Thread {
            val sb = StringBuilder()
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val rt = Thread {
                    try {
                        BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                            val buf = CharArray(4096)
                            while (true) {
                                val n = r.read(buf)
                                if (n <= 0) break
                                synchronized(sb) { if (sb.length < 200000) sb.append(buf, 0, n) }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                rt.start()
                val finished = p.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    synchronized(sb) { sb.append("\n[超时 ").append(timeout).append("s]") }
                }
                rt.join(1500)
                val code = try {
                    p.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                synchronized(sb) { sb.append("\n\nexit=").append(code) }
            } catch (t: Throwable) {
                synchronized(sb) { sb.append("执行异常: ").append(t.toString()) }
            }
            onDone(synchronized(sb) { sb.toString() })
        }.apply { isDaemon = true }.start()
    }
}
