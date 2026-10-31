package cc.xbta.shell2apk

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** 「试运行」：在生成器自己的进程里直接执行一次，方便验证配置是否正确。 */
object ExecEngine {

    fun run(ctx: Activity, task: Task, onDone: (String) -> Unit) {
        try {
            when (task.kind) {
                Task.KIND_URL -> {
                    openUrl(ctx, task.value)
                    onDone("已发送打开请求")
                }
                Task.KIND_ACTIVITY -> onDone(startActivitySpec(ctx, task.value))
                Task.KIND_INTENT -> {
                    fireIntent(ctx, task)
                    onDone("已发送 Intent")
                }
                Task.KIND_BROADCAST -> {
                    fireBroadcast(ctx, task)
                    onDone("已发送广播")
                }
                else -> {
                    if (task.exec == Task.EXEC_SHIZUKU) {
                        val st = shizukuState(ctx)
                        if (st != null) {
                            onDone(st)
                            return
                        }
                    }
                    shell(task, onDone, task.exec)
                }
            }
        } catch (t: Throwable) {
            onDone("失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    // ---------------- helpers ----------------

    fun componentOf(spec: String): ComponentName? {
        val s = spec.trim()
        if (s.isEmpty() || !s.contains("/")) return null
        val p = s.substringBefore("/").trim()
        var c = s.substringAfter("/").trim()
        if (p.isEmpty() || c.isEmpty()) return null
        if (c.startsWith(".")) c = p + c
        return ComponentName(p, c)
    }

    fun putExtras(intent: Intent, extrasRaw: String) {
        if (extrasRaw.isBlank()) return
        try {
            val obj = JSONObject(extrasRaw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = obj.get(k)) {
                    is Boolean -> intent.putExtra(k, v)
                    is Int -> intent.putExtra(k, v)
                    is Long -> intent.putExtra(k, v)
                    is Double -> intent.putExtra(k, v)
                    else -> intent.putExtra(k, v.toString())
                }
            }
        } catch (ignored: Throwable) {
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

    /** value 支持两种写法："包名/类名"（启动指定 Activity）或 "包名"（启动默认入口） */
    private fun startActivitySpec(ctx: Activity, spec: String): String {
        val s = spec.trim()
        if (s.isEmpty()) return "未选择活动"
        val cn = componentOf(s)
        val i = if (cn != null) Intent().setComponent(cn)
        else ctx.packageManager.getLaunchIntentForPackage(s)
        if (i == null) return "未找到：$s"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "已启动 " + (cn?.flattenToShortString() ?: s)
    }

    private fun fireIntent(ctx: Activity, task: Task) {
        val i = Intent()
        if (task.action.isNotBlank()) i.action = task.action.trim()
        val uri = task.value.trim()
        if (uri.isNotBlank()) i.data = Uri.parse(uri)
        val cn = componentOf(task.component)
        if (cn != null) {
            i.component = cn
        } else if (task.component.isNotBlank()) {
            i.setPackage(task.component.trim())
        }
        putExtras(i, task.extras)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun fireBroadcast(ctx: Activity, task: Task) {
        val action = task.action.trim()
        require(action.isNotEmpty()) { "广播需要填 action" }
        val i = Intent(action)
        if (task.component.isNotBlank()) i.setPackage(task.component.trim())
        putExtras(i, task.extras)
        ctx.sendBroadcast(i)
    }

    /**
     * 检查 Shizuku 可用性。返回 null 表示已授权可直接执行，返回字符串表示需要提示用户的原因。
     */
    private fun shizukuState(ctx: Activity): String? {
        if (!Shizuku.pingBinder()) return "Shizuku 未运行：请先打开 Shizuku 启动服务"
        if (Shizuku.isPreV11()) return "Shizuku 版本太旧，请升级到 11 以上"
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return "Shizuku 权限已被拒绝：请在 Shizuku 应用里允许 Shell2APK"
        }
        try {
            Shizuku.requestPermission(2001)
            return "已弹出 Shizuku 授权请求，允许后再点一次「试运行」"
        } catch (t: Throwable) {
            return "请求 Shizuku 授权失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun shell(task: Task, onDone: (String) -> Unit, exec: String) {
        val cmd = task.value
        val timeout = task.timeoutSec.coerceIn(1, 600)
        Thread {
            val sb = StringBuilder()
            try {
                val p: java.lang.Process = when (exec) {
                    Task.EXEC_ROOT -> ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    Task.EXEC_SHIZUKU -> Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", cmd), null, null)
                    else -> ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
                }
                val rt = Thread { pump(p.inputStream, sb) }
                val et = Thread { pump(p.errorStream, sb) }
                rt.isDaemon = true
                et.isDaemon = true
                rt.start()
                et.start()
                val finished = p.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    try {
                        p.destroyForcibly()
                    } catch (ignored: Throwable) {
                    }
                    synchronized(sb) { sb.append("\n[超时 ").append(timeout).append("s]") }
                }
                rt.join(1500)
                et.join(1500)
                val code = try {
                    p.exitValue()
                } catch (e: IllegalThreadStateException) {
                    -1
                }
                synchronized(sb) { sb.append("\n\nexit=").append(code) }
            } catch (t: Throwable) {
                synchronized(sb) { sb.append("执行异常: ").append(t.toString()) }
                if (exec == Task.EXEC_ROOT) {
                    synchronized(sb) { sb.append("\n（未获取 root 权限：su 不可用或已拒绝授权）") }
                }
            }
            onDone(synchronized(sb) { sb.toString() })
        }.apply { isDaemon = true }.start()
    }

    private fun pump(input: java.io.InputStream, sb: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input)).use { r ->
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
}
