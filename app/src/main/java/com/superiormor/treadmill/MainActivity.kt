package com.superiormor.treadmill

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import kotlin.math.max
import kotlin.math.min

// ==========================================
// 1. КОНСТАНТЫ И ПРОТОКОЛЫ BLE
// ==========================================
object BleUuids {
    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb")
    val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Кастомный сервис дорожки hnk_sports (0xFFF0)
    val CUSTOM_SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CUSTOM_SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
}

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isTarget: Boolean,
    val device: BluetoothDevice
)

data class WorkoutState(
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val connectedDeviceName: String = "",
    val isRunning: Boolean = false,
    val statusText: String = "Нажмите «Поиск BLE», чтобы найти hnk_sports",
    val currentSpeedKmh: Float = 0.0f,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val caloriesKcal: Int = 0,
    val estimatedSteps: Int = 0,
    val rawBytesLog: String = "",
    val txBytesLog: String = "",
    val servicesDump: String = "",
    val updateStatus: String = "",
    val isUpdating: Boolean = false,
    val updateProgress: Float = 0f,
    val discoveredDevices: List<DiscoveredDevice> = emptyList()
)

// ==========================================
// 2. VIEWMODEL: BLE, УПРАВЛЕНИЕ И ОБНОВЛЕНИЯ
// ==========================================
@SuppressLint("MissingPermission")
class TreadmillViewModel : ViewModel() {
    private val _state = MutableStateFlow(WorkoutState())
    val state = _state.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var workoutStartTime: Instant? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var isFaEfProtocol: Boolean = true

    private fun isTargetDevice(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.contains("hnk") ||
               n.contains("sports") ||
               n.contains("unix") ||
               n.contains("r-280") ||
               n.contains("r280") ||
               n.contains("fit") ||
               n.contains("fs-")
    }

    // --- ПАКЕТОСТРОИТЕЛЬ ДЛЯ FA EF (hnk_sports) ---
    // Формат: [0xFA] [0xEF] [CMD] [D1] [D2] [D3] [D4] [D5] [XOR_CHECKSUM]
    fun buildFaEfPacket(cmd: Byte, d1: Byte = 0, d2: Byte = 0, d3: Byte = 0, d4: Byte = 0, d5: Byte = 0): ByteArray {
        val cs = (cmd.toInt() xor d1.toInt() xor d2.toInt() xor d3.toInt() xor d4.toInt() xor d5.toInt()).toByte()
        return byteArrayOf(
            0xFA.toByte(),
            0xEF.toByte(),
            cmd,
            d1, d2, d3, d4, d5,
            cs
        )
    }

