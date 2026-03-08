package com.louis.bleota.ota

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.util.Log

/**
 * @dec : BLE GATT 回调处理
 *
 * @author : Louix
 * @time : 2026/3/8
 */
class MyGattCallback(
    private val otaService: OtaBleServiceOptimized
) : BluetoothGattCallback() {

    companion object {
        private const val TAG = "MyGattCallback"
    }

    private var bluetoothGatt: BluetoothGatt? = null

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to GATT server")
                bluetoothGatt = gatt
                gatt?.let {
                    // 通知服务已连接
                    otaService.onConnected(it)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected from GATT server")
                bluetoothGatt = null
                // 清理断线状态
                otaService.cleanupOnDisconnect()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        Log.d(TAG, "onServicesDiscovered: status=$status")
        gatt?.let {
            otaService.onServicesDiscovered(it, status)
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int
    ) {
        Log.d(TAG, "onCharacteristicWrite: status=$status")
        if (gatt != null && characteristic != null) {
            otaService.onCharacteristicWrite(gatt, characteristic, status)
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
        gatt?.let {
            otaService.onMtuChanged(it, mtu, status)
        }
    }

    // 获取当前 GATT 实例
    fun getGatt(): BluetoothGatt? = bluetoothGatt

    // 断开连接
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect failed: ${e.message}")
        }
    }

    // 关闭 GATT
    fun close() {
        try {
            bluetoothGatt?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "close failed: ${e.message}")
        }
        bluetoothGatt = null
    }
}