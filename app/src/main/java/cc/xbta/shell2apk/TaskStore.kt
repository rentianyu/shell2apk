package cc.xbta.shell2apk

import android.content.Context
import org.json.JSONArray

/** 用 SharedPreferences 存任务列表（JSON）。 */
class TaskStore(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("shell2apk", Context.MODE_PRIVATE)

    fun load(): MutableList<Task> {
        val out = mutableListOf<Task>()
        val raw = sp.getString(KEY, null) ?: return seed()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(Task.fromJson(arr.getJSONObject(i)))
        } catch (t: Throwable) {
            return seed()
        }
        return out
    }

    fun save(list: List<Task>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun seed(): MutableList<Task> {
        val demo = Task(
            name = "示例：查看系统信息",
            iconText = "i",
            kind = Task.KIND_SHELL,
            value = "echo \"Android $(getprop ro.build.version.release)\"\nuname -a",
            showOutput = true,
            timeoutSec = 20
        )
        save(listOf(demo))
        return mutableListOf(demo)
    }

    companion object {
        private const val KEY = "tasks"
    }
}