    // --- BLE СКАНЕР ---
    fun startScan(context: Context) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(statusText = "Bluetooth выключен! Включите Bluetooth на телефоне.")
            return
        }

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _state.value = _state.value.copy(statusText = "Не удалось запустить BLE сканер.")
            return
        }

        stopScan()

        _state.value = _state.value.copy(
            isScanning = true,
            statusText = "Поиск устройств (ищем hnk_sports)...",
            discoveredDevices = emptyList()
        )

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val devName = result.scanRecord?.deviceName ?: dev.name ?: "Unknown Device"
                val address = dev.address ?: ""
                val rssi = result.rssi
                val isTarget = isTargetDevice(devName)

                Log.d("BLE_DUMP", "Found: '$devName' ($address) RSSI: $rssi, target=$isTarget")

                val current = _state.value.discoveredDevices.toMutableList()
                val idx = current.indexOfFirst { it.address == address }
                val item = DiscoveredDevice(devName, address, rssi, isTarget, dev)

                if (idx >= 0) {
                    current[idx] = item
                } else {
                    if (isTarget) current.add(0, item) else current.add(item)
                }
                _state.value = _state.value.copy(discoveredDevices = current)

                if (isTarget && !_state.value.isConnected) {
                    Log.d("BLE_DUMP", "Auto-connecting to target: $devName ($address)")
                    stopScan()
                    _state.value = _state.value.copy(
                        statusText = "Найдено $devName! Подключение...",
                        isScanning = false
                    )
                    connectToDevice(context, dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = _state.value.copy(statusText = "Ошибка сканирования: $errorCode", isScanning = false)
            }
        }

        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bleScanner?.startScan(null, settings, scanCallback)

            scanTimeoutJob = viewModelScope.launch {
                delay(25000)
                if (_state.value.isScanning) {
                    stopScan()
                    _state.value = _state.value.copy(
                        isScanning = false,
                        statusText = if (_state.value.isConnected) _state.value.statusText else "Поиск завершен. Выберите hnk_sports из списка."
                    )
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(statusText = "Ошибка: ${e.message}", isScanning = false)
        }
    }

    fun stopScan() {
        try {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            scanCallback?.let { bleScanner?.stopScan(it) }
            scanCallback = null
            _state.value = _state.value.copy(isScanning = false)
        } catch (_: Exception) {}
    }

    // --- ПОДКЛЮЧЕНИЕ К УСТРОЙСТВУ ---
    fun connectToDevice(context: Context, device: BluetoothDevice) {
        stopScan()
        val devName = device.name ?: "hnk_sports"
        _state.value = _state.value.copy(
            statusText = "Подключение к $devName (${device.address})...",
            connectedDeviceName = devName
        )

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE_DUMP", "Connected to GATT! Discovering services...")
                    _state.value = _state.value.copy(isConnected = true, statusText = "Подключено к $devName. Поиск сервисов...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE_DUMP", "Disconnected. Status: $status")
                    _state.value = _state.value.copy(
                        isConnected = false,
                        isRunning = false,
                        statusText = "Отключено (Статус: $status). Нажмите Поиск для повтора."
                    )
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val dump = buildString {
                    gatt.services.forEach { s ->
                        appendLine("Service: ${s.uuid}")
                        s.characteristics.forEach { c ->
                            appendLine("  └── Char: ${c.uuid} (Props: ${c.properties})")
                        }
                    }
                }
                _state.value = _state.value.copy(servicesDump = dump)
                Log.d("BLE_DUMP", "DUMP:\n$dump")

                // Проверяем сервис 0xFFF0 (основной для hnk_sports)
                val fff0 = gatt.getService(BleUuids.CUSTOM_SERVICE_FFF0)
                val ffe0 = gatt.getService(BleUuids.CUSTOM_SERVICE_FFE0)
                val custom = fff0 ?: ffe0

                if (custom != null) {
                    isFaEfProtocol = true
                    val uuidShort = custom.uuid.toString().substring(4, 8).uppercase(Locale.ROOT)
                    Log.d("BLE_DUMP", "Custom service found: 0x$uuidShort")

                    val notifyChar = custom.characteristics.firstOrNull {
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    }

                    // Для записи: ищем FFF2 или характеристику с WRITE / WRITE_NO_RESPONSE
                    val writeChar = custom.characteristics.firstOrNull {
                        it.uuid.toString().contains("fff2", ignoreCase = true) ||
                        it.uuid.toString().contains("ffe2", ignoreCase = true)
                    } ?: custom.characteristics.firstOrNull {
                        (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }

                    controlChar = writeChar
                    if (notifyChar != null) {
                        enableNotification(gatt, notifyChar)
                    }

                    _state.value = _state.value.copy(statusText = "Готово (Протокол FA EF, сервис 0x$uuidShort)")
                    return
                }

                // Проверяем стандартный FTMS (1826)
                val ftms = gatt.getService(BleUuids.FTMS_SERVICE)
                if (ftms != null) {
                    isFaEfProtocol = false
                    controlChar = ftms.getCharacteristic(BleUuids.CONTROL_POINT)
                    val dataChar = ftms.getCharacteristic(BleUuids.TREADMILL_DATA)
                    if (dataChar != null) {
                        enableNotification(gatt, dataChar)
                    }
                    _state.value = _state.value.copy(statusText = "Готово (Стандарт FTMS 1826)")
                    return
                }

                _state.value = _state.value.copy(statusText = "Подключено к $devName (см. дамп)")
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val bytes = characteristic.value ?: return
                val hex = bytes.joinToString(" ") { "%02X".format(it) }
                Log.d("BLE_DUMP", "RX [${characteristic.uuid}]: $hex")
                _state.value = _state.value.copy(rawBytesLog = hex)

                parseTelemetry(bytes)
            }
        }

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(statusText = "Ошибка подключения: ${e.message}")
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleUuids.CCCD)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    // --- ПАРСИНГ ТЕЛЕМЕТРИИ С ДОРОЖКИ ---
    private fun parseTelemetry(bytes: ByteArray) {
        if (bytes.size < 4) return

        // 1. Протокол FA EF (hnk_sports: FA EF 91 00 00 00 00 00 91)
        if (bytes.size >= 9 && bytes[0] == 0xFA.toByte() && bytes[1] == 0xEF.toByte()) {
            val cmd = bytes[2].toInt() and 0xFF
            if (cmd == 0x91 || cmd == 0x51 || cmd == 0x53) {
                val statusByte = bytes[3].toInt() and 0xFF  // 0 = Стоп, 1 = Разгон/Старт, 2 = Бег, 3 = Пауза
                val rawSpeed = bytes[4].toInt() and 0xFF    // Скорость в 0.1 км/ч (например, 15 = 1.5 км/ч)
                val rawDist = (bytes[5].toInt() and 0xFF) or ((bytes[6].toInt() and 0xFF) shl 8)
                val rawCal = bytes[7].toInt() and 0xFF

                val isRunning = statusByte != 0
                val speedKmh = if (isRunning && rawSpeed > 0) rawSpeed / 10.0f else 0.0f

                val currentSec = if (isRunning) _state.value.elapsedSeconds + 1 else _state.value.elapsedSeconds
                val currentDist = if (isRunning && rawDist > 0) rawDist else _state.value.distanceMeters + (speedKmh / 3.6f).toInt()
                val currentCal = if (isRunning && rawCal > 0) rawCal else (currentDist * 0.065f).toInt()
                val steps = (currentDist / 0.75f).toInt()

                _state.value = _state.value.copy(
                    isRunning = isRunning,
                    currentSpeedKmh = speedKmh,
                    elapsedSeconds = currentSec,
                    distanceMeters = currentDist,
                    caloriesKcal = currentCal,
                    estimatedSteps = steps
                )
                return
            }
        }

        // 2. Стандартный FTMS (если подключен FTMS)
        if (!isFaEfProtocol) {
            val rawSpeed = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            val speedKmh = rawSpeed / 100.0f
            val currentDist = _state.value.distanceMeters + (speedKmh / 3.6f).toInt()
            val currentSec = _state.value.elapsedSeconds + 1
            val currentCal = (currentDist * 0.065f).toInt()
            val steps = (currentDist / 0.75f).toInt()

            _state.value = _state.value.copy(
                currentSpeedKmh = speedKmh,
                distanceMeters = currentDist,
                elapsedSeconds = currentSec,
                caloriesKcal = currentCal,
                estimatedSteps = steps
            )
        }
    }

    // --- ОТПРАВКА БАЙТОВ В ДОРОЖКУ ---
    fun sendBytes(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = controlChar ?: return

        // Выставляем правильный тип записи для характеристики
        if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        char.value = bytes
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val ok = gatt.writeCharacteristic(char)
        Log.d("BLE_DUMP", "TX [${char.uuid}] ok=$ok: $hex")
        _state.value = _state.value.copy(txBytesLog = hex)
    }

    // --- УПРАВЛЕНИЕ: СТАРТ / СТОП / СКОРОСТЬ ---
    fun startBelt() {
        workoutStartTime = Instant.now()
        if (isFaEfProtocol) {
            // Для hnk_sports отправляем команду старта протокола FA EF
            // CMD 0x01 — команда старта мотора
            sendBytes(buildFaEfPacket(0x01.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00))
            // Альтернативная команда старта для некоторых китайских ревизий
            sendBytes(buildFaEfPacket(0x01.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00))
            // Также отправляем команду начальной скорости 1.0 км/ч (0x0A)
            sendBytes(buildFaEfPacket(0x03.toByte(), 10, 0x00, 0x00, 0x00, 0x00))
        } else {
            // Чистый FTMS
            sendBytes(byteArrayOf(0x00)) // Request control
            sendBytes(byteArrayOf(0x07)) // Start
        }
        _state.value = _state.value.copy(isRunning = true, currentSpeedKmh = max(1.0f, _state.value.currentSpeedKmh))
    }

    fun stopBelt() {
        if (isFaEfProtocol) {
            // CMD 0x02 — остановка мотора
            sendBytes(buildFaEfPacket(0x02.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00))
            sendBytes(buildFaEfPacket(0x02.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00))
        } else {
            sendBytes(byteArrayOf(0x08, 0x01))
        }
        _state.value = _state.value.copy(isRunning = false, currentSpeedKmh = 0.0f)
    }

    fun changeSpeed(deltaKmh: Float) {
        val targetSpeed = min(12.0f, max(1.0f, _state.value.currentSpeedKmh + deltaKmh))
        if (isFaEfProtocol) {
            // CMD 0x03 — установка скорости (в десятых долях км/ч, например 1.5 -> 15)
            val speedRaw = (targetSpeed * 10).toInt().toByte()
            sendBytes(buildFaEfPacket(0x03.toByte(), speedRaw, 0x00, 0x00, 0x00, 0x00))
        } else {
            val rawSpeed = (targetSpeed * 100).toInt()
            val cmd = byteArrayOf(
                0x02,
                (rawSpeed and 0xFF).toByte(),
                ((rawSpeed shr 8) and 0xFF).toByte()
            )
            sendBytes(cmd)
        }
        _state.value = _state.value.copy(currentSpeedKmh = targetSpeed)
    }

    // --- ОБНОВЛЕНИЕ ПРИЛОЖЕНИЯ ИЗ GITHUB ---
    fun checkAndInstallUpdate(context: Context) {
        _state.value = _state.value.copy(isUpdating = true, updateStatus = "Проверка обновлений...")
        AppUpdater.downloadAndInstall(
            context = context,
            onStatusChange = { msg -> _state.value = _state.value.copy(updateStatus = msg) },
            onProgress = { p -> _state.value = _state.value.copy(updateProgress = p) },
            onDone = { _state.value = _state.value.copy(isUpdating = false) }
        )
    }

    // --- ВЫГРУЗКА В GOOGLE FIT ---
    fun syncToGoogleFit(context: Context) {
        viewModelScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val endTime = Instant.now()
                val startTime = workoutStartTime ?: endTime.minusSeconds(_state.value.elapsedSeconds.toLong().coerceAtLeast(60))
                val offset = ZoneOffset.systemDefault().rules.getOffset(startTime)

                val records = listOf(
                    ExerciseSessionRecord(
                        startTime = startTime, startZoneOffset = offset,
                        endTime = endTime, endZoneOffset = offset,
                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
                        title = "Unix Fit R-280 Run"
                    ),
                    StepsRecord(
                        startTime = startTime, startZoneOffset = offset,
                        endTime = endTime, endZoneOffset = offset,
                        count = _state.value.estimatedSteps.toLong().coerceAtLeast(1)
                    ),
                    TotalCaloriesBurnedRecord(
                        startTime = startTime, startZoneOffset = offset,
                        endTime = endTime, endZoneOffset = offset,
                        energy = Energy.kilocalories(_state.value.caloriesKcal.toDouble().coerceAtLeast(1.0))
                    )
                )
                client.insertRecords(records)
                _state.value = _state.value.copy(statusText = "Тренировка сохранена в Google Fit!")
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusText = "Ошибка синхронизации: ${e.localizedMessage}")
            }
        }
    }
}

