package com.louis.bleota.ota

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.zip.CRC32

/**
 * OTA 升级 ViewModel
 *
 * @author : Louix
 * @time : 2026/3/8 18:37
 */
class OtaViewModel(application: Application) : AndroidViewModel(application) {

    // 创建 OTA 服务和回调
    private val otaService = OtaBleServiceOptimized(
        context = application,
        gattCallback = object : OtaBleServiceOptimized.BleGattCallback {
            override fun onOtaCompleted(success: Boolean) {
                // 可以在这里处理 OTA 完成事件
            }

            override fun onOtaProgress(percent: Int) {
                // 可以在这里处理进度更新
            }

            override fun onOtaError(error: String) {
                // 可以在这里处理错误
            }
        }
    )

    // 创建 GATT 回调
    private val gattCallback = MyGattCallback(otaService)

    // 保存 GATT 连接实例
    private var bluetoothGatt: BluetoothGatt? = null

    // 暴露状态给 UI，使用 WhileSubscribed 避免内存泄漏
    val uiState = otaService.stateFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000), // 5秒后停止收集
            initialValue = OtaBleServiceOptimized.OtaState.Idle
        )

    /**
     * 连接设备
     */
    fun connect(device: BluetoothDevice) {
        try {
            // 断开之前的连接
            disconnect()

            // 建立新连接
            bluetoothGatt = device.connectGatt(
                getApplication(),
                false,
                gattCallback
            )
        } catch (e: SecurityException) {
            // 处理权限异常
            // OtaBleServiceOptimized 内部已经有权限处理，这里可以记录日志
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        gattCallback.disconnect()
        gattCallback.close()
        bluetoothGatt = null
    }

    /**
     * 开始 OTA 升级
     * @param firmwareData 固件数据
     * @param calculateChecksum 是否计算校验和
     */
    fun startUpgrade(firmwareData: ByteArray, calculateChecksum: Boolean = true) {
        val checksum = if (calculateChecksum) {
            // 计算 CRC32 校验和
            val crc32 = CRC32()
            crc32.update(firmwareData)
            crc32.value
        } else {
            null
        }

        otaService.startOta(firmwareData, checksum)
    }

    /**
     * 获取当前 OTA 进度百分比
     */
    fun getProgressPercent(): Int {
        return when (val state = uiState.value) {
            is OtaBleServiceOptimized.OtaState.Uploading -> state.percent
            else -> 0
        }
    }

    /**
     * 是否可以重试
     */
    fun canRetry(): Boolean {
        return when (val state = uiState.value) {
            is OtaBleServiceOptimized.OtaState.Error -> state.canRetry
            else -> false
        }
    }

    /**
     * 重试 OTA
     */
    fun retry(firmwareData: ByteArray) {
        if (canRetry()) {
            startUpgrade(firmwareData)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        otaService.destroy()
    }
}