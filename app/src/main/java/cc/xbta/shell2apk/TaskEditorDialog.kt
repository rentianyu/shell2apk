package cc.xbta.shell2apk

import android.app.AlertDialog
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import cc.xbta.shell2apk.databinding.DialogTaskBinding

/** 编辑 / 新建任务的表单弹窗 */
class TaskEditorDialog(
    private val act: AppCompatActivity,
    original: Task,
    private val folders: MutableList<Folder>,
    /** 新建文件夹：由调用方负责落库，返回新对象（失败返回 null） */
    private val onCreateFolder: ((String) -> Folder?)? = null,
    private val onSaved: (Task) -> Unit
) {

    companion object {
        private const val NEW_FOLDER = "＋ 新建文件夹…"
    }

    private val task = original.copy()
    private val b: DialogTaskBinding
    private val dlg: AlertDialog
    private var kind: String = task.kind
    private var folderId: String = task.folderId
    private var exec: String = task.exec

    init {
        val view = LayoutInflater.from(act).inflate(R.layout.dialog_task, null)
        b = DialogTaskBinding.bind(view)
        dlg = AlertDialog.Builder(act).setView(view).create()

        try {
            setupKind()
            setupExec()
            setupFolder()
            bindValues()
        } catch (t: Throwable) {
            // 兜底：初始化出问题也不要把整个 App 带走
            android.util.Log.e("S2A", "editor init failed", t)
            Toast.makeText(act, "界面初始化异常：" + t, Toast.LENGTH_LONG).show()
        }

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

    private fun setupExec() {
        b.inputExec.setText(Task.execLabel(exec))
        b.rowExec.setOnClickListener { pickExec() }
    }

    /**
     * 执行身份：单选对话框。
     * 不设 message（message 会和列表抢空间，某些机型上列表项点不动），说明并入选项文字。
     */
    private fun pickExec() {
        val labels = arrayOf(
            "普通 — 本应用自身权限",
            "Shizuku — ADB 权限（无需 root）",
            "Root — su（需设备已 root）"
        )
        val idx = Task.EXECS.indexOf(exec).coerceAtLeast(0)
        val d = AlertDialog.Builder(act)
            .setTitle("执行身份")
            .setSingleChoiceItems(labels, idx) { dd, which ->
                exec = Task.EXECS[which]
                b.inputExec.setText(Task.execLabel(exec))
                dd.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
        d.show()
    }

    private fun setupFolder() {
        val cur = folderId.ifBlank { task.folderId }
        val idx = folders.indexOfFirst { it.id == cur }
        folderId = if (idx >= 0) folders[idx].id else ""
        b.inputFolder.setText(if (idx >= 0) folders[idx].name else "（未分组）")
        b.rowFolder.setOnClickListener { pickFolder() }
    }

    /** 文件夹：点击弹单选对话框（末项是新建文件夹） */
    private fun pickFolder() {
        val names = mutableListOf("（未分组）")
        folders.forEach { names.add(it.name) }
        names.add(NEW_FOLDER)
        val sel = folders.indexOfFirst { it.id == folderId } + 1
        AlertDialog.Builder(act)
            .setTitle("文件夹")
            .setSingleChoiceItems(names.toTypedArray(), if (sel < 0) 0 else sel) { d, pos ->
                when {
                    pos == 0 -> {
                        folderId = ""
                        b.inputFolder.setText("（未分组）")
                        d.dismiss()
                    }
                    pos == names.size - 1 -> {
                        d.dismiss()
                        askNewFolder()
                    }
                    else -> {
                        folderId = folders[pos - 1].id
                        b.inputFolder.setText(folders[pos - 1].name)
                        d.dismiss()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 在新建任务的同时新建文件夹，建完自动选中 */
    private fun askNewFolder() {
        val et = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "文件夹名称"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(act)
            .setTitle("新建文件夹")
            .setView(et)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(act, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val f = onCreateFolder?.invoke(name)
                if (f == null) {
                    Toast.makeText(act, "无法新建文件夹", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                folderId = f.id
                setupFolder()
                b.inputFolder.setText(f.name)
                Toast.makeText(act, "已创建并选中：" + f.name, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
        b.inputExec.setText(Task.execLabel(task.exec))
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
        task.exec = exec
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
