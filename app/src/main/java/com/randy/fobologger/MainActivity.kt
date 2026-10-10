package com.randy.fobologger

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private lateinit var targetSpinner: Spinner
    private val lines = ArrayList<String>()
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val targets = listOf("N0334", "N0201", "N02EZ", "N02JB")
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val lastPayload = mutableMapOf<String, String>()
    private val packetCounts = mutableMapOf<String, Int>()
    private val FEAF = ParcelUuid.fromString("0000feaf-0000-1000-8000-00805f9b34fb")
    private val D11 = UUID.fromString("18ee2ef5-263d-4559-959f-4f9c429f9d11")
    private val D12 = UUID.fromString("18ee2ef5-263d-4559-959f-4f9c429f9d12")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val scanner get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handleResult(result) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::handleResult) }
        override fun onScanFailed(errorCode: Int) { addLine("${now()}\tSCAN_FAILED\t$errorCode") }
    }

    private fun handleResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val name = record.deviceName ?: try { result.device.name } catch (_: SecurityException) { null } ?: return
        if (name !in targets) return
        devices[name] = result.device
        val raw = record.bytes.toHex()
        val feaf = record.getServiceData(FEAF)?.toHex() ?: ""
        val serviceData = record.serviceData.entries.joinToString(";") { "${it.key.uuid}=${it.value.toHex()}" }
        val manufacturer = record.manufacturerSpecificData.let { a ->
            (0 until a.size()).joinToString(";") { i -> "%04X=%s".format(a.keyAt(i), a.valueAt(i).toHex()) }
        }
        val count = (packetCounts[name] ?: 0) + 1
        packetCounts[name] = count
        val old = lastPayload[name]
        val diff = if (old != null && old != feaf) byteDiff(old, feaf) else if (old == null) "FIRST" else "same"
        lastPayload[name] = feaf
        addLine("${now()}\tADV\t$name\t#=$count\taddr=${result.device.address}\tRSSI=${result.rssi}\tFEAF=$feaf\tdiff=$diff\traw=$raw\tservice=$serviceData\tmfg=$manufacturer")
        runOnUiThread { status.text = "Scanning · ${packetCounts.entries.joinToString("  ") { "${it.key}:${it.value}" }}" }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            addLine("${now()}\tGATT_STATE\tstatus=$statusCode\tstate=$newState\taddr=${g.device.address}")
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { g.close(); if (gatt === g) gatt = null }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            addLine("${now()}\tGATT_SERVICES\tstatus=$statusCode")
            g.services.forEach { s ->
                addLine("${now()}\tSERVICE\t${s.uuid}")
                s.characteristics.forEach { c -> addLine("${now()}\tCHAR\t${c.uuid}\tprops=${c.properties}") }
            }
            // v4 observation mode: enable standard CCCD indications on D12 only.
            // Never writes to D11 or to either FOBO characteristic value.
            subscribeD12(g)
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) {
            addLine("${now()}\tGATT_READ\t${short(c.uuid)}\tstatus=$statusCode\tvalue=${c.value?.toHex() ?: ""}")
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            addLine("${now()}\tGATT_READ\t${short(c.uuid)}\tstatus=$statusCode\tvalue=${value.toHex()}")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            addLine("${now()}\tCCCD_WRITE\t${short(d.characteristic.uuid)}\tstatus=$statusCode\tvalue=${d.value?.toHex() ?: ""}")
            if (d.characteristic.uuid == D12 && statusCode == BluetoothGatt.GATT_SUCCESS) {
                addLine("${now()}\t=== D12 INDICATIONS ARMED · NO D11 COMMAND WRITES ===")
                runOnUiThread { status.text = "Connected · D12 indications armed · no D11 writes" }
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            addLine("${now()}\tGATT_INDICATION\t${short(c.uuid)}\tlen=${value.size}\tvalue=${value.toHex()}")
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            addLine("${now()}\tGATT_INDICATION\t${short(c.uuid)}\tvalue=${c.value?.toHex() ?: ""}")
        }
    }

    private fun subscribeD12(g: BluetoothGatt) {
        val c = g.getService(FEAF.uuid)?.getCharacteristic(D12)
        if (c == null) {
            addLine("${now()}\tD12_SUBSCRIBE_FAILED\treason=characteristic_missing")
            return
        }
        val supportsIndicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        addLine("${now()}\tD12_SUBSCRIBE_REQUEST\tprops=${c.properties}\tindicate=$supportsIndicate")
        if (!supportsIndicate) {
            addLine("${now()}\tD12_SUBSCRIBE_FAILED\treason=indicate_not_supported")
            return
        }
        val localOk = g.setCharacteristicNotification(c, true)
        addLine("${now()}\tD12_LOCAL_NOTIFY\tok=$localOk")
        if (!localOk) return
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) {
            addLine("${now()}\tD12_SUBSCRIBE_FAILED\treason=cccd_missing")
            return
        }
        addLine("${now()}\tCCCD_WRITE_REQUEST\tD12\tvalue=0200")
        if (Build.VERSION.SDK_INT >= 33) {
            val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            addLine("${now()}\tCCCD_WRITE_QUEUED\tresult=$rc")
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION")
            val queued = g.writeDescriptor(cccd)
            addLine("${now()}\tCCCD_WRITE_QUEUED\tresult=$queued")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi(); requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,12,20,12) }
        root.addView(TextView(this).apply { text = "FOBO Tire Lite Diagnostic Logger v4"; textSize = 24f })
        status = TextView(this).apply { text = "Ready · passive scan + safe D12 indication subscription"; textSize = 15f }
        root.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "START SCAN"; setOnClickListener { toggleScan() } }
        row1.addView(startButton)
        row1.addView(Button(this).apply { text = "NORMAL"; setOnClickListener { mark("NORMAL") } })
        row1.addView(Button(this).apply { text = "REMOVED"; setOnClickListener { mark("SENSOR REMOVED") } })
        row1.addView(Button(this).apply { text = "REINSTALLED"; setOnClickListener { mark("SENSOR REINSTALLED") } })
        row1.addView(Button(this).apply { text = "EXPORT"; setOnClickListener { shareLog() } })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        targetSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, targets) }
        row2.addView(targetSpinner, LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.WRAP_CONTENT))
        row2.addView(Button(this).apply { text = "SUBSCRIBE D12 (NO D11 WRITES)"; setOnClickListener { connectReadOnly() } })
        row2.addView(Button(this).apply { text = "DISCONNECT"; setOnClickListener { disconnectGatt() } })
        row2.addView(Button(this).apply { text = "CLEAR"; setOnClickListener { lines.clear(); logView.text = ""; packetCounts.clear(); lastPayload.clear() } })
        root.addView(row2)

        root.addView(TextView(this).apply { text = "v4 writes only the standard D12 CCCD (0x2902) to enable indications. It NEVER writes D11 or either FOBO characteristic value. Use NORMAL / REMOVED / REINSTALLED while subscribed."; textSize = 12f })
        logView = TextView(this).apply { textSize = 10f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun connectReadOnly() {
        if (!hasPermissions()) { requestPermissionsIfNeeded(); return }
        val name = targetSpinner.selectedItem.toString()
        val d = devices[name]
        if (d == null) { Toast.makeText(this, "Scan until $name has been seen first", Toast.LENGTH_LONG).show(); return }
        disconnectGatt()
        addLine("${now()}\t=== SAFE D12 SUBSCRIBE CONNECT $name ${d.address} ===")
        gatt = if (Build.VERSION.SDK_INT >= 23) d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE) else d.connectGatt(this, false, gattCallback)
    }

    private fun disconnectGatt() { try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}; gatt = null }
    private fun mark(label: String) { addLine("${now()}\t*** MARK: $label ***") }

    private fun toggleScan() { if (!hasPermissions()) { requestPermissionsIfNeeded(); return }; if (scanning) stopScan() else startScan() }
    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestPermissionsIfNeeded() { if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 100) else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100) }

    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (!adapter.isEnabled) { status.text = "Bluetooth is off"; return }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        scanner.startScan(null, settings, callback); scanning = true; startButton.text = "STOP SCAN"
        addLine("${now()}\t=== SCAN START ===")
    }
    private fun stopScan() { if (hasPermissions()) scanner.stopScan(callback); scanning = false; startButton.text = "START SCAN"; addLine("${now()}\t=== SCAN STOP ===") }

    private fun addLine(s: String) { runOnUiThread { lines.add(s); if (lines.size > 15000) lines.removeAt(0); logView.append(s + "\n") } }
    private fun shareLog() {
        val text = lines.joinToString("\n")
        val name = "FOBO_v4_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        getExternalFilesDir(null)?.let { File(it, name).writeText(text) }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("FOBO log", text))
        Toast.makeText(this, "Saved $name and copied log to clipboard", Toast.LENGTH_LONG).show()
    }
    private fun byteDiff(a: String, b: String): String {
        if (a.length != b.length) return "LEN ${a.length/2}->${b.length/2}"
        val changed = mutableListOf<String>()
        var i = 0
        while (i + 1 < a.length) { if (a.substring(i,i+2) != b.substring(i,i+2)) changed += "${i/2}:${a.substring(i,i+2)}>${b.substring(i,i+2)}"; i += 2 }
        return if (changed.isEmpty()) "same" else changed.joinToString(",")
    }
    private fun short(u: UUID) = when(u) { D11 -> "9D11"; D12 -> "9D12"; else -> u.toString() }
    private fun now() = sdf.format(Date())
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    override fun onDestroy() { if (scanning && hasPermissions()) scanner.stopScan(callback); disconnectGatt(); super.onDestroy() }
}
