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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import kotlin.math.max
import kotlin.math.min

// ==========================================
// 1. КОНСТАНТЫ BLE (FTMS + Китайские чипы)
// ==========================================
object BleUuids {
    // Стандартный FTMS (Fitness Machine Service)
    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb") // Notify (телеметрия)
    val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")  // Write (команды)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Китайские кастомные чипы (FitShow, Spax, FS, hnk_sports и др.)
    val CUSTOM_SERVICE_FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CUSTOM_SERVICE_FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
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
    val statusText: String = "Нажмите «Поиск BLE», чтобы найти hnk_sports",
    val currentSpeedKmh: Float = 0.0f,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val caloriesKcal: Int = 0,
    val estimatedSteps: Int = 0,
    val rawBytesLog: String = "",
    val servicesDump: String = "",
    val discoveredDevices: List<DiscoveredDevice> = emptyList()
)

// ==========================================
// 2. VIEWMODEL: БИЗНЕС-ЛОГИКА, BLE И HEALTH
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

    // --- BLE СКАНЕР ---
    fun startScan(context: Context) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter == null) {
            _state.value = _state.value.copy(statusText = "Bluetooth не поддерживается на устройстве")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = _state.value.copy(statusText = "Bluetooth выключен! Пожалуйста, включите его.")
            return
        }

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            _state.value = _state.value.copy(statusText = "Не удалось запустить BLE сканер. Проверьте Bluetooth.")
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
                val devName = result.scanRecord?.deviceName 
                    ?: dev.name 
                    ?: "Unknown Device"
                val address = dev.address ?: ""
                val rssi = result.rssi
                val isTarget = isTargetDevice(devName)

                Log.d("BLE_DUMP", "Found: '$devName' ($address) RSSI: $rssi, target=$isTarget")

                val current = _state.value.discoveredDevices.toMutableList()
                val idx = current.indexOfFirst { it.address == address }
                val item = DiscoveredDevice(
                    name = devName,
                    address = address,
                    rssi = rssi,
                    isTarget = isTarget,
                    device = dev
                )

                if (idx >= 0) {
                    current[idx] = item
                } else {
                    if (isTarget) {
                        current.add(0, item)
                    } else {
                        current.add(item)
                    }
                }
                _state.value = _state.value.copy(discoveredDevices = current)

                // Если найдена целевая дорожка (hnk_sports / unix) — подключаемся сразу!
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
                val err = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Сканирование уже запущено"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Ошибка регистрации приложения"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE сканирование не поддерживается"
                    SCAN_FAILED_INTERNAL_ERROR -> "Внутренняя ошибка BLE стека"
                    else -> "Ошибка сканирования: $errorCode"
                }
                Log.e("BLE_DUMP", "Scan failed: $err")
                _state.value = _state.value.copy(statusText = err, isScanning = false)
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            bleScanner?.startScan(null, settings, scanCallback)

            scanTimeoutJob = viewModelScope.launch {
                delay(25000)
                if (_state.value.isScanning) {
                    stopScan()
                    _state.value = _state.value.copy(
                        isScanning = false,
                        statusText = if (_state.value.isConnected) _state.value.statusText else "Поиск завершен. Если hnk_sports в списке, нажмите «Подключить»."
                    )
                }
            }
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(statusText = "Нет разрешения на поиск Bluetooth", isScanning = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(statusText = "Ошибка: ${e.message}", isScanning = false)
        }
    }

    fun stopScan() {
        try {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            scanCallback?.let { cb ->
                bleScanner?.stopScan(cb)
            }
            scanCallback = null
            _state.value = _state.value.copy(isScanning = false)
        } catch (e: Exception) {
            Log.e("BLE_DUMP", "Error stopping scan", e)
        }
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
                Log.d("BLE_DUMP", "onConnectionStateChange: status=$status, newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE_DUMP", "Connected to GATT server! Discovering services...")
                    _state.value = _state.value.copy(isConnected = true, statusText = "Подключено к $devName. Поиск сервисов...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE_DUMP", "Disconnected from GATT server. Status: $status")
                    _state.value = _state.value.copy(
                        isConnected = false,
                        statusText = "Отключено (Статус: $status). Нажмите Поиск для повтора."
                    )
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d("BLE_DUMP", "onServicesDiscovered status=$status, count=${gatt.services.size}")
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

                // 1. Проверяем стандартный FTMS (0x1826)
                val ftms = gatt.getService(BleUuids.FTMS_SERVICE)
                if (ftms != null) {
                    Log.d("BLE_DUMP", "Bingo! FTMS 0x1826 found.")
                    controlChar = ftms.getCharacteristic(BleUuids.CONTROL_POINT)
                    val dataChar = ftms.getCharacteristic(BleUuids.TREADMILL_DATA)
                    if (dataChar != null) {
                        enableNotification(gatt, dataChar)
                    }
                    _state.value = _state.value.copy(statusText = "Готово (Стандарт FTMS 1826)")
                    return
                }

                // 2. Проверяем китайские FitShow сервисы FFE0 или FFF0
                val customFfe0 = gatt.getService(BleUuids.CUSTOM_SERVICE_FFE0)
                val customFff0 = gatt.getService(BleUuids.CUSTOM_SERVICE_FFF0)
                val custom = customFfe0 ?: customFff0

                if (custom != null) {
                    val uuidShort = custom.uuid.toString().substring(4, 8).uppercase(Locale.ROOT)
                    Log.d("BLE_DUMP", "Custom FitShow/Chinese service found: 0x$uuidShort")
                    val notifyChar = custom.characteristics.firstOrNull {
                        (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    }
                    val writeChar = custom.characteristics.firstOrNull {
                        (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }

                    controlChar = writeChar
                    if (notifyChar != null) {
                        enableNotification(gatt, notifyChar)
                    }
                    _state.value = _state.value.copy(statusText = "Готово (Кастомный протокол 0x$uuidShort)")
                    return
                }

                // 3. Если другой чип: подписываемся на первую подходящую характеристику
                val anyNotify = gatt.services.flatMap { it.characteristics }.firstOrNull {
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                }
                val anyWrite = gatt.services.flatMap { it.characteristics }.firstOrNull {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }

                if (anyNotify != null) {
                    enableNotification(gatt, anyNotify)
                }
                controlChar = anyWrite
                _state.value = _state.value.copy(statusText = "Подключено к $devName (см. дамп сервисов)")
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val bytes = characteristic.value ?: return
                val hex = bytes.joinToString(" ") { "%02X".format(it) }
                Log.d("BLE_DUMP", "RX [${characteristic.uuid}]: $hex")
                _state.value = _state.value.copy(rawBytesLog = hex)
                parseFtmsTelemetry(bytes)
            }
        }

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(statusText = "Ошибка: нет разрешения BLUETOOTH_CONNECT")
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

    // --- ПАРСИНГ ТЕЛЕМЕТРИИ ---
    private fun parseFtmsTelemetry(bytes: ByteArray) {
        if (bytes.size < 4) return
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

    // --- КОМАНДЫ УПРАВЛЕНИЯ ДОРОЖКОЙ ---
    private fun sendCommand(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = controlChar ?: return
        char.value = bytes
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d("BLE_DUMP", "TX [${char.uuid}]: $hex")
        gatt.writeCharacteristic(char)
    }

    fun requestControl() = sendCommand(byteArrayOf(0x00))

    fun startBelt() {
        workoutStartTime = Instant.now()
        val charUuid = controlChar?.uuid?.toString() ?: ""
        if (charUuid.contains("2ad9", ignoreCase = true)) {
            requestControl()
            sendCommand(byteArrayOf(0x07))
        } else {
            // FitShow пакет старта + FTMS байт на случай совместимости
            sendCommand(byteArrayOf(0x02, 0x53, 0x01, 0x01, 0x53, 0x03))
            sendCommand(byteArrayOf(0x07))
        }
    }

    fun stopBelt() {
        val charUuid = controlChar?.uuid?.toString() ?: ""
        if (charUuid.contains("2ad9", ignoreCase = true)) {
            sendCommand(byteArrayOf(0x08, 0x01))
        } else {
            sendCommand(byteArrayOf(0x02, 0x53, 0x01, 0x00, 0x52, 0x03))
            sendCommand(byteArrayOf(0x08, 0x01))
        }
    }

    fun changeSpeed(deltaKmh: Float) {
        val targetSpeed = min(12.0f, max(1.0f, _state.value.currentSpeedKmh + deltaKmh))
        val rawSpeed = (targetSpeed * 100).toInt()
        
        val charUuid = controlChar?.uuid?.toString() ?: ""
        if (charUuid.contains("2ad9", ignoreCase = true)) {
            val cmd = byteArrayOf(
                0x02,
                (rawSpeed and 0xFF).toByte(),
                ((rawSpeed shr 8) and 0xFF).toByte()
            )
            sendCommand(cmd)
        } else {
            // FitShow команда скорости
            val speedInt = (targetSpeed * 10).toInt()
            val checksum = (0x53 xor 0x02 xor (speedInt and 0xFF)).toByte()
            sendCommand(byteArrayOf(0x02, 0x53, 0x02, (speedInt and 0xFF).toByte(), checksum, 0x03))
        }
    }

    // --- ВЫГРУЗКА В HEALTH CONNECT / GOOGLE FIT ---
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
                _state.value = _state.value.copy(statusText = "Тренировка сохранена в Health Connect / Google Fit!")
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusText = "Ошибка синхронизации: ${e.localizedMessage}")
            }
        }
    }
}

