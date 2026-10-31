package cc.xbta.shell2apk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cc.xbta.shell2apk.apk.ApkBuilder
import cc.xbta.shell2apk.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: TaskStore
    private lateinit var adapter: TaskAdapter
    private val items = mutableListOf<Task>()
    private val folders = mutableListOf<Folder>()
    private var latest: UpdateChecker.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyEdgeToEdge()

        store = TaskStore(this)
        items.addAll(store.load())
        folders.addAll(store.loadFolders())

        adapter = TaskAdapter(
            onFolderClick = { toggleFolder(it) },
            onFolderLongClick = { folderMenu(it) },
            onTaskClick = { openEditor(it) },
            onTaskBuild = { buildApk(it) },
            onTaskLongClick = { taskMenu(it) }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.toolbar.inflateMenu(R.menu.menu_main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new -> {
                    openEditor(Task())
                    true
                }
                R.id.action_new_folder -> {
                    askFolderName(null)
                    true
                }
                R.id.action_update -> {
                    checkUpdate(silent = false)
                    true
                }
                R.id.action_update_source -> {
                    showUpdateSourceDialog()
                    true
                }
                R.id.action_about -> {
                    showAbout()
                    true
                }
                else -> false
            }
        }
        b.updateBar.setOnClickListener { latest?.let { confirmUpdate(it) } }

        refresh()
        checkUpdate(silent = true)
    }

    // ---------------- 列表 ----------------

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val known = folders.map { it.id }.toSet()
        for (f in folders) {
            val inFolder = items.filter { it.folderId == f.id }
            rows.add(Row.FolderRow(f, inFolder.size))
            if (f.expanded) inFolder.forEach { rows.add(Row.TaskRow(it, true)) }
        }
        items.filter { it.folderId.isBlank() || it.folderId !in known }
            .forEach { rows.add(Row.TaskRow(it, false)) }
        return rows
    }

    private fun refresh() {
        adapter.submit(buildRows())
        val empty = items.isEmpty() && folders.isEmpty()
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        b.recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun toggleFolder(f: Folder) {
        f.expanded = !f.expanded
        store.saveFolders(folders)
        refresh()
    }

    // ---------------- 文件夹 ----------------

    private fun askFolderName(existing: Folder?) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "文件夹名称"
            setText(existing?.name ?: "")
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建文件夹" else "重命名文件夹")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名称不能为空")
                    return@setPositiveButton
                }
                if (existing == null) {
                    folders.add(Folder(name = name, expanded = true))
                } else {
                    existing.name = name
                }
                store.saveFolders(folders)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun folderMenu(f: Folder) {
        val opts = arrayOf("重命名", "删除文件夹")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> askFolderName(f)
                    else -> deleteFolder(f)
                }
            }
            .show()
    }

    private fun deleteFolder(f: Folder) {
        val n = items.count { it.folderId == f.id }
        AlertDialog.Builder(this)
            .setTitle("删除文件夹")
            .setMessage("删除「${f.name}」？其中 $n 个任务会回到未分组，不会被删掉。")
            .setPositiveButton("删除") { _, _ ->
                folders.removeAll { it.id == f.id }
                items.forEach { if (it.folderId == f.id) it.folderId = "" }
                store.saveFolders(folders)
                store.save(items)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 任务 ----------------

    private fun openEditor(task: Task) {
        TaskEditorDialog(
            this, task, folders,
            onCreateFolder = { name ->
                val f = Folder(name = name, expanded = true)
                folders.add(f)
                store.saveFolders(folders)
                refresh()
                f
            }
        ) { saved ->
            val idx = items.indexOfFirst { it.id == saved.id }
            if (idx >= 0) items[idx] = saved else items.add(saved)
            store.save(items)
            refresh()
        }.show()
    }

    private fun taskMenu(t: Task) {
        val opts = arrayOf("编辑", "生成 APK", "删除任务")
        AlertDialog.Builder(this)
            .setTitle(t.name.ifBlank { "未命名任务" })
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> openEditor(t)
                    1 -> buildApk(t)
                    else -> confirmDeleteTask(t)
                }
            }
            .show()
    }

    private fun confirmDeleteTask(t: Task) {
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage("确定删除「${t.name.ifBlank { "未命名任务" }}」？")
            .setPositiveButton("删除") { _, _ ->
                items.removeAll { it.id == t.id }
                store.save(items)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildApk(task: Task) {
        if (task.name.isBlank()) {
            toast("请先填写名称")
            return
        }
        if (task.packageName.isNotBlank()) {
            val err = ApkBuilder.validatePackage(task.packageName)
            if (err != null) {
                toast(err)
                openEditor(task)
                return
            }
        }
        val pkg = ApkBuilder.resolvePackage(task)
        val pd = AlertDialog.Builder(this)
            .setMessage("正在打包 APK…")
            .setCancelable(false)
            .create()
        pd.show()
        Thread {
            var ok = false
            var payload = ""
            try {
                val base = getExternalFilesDir(null) ?: filesDir
                val dir = File(base, "generated")
                dir.mkdirs()
                val safe = task.name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "app" }
                val out = File(dir, "$safe.apk")
                if (out.exists()) out.delete()
                ApkBuilder.build(applicationContext, task, out)
                ok = true
                payload = out.absolutePath
            } catch (t: Throwable) {
                payload = (t.message ?: t.toString())
                if (payload.length > 1500) payload = payload.substring(0, 1500)
            }
            runOnUiThread {
                pd.dismiss()
                if (ok) showResult(File(payload), pkg) else showError(payload)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showResult(f: File, pkg: String) {
        val kb = f.length() / 1024.0
        AlertDialog.Builder(this)
            .setTitle("生成成功")
            .setMessage("${f.name}\n${String.format("%.1f", kb)} KB\n\n包名：$pkg\n\n位置：\n${f.parent}")
            .setPositiveButton("安装") { _, _ -> install(f) }
            .setNeutralButton("分享") { _, _ -> share(f) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("出错")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------- 更新 ----------------

    private fun checkUpdate(silent: Boolean) {
        if (!silent) toast("正在检查更新…")
        Thread {
            try {
                val rel = UpdateChecker.check(applicationContext)
                val local = UpdateChecker.localVersionName(applicationContext)
                val newer = UpdateChecker.isNewer(rel.versionName, local)
                runOnUiThread {
                    latest = if (newer) rel else null
                    b.updateBar.visibility = if (newer) View.VISIBLE else View.GONE
                    if (newer) b.updateText.text = "发现新版本 ${rel.versionName}（当前 $local）· 点此更新"
                    if (!silent) toast(if (newer) "发现新版本 ${rel.versionName}" else "已是最新版本 $local")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (!silent) toast("检查失败：" + (t.message ?: t.javaClass.simpleName))
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun confirmUpdate(rel: UpdateChecker.Release) {
        val mb = if (rel.size > 0) String.format("%.1f MB", rel.size / 1024.0 / 1024.0) else "未知大小"
        AlertDialog.Builder(this)
            .setTitle("更新到 ${rel.versionName}")
            .setMessage("线路：${rel.via}\n源：${UpdateChecker.sourceLabel(this)}\n大小：$mb\n\n下载完成后会调起系统安装器，覆盖安装即可。")
            .setPositiveButton("下载") { _, _ -> downloadAndInstall(rel) }
            .setNeutralButton("更新源") { _, _ -> showUpdateSourceDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndInstall(rel: UpdateChecker.Release) {
        val pd = AlertDialog.Builder(this)
            .setMessage("正在下载… 0%")
            .setCancelable(false)
            .create()
        pd.show()
        Thread {
            try {
                val dir = File(cacheDir, "update").apply { mkdirs() }
                val f = File(dir, "shell2apk-${rel.versionName}.apk")
                if (f.exists()) f.delete()
                UpdateChecker.downloadFirst(listOf(rel.downloadUrl) + rel.altUrls, f) { p ->
                    runOnUiThread { pd.setMessage("正在下载… $p%") }
                }
                runOnUiThread {
                    pd.dismiss()
                    install(f)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    pd.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("下载失败")
                        .setMessage(
                            (t.message ?: t.javaClass.simpleName) +
                                "\n\n已自动试过所有内置线路。可以换个更新源再试，或用浏览器打开发布页手动下载。"
                        )
                        .setPositiveButton("打开发布页") { _, _ -> openUrl(rel.pageUrl) }
                        .setNeutralButton("更新源") { _, _ -> showUpdateSourceDialog() }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showUpdateSourceDialog() {
        val pool = UpdateChecker.BUILTIN_PROXIES.joinToString("\n") { " · " + it.replace("https://", "") }
        val labels = arrayOf(
            "自动：内置线路依次尝试（推荐）",
            "GitHub 直链（需能直连 GitHub）",
            "自定义代理前缀"
        )
        val cur = when (UpdateChecker.source(this)) {
            UpdateChecker.SOURCE_DIRECT -> 1
            UpdateChecker.SOURCE_CUSTOM -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("更新源")
            .setMessage("内置线路池：\n" + pool + "\n · 以上都失败时走 GitHub 直链")
            .setSingleChoiceItems(labels, cur) { d, which ->
                when (which) {
                    0 -> {
                        UpdateChecker.setSource(this, UpdateChecker.SOURCE_AUTO, "")
                        toast("已切到自动线路")
                        d.dismiss()
                    }
                    1 -> {
                        UpdateChecker.setSource(this, UpdateChecker.SOURCE_DIRECT, "")
                        toast("已切到 GitHub 直链")
                        d.dismiss()
                    }
                    else -> {
                        d.dismiss()
                        askCustomPrefix()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askCustomPrefix() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://your-proxy/"
            setText(UpdateChecker.customPrefix(this@MainActivity))
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义代理前缀")
            .setMessage("把 GitHub 地址拼在这个前缀后面，例如 https://gh-proxy.com/")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val p = et.text.toString().trim()
                if (p.isEmpty()) {
                    toast("前缀不能为空")
                    return@setPositiveButton
                }
                UpdateChecker.setSource(this, UpdateChecker.SOURCE_CUSTOM, p)
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 通用 ----------------

    /**
     * Android 15+ 强制 edge-to-edge：不做适配的话工具栏会顶到状态栏里（时间/信号压住标题）。
     * 这里让绿色工具栏铺满状态栏区域，再把标题往下推一个状态栏高度。
     */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(b.appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.recycler) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottom + dp(16))
            insets
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun uriOf(f: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun openUrl(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (t: Throwable) {
            toast("打不开：$url")
        }
    }

    private fun install(f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriOf(f), "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (t: Throwable) {
            toast("无法调起安装器：" + (t.message ?: ""))
        }
    }

    private fun share(f: File) {
        try {
            val i = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uriOf(f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "分享 APK"))
        } catch (t: Throwable) {
            toast("分享失败：" + (t.message ?: ""))
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setMessage(
                "1. 新建任务：填名称 + 选类型 + 填内容\n" +
                    "2. 点「生成」→ 输出一个独立 APK，安装后点桌面图标即执行\n" +
                    "3. 长按任务/文件夹可编辑、删除\n\n" +
                "包名：留空自动分配；填了就用你的。\n" +
                    "包名相同 = 同一个 App 升级，包名不同 = 两个 App 并存。\n\n" +
                "类型说明：\n" +
                    "· Shell：/system/bin/sh -c 执行，非 root 下只能用系统命令、访问本应用有权限的目录\n" +
                    "· 链接：打开 URL 或任意 Scheme（weixin://、intent://、market://…）\n" +
                    "· 活动：启动手机上某个 App 的指定 Activity（可点按钮从已装应用里选）\n" +
                    "· Intent：自定义 action / component / uri / extras\n" +
                    "· 广播：发送广播，填 action（必填）与目标包名（可选）\n\n" +
                "更新源：可在菜单里选 GitHub 直链、GitHub 代理或自定义代理前缀。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