// ==========================================
// 3. IN-APP ОБНОВЛЕНИЕ ИЗ GITHUB
// ==========================================
object AppUpdater {
    fun downloadAndInstall(
        context: Context,
        onStatusChange: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatusChange("Запрос списка версий с GitHub...")
                val apiUrl = URL("https://api.github.com/repos/Belorx/ReFit/releases")
                val conn = apiUrl.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    onStatusChange("Ответ сервера: ${conn.responseCode}")
                    onDone()
                    return@launch
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                // Ищем прямую ссылку на APK в релизах
                val regex = """"browser_download_url":\s*"([^"]+\.apk)"""".toRegex()
                val match = regex.find(jsonStr)
                if (match == null) {
                    onStatusChange("Новый APK не найден в релизах")
                    onDone()
                    return@launch
                }

                val apkUrlStr = match.groupValues[1]
                val apkName = apkUrlStr.substringAfterLast("/")
                onStatusChange("Скачивание $apkName...")

                var redirectConn = URL(apkUrlStr).openConnection() as HttpURLConnection
                redirectConn.instanceFollowRedirects = true
                redirectConn.connectTimeout = 15000
                redirectConn.readTimeout = 20000

                var redirectCount = 0
                while ((redirectConn.responseCode == 301 || redirectConn.responseCode == 302 || redirectConn.responseCode == 307) && redirectCount < 5) {
                    val loc = redirectConn.getHeaderField("Location") ?: break
                    redirectConn = URL(loc).openConnection() as HttpURLConnection
                    redirectCount++
                }

                val totalLength = redirectConn.contentLength
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(cacheDir, "refit-update.apk")
                if (apkFile.exists()) apkFile.delete()

                redirectConn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalLength > 0) {
                                onProgress(totalRead.toFloat() / totalLength.toFloat())
                            }
                        }
                    }
                }

                onStatusChange("Запуск установщика $apkName...")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                onStatusChange("Ошибка: ${e.localizedMessage}")
            } finally {
                onDone()
            }
        }
    }
}

