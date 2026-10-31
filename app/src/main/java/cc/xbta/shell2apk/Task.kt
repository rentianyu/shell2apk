package cc.xbta.shell2apk

import org.json.JSONObject
import java.util.UUID

/**
 * 一个「动作」定义：生成出来的 APK 安装到手机后，点开就执行它。
 */
data class Task(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var iconText: String = "",
    var kind: String = KIND_SHELL,
    var value: String = "",
    var action: String = "",
    var component: String = "",
    var extras: String = "",
    var showOutput: Boolean = true,
    var timeoutSec: Int = 20,
    var packageName: String = ""
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("iconText", iconText)
        put("kind", kind)
        put("value", value)
        put("action", action)
        put("component", component)
        put("extras", extras)
        put("showOutput", showOutput)
        put("timeoutSec", timeoutSec)
        put("packageName", packageName)
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
    }.toString()

    companion object {
        const val KIND_SHELL = "shell"
        const val KIND_URL = "url"
        const val KIND_INTENT = "intent"
        const val KIND_APP = "app"

        fun kindLabel(kind: String): String = when (kind) {
            KIND_URL -> "打开链接"
            KIND_APP -> "启动应用"
            KIND_INTENT -> "发送 Intent"
            else -> "Shell 命令"
        }

        fun fromJson(o: JSONObject): Task = Task(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            iconText = o.optString("iconText", ""),
            kind = o.optString("kind", KIND_SHELL),
            value = o.optString("value", ""),
            action = o.optString("action", ""),
            component = o.optString("component", ""),
            extras = o.optString("extras", ""),
            showOutput = o.optBoolean("showOutput", true),
            timeoutSec = o.optInt("timeoutSec", 20),
            packageName = o.optString("packageName", "")
        )
    }
}
