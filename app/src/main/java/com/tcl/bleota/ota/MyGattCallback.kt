package com.tcl.bleota.ota

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile

/**
 * @dec :
 *
 * @author : Louix
 * @email : zhaotian.liu@tcl.com
 * @time : 2026/3/6 19:49
 */
class MyGattCallback(private val otaService: OtaBleServiceOptimized) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            otaService.onConnected(gatt)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            otaService.cleanupOnDisconnect() // 关键：断线清理
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        otaService.onServicesDiscovered(gatt, status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        // 关键：将原生回调转发给我们的优化服务
        otaService.onCharacteristicWrite(gatt, characteristic, status)
    }
}