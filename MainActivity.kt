package com.komaki.rangepro

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

private val KNOWN_SERVICES = listOf(
    UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912")
)
private val FFF1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

data class Telemetry(
    val soc: Double? = null, val voltage: Double? = null, val current: Double? = null,
    val power: Double? = null, val temperature: Double? = null,
    val cells: List<Double> = emptyList(), val cycles: Int? = null,
    val charging: Boolean? = null, val raw: String = "Waiting for BMS data"
)

/** Intentionally unimplemented until Komaki/PackDial protocol documentation is obtained.
 * Never infer field offsets or transmit guessed commands to FFF2 or any BMS characteristic.
 */
interface BmsProtocolAdapter {
    fun decode(characteristic: UUID, bytes: ByteArray): Telemetry?
}
class UndecodedBmsAdapter : BmsProtocolAdapter {
    override fun decode(characteristic: UUID, bytes: ByteArray): Telemetry? = null
}

class MainActivity : ComponentActivity() {
    private var gatt: BluetoothGatt? = null
    private val adapter = UndecodedBmsAdapter()
    private val telemetry = mutableStateOf(Telemetry())
    private val status = mutableStateOf("Not connected")
    private val deviceName = mutableStateOf("No BMS selected")
    private val logLines = mutableStateListOf<String>()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) scanAndConnect() else addLog("Bluetooth permissions not granted")
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (name.contains("NEO48030L260904337", true) || name.contains("NEO", true)) {
                try { BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner.stopScan(this) } catch (_: SecurityException) {}
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { addLog("BLE scan failed: $errorCode") }
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status.value = "Connected · discovering services"
                    addLog("Connected; discovering GATT services")
                    g.discoverServices()
                } else {
                    status.value = "Disconnected"
                    addLog("BMS disconnected")
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) { addLog("GATT discovery failed: $statusCode"); return@runOnUiThread }
                g.services.forEach { service ->
                    addLog("SERVICE ${service.uuid}")
                    service.characteristics.forEach { c ->
                        addLog("CHAR ${c.uuid} props=${c.properties}")
                        if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 &&
                            (c.uuid == FFF1 || service.uuid in KNOWN_SERVICES)) {
                            enableNotifications(g, c)
                        } else if ((c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                            g.readCharacteristic(c)
                        }
                    }
                }
                status.value = "Connected · protocol not decoded"
            }
        }
        @Deprecated("Legacy callback for broad Android compatibility")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            receive(c.uuid, value, "READ")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(c.uuid, value, "NOTIFY")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("KOMAKI RANGE PRO", style = MaterialTheme.typography.headlineSmall)
                        Text("Komaki X1 · Native Android · direct BLE")
                        Card { Column(Modifier.padding(16.dp)) {
                            Text("BMS CONNECTION", style = MaterialTheme.typography.titleMedium)
                            Text(status.value)
                            Text(deviceName.value)
                            Button(onClick = { requestPermissionsAndScan() }) { Text("CONNECT BMS") }
                        } }
                        Text("BATTERY TELEMETRY", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("SOC", telemetry.value.soc?.let { "%.0f%%".format(it) } ?: "—")
                            Metric("Voltage", telemetry.value.voltage?.let { "%.2f V".format(it) } ?: "—")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("Current", telemetry.value.current?.let { "%.2f A".format(it) } ?: "—")
                            Metric("Power", telemetry.value.power?.let { "%.0f W".format(it) } ?: "—")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("Temperature", telemetry.value.temperature?.let { "%.1f °C".format(it) } ?: "—")
                            Metric("Cycles", telemetry.value.cycles?.toString() ?: "—")
                        }
                        Text("CELL VOLTAGES · 15S", style = MaterialTheme.typography.titleMedium)
                        Text(if (telemetry.value.cells.isEmpty()) "Waiting for decoded cell data" else telemetry.value.cells.mapIndexed { i,v -> "C${i+1}: %.3f V".format(v) }.joinToString("   "))
                        Text("GPS / RIDES / CHARGING", style = MaterialTheme.typography.titleMedium)
                        Text("Native project foundation created. GPS foreground tracking, Room history and charging analytics are next modules; live BMS values require the verified protocol.")
                        Text("DIAGNOSTICS", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(Modifier.weight(1f)) { items(logLines.size) { Text(logLines[it], style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
    }
    @Composable private fun Metric(label: String, value: String) {
        Card(Modifier.width(160.dp)) { Column(Modifier.padding(12.dp)) { Text(label); Text(value, style = MaterialTheme.typography.titleLarge) } }
    }
    private fun requestPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else scanAndConnect()
    }
    private fun scanAndConnect() {
        try {
            status.value = "Scanning for Komaki BMS…"
            BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner.startScan(scanCallback)
            addLog("Scanning BLE devices; select matching NEO BMS when found")
        } catch (e: Exception) { addLog("Scan error: ${e.message}") }
    }
    private fun connect(device: BluetoothDevice) {
        try {
            deviceName.value = device.name ?: device.address
            status.value = "Connecting…"
            gatt?.close()
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) { addLog("Connect error: ${e.message}") }
    }
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(c, true)
            val descriptor = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else { @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
            }
        } catch (e: Exception) { addLog("Notify setup error: ${e.message}") }
    }
    private fun receive(uuid: UUID, bytes: ByteArray, kind: String) {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        runOnUiThread {
            addLog("$kind $uuid : $hex")
            adapter.decode(uuid, bytes)?.let { telemetry.value = it }
        }
    }
    private fun addLog(line: String) { runOnUiThread { logLines.add(0, line); if (logLines.size > 100) logLines.removeAt(logLines.lastIndex) } }
    override fun onDestroy() { gatt?.close(); super.onDestroy() }
}
