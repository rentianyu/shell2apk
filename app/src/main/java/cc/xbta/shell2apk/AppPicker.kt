package cc.xbta.shell2apk

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** 选手机上已安装应用 → 再选它的 Activity，结果填成 "包名/类名" */
object AppPicker {

    data class AppItem(val label: String, val pkg: String, val icon: Drawable?)

    fun pick(ctx: Activity, onPicked: (String) -> Unit) {
        val apps = listLaunchable(ctx)
        if (apps.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("没有找到可启动的应用")
                .setMessage("可能是系统限制了包查询权限。可以直接手填「包名/类名」。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("选择应用")
            .setAdapter(AppAdapter(ctx, apps)) { _, which -> pickActivity(ctx, apps[which], onPicked) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickActivity(ctx: Activity, app: AppItem, onPicked: (String) -> Unit) {
        val acts = listActivities(ctx, app.pkg)
        if (acts.isEmpty()) {
            onPicked(app.pkg)
            return
        }
        val labels = acts.map { it.removePrefix(app.pkg).ifBlank { it } }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("${app.label} 的活动")
            .setItems(labels) { _, which -> onPicked(app.pkg + "/" + acts[which]) }
            .setNeutralButton("用默认入口") { _, _ -> onPicked(app.pkg) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun listLaunchable(ctx: Activity): List<AppItem> {
        return try {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val ris = pm.queryIntentActivities(intent, 0)
            val map = LinkedHashMap<String, AppItem>()
            for (ri in ris) {
                val pkg = ri.activityInfo.packageName
                if (map.containsKey(pkg)) continue
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                val icon = try {
                    ri.loadIcon(pm)
                } catch (t: Throwable) {
                    null
                }
                map[pkg] = AppItem(label, pkg, icon)
            }
            map.values.sortedBy { it.label.lowercase() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun listActivities(ctx: Activity, pkg: String): List<String> {
        return try {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            val arr = info.activities ?: return emptyList()
            val all = arr.map { it.name }
            val exported = arr.filter { it.exported }.map { it.name }
            (if (exported.isNotEmpty()) exported else all).sorted()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private class AppAdapter(private val ctx: Activity, private val items: List<AppItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView
                ?: LayoutInflater.from(ctx).inflate(R.layout.item_picker, parent, false)
            val iv = v.findViewById<ImageView>(R.id.pickerIcon)
            val t1 = v.findViewById<TextView>(R.id.pickerText)
            val t2 = v.findViewById<TextView>(R.id.pickerSub)
            val it = items[position]
            t1.text = it.label
            t2.text = it.pkg
            iv.setImageDrawable(it.icon)
            return v
        }
    }
}
