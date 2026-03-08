package com.louis.bleota


import androidx.activity.ComponentActivity
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.louis.bleota.ota.OtaBleServiceOptimized
import com.louis.bleota.ota.OtaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 在你的测试代码中
 * val device: BluetoothDevice = // 获取你的蓝牙设备
 * otaActivity.connectDevice(device)
 * 然后观察 Logcat 输出
 */
class OtaActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OtaActivity"
    }

    private val viewModel: OtaViewModel by viewModels()
    private var isOtaStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 观察 OTA 状态
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleStateChange(state)
            }
        }
    }

    private fun handleStateChange(state: OtaBleServiceOptimized.OtaState) {
        when (state) {
            is OtaBleServiceOptimized.OtaState.Idle -> {
                Log.d(TAG, "State: Idle - Ready to start")
            }
            is OtaBleServiceOptimized.OtaState.Connecting -> {
                Log.d(TAG, "State: Connecting...")
            }
            is OtaBleServiceOptimized.OtaState.Discovering -> {
                Log.d(TAG, "State: Discovering services...")
            }
            is OtaBleServiceOptimized.OtaState.Ready -> {
                Log.d(TAG, "State: Ready - Connected, ready for OTA")

                // 自动开始 OTA（仅用于 demo）
                if (!isOtaStarted) {
                    isOtaStarted = true
                    lifecycleScope.launch {
                        delay(1000) // 延迟 1 秒
                        Log.d(TAG, "Auto-starting OTA for demo")
                        startOta()
                    }
                }
            }
            is OtaBleServiceOptimized.OtaState.Uploading -> {
                Log.d(TAG, "State: Uploading - Progress: ${state.percent}% (${state.progress}/${state.total} bytes)")

                // 每 10% 打印一次详细日志
                if (state.percent % 10 == 0) {
                    Log.i(TAG, "=== OTA Progress: ${state.percent}% ===")
                }
            }
            is OtaBleServiceOptimized.OtaState.Verifying -> {
                Log.d(TAG, "State: Verifying firmware...")
            }
            is OtaBleServiceOptimized.OtaState.Success -> {
                Log.i(TAG, "★★★ State: Success - OTA completed successfully! ★★★")
                isOtaStarted = false
            }
            is OtaBleServiceOptimized.OtaState.Error -> {
                Log.e(TAG, "State: Error - ${state.message}")
                if (state.canRetry) {
                    Log.d(TAG, "Error is retryable, will retry in 3 seconds...")

                    // 自动重试（仅用于 demo）
                    lifecycleScope.launch {
                        delay(3000)
                        retry()
                    }
                } else {
                    Log.e(TAG, "Error is NOT retryable, OTA failed permanently")
                }
                isOtaStarted = false
            }
        }
    }

    fun connectDevice(device: BluetoothDevice) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Connecting to device:")
        Log.d(TAG, "  Address: ${device.address}")
        try {
            Log.d(TAG, "  Name: ${device.name ?: "Unknown"}")
        } catch (e: SecurityException) {
            Log.d(TAG, "  Name: <Permission denied>")
        }
        Log.d(TAG, "========================================")

        viewModel.connect(device)
    }

    private fun startOta() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting OTA upgrade")

        // 创建测试固件数据
        val firmwareData = createMockFirmwareData()

        Log.d(TAG, "Firmware info:")
        Log.d(TAG, "  Size: ${firmwareData.size} bytes")
        Log.d(TAG, "  First 16 bytes: ${firmwareData.take(16).joinToString(" ") { "%02X".format(it) }}")
        Log.d(TAG, "========================================")

        viewModel.startUpgrade(firmwareData, calculateChecksum = true)
    }

    private fun retry() {
        Log.d(TAG, "Retrying OTA upgrade...")
        val firmwareData = createMockFirmwareData()
        viewModel.retry(firmwareData)
    }

    /**
     * 创建模拟固件数据
     */
    private fun createMockFirmwareData(): ByteArray {
        // 创建 50KB 的测试数据（更真实的固件大小）
        val size = 50 * 1024
        return ByteArray(size) { index ->
            // 创建一些有规律的数据模式
            when (index % 4) {
                0 -> 0xFF.toByte()
                1 -> (index / 256).toByte()
                2 -> (index % 256).toByte()
                else -> 0x00
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed, cleaning up...")
    }
}
