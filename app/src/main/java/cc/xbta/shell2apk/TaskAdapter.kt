package cc.xbta.shell2apk

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cc.xbta.shell2apk.databinding.ItemTaskBinding

class TaskAdapter(
    private val items: MutableList<Task>,
    private val onClick: (Task) -> Unit,
    private val onBuild: (Task) -> Unit
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val b = holder.b
        b.icon.text = if (t.iconText.isBlank()) t.name.trim().take(1).ifBlank { ">" } else t.iconText.trim()
        b.name.text = t.name.ifBlank { "未命名任务" }
        val v = t.value.replace("\n", " ").trim()
        b.summary.text = Task.kindLabel(t.kind) + " · " + if (v.isEmpty()) "（未填写）" else v
        b.root.setOnClickListener { onClick(t) }
        b.btnBuild.setOnClickListener { onBuild(t) }
    }
}
