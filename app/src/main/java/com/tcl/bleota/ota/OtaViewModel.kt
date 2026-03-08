package com.tcl.bleota.ota

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * @dec :
 *
 * @author : Louix
 * @email : zhaotian.liu@tcl.com
 * @time : 2026/3/8 18:37
 */
class OtaViewModel(val application: Application) : AndroidViewModel(application) {
    private val otaService = OtaBleServiceOptimized(application, object : OtaBleServiceOptimized.BleGattCallback {})
    private val gattCallback = MyGattCallback(otaService)

    // 暴露状态给 UI
    val uiState = otaService.stateFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, OtaBleServiceOptimized.OtaState.Idle)

    fun connect(device: BluetoothDevice) {
        try {
            device.connectGatt(application, false, gattCallback)
        } catch (e: SecurityException) {
        }
    }

    fun startUpgrade(data: ByteArray) {
        otaService.startOta(data)
    }

    override fun onCleared() {
        super.onCleared()
        otaService.destroy()
    }
}