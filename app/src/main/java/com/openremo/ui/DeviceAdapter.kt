package com.openremo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openremo.data.RemoteRecord
import com.openremo.databinding.ItemDeviceBinding
import com.openremo.protocol.ScannedDevice

class DeviceAdapter(
    private val onConnect: (ScannedDevice) -> Unit,
) : ListAdapter<ScannedDevice, DeviceAdapter.DeviceHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: ScannedDevice) {
            binding.itemName.text = device.name
            binding.itemAddress.text = device.address
            binding.itemRssi.text = "信号 ${RemoteRecord.rssiToQuality(device.rssi)}"
            binding.itemConnect.setOnClickListener { onConnect(device) }
            binding.itemCard.setOnClickListener { onConnect(device) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ScannedDevice>() {
        override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean =
            oldItem.address == newItem.address

        override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice): Boolean =
            oldItem == newItem
    }
}
