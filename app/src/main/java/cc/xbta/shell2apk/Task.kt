package cc.xbta.shell2apk

import org.json.JSONObject
import java.util.UUID

/** 首页的文件夹（任务分组） */
data class Folder(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var expanded: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("expanded", expanded)
    }

    companion object {
        fun fromJson(o: JSONObject): Folder = Folder(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "文件夹"),
            expanded = o.optBoolean("expanded", true)
        )
    }
}

/**
 * 一个「动作」定义：生成出来的 APK 安装到手机后，点开就执行它。
 */
data class Task(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var iconText: String = "",
    var kind: String = KIND_SHELL,
    var value: String = "",
    var folderId: String = "",
    var action: String = "",
    var component: String = "",
    var extras: String = "",
    var showOutput: Boolean = true,
    var timeoutSec: Int = 20,
    var packageName: String = "",
    /** shell 命令的执行身份：shell（本应用）/ shizuku（ADB 权限）/ root（su） */
    var exec: String = EXEC_SHELL
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("iconText", iconText)
        put("kind", kind)
        put("value", value)
        put("folderId", folderId)
        put("action", action)
        put("component", component)
        put("extras", extras)
        put("showOutput", showOutput)
        put("timeoutSec", timeoutSec)
        put("packageName", packageName)
        put("exec", exec)
    }

    /** 写进被生成 APK 的 assets/config.json */
    fun toRunnerConfig(): String = JSONObject().apply {
        put("name", name)
        put("kind", kind)
        put("value", value)
        put("action", action)
        put("component", component)
        put("extras", extras)
        put("showOutput", showOutput)
        put("timeoutSec", timeoutSec)
        put("exec", exec)
    }.toString()

    companion object {
        const val KIND_SHELL = "shell"
        const val KIND_URL = "url"
        const val KIND_ACTIVITY = "activity"
        const val KIND_INTENT = "intent"
        const val KIND_BROADCAST = "broadcast"

        const val EXEC_SHELL = "shell"
        const val EXEC_SHIZUKU = "shizuku"
        const val EXEC_ROOT = "root"

        val EXECS = listOf(EXEC_SHELL, EXEC_SHIZUKU, EXEC_ROOT)

        fun execLabel(e: String): String = when (e) {
            EXEC_SHIZUKU -> "Shizuku（ADB 权限）"
            EXEC_ROOT -> "Root（su）"
            else -> "普通（本应用权限）"
        }

        /** 下拉框顺序，与 kindLabel 一一对应 */
        val KINDS = listOf(KIND_SHELL, KIND_URL, KIND_ACTIVITY, KIND_INTENT, KIND_BROADCAST)

        fun kindLabel(kind: String): String = when (kind) {
            KIND_URL -> "打开链接"
            KIND_ACTIVITY -> "启动活动"
            KIND_INTENT -> "发送 Intent"
            KIND_BROADCAST -> "发送广播"
            else -> "Shell 命令"
        }

        fun fromJson(o: JSONObject): Task {
            // 兼容旧数据：早期版本把「活动」叫 app（只存包名）
            val rawKind = o.optString("kind", KIND_SHELL)
            val kind = if (rawKind == "app") KIND_ACTIVITY else rawKind
            return Task(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                iconText = o.optString("iconText", ""),
                kind = kind,
                value = o.optString("value", ""),
                folderId = o.optString("folderId", ""),
                action = o.optString("action", ""),
                component = o.optString("component", ""),
                extras = o.optString("extras", ""),
                showOutput = o.optBoolean("showOutput", true),
                timeoutSec = o.optInt("timeoutSec", 20),
                packageName = o.optString("packageName", ""),
                exec = o.optString("exec", EXEC_SHELL)
            )
        }
    }
}
