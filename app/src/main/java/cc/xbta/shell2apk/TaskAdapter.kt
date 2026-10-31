package cc.xbta.shell2apk

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cc.xbta.shell2apk.databinding.ItemFolderBinding
import cc.xbta.shell2apk.databinding.ItemTaskBinding

/** 首页一行的两种形态：文件夹行 / 任务行 */
sealed class Row {
    data class FolderRow(val folder: Folder, val count: Int) : Row()
    data class TaskRow(val task: Task, val inFolder: Boolean) : Row()
}

class TaskAdapter(
    private val onFolderClick: (Folder) -> Unit,
    private val onFolderLongClick: (Folder) -> Unit,
    private val onTaskClick: (Task) -> Unit,
    private val onTaskBuild: (Task) -> Unit,
    private val onTaskLongClick: (Task) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    fun submit(data: List<Row>) {
        rows = data
        notifyDataSetChanged()
    }

    class FolderVH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)
    class TaskVH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.FolderRow) TYPE_FOLDER else TYPE_TASK

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderVH(ItemFolderBinding.inflate(inf, parent, false))
        } else {
            TaskVH(ItemTaskBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.FolderRow -> {
                val b = (holder as FolderVH).b
                b.arrow.text = if (r.folder.expanded) "\u25BE" else "\u25B8"
                b.folderName.text = r.folder.name
                b.folderCount.text = r.count.toString()
                b.root.setOnClickListener { onFolderClick(r.folder) }
                b.root.setOnLongClickListener {
                    onFolderLongClick(r.folder)
                    true
                }
            }
            is Row.TaskRow -> {
                val b = (holder as TaskVH).b
                val t = r.task
                val density = b.root.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()
                b.root.setPadding(dp(if (r.inFolder) 30 else 14), dp(10), dp(8), dp(10))
                b.icon.text = if (t.iconText.isBlank()) t.name.trim().take(1).ifBlank { ">" } else t.iconText.trim()
                b.name.text = t.name.ifBlank { "未命名任务" }
                val extra = when {
                    t.kind == Task.KIND_BROADCAST || t.kind == Task.KIND_INTENT -> t.action.ifBlank { t.value }
                    else -> t.value
                }.replace("\n", " ").trim()
                b.summary.text = Task.kindLabel(t.kind) + " · " + if (extra.isEmpty()) "（未填写）" else extra
                b.root.setOnClickListener { onTaskClick(t) }
                b.root.setOnLongClickListener {
                    onTaskLongClick(t)
                    true
                }
                b.btnBuild.setOnClickListener { onTaskBuild(t) }
            }
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_TASK = 1
    }
}
