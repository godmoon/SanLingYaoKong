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
import com.openremo.data.RemoRepository
import com.openremo.databinding.ActivityMainBinding
import com.openremo.ui.view.RemoteFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (granted) {
                Snackbar.make(binding.root, "权限已授予，可以扫描设备了", Snackbar.LENGTH_SHORT).show()
                if (RemoRepository.uiState.value.savedDevice.deviceAddress.isNotBlank() &&
                    !RemoRepository.uiState.value.connected
                ) {
                    RemoRepository.connectSaved()
                }
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_remote -> show(RemoteFragment(), TAG_REMOTE)
                R.id.nav_devices -> show(DevicesFragment(), TAG_DEVICES)
                R.id.nav_settings -> show(SettingsFragment(), TAG_SETTINGS)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        // 首次创建时显示遥控页；重建时交给 FragmentManager 恢复原页面
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_remote
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoRepository.messages.collect { text ->
                    Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun show(fragment: Fragment, tag: String) {
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        val existing = manager.findFragmentByTag(tag)
        manager.fragments.forEach { transaction.hide(it) }
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.fragment_container, fragment, tag)
        }
        // 用 commitNow 保证连续切换时 hide/show 不会排队交错
        transaction.commitNow()
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.fragments.firstOrNull { !it.isHidden }

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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    fun openRemote() {
        binding.bottomNav.selectedItemId = R.id.nav_remote
    }

    interface PermissionsAware {
        fun onPermissionsResult()
    }

    private companion object {
        const val TAG_REMOTE = "remote"
        const val TAG_DEVICES = "devices"
        const val TAG_SETTINGS = "settings"
    }
}
