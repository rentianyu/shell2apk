package cc.xbta.shell2apk

import android.app.AlertDialog
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cc.xbta.shell2apk.databinding.DialogTaskBinding

/** 编辑 / 新建任务的表单弹窗 */
class TaskEditorDialog(
    private val act: AppCompatActivity,
    original: Task,
    private val folders: List<Folder>,
    private val onSaved: (Task) -> Unit
) {

    private val task = original.copy()
    private val b: DialogTaskBinding
    private val dlg: AlertDialog
    private var kind: String = task.kind
    private var folderId: String = task.folderId

    init {
        val view = LayoutInflater.from(act).inflate(R.layout.dialog_task, null)
        b = DialogTaskBinding.bind(view)
        dlg = AlertDialog.Builder(act).setView(view).create()

        setupKind()
        setupFolder()
        bindValues()

        b.btnCancel.setOnClickListener { dlg.dismiss() }
        b.btnTest.setOnClickListener { runTest() }
        b.btnBuild.setOnClickListener { save() }
        b.btnPickActivity.setOnClickListener {
            AppPicker.pick(act) { spec -> b.inputValue.setText(spec) }
        }

        dlg.setOnShowListener {
            val dm = act.resources.displayMetrics
            dlg.window?.setLayout((dm.widthPixels * 0.95f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun show() = dlg.show()

    // ---------------- 下拉 ----------------

    private fun setupKind() {
        val labels = Task.KINDS.map { Task.kindLabel(it) }.toTypedArray()
        b.inputKind.setAdapter(ArrayAdapter(act, android.R.layout.simple_list_item_1, labels))
        b.inputKind.setText(Task.kindLabel(task.kind), false)
        b.inputKind.setOnItemClickListener { _, _, pos, _ ->
            applyKind(Task.KINDS[pos])
        }
        applyKind(task.kind)
    }

    private fun setupFolder() {
        val names = mutableListOf("（未分组）")
        folders.forEach { names.add(it.name) }
        b.inputFolder.setAdapter(ArrayAdapter(act, android.R.layout.simple_list_item_1, names.toTypedArray()))
        val idx = folders.indexOfFirst { it.id == task.folderId }
        folderId = if (idx >= 0) folders[idx].id else ""
        b.inputFolder.setText(if (idx >= 0) folders[idx].name else "（未分组）", false)
        b.inputFolder.setOnItemClickListener { _, _, pos, _ ->
            folderId = if (pos == 0) "" else folders[pos - 1].id
            b.inputFolder.setText(if (pos == 0) "（未分组）" else folders[pos - 1].name, false)
        }
    }

    private fun bindValues() {
        b.inputName.setText(task.name)
        b.inputPackage.setText(task.packageName)
        b.inputIcon.setText(task.iconText)
        b.inputValue.setText(task.value)
        b.inputAction.setText(task.action)
        b.inputComponent.setText(task.component)
        b.inputExtras.setText(task.extras)
        b.switchOutput.isChecked = task.showOutput
        b.inputTimeout.setText(task.timeoutSec.toString())
    }

    // ---------------- 类型联动 ----------------

    private fun applyKind(k: String) {
        kind = k
        val isShell = k == Task.KIND_SHELL
        val isAdv = k == Task.KIND_INTENT || k == Task.KIND_BROADCAST
        val isCast = k == Task.KIND_BROADCAST

        b.groupShell.visibility = if (isShell) View.VISIBLE else View.GONE
        b.groupIntent.visibility = if (isAdv) View.VISIBLE else View.GONE
        b.btnPickActivity.visibility = if (k == Task.KIND_ACTIVITY) View.VISIBLE else View.GONE
        b.layoutValue.visibility = if (isCast) View.GONE else View.VISIBLE

        b.layoutValue.setHint(
            when (k) {
                Task.KIND_URL -> "URL / URL Scheme（https://… 或 weixin://）"
                Task.KIND_ACTIVITY -> "活动：包名/类名（可点下面按钮选择）"
                Task.KIND_INTENT -> "uri（可留空）"
                else -> "Shell 命令（可多行）"
            }
        )
        b.layoutAction.setHint(if (isCast) "广播 action（必填）" else "action（如 android.intent.action.VIEW）")
        b.layoutComponent.setHint(if (isCast) "目标包名（可选）" else "component（包名/类名）")

        b.inputValue.setMinLines(if (isShell) 3 else 1)
        b.inputValue.setSingleLine(!isShell)
        b.inputKind.setText(Task.kindLabel(k), false)
    }

    // ---------------- 取值 ----------------

    private fun collect(): Task {
        task.name = b.inputName.text?.toString()?.trim() ?: ""
        task.packageName = b.inputPackage.text?.toString()?.trim() ?: ""
        task.iconText = b.inputIcon.text?.toString()?.trim() ?: ""
        task.kind = kind
        task.folderId = folderId
        task.value = b.inputValue.text?.toString() ?: ""
        task.action = b.inputAction.text?.toString()?.trim() ?: ""
        task.component = b.inputComponent.text?.toString()?.trim() ?: ""
        task.extras = b.inputExtras.text?.toString()?.trim() ?: ""
        task.showOutput = b.switchOutput.isChecked
        task.timeoutSec = b.inputTimeout.text?.toString()?.trim()?.toIntOrNull() ?: 20
        return task
    }

    private fun validate(t: Task): Boolean {
        if (t.name.isBlank()) {
            b.layoutName.error = "请填写名称"
            return false
        }
        if (t.packageName.isNotBlank()) {
            val err = cc.xbta.shell2apk.apk.ApkBuilder.validatePackage(t.packageName)
            if (err != null) {
                b.layoutPackage.error = err
                return false
            }
        }
        b.layoutPackage.error = null
        return when (t.kind) {
            Task.KIND_BROADCAST -> {
                if (t.action.isBlank()) {
                    b.layoutAction.error = "广播必须填 action"
                    false
                } else true
            }
            Task.KIND_ACTIVITY -> {
                if (t.value.isBlank()) {
                    b.layoutValue.error = "请选择或填写活动"
                    false
                } else true
            }
            Task.KIND_INTENT -> true
            else -> {
                if (t.value.isBlank()) {
                    b.layoutValue.error = "请填写内容"
                    false
                } else true
            }
        }
    }

    private fun save() {
        val t = collect()
        if (!validate(t)) return
        onSaved(t)
        dlg.dismiss()
    }

    private fun runTest() {
        val t = collect()
        if (!validate(t)) return
        ExecEngine.run(act, t) { result ->
            act.runOnUiThread {
                val tv = TextView(act).apply {
                    text = if (result.isBlank()) "（无输出）" else result
                    setTextIsSelectable(true)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(28, 24, 28, 24)
                    movementMethod = ScrollingMovementMethod()
                    setTextColor(0xFFE6E9EC.toInt())
                    setBackgroundColor(0xFF14191E.toInt())
                }
                AlertDialog.Builder(act)
                    .setTitle("试运行结果")
                    .setView(tv)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }
}
