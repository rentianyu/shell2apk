package cc.xbta.shell2apk

import android.app.Activity
import android.app.AlertDialog
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cc.xbta.shell2apk.databinding.DialogTaskBinding

/** 编辑 / 新建任务的表单弹窗 */
class TaskEditorDialog(
    private val act: AppCompatActivity,
    original: Task,
    private val onSaved: (Task) -> Unit
) {

    private val task = original.copy()
    private val b: DialogTaskBinding
    private val dlg: AlertDialog

    init {
        val view = LayoutInflater.from(act).inflate(R.layout.dialog_task, null)
        b = DialogTaskBinding.bind(view)
        dlg = AlertDialog.Builder(act).setView(view).create()
        bindValues()
        b.groupKind.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) applyKind(kindOf(checkedId))
        }
        b.kindShell.isChecked = task.kind == Task.KIND_SHELL
        b.kindUrl.isChecked = task.kind == Task.KIND_URL
        b.kindApp.isChecked = task.kind == Task.KIND_APP
        b.kindIntent.isChecked = task.kind == Task.KIND_INTENT
        applyKind(task.kind)

        b.btnCancel.setOnClickListener { dlg.dismiss() }
        b.btnTest.setOnClickListener { runTest() }
        b.btnBuild.setOnClickListener { save() }

        dlg.setOnShowListener {
            val dm = act.resources.displayMetrics
            dlg.window?.setLayout((dm.widthPixels * 0.95f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun show() = dlg.show()

    private fun bindValues() {
        b.inputName.setText(task.name)
        b.inputIcon.setText(task.iconText)
        b.inputValue.setText(task.value)
        b.inputAction.setText(task.action)
        b.inputComponent.setText(task.component)
        b.inputExtras.setText(task.extras)
        b.switchOutput.isChecked = task.showOutput
        b.inputTimeout.setText(task.timeoutSec.toString())
    }

    private fun kindOf(id: Int): String = when (id) {
        R.id.kindUrl -> Task.KIND_URL
        R.id.kindApp -> Task.KIND_APP
        R.id.kindIntent -> Task.KIND_INTENT
        else -> Task.KIND_SHELL
    }

    private fun currentKind(): String = when {
        b.kindUrl.isChecked -> Task.KIND_URL
        b.kindApp.isChecked -> Task.KIND_APP
        b.kindIntent.isChecked -> Task.KIND_INTENT
        else -> Task.KIND_SHELL
    }

    private fun applyKind(kind: String) {
        b.groupShell.visibility = if (kind == Task.KIND_SHELL) android.view.View.VISIBLE else android.view.View.GONE
        b.groupIntent.visibility = if (kind == Task.KIND_INTENT) android.view.View.VISIBLE else android.view.View.GONE
        b.layoutValue.setHint(
            when (kind) {
                Task.KIND_URL -> "URL / URL Scheme（如 https://… 或 weixin://）"
                Task.KIND_APP -> "应用包名（如 com.tencent.mm）"
                Task.KIND_INTENT -> "uri（可留空，如 tel:10086）"
                else -> "Shell 命令（多行可写多行命令）"
            }
        )
        b.inputValue.minLines = if (kind == Task.KIND_SHELL || kind == Task.KIND_INTENT) 3 else 1
        b.inputValue.isSingleLine = !(kind == Task.KIND_SHELL || kind == Task.KIND_INTENT)
    }

    private fun collect(): Task {
        task.name = b.inputName.text?.toString()?.trim() ?: ""
        task.iconText = b.inputIcon.text?.toString()?.trim() ?: ""
        task.kind = currentKind()
        task.value = b.inputValue.text?.toString() ?: ""
        task.action = b.inputAction.text?.toString()?.trim() ?: ""
        task.component = b.inputComponent.text?.toString()?.trim() ?: ""
        task.extras = b.inputExtras.text?.toString()?.trim() ?: ""
        task.showOutput = b.switchOutput.isChecked
        task.timeoutSec = b.inputTimeout.text?.toString()?.trim()?.toIntOrNull() ?: 20
        return task
    }

    private fun save() {
        val t = collect()
        if (t.name.isBlank()) {
            b.layoutName.error = "请填写名称"
            return
        }
        if (t.kind != Task.KIND_INTENT && t.value.isBlank()) {
            b.layoutValue.error = "请填写内容"
            return
        }
        onSaved(t)
        dlg.dismiss()
    }

    private fun runTest() {
        val t = collect()
        if (t.kind != Task.KIND_INTENT && t.value.isBlank()) {
            b.layoutValue.error = "内容为空，无法试运行"
            return
        }
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