// ==========================================
// 3. UI (JETPACK COMPOSE) И РАЗРЕШЕНИЯ
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

    // 1. Разрешения Bluetooth и геолокации
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

    // 2. Health Connect launcher (отдельно от BLE разрешений)
    val healthConnectLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        vm.syncToGoogleFit(context)
    }

    // Запрос Bluetooth-разрешений при первом запуске
    LaunchedEffect(Unit) {
        if (!hasBtPermissions) {
            permLauncher.launch(bluetoothPermissions.toTypedArray())
        }
    }

    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val isBtEnabled = btManager?.adapter?.isEnabled == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "UNIX FIT R-280", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(text = "hnk_sports controller", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.statusText,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Предупреждение если нет разрешений
        if (!hasBtPermissions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "⚠️ Требуются разрешения на Bluetooth и геолокацию",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Android требует разрешение на поиск BLE устройств поблизости.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permLauncher.launch(bluetoothPermissions.toTypedArray()) }) {
                        Text("Предоставить доступ")
                    }
                }
            }
        }

        // Предупреждение если Bluetooth выключен
        if (!isBtEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "⚠️ Bluetooth отключен на телефоне",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (_: Exception) {}
                    }) {
                        Text("Включить Bluetooth")
                    }
                }
            }
        }

        // Кнопка сканирования
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (!hasBtPermissions) {
                        permLauncher.launch(bluetoothPermissions.toTypedArray())
                    } else {
                        vm.startScan(context)
                    }
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Найдено устройств: ${state.discoveredDevices.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    state.discoveredDevices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = dev.name,
                                        fontWeight = if (dev.isTarget) FontWeight.Bold else FontWeight.Normal,
                                        color = if (dev.isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (dev.isTarget) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                "Дорожка",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${dev.address} | RSSI: ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { vm.connectToDevice(context, dev.device) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Подключить")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        // Дашборд тренировки
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        // Кнопки управления бегом
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

        // Диагностический блок BLE (сырые байты и дамп сервисов)
        if (state.rawBytesLog.isNotEmpty() || state.servicesDump.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📡 BLE ДИАГНОСТИКА (для Antigravity Flash)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )

                    if (state.rawBytesLog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Сырые байты (RX): ${state.rawBytesLog}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }

                    if (state.servicesDump.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
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
                                Toast.makeText(context, "Дамп сервисов скопирован!", Toast.LENGTH_SHORT).show()
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
}
