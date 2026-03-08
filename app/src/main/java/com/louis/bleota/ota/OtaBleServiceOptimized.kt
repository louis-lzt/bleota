package com.louis.bleota.ota

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/**
 * 高可靠 BLE OTA 升级服务 (生产版本)
 *
 * 核心特性：
 * 1. 单 In-Flight 模型：严格串行化写入，避免回调竞态。
 * 2. 幂等完成机制：防止重复回调导致的信号量泄漏。
 * 3. 自适应流控：根据设备 ACK 速度动态调整发送间隔。
 * 4. 断线自愈：自动清理僵尸任务，确保重连后可立即恢复。
 * 5. 包顺序保证：重试机制不会打乱数据包顺序。
 * 6. 固件完整性校验：支持 CRC32 校验。
 * 7. 完整的权限处理：支持 Android 12+ 的运行时权限。
 *
 * @author Liu Zhaotian
 */
class OtaBleServiceOptimized(
    private val context: Context,
    private val gattCallback: BleGattCallback? = null
) {

    companion object {
        private const val TAG = "OtaBleService"

        // ⚠️ 请替换为你实际设备的 UUID
        // 此处使用 Nordic UART Service (NUS) 作为示例
        private val OTA_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val OTA_WRITE_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

        // 核心配置：强制并发数为 1 (Single In-Flight)
        private const val MAX_CONCURRENT_WRITES = 1

        // 默认发送间隔 (ms)
        private const val DEFAULT_WRITE_INTERVAL_MS = 15L
        private const val MIN_WRITE_INTERVAL_MS = 2L
        private const val MAX_WRITE_INTERVAL_MS = 100L

        // 超时配置
        private const val WRITE_TIMEOUT_MS = 3000L

        // 默认 MTU
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_SIZE = 3
    }

    // 状态流：供 UI 观察进度
    private val _stateFlow = MutableStateFlow<OtaState>(OtaState.Idle)
    val stateFlow: StateFlow<OtaState> = _stateFlow.asStateFlow()

    // 信号量：控制并发写入
    private val flowControlSemaphore = Semaphore(MAX_CONCURRENT_WRITES)

    // 写入队列：Channel 作为无界队列缓冲待发送数据包
    private val channelMutex = Mutex()
    private var _writeChannel = Channel<OtaPacket>(Channel.UNLIMITED)
    private var _retryChannel = Channel<OtaPacket>(Channel.UNLIMITED)

    // 作用域：管理协程生命周期
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 当前正在进行的写入 ID (用于幂等检查)
    private val inFlightWriteId = AtomicInteger(0)

    // 标记当前写入是否已完成 (防止重复释放信号量)
    private val inFlightCompleted = AtomicBoolean(true)

    // 超时任务 Job
    private var inFlightTimeoutJob: Job? = null

    // 当前发送间隔 (自适应调整)
    @Volatile
    private var currentWriteIntervalMs = DEFAULT_WRITE_INTERVAL_MS

    // MTU 相关
    @Volatile
    private var currentMtu = DEFAULT_MTU
    private val effectivePayloadSize: Int
        get() = currentMtu - ATT_HEADER_SIZE

    // GATT 对象引用
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // 锁：保护 inFlight 状态
    private val inFlightLock = Any()

    // 消费者协程 Job
    private var consumerJob: Job? = null

    // 进度追踪
    private val currentPacketIndex = AtomicInteger(0)
    private var totalPackets = 0

    // 固件校验
    private var expectedChecksum: Long? = null
    private val crc32 = CRC32()

    /**
     * 检查蓝牙权限
     */
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12 以下不需要运行时权限
        }
    }

    /**
     * 安全执行需要权限的操作
     */
    private inline fun <T> executeWithPermission(
        operation: String,
        block: () -> T
    ): T? {
        return try {
            if (!hasBluetoothPermissions()) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for $operation")
                _stateFlow.value = OtaState.Error("Missing Bluetooth permission", canRetry = false)
                null
            } else {
                block()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in $operation", e)
            _stateFlow.value = OtaState.Error("Bluetooth permission denied", canRetry = false)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception in $operation", e)
            _stateFlow.value = OtaState.Error("Operation failed: ${e.message}", canRetry = true)
            null
        }
    }

    /**
     * 初始化 GATT 连接
     * 注意：实际项目中应在 ConnectionCallback 中调用此方法
     */
    fun onConnected(gatt: BluetoothGatt) {
        bluetoothGatt = gatt
        _stateFlow.value = OtaState.Discovering
        Log.d(TAG, "BLE Connected, discovering services...")

        // 请求更大的 MTU
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            executeWithPermission("requestMtu") {
                try {
                    gatt.requestMtu(512)
                } catch (e: SecurityException) {
                    Log.e(TAG, "gatt.requestMtu", e)
                    // 处理权限异常
                }
            }
        }

        executeWithPermission("discoverServices") {
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied", e)
                // 处理权限异常
            }
        }
    }

    /**
     * MTU 变更回调
     */
    fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            currentMtu = mtu
            Log.d(TAG, "MTU changed to: $mtu, effective payload: $effectivePayloadSize")
        }
    }

    /**
     * 服务发现完成，查找 OTA Characteristic
     */
    fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed: $status")
            _stateFlow.value = OtaState.Error("Service discovery failed", canRetry = true)
            return
        }

        executeWithPermission("getService") {
            val service = gatt.getService(OTA_SERVICE_UUID)
            val char = service?.getCharacteristic(OTA_WRITE_CHAR_UUID)

            if (char == null) {
                Log.e(TAG, "OTA Characteristic not found!")
                _stateFlow.value = OtaState.Error("OTA Characteristic not found", canRetry = false)
                return@executeWithPermission
            }

            writeCharacteristic = char
            Log.d(TAG, "OTA Service & Characteristic found. Ready to start.")
            _stateFlow.value = OtaState.Ready

            // 启动消费者协程
            startConsumer()
        }
    }

    /**
     * 开始 OTA 升级
     * @param firmwareData 固件二进制数据
     * @param checksum 可选的 CRC32 校验和
     */
    fun startOta(firmwareData: ByteArray, checksum: Long? = null) {
        if (writeCharacteristic == null) {
            Log.e(TAG, "Cannot start OTA: Characteristic not ready")
            _stateFlow.value = OtaState.Error("Characteristic not ready", canRetry = true)
            return
        }

        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Cannot start OTA: Missing permissions")
            _stateFlow.value = OtaState.Error("Missing Bluetooth permission", canRetry = false)
            return
        }

        expectedChecksum = checksum
        crc32.reset()

        serviceScope.launch {
            Log.d(TAG, "Starting OTA, total size: ${firmwareData.size} bytes")
            _stateFlow.value = OtaState.Uploading(0, firmwareData.size)

            // 分包逻辑
            val mtuSize = effectivePayloadSize
            val chunks = mutableListOf<ByteArray>()
            var offset = 0

            while (offset < firmwareData.size) {
                val chunkSize = minOf(mtuSize, firmwareData.size - offset)
                val chunk = firmwareData.copyOfRange(offset, offset + chunkSize)
                chunks.add(chunk)
                offset += chunkSize
            }

            val packets = chunks.mapIndexed { index, chunk ->
                OtaPacket(
                    id = index,
                    data = chunk,
                    total = chunks.size,
                    isLast = index == chunks.size - 1,
                    checksum = if (index == chunks.size - 1) expectedChecksum else null
                )
            }

            totalPackets = packets.size
            currentPacketIndex.set(0)

            // 清空旧队列数据
            channelMutex.withLock {
                clearChannel(_writeChannel)
                clearChannel(_retryChannel)
            }

            // 发送所有包到队列
            packets.forEach { packet ->
                if (!_writeChannel.trySend(packet).isSuccess) {
                    Log.e(TAG, "Failed to enqueue packet ${packet.id}")
                }
            }

            Log.d(TAG, "All ${packets.size} packets enqueued. Consumer processing...")
        }
    }

    /**
     * 清空 Channel
     */
    private fun clearChannel(channel: Channel<OtaPacket>) {
        while (true) {
            val result = channel.tryReceive()
            if (result.isFailure) break
        }
    }

    /**
     * 消费者协程：从队列取包并发送
     */
    private fun startConsumer() {
        consumerJob?.cancel()
        consumerJob = serviceScope.launch {
            try {
                while (isActive) {
                    // 使用 select 优先处理重试队列
                    val packet = channelMutex.withLock {
                        select<OtaPacket?> {
                            _retryChannel.onReceiveCatching { result ->
                                result.getOrNull()
                            }
                            _writeChannel.onReceiveCatching { result ->
                                result.getOrNull()
                            }
                        }
                    } ?: break

                    // 1. 获取许可
                    flowControlSemaphore.acquire()

                    // 2. 标记新的 In-Flight 任务
                    val writeId = inFlightWriteId.incrementAndGet()
                    synchronized(inFlightLock) {
                        inFlightCompleted.set(false)
                    }

                    // 3. 执行写入
                    sendPacketInternal(packet, writeId)

                    // 4. 添加发送间隔
                    delay(currentWriteIntervalMs)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Consumer coroutine cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Consumer coroutine error", e)
                _stateFlow.value = OtaState.Error("Transfer failed: ${e.message}", canRetry = true)
            }
        }
    }

    /**
     * 执行底层写入
     */
    @SuppressLint("MissingPermission")
    private fun sendPacketInternal(packet: OtaPacket, writeId: Int) {
        val char = writeCharacteristic ?: run {
            handleWriteDoneInternal("NoChar", success = false, expectedWriteId = writeId)
            return
        }
        val gatt = bluetoothGatt ?: run {
            handleWriteDoneInternal("NoGatt", success = false, expectedWriteId = writeId)
            return
        }

        // 更新 CRC
        crc32.update(packet.data)

        // 设置写入类型
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        // 写入数据 - 使用兼容新旧API的方式，并处理权限
        val success = executeWithPermission("writeCharacteristic") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    char,
                    packet.data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                result == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = packet.data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } ?: false

        Log.v(TAG, "Writing packet ${packet.id}/${packet.total} (${packet.data.size} bytes, ID: $writeId)")

        if (!success) {
            Log.w(TAG, "writeCharacteristic failed or returned false.")
            handleWriteDoneInternal("WriteFail", success = false, expectedWriteId = writeId)

            // 重新入队到重试队列（保持顺序）
            serviceScope.launch {
                channelMutex.withLock {
                    if (!_retryChannel.trySend(packet).isSuccess) {
                        Log.e(TAG, "Failed to enqueue retry for packet ${packet.id}")
                    }
                }
            }
            return
        }

        // 4. 启动超时计时器
        startTimeoutTimer(writeId, packet)
    }

    /**
     * 超时计时器
     */
    private fun startTimeoutTimer(writeId: Int, packet: OtaPacket) {
        inFlightTimeoutJob?.cancel()
        inFlightTimeoutJob = serviceScope.launch {
            delay(WRITE_TIMEOUT_MS)
            Log.w(TAG, "Write timeout for packet ${packet.id} (ID: $writeId)")
            handleWriteDoneInternal("Timeout", success = false, expectedWriteId = writeId)

            // 超时也要重试
            channelMutex.withLock {
                if (!_retryChannel.trySend(packet).isSuccess) {
                    Log.e(TAG, "Failed to enqueue timeout retry for packet ${packet.id}")
                }
            }
        }
    }

    /**
     * 核心：统一完成处理 (幂等 + 流控)
     */
    fun handleWriteDoneInternal(from: String, success: Boolean, expectedWriteId: Int?) {
        synchronized(inFlightLock) {
            val currentWriteId = inFlightWriteId.get()

            // 1. 忽略过期的超时任务
            if (expectedWriteId != null && expectedWriteId != currentWriteId) {
                Log.v(TAG, "Ignoring stale completion from $from for ID $expectedWriteId (Current: $currentWriteId)")
                return
            }

            // 2. 幂等检查
            if (!inFlightCompleted.compareAndSet(false, true)) {
                Log.w(TAG, "Duplicate completion ignored from $from")
                return
            }

            // 3. 取消超时任务
            inFlightTimeoutJob?.cancel()
        }

        // 4. 业务逻辑处理
        if (success) {
            adjustInterval(decrease = true)
            updateProgress(success = true)
        } else {
            adjustInterval(decrease = false)
            Log.e(TAG, "Write failed from $from. Throttling...")
        }

        // 5. 释放信号量
        flowControlSemaphore.release()
        Log.v(TAG, "Semaphore released by $from. Available: ${flowControlSemaphore.availablePermits()}")
    }

    /**
     * GATT 回调入口
     */
    fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (characteristic.uuid != OTA_WRITE_CHAR_UUID) return

        val success = (status == BluetoothGatt.GATT_SUCCESS)
        handleWriteDoneInternal("GattCallback", success, expectedWriteId = null)
    }

    /**
     * 更新进度
     */
    private fun updateProgress(success: Boolean) {
        if (success) {
            val currentIndex = currentPacketIndex.incrementAndGet()
            val currentState = _stateFlow.value

            if (currentState is OtaState.Uploading) {
                val progress = (currentIndex * effectivePayloadSize).coerceAtMost(currentState.total)

                if (currentIndex >= totalPackets) {
                    // 完成传输，进行校验
                    verifyAndComplete()
                } else {
                    _stateFlow.value = OtaState.Uploading(progress, currentState.total)
                }
            }
        }
    }

    /**
     * 校验并完成
     */
    private fun verifyAndComplete() {
        _stateFlow.value = OtaState.Verifying

        serviceScope.launch {
            delay(100) // 给设备一点处理时间

            val actualChecksum = crc32.value
            if (expectedChecksum != null && actualChecksum != expectedChecksum) {
                Log.e(TAG, "Checksum mismatch! Expected: $expectedChecksum, Actual: $actualChecksum")
                _stateFlow.value = OtaState.Error("Firmware verification failed", canRetry = true)
            } else {
                Log.d(TAG, "OTA completed successfully. Checksum: $actualChecksum")
                _stateFlow.value = OtaState.Success
                gattCallback?.onOtaCompleted(true)
            }
        }
    }

    /**
     * 自适应调整发送间隔
     */
    private fun adjustInterval(decrease: Boolean) {
        currentWriteIntervalMs = if (decrease) {
            maxOf(MIN_WRITE_INTERVAL_MS, (currentWriteIntervalMs * 0.9).toLong())
        } else {
            minOf(MAX_WRITE_INTERVAL_MS, (currentWriteIntervalMs * 1.5).toLong())
        }
        Log.v(TAG, "Adjusted write interval to ${currentWriteIntervalMs}ms")
    }

    /**
     * 断线清理
     */
    fun cleanupOnDisconnect() {
        Log.w(TAG, "Cleaning up on disconnect...")

        serviceScope.launch {
            // 1. 取消所有任务
            inFlightTimeoutJob?.cancel()
            consumerJob?.cancel()

            // 2. 强制重置状态
            synchronized(inFlightLock) {
                inFlightCompleted.set(true)
            }

            // 3. 恢复信号量
            val permitsToRelease = MAX_CONCURRENT_WRITES - flowControlSemaphore.availablePermits()
            repeat(permitsToRelease) {
                flowControlSemaphore.release()
            }

            // 4. 重建队列
            channelMutex.withLock {
                _writeChannel.close()
                _retryChannel.close()
                _writeChannel = Channel(Channel.UNLIMITED)
                _retryChannel = Channel(Channel.UNLIMITED)
            }

            // 5. 重置状态
            _stateFlow.value = OtaState.Idle
            currentWriteIntervalMs = DEFAULT_WRITE_INTERVAL_MS
            currentPacketIndex.set(0)
            totalPackets = 0
            crc32.reset()

            Log.d(TAG, "Cleanup finished. Ready for reconnect.")
        }
    }

    /**
     * 销毁服务
     */
    fun destroy() {
        Log.d(TAG, "Destroying OTA service...")

        // 取消所有协程
        serviceScope.cancel()

        // 等待协程完成
        runBlocking {
            serviceScope.coroutineContext[Job]?.children?.forEach {
                try {
                    it.join()
                } catch (e: CancellationException) {
                    // Expected
                }
            }
        }

        // 关闭资源
        runBlocking {
            channelMutex.withLock {
                _writeChannel.close()
                _retryChannel.close()
            }
        }

        // 安全关闭 GATT
        executeWithPermission("closeGatt") {
            try {
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "executeWithPermission", e)
                // 处理权限异常
            }
        }

        bluetoothGatt = null
        writeCharacteristic = null

        Log.d(TAG, "OTA service destroyed")
    }

    // ---------------- Data Classes ----------------

    data class OtaPacket(
        val id: Int,
        val data: ByteArray,
        val total: Int,
        val isLast: Boolean = false,
        val checksum: Long? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as OtaPacket

            if (id != other.id) return false
            if (!data.contentEquals(other.data)) return false
            if (total != other.total) return false
            if (isLast != other.isLast) return false
            if (checksum != other.checksum) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + data.contentHashCode()
            result = 31 * result + total
            result = 31 * result + isLast.hashCode()
            result = 31 * result + (checksum?.hashCode() ?: 0)
            return result
        }
    }

    sealed class OtaState {
        object Idle : OtaState()
        object Connecting : OtaState()
        object Discovering : OtaState()
        object Ready : OtaState()
        data class Uploading(val progress: Int, val total: Int) : OtaState() {
            val percent: Int = (progress.toFloat() / total * 100).toInt()
        }
        object Verifying : OtaState()
        object Success : OtaState()
        data class Error(val message: String, val canRetry: Boolean = true) : OtaState()
    }

    // 接口：用于回调 GATT 事件
    interface BleGattCallback {
        fun onOtaCompleted(success: Boolean) {}
        fun onOtaProgress(percent: Int) {}
        fun onOtaError(error: String) {}
    }
}