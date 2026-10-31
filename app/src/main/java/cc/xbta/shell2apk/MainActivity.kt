package cc.xbta.shell2apk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cc.xbta.shell2apk.apk.ApkBuilder
import cc.xbta.shell2apk.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: TaskStore
    private lateinit var adapter: TaskAdapter
    private val items = mutableListOf<Task>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = TaskStore(this)
        items.addAll(store.load())

        adapter = TaskAdapter(
            items,
            onClick = { openEditor(it) },
            onBuild = { buildApk(it) }
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
                R.id.action_about -> {
                    showAbout()
                    true
                }
                else -> false
            }
        }
        refreshList()
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        val empty = items.isEmpty()
        b.empty.visibility = if (empty) View.VISIBLE else View.GONE
        b.recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openEditor(task: Task) {
        val d = TaskEditorDialog(this, task) { saved ->
            val idx = items.indexOfFirst { it.id == saved.id }
            if (idx >= 0) items[idx] = saved else items.add(saved)
            store.save(items)
            refreshList()
        }
        d.show()
    }

    private fun buildApk(task: Task) {
        if (task.name.isBlank()) {
            toast("请先填写名称")
            return
        }
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
                if (ok) showResult(File(payload)) else showError(payload)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showResult(f: File) {
        val kb = (f.length() / 1024.0)
        AlertDialog.Builder(this)
            .setTitle("生成成功")
            .setMessage("${f.name}\n${String.format("%.1f", kb)} KB\n\n位置：\n${f.parent}")
            .setPositiveButton("安装") { _, _ -> install(f) }
            .setNeutralButton("分享") { _, _ -> share(f) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("生成失败")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun uriOf(f: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

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
                    "2. 点「生成」→ 改包名/图标/应用名，输出一个独立 APK\n" +
                    "3. 安装后点桌面图标即执行对应动作\n\n" +
                    "类型说明：\n" +
                    "· Shell：用 /system/bin/sh -c 执行，非 root 下只能调用系统命令、访问本应用有权限的目录\n" +
                    "· 链接：打开 URL 或任意 URL Scheme（weixin://、intent://、market:// 等）\n" +
                    "· 应用：按包名启动已安装的应用\n" +
                    "· Intent：自定义 action / component / uri / extras\n\n" +
                    "生成的 APK 与本 App 使用同一签名，可覆盖升级。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
