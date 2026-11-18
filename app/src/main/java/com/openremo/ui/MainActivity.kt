package com.openremo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.openremo.R
import com.openremo.data.AppState
import com.openremo.data.RemoRepository
import com.openremo.databinding.ActivityMainBinding
import com.openremo.ui.view.RemoteFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = hasBlePermissions()
            if (granted) {
                Snackbar.make(binding.root, "权限已授予", Snackbar.LENGTH_SHORT).show()
                // 信号任务会自动开始，并在启动时选出信号最好的遥控器
            } else {
                Snackbar.make(binding.root, "没有蓝牙权限无法扫描和连接设备", Snackbar.LENGTH_LONG).show()
            }
            (currentFragment() as? PermissionsAware)?.onPermissionsResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RemoteFragment(), TAG_REMOTE)
                .commitNow()
        }

        // 首次启动先请求权限，否则无法扫描信号、也无法自动选中最好的遥控器
        if (!hasBlePermissions()) ensureBlePermissions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.messages.collect { text ->
                    Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        // 先标记前台再分发 Fragment 生命周期，轮询任务据此判断是否需要工作
        AppState.foreground = true
        super.onStart()
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragment_container)

    fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    fun ensureBlePermissions() {
        if (hasBlePermissions()) {
            (currentFragment() as? PermissionsAware)?.onPermissionsResult()
            return
        }
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    /** 单页模式保留此方法，兼容旧调用方。 */
    fun openRemote() = Unit

    private var notificationAsked = false

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (has(Manifest.permission.POST_NOTIFICATIONS)) return
        if (notificationAsked) return
        notificationAsked = true
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    interface PermissionsAware {
        fun onPermissionsResult()
    }

    private companion object {
        const val TAG_REMOTE = "remote"
    }
}
