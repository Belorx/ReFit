package com.superiormor.treadmill

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.units.Energy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import kotlin.math.max
import kotlin.math.min

// ==========================================
// 1. КОНСТАНТЫ BLE (FTMS + Китайский кастом)
// ==========================================
object BleUuids {
    // Стандартный FTMS (Fitness Machine Service)
    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val TREADMILL_DATA: UUID = UUID.fromString("00002acd-0000-1000-8000-00805f9b34fb") // Notify (телеметрия)
    val CONTROL_POINT: UUID = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")  // Write (команды)
    
    // Дескриптор для подписки на уведомления (стандарт для всех BLE)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Если в R-280 стоит кастомный китайский чип (FitShow и аналоги), раскомментируй в процессе реверс-инжиниринга:
    // val CUSTOM_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    // val CUSTOM_CHAR_NOTIFY: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    // val CUSTOM_CHAR_WRITE: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
}

data class WorkoutState(
    val isConnected: Boolean = false,
    val statusText: String = "Disconnected",
    val currentSpeedKmh: Float = 0.0f,
    val elapsedSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val caloriesKcal: Int = 0,
    val estimatedSteps: Int = 0
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

    // --- BLE СКАНЕР ---
    fun startScan(context: Context) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: return
        
        _state.value = _state.value.copy(statusText = "Scanning...")
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: ""
                // Ищем по типичным префиксам китайских дорожек
                if (name.contains("UNIX", true) || name.contains("FS-", true) || name.contains("R-280", true) || name.contains("Fit", true)) {
                    scanner.stopScan(this)
                    _state.value = _state.value.copy(statusText = "Found $name. Connecting...")
                    connectToDevice(context, result.device)
                }
            }
        })
    }

    // --- ПОДКЛЮЧЕНИЕ И GATT CALLBACK ---
    private fun connectToDevice(context: Context, device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.value = _state.value.copy(isConnected = true, statusText = "Connected. Discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = _state.value.copy(isConnected = false, statusText = "Disconnected")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                // ПЕЧАТАЕМ ВСЕ UUID В LOGCAT (пригодится в Antigravity, если там не FTMS)
                gatt.services.forEach { service ->
                    Log.d("BLE_DUMP", "Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d("BLE_DUMP", "   └── Char: ${char.uuid} (Props: ${char.properties})")
                    }
                }

                val service = gatt.getService(BleUuids.FTMS_SERVICE)
                if (service == null) {
                    _state.value = _state.value.copy(statusText = "FTMS not found! Check Logcat (BLE_DUMP)")
                    return
                }

                controlChar = service.getCharacteristic(BleUuids.CONTROL_POINT)
                val dataChar = service.getCharacteristic(BleUuids.TREADMILL_DATA)

                // Подписываемся на телеметрию
                if (dataChar != null) {
                    gatt.setCharacteristicNotification(dataChar, true)
                    val descriptor = dataChar.getDescriptor(BleUuids.CCCD)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
                _state.value = _state.value.copy(statusText = "Ready")
            }

            // Сюда прилетают байты с дорожки
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == BleUuids.TREADMILL_DATA) {
                    parseFtmsTelemetry(characteristic.value)
                }
            }
        })
    }

    // --- ПАРСИНГ ТЕЛЕМЕТРИИ (Under the hood) ---
    private fun parseFtmsTelemetry(bytes: ByteArray) {
        if (bytes.size < 4) return
        // В FTMS первые 2 байта — битовая маска флагов (какие поля присутствуют в пакете)
        // Следующие 2 байта (UInt16, Little Endian) — мгновенная скорость с разрешением 0.01 км/ч
        val rawSpeed = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val speedKmh = rawSpeed / 100.0f

        // ВНИМАНИЕ: Смещения (offsets) для дистанции, калорий и времени в FTMS зависят от флагов в bytes[0..1].
        // Для заглушки (чтобы код работал сразу) считаем их математически или берем по типовым индексам.
        // В Antigravity просто скинешь лог массива bytes, и Flash напишет точный побитовый сдвиг под R-280.
        val currentDist = _state.value.distanceMeters + (speedKmh / 3.6f).toInt()
        val currentSec = _state.value.elapsedSeconds + 1
        val currentCal = (currentDist * 0.065f).toInt() // Усредненный расход
        
        // Дорожки почти никогда не отдают шаги напрямую, считаем через длину шага (~0.75м)
        val steps = (currentDist / 0.75f).toInt()

        _state.value = _state.value.copy(
            currentSpeedKmh = speedKmh,
            distanceMeters = currentDist,
            elapsedSeconds = currentSec,
            caloriesKcal = currentCal,
            estimatedSteps = steps
        )
    }

    // --- КОМАНДЫ УПРАВЛЕНИЯ ДОРОЖКОЙ (FTMS Control Point) ---
    private fun sendCommand(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val char = controlChar ?: return
        char.value = bytes
        gatt.writeCharacteristic(char)
    }

    fun requestControl() = sendCommand(byteArrayOf(0x00)) // Захват управления (обязательно перед стартом в FTMS)

    fun startBelt() {
        requestControl()
        workoutStartTime = Instant.now()
        // 0x07 — команда Start/Resume в FTMS
        sendCommand(byteArrayOf(0x07))
    }

    fun stopBelt() {
        // 0x08 — Stop/Pause, 0x01 — Stop (0x02 — Pause)
        sendCommand(byteArrayOf(0x08, 0x01))
    }

    fun changeSpeed(deltaKmh: Float) {
        val targetSpeed = min(12.0f, max(1.0f, _state.value.currentSpeedKmh + deltaKmh))
        val rawSpeed = (targetSpeed * 100).toInt() // Переводим в шаг 0.01 км/ч
        
        // 0x02 — код установки скорости + 2 байта Little Endian
        val cmd = byteArrayOf(
            0x02,
            (rawSpeed and 0xFF).toByte(),
            ((rawSpeed shr 8) and 0xFF).toByte()
        )
        sendCommand(cmd)
    }

    // --- ВЫГРУЗКА В GOOGLE FIT (ЧЕРЕЗ HEALTH CONNECT) ---
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
                _state.value = _state.value.copy(statusText = "Synced to Health Connect / Google Fit!")
            } catch (e: Exception) {
                _state.value = _state.value.copy(statusText = "Sync Error: ${e.localizedMessage}")
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

    // Запрос разрешений BLE + Health Connect при старте
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions granted/denied */ }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Статус и кнопка поиска
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "UNIX FIT R-280 CONTROLLER", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = state.statusText, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { vm.startScan(context) }, enabled = !state.isConnected) {
                Text("Scan & Connect BLE")
            }
        }

        // Дашборд телеметрии
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speed: ${"%.1f".format(state.currentSpeedKmh)} km/h", style = MaterialTheme.typography.headlineMedium)
                Divider()
                Text("Time: ${state.elapsedSeconds / 60}m ${state.elapsedSeconds % 60}s")
                Text("Distance: ${state.distanceMeters} m")
                Text("Steps (est.): ${state.estimatedSteps}")
                Text("Calories: ${state.caloriesKcal} kcal")
            }
        }

        // Пульт управления
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { vm.startBelt() }, enabled = state.isConnected) { Text("START") }
                Button(
                    onClick = { vm.stopBelt() },
                    enabled = state.isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("STOP") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(onClick = { vm.changeSpeed(-0.5f) }, enabled = state.isConnected) { Text("- 0.5 km/h") }
                FilledTonalButton(onClick = { vm.changeSpeed(+0.5f) }, enabled = state.isConnected) { Text("+ 0.5 km/h") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { vm.syncToGoogleFit(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Workout to Google Fit")
            }
        }
    }
}
