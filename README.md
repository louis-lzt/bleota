# Android BLE OTA 固件升级服务

[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg)](https://android-arsenal.com/api?level=21)
[![Language](https://img.shields.io/badge/language-Kotlin-orange.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

一个生产级、高可靠性的 Android 蓝牙低功耗（BLE）OTA 固件升级服务，基于 Kotlin 协程和现代 Android 架构构建。

## ✨ 核心特性

- **🔒 高可靠性**：单 In-Flight 写入模型，配合幂等完成机制
- **🚀 自适应流控**：根据设备响应速度动态调整发送间隔
- **♻️ 自动恢复**：自动重试机制，优雅处理连接失败
- **📦 包顺序保证**：即使在重试过程中也能保证数据包顺序
- **✅ 完整性校验**：支持 CRC32 校验和验证固件完整性
- **📱 Android 12+ 支持**：完整的运行时权限处理（BLUETOOTH_CONNECT）
- **🏗️ MVVM 架构**：使用 ViewModel 和 StateFlow 实现清晰的关注点分离
- **⚡ 基于协程**：高效的异步操作，不阻塞 UI 线程

## 📋 环境要求

- Android API 21+（Android 5.0 Lollipop）
- Kotlin 1.5+
- AndroidX
- Coroutines 1.6+

## 🚀 快速开始

### 1. 添加依赖

```gradle
dependencies {
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
}
```

### 2. 添加权限
```
    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <!-- Android 12+ -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
```

### 3. 基本使用
```kotlin
class OtaActivity : AppCompatActivity() {
    private val viewModel: OtaViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 观察 OTA 状态
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is OtaState.Uploading -> {
                        updateProgress(state.percent)
                    }
                    is OtaState.Success -> {
                        showSuccess()
                    }
                    is OtaState.Error -> {
                        showError(state.message)
                    }
                    // ... 处理其他状态
                }
            }
        }
    }
    
    private fun startOta(device: BluetoothDevice, firmware: ByteArray) {
        viewModel.connect(device)
        // 连接成功后会自动开始 OTA
        viewModel.startUpgrade(firmware)
    }
}
```

### 4. 架构设计
┌─────────────────┐
│    UI 层        │
│  (Activity)     │
└────────┬────────┘
│
┌────────▼────────┐
│   ViewModel     │
│  (OtaViewModel) │
└────────┬────────┘
│
┌────────▼────────┐
│    服务层       │
│(OtaBleService)  │
└────────┬────────┘
│
┌────────▼────────┐
│  GATT 回调      │
│(MyGattCallback) │
└─────────────────┘
### 5. 核心组件

#### OtaBleServiceOptimized
OTA 服务的核心，负责：
- BLE 连接管理
- 数据包分片和传输
- 流控和重试逻辑
- 进度跟踪和状态管理

#### OtaViewModel
MVVM ViewModel 提供：
- 生命周期感知的状态管理
- 通过 StateFlow 暴露 UI 状态
- 连接和 OTA 操作 API

#### MyGattCallback
BluetoothGattCallback 实现，处理：
- 连接状态变化
- 服务发现
- 特征值写入确认
- MTU 协商

### 6.状态流转

```kotlin
sealed class OtaState {
    object Idle : OtaState()              // 空闲
    object Connecting : OtaState()        // 连接中
    object Discovering : OtaState()       // 发现服务中
    object Ready : OtaState()             // 就绪
    data class Uploading(                 // 上传中
        val progress: Int, 
        val total: Int
    ) : OtaState()
    object Verifying : OtaState()         // 校验中
    object Success : OtaState()           // 成功
    data class Error(                     // 错误
        val message: String, 
        val canRetry: Boolean
    ) : OtaState()
}
```
### 7.配置说明

#### UUID 配置
在 `OtaBleServiceOptimized` 中更新 UUID 以匹配你的设备：

```kotlin
companion object {
    // 替换为你设备的实际 UUID
    private val OTA_SERVICE_UUID = UUID.fromString("your-service-uuid")
    private val OTA_WRITE_CHAR_UUID = UUID.fromString("your-characteristic-uuid")
}
```
#### 调优参数
```kotlin
// 写入间隔（毫秒）
private const val DEFAULT_WRITE_INTERVAL_MS = 15L    // 默认间隔
private const val MIN_WRITE_INTERVAL_MS = 2L         // 最小间隔
private const val MAX_WRITE_INTERVAL_MS = 100L       // 最大间隔

// 超时配置
private const val WRITE_TIMEOUT_MS = 3000L           // 写入超时

// MTU 设置
private const val DEFAULT_MTU = 23                   // 默认 MTU
```
### 8.错误处理
服务提供全面的错误处理：

- **连接失败**：自动重试，采用指数退避策略
- **写入超时**：数据包重传，同时调整流控参数
- **权限错误**：清晰的权限缺失错误提示
- **校验失败**：固件完整性验证失败处理

### 9.测试使用

运行 demo activity 来测试 OTA 功能：

```kotlin
// Demo 会自动执行：
// 1. 连接设备
// 2. 发现服务
// 3. 使用模拟固件开始 OTA
// 4. 记录进度并处理错误
```

使用标签过滤监控日志：
```
adb logcat -s OtaBleService:V MyGattCallback:V OtaActivity:V
```

### 10.贡献指南

欢迎贡献代码！请随时提交 Pull Request。

1. Fork 本仓库
2. 创建你的特性分支（`git checkout -b feature/AmazingFeature`）
3. 提交你的更改（`git commit -m 'Add some AmazingFeature'`）
4. 推送到分支（`git push origin feature/AmazingFeature`）
5. 开启一个 Pull Request

### 11.开源协议

本项目采用 MIT 协议 - 查看 [LICENSE](LICENSE) 文件了解详情。

### 12.联系方式

- 作者：刘照田
- 邮箱：[1345952680@qq.com]
- GitHub：[@louis-lzt](https://github.com/louis-lzt)