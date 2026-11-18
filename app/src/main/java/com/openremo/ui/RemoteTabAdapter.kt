package com.openremo.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openremo.R
import com.openremo.data.RemoteRecord
import com.openremo.data.RemoRepository
import com.openremo.databinding.ItemRemoteTabBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 顶部横向切换条里的一项。 */
data class RemoteTab(
    val record: RemoteRecord,
    val selected: Boolean,
    val phase: RemoRepository.Phase,
    val connected: Boolean,
)

class RemoteTabAdapter(
    private val onClick: (RemoteRecord) -> Unit,
    private val onLongClick: (View, RemoteRecord) -> Unit,
) : ListAdapter<RemoteTab, RemoteTabAdapter.TabHolder>(Diff) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabHolder {
        val binding = ItemRemoteTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return TabHolder(binding)
    }

    override fun onBindViewHolder(holder: TabHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TabHolder(private val binding: ItemRemoteTabBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: RemoteTab) {
            val record = tab.record
            val context = binding.root.context

            binding.tabName.text = record.title
            binding.tabSignal.text = if (record.rssi == RemoteRecord.RSSI_UNKNOWN) {
                "信号 --"
            } else {
                "信号 ${record.signal}"
            }
            binding.tabSignal.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary),
            )

            val dotColor = when {
                tab.connected -> R.color.success
                tab.selected && tab.phase == RemoRepository.Phase.AUTHENTICATING -> R.color.warning
                tab.selected && tab.phase == RemoRepository.Phase.CONNECTING -> R.color.warning
                tab.selected && tab.phase == RemoRepository.Phase.RECONNECTING -> R.color.warning
                tab.selected && tab.phase == RemoRepository.Phase.ERROR -> R.color.danger
                else -> R.color.text_tertiary
            }
            binding.tabDot.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColor))

            if (record.hasTimer) {
                binding.tabTimer.visibility = View.VISIBLE
                val day = if (sameDay(record.timerAt)) "" else "明天 "
                binding.tabTimer.text = "$day${timeFormat.format(Date(record.timerAt))} 关"
            } else {
                binding.tabTimer.visibility = View.GONE
            }

            binding.tabCard.setStrokeColor(
                ContextCompat.getColor(
                    context,
                    if (tab.selected) R.color.primary else R.color.divider,
                ),
            )
            binding.tabCard.strokeWidth =
                if (tab.selected) dp(2) else dp(1)
            binding.tabCard.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (tab.selected) R.color.surface_high else R.color.surface,
                ),
            )

            binding.tabCard.setOnClickListener { onClick(record) }
            binding.tabCard.setOnLongClickListener {
                onLongClick(binding.tabCard, record)
                true
            }
        }

        private fun dp(value: Int): Int =
            (value * binding.root.resources.displayMetrics.density).toInt()

        private fun sameDay(at: Long): Boolean {
            val now = java.util.Calendar.getInstance()
            val target = java.util.Calendar.getInstance().apply { timeInMillis = at }
            return now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
        }
    }

    private object Diff : DiffUtil.ItemCallback<RemoteTab>() {
        override fun areItemsTheSame(oldItem: RemoteTab, newItem: RemoteTab): Boolean =
            oldItem.record.address == newItem.record.address

        override fun areContentsTheSame(oldItem: RemoteTab, newItem: RemoteTab): Boolean =
            oldItem == newItem
    }
}