// ==========================================
// 4. UI И КОМПОНЕНТЫ (JETPACK COMPOSE)
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = TreadmillViewModel()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TreadmillScreen(vm, this)
                }
            }
        }
    }
}

@Composable
fun TreadmillScreen(vm: TreadmillViewModel, context: Context) {
    val state by vm.state.collectAsState()

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    fun checkBluetoothPermissions(): Boolean {
        return bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var hasBtPermissions by remember { mutableStateOf(checkBluetoothPermissions()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasBtPermissions = checkBluetoothPermissions()
        if (hasBtPermissions) {
            vm.startScan(context)
        }
    }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        vm.syncToGoogleFit(context)
    }

    LaunchedEffect(Unit) {
        if (!hasBtPermissions) {
            permLauncher.launch(bluetoothPermissions.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Шапка и плашка обновления
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "UNIX FIT R-280", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = "hnk_sports v1.1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }

                    Button(
                        onClick = { vm.checkAndInstallUpdate(context) },
                        enabled = !state.isUpdating,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (state.isUpdating) "Загрузка..." else "🔄 Обновить APK")
                    }
                }

                if (state.updateStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = state.updateStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (state.isUpdating && state.updateProgress > 0f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.updateProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Статус подключения
        Text(
            text = state.statusText,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        // Кнопка поиска
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (!hasBtPermissions) permLauncher.launch(bluetoothPermissions.toTypedArray())
                    else vm.startScan(context)
                },
                enabled = !state.isConnected && !state.isScanning
            ) {
                Text(if (state.isScanning) "Идет поиск..." else "🔍 Поиск BLE (hnk_sports)")
            }

            if (state.isScanning) {
                OutlinedButton(onClick = { vm.stopScan() }) {
                    Text("Остановить")
                }
            }
        }

        // Список найденных устройств
        if (state.discoveredDevices.isNotEmpty() && !state.isConnected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Найдено устройств: ${state.discoveredDevices.size}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    state.discoveredDevices.forEach { dev ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.name, fontWeight = if (dev.isTarget) FontWeight.Bold else FontWeight.Normal)
                                Text("${dev.address} | RSSI: ${dev.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { vm.connectToDevice(context, dev.device) }) {
                                Text("Подключить")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        // Дашборд показателей
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Скорость: ${"%.1f".format(state.currentSpeedKmh)} км/ч",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Время: ${state.elapsedSeconds / 60} мин ${state.elapsedSeconds % 60} с")
                    Text("Дистанция: ${state.distanceMeters} м")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Шаги: ~${state.estimatedSteps}")
                    Text("Калории: ${state.caloriesKcal} ккал")
                }
            }
        }

        // Главные кнопки управления
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = { vm.startBelt() },
                    enabled = state.isConnected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("▶ СТАРТ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { vm.stopBelt() },
                    enabled = state.isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("⏹ СТОП", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FilledTonalButton(
                    onClick = { vm.changeSpeed(-0.5f) },
                    enabled = state.isConnected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("- 0.5 км/ч")
                }
                FilledTonalButton(
                    onClick = { vm.changeSpeed(+0.5f) },
                    enabled = state.isConnected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ 0.5 км/ч")
                }
            }

            OutlinedButton(
                onClick = {
                    val hcPermissions = setOf(
                        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
                        HealthPermission.getWritePermission(StepsRecord::class),
                        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
                    )
                    healthConnectLauncher.launch(hcPermissions)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Синхронизировать с Google Fit")
            }
        }

        // Блок BLE Тестирования и Диагностики для hnk_sports (FA EF)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "📡 ДИАГНОСТИКА И ТЕСТ КОМАНД (FA EF)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )

                if (state.rawBytesLog.isNotEmpty()) {
                    Text(
                        text = "Сырые байты (RX): ${state.rawBytesLog}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.txBytesLog.isNotEmpty()) {
                    Text(
                        text = "Отправлено (TX): ${state.txBytesLog}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Быстрые тесты команд hnk_sports:", style = MaterialTheme.typography.labelSmall)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x01.toByte(), 0x01)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 01: Старт", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x02.toByte(), 0x00)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 02: Стоп", fontSize = 11.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x03.toByte(), 10)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 03: 1.0 км/ч", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x03.toByte(), 20)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 03: 2.0 км/ч", fontSize = 11.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x53.toByte(), 0x01, 0x01)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 53: S-Старт", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { vm.sendBytes(vm.buildFaEfPacket(0x91.toByte())) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FA EF 91: Опрос", fontSize = 11.sp)
                    }
                }

                // Кастомный HEX ввод
                var customHexInput by remember { mutableStateOf("FA EF 01 01 00 00 00 00 00") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Кастомный HEX", fontSize = 11.sp) },
                        singleLine = true
                    )
                    Button(onClick = {
                        try {
                            val clean = customHexInput.replace(" ", "").replace("0x", "")
                            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            vm.sendBytes(bytes)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Ошибка HEX формата", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Шли")
                    }
                }

                // Дамп сервисов
                if (state.servicesDump.isNotEmpty()) {
                    var showDump by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showDump = !showDump }) {
                            Text(if (showDump) "Скрыть дамп сервисов" else "Показать дамп сервисов")
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("BLE Dump", state.servicesDump))
                            Toast.makeText(context, "Дамп скопирован!", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("📋", fontSize = 16.sp)
                        }
                    }
                    if (showDump) {
                        Text(
                            text = state.servicesDump,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
