package com.openremo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openremo.data.RemoRepository
import com.openremo.databinding.ActivityLogBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopy.setOnClickListener { copyLogs() }
        binding.btnShare.setOnClickListener { shareLogs() }
        binding.btnClear.setOnClickListener {
            RemoRepository.clearLogs()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.logs.collect { entries ->
                    val text = if (entries.isEmpty()) {
                        "暂无日志。\n\n连接设备后，这里会显示每一条收发的原始帧，方便定位兼容性问题。"
                    } else {
                        entries.joinToString("\n") {
                            "${formatter.format(Date(it.time))}  [${it.tag}]  ${it.message}"
                        }
                    }
                    binding.logText.text = text
                    binding.logCount.text = "${entries.size} 条"
                    binding.logScroll.post {
                        binding.logScroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenRemo log", RemoRepository.logText()))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenRemo 通信日志")
            putExtra(Intent.EXTRA_TEXT, RemoRepository.logText())
        }
        startActivity(Intent.createChooser(intent, "分享日志"))
    }
}
