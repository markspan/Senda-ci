package io.github.markspan.sensorium

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.github.markspan.sensorium.ble.PmdCommand
import io.github.markspan.sensorium.lsl.LslNative
import io.github.markspan.sensorium.sensors.AudioStreamer
import io.github.markspan.sensorium.sensors.PhoneSensor
import io.github.markspan.sensorium.sensors.PhoneSensors
import io.github.markspan.sensorium.sensors.SensorCatalog
import io.github.markspan.sensorium.stream.LanAddresses
import io.github.markspan.sensorium.stream.PacketLoss
import io.github.markspan.sensorium.stream.SensorPreview
import io.github.markspan.sensorium.stream.StreamService
import io.github.markspan.sensorium.stream.StreamState
import io.github.markspan.sensorium.stream.StreamUiState
import io.github.markspan.sensorium.ui.SignalView
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private data class FoundDevice(val address: String, val name: String, var rssi: Int)

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    // Phone sensors
    private lateinit var phoneSensors: List<PhoneSensor>
    private val selectedSensors = mutableSetOf<String>()
    private val sensorSwitches = mutableMapOf<String, MaterialSwitch>()
    private lateinit var sensorRate: AutoCompleteTextView

    // Audio
    private lateinit var audioSwitch: MaterialSwitch
    private lateinit var audioRate: AutoCompleteTextView

    // Polar
    private val devices = mutableListOf<FoundDevice>()
    private var selected: String? = null
    private var scanning = false
    private lateinit var polarSwitch: MaterialSwitch
    private lateinit var scanButton: MaterialButton
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var noDevices: TextView
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var ecgSwitch: MaterialSwitch
    private lateinit var accSwitch: MaterialSwitch
    private lateinit var accRate: AutoCompleteTextView
    private lateinit var accRange: AutoCompleteTextView

    private lateinit var arrivalSwitch: MaterialSwitch

    private lateinit var streamButton: MaterialButton
    private var wasRunning = false
    private var shownPreviewKeys: List<String> = emptyList()

    private val scanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (blePermissions().all { result[it] == true || granted(it) }) startScan()
            else snackbar(getString(R.string.permissions_needed))
        }

    /** Permissions for the selected streams; streaming starts once the user has answered. */
    private val streamPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startStreaming() }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ensureReadyAndScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sensorRate = findViewById(R.id.sensorRate)
        audioSwitch = findViewById(R.id.audioSwitch)
        audioRate = findViewById(R.id.audioRate)
        polarSwitch = findViewById(R.id.polarSwitch)
        scanButton = findViewById(R.id.scanButton)
        scanProgress = findViewById(R.id.scanProgress)
        noDevices = findViewById(R.id.noDevices)
        ecgSwitch = findViewById(R.id.ecgSwitch)
        accSwitch = findViewById(R.id.accSwitch)
        accRate = findViewById(R.id.accRate)
        accRange = findViewById(R.id.accRange)
        arrivalSwitch = findViewById(R.id.arrivalSwitch)
        streamButton = findViewById(R.id.streamButton)

        deviceAdapter = DeviceAdapter()
        findViewById<RecyclerView>(R.id.deviceList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        phoneSensors = PhoneSensors.list(this)
        restoreSettings()
        val silent = PhoneSensors.silentKeys
        if (silent != null || StreamState.state.value.running) {
            phoneSensors = phoneSensors.filterNot { it.key in silent.orEmpty() }
            buildSensorList()
        } else {
            // Hide sensors that send nothing; the list appears once they have been checked.
            findViewById<TextView>(R.id.noSensors).apply {
                setText(R.string.checking_sensors)
                visibility = View.VISIBLE
            }
            PhoneSensors.probe(this, phoneSensors) { silentKeys ->
                if (isDestroyed) return@probe
                phoneSensors = phoneSensors.filterNot { it.key in silentKeys }
                buildSensorList()
            }
        }

        findViewById<View>(R.id.copyAddress).setOnClickListener { copyKnownPeers() }
        findViewById<View>(R.id.selectAll).setOnClickListener { setAllSensors(true) }
        findViewById<View>(R.id.selectNone).setOnClickListener { setAllSensors(false) }
        scanButton.setOnClickListener { if (scanning) stopScan() else ensureReadyAndScan() }
        streamButton.setOnClickListener { toggleStreaming() }
        listOf(audioSwitch, accSwitch, polarSwitch, arrivalSwitch).forEach {
            it.setOnCheckedChangeListener { _, _ -> updateControls(StreamState.state.value) }
        }

        findViewById<SignalView>(R.id.audioView).bind(StreamState.audioPreview, "Microphone")
        findViewById<SignalView>(R.id.ecgView).bind(StreamState.ecgPreview, "H10 ECG (µV)")
        findViewById<SignalView>(R.id.polarAccView).bind(StreamState.polarAccPreview, "H10 ACC X / Y / Z (mG)")
        findViewById<TextView>(R.id.versionText).text = LslNative.versionString()
            ?.let { "Sensorium ${BuildConfig.VERSION_NAME} · liblsl $it" }
            ?: "liblsl failed to load"
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_about) showAbout()
                item.itemId == R.id.action_about
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamState.state.collect(::render)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = showNetwork()
        override fun onLost(network: Network) = showNetwork()
        override fun onAvailable(network: Network) = showNetwork()
    }

    override fun onStart() {
        super.onStart()
        showNetwork()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    /** Updates the phone IP address card and the network line in the status card. */
    private fun showNetwork() = runOnUiThread {
        val addresses = LanAddresses.list()
        findViewById<TextView>(R.id.networkText).text =
            if (addresses.isEmpty()) getString(R.string.no_network)
            else "LSL on " + addresses.joinToString { "${it.interfaceName} ${it.address}" }
        findViewById<TextView>(R.id.addressText).text =
            if (addresses.isEmpty()) getString(R.string.no_address)
            else addresses.joinToString("\n") { it.address }
        findViewById<TextView>(R.id.addressInterface).text =
            addresses.joinToString(" · ") { it.interfaceName }
        findViewById<TextView>(R.id.knownPeersText).text =
            if (addresses.isEmpty()) getString(R.string.no_network) else LanAddresses.knownPeersLine(addresses)
        findViewById<View>(R.id.copyAddress).isEnabled = addresses.isNotEmpty()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        saveSettings()
    }

    // --- settings -------------------------------------------------------------------------------

    private fun restoreSettings() {
        val defaults = phoneSensors
            .filter { it.sensor.type in setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE) }
            .map { it.key }.toSet()
        selectedSensors += prefs.getStringSet("sensors", null) ?: defaults
        setupDropdown(sensorRate, SensorCatalog.RATES, prefs.getInt("sensorRate", SensorCatalog.DEFAULT_RATE))
        audioSwitch.isChecked = prefs.getBoolean("audio", false)
        setupDropdown(audioRate, AudioStreamer.RATES, prefs.getInt("audioRate", AudioStreamer.DEFAULT_RATE))

        polarSwitch.isChecked = prefs.getBoolean("polar", false)
        arrivalSwitch.isChecked = prefs.getBoolean("arrivalStamps", false)
        ecgSwitch.isChecked = prefs.getBoolean("ecg", true)
        accSwitch.isChecked = prefs.getBoolean("acc", true)
        setupDropdown(accRate, PmdCommand.ACC_SAMPLE_RATES, prefs.getInt("accRate", 200))
        setupDropdown(accRange, PmdCommand.ACC_RANGES_G, prefs.getInt("accRange", 8))
        val lastAddress = prefs.getString("lastAddress", null)
        val lastName = prefs.getString("lastName", null)
        if (lastAddress != null && lastName != null) {
            devices += FoundDevice(lastAddress, lastName, 0)
            selected = lastAddress
        }
    }

    private fun saveSettings() = prefs.edit {
        putStringSet("sensors", selectedSensors.toSet())
        putInt("sensorRate", dropdownValue(sensorRate, SensorCatalog.DEFAULT_RATE))
        putBoolean("audio", audioSwitch.isChecked)
        putInt("audioRate", dropdownValue(audioRate, AudioStreamer.DEFAULT_RATE))
        putBoolean("polar", polarSwitch.isChecked)
        putBoolean("arrivalStamps", arrivalSwitch.isChecked)
        putBoolean("ecg", ecgSwitch.isChecked)
        putBoolean("acc", accSwitch.isChecked)
        putInt("accRate", dropdownValue(accRate, 200))
        putInt("accRange", dropdownValue(accRange, 8))
    }

    /** Dropdown of integer values; 0 is shown as "Max". */
    private fun setupDropdown(view: AutoCompleteTextView, values: List<Int>, current: Int) {
        val items = values.map(::label)
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
        view.setText(label(if (current in values) current else values.last()), false)
    }

    private fun label(value: Int) = if (value == 0) getString(R.string.sensor_rate_max) else value.toString()

    private fun dropdownValue(view: AutoCompleteTextView, default: Int): Int {
        val text = view.text.toString()
        return if (text == getString(R.string.sensor_rate_max)) 0 else text.toIntOrNull() ?: default
    }

    /** A stable id for this installation, so LSL can recognise the streams after a restart. */
    private fun sourcePrefix(): String {
        val id = prefs.getString("installId", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit { putString("installId", it) }
        }
        return "sensorium-$id"
    }

    // --- phone sensors --------------------------------------------------------------------------

    private fun buildSensorList() {
        val container = findViewById<LinearLayout>(R.id.sensorList)
        findViewById<TextView>(R.id.noSensors).apply {
            setText(R.string.no_sensors)
            visibility = if (phoneSensors.isEmpty()) View.VISIBLE else View.GONE
        }
        phoneSensors.forEach { ps ->
            val row = layoutInflater.inflate(R.layout.item_sensor, container, false)
            row.findViewById<TextView>(R.id.sensorTitle).text = ps.title
            row.findViewById<TextView>(R.id.sensorDetail).text = ps.detail
            val sw = row.findViewById<MaterialSwitch>(R.id.sensorSwitch)
            sw.isChecked = ps.key in selectedSensors
            sw.contentDescription = ps.title
            sw.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedSensors += ps.key else selectedSensors -= ps.key
            }
            row.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            sensorSwitches[ps.key] = sw
            container.addView(row)
        }
    }

    private fun setAllSensors(on: Boolean) {
        if (StreamState.state.value.running) return
        sensorSwitches.values.forEach { it.isChecked = on }
    }

    // --- scanning -------------------------------------------------------------------------------

    private fun blePermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val adapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private fun ensureReadyAndScan() {
        val missing = blePermissions().filterNot(::granted)
        if (missing.isNotEmpty()) {
            scanPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (adapter?.isEnabled != true) {
            Snackbar.make(findViewById(R.id.root), R.string.bluetooth_off, Snackbar.LENGTH_LONG)
                .setAction(R.string.enable_bluetooth) {
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.show()
            return
        }
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        devices.retainAll { it.address == selected }
        deviceAdapter.notifyDataSetChanged()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        handler.postDelayed(::stopScan, 20_000)
        updateScanUi()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        updateScanUi()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith("Polar")) return
            val existing = devices.firstOrNull { it.address == result.device.address }
            if (existing != null) {
                existing.rssi = result.rssi
            } else {
                devices += FoundDevice(result.device.address, name, result.rssi)
                if (selected == null && name.contains("H10")) selected = result.device.address
            }
            deviceAdapter.notifyDataSetChanged()
            updateControls(StreamState.state.value)
        }

        override fun onScanFailed(errorCode: Int) {
            snackbar("Scan failed (error $errorCode)")
            scanning = false
            updateScanUi()
        }
    }

    private fun updateScanUi() {
        scanButton.setText(if (scanning) R.string.stop_scan else R.string.scan)
        scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        noDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    // --- about --------------------------------------------------------------------------------

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.aboutVersion).text = "Sensorium ${BuildConfig.VERSION_NAME}"
        view.findViewById<TextView>(R.id.aboutLibraries).text =
            "Sensorium: GPL-3.0 · " + (LslNative.versionString()?.let { "liblsl $it: MIT" } ?: "liblsl: MIT")
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.about_open_github) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.project_url).toUri())) }
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    // --- streaming ------------------------------------------------------------------------------

    private fun selectedPhoneSensors() = phoneSensors.filter { it.key in selectedSensors }

    /** Runtime permissions the selected streams need and that are not granted yet. */
    private fun missingStreamPermissions(): List<String> = buildList {
        if (audioSwitch.isChecked) add(Manifest.permission.RECORD_AUDIO)
        val types = selectedPhoneSensors().map { it.sensor.type }.toSet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (Sensor.TYPE_STEP_COUNTER in types || Sensor.TYPE_STEP_DETECTOR in types)
        ) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Sensor.TYPE_HEART_RATE in types || Sensor.TYPE_HEART_BEAT in types) add(Manifest.permission.BODY_SENSORS)
        if (polarSwitch.isChecked) addAll(blePermissions())
    }.filterNot(::granted)

    private fun toggleStreaming() {
        if (StreamState.state.value.running) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            return
        }
        if (selectedPhoneSensors().isEmpty() && !audioSwitch.isChecked && !polarSwitch.isChecked) {
            snackbar(getString(R.string.nothing_selected))
            return
        }
        if (polarSwitch.isChecked && devices.none { it.address == selected }) {
            snackbar(getString(R.string.select_device))
            return
        }
        val missing = missingStreamPermissions()
        if (missing.isNotEmpty()) streamPermissionLauncher.launch(missing.toTypedArray())
        else startStreaming()
    }

    /** Starts the service with whatever the granted permissions allow. */
    private fun startStreaming() {
        stopScan()
        saveSettings()

        val sensorKeys = selectedPhoneSensors().filter { ps ->
            when (ps.sensor.type) {
                Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACTIVITY_RECOGNITION)
                Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT -> granted(Manifest.permission.BODY_SENSORS)
                else -> true
            }
        }.map { it.key }
        val audio = audioSwitch.isChecked && granted(Manifest.permission.RECORD_AUDIO)
        if (audioSwitch.isChecked && !audio) snackbar(getString(R.string.audio_permission_needed))
        val device = devices.firstOrNull { it.address == selected }
            ?.takeIf { polarSwitch.isChecked && blePermissions().all(::granted) }
        if (polarSwitch.isChecked && device == null) snackbar(getString(R.string.permissions_needed))
        if (sensorKeys.isEmpty() && !audio && device == null) return

        val intent = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_DEVICE_NAME, Build.MODEL)
            .putExtra(StreamService.EXTRA_SOURCE_PREFIX, sourcePrefix())
            .putExtra(StreamService.EXTRA_SENSORS, sensorKeys.toTypedArray())
            .putExtra(StreamService.EXTRA_SENSOR_RATE, dropdownValue(sensorRate, SensorCatalog.DEFAULT_RATE))
            .putExtra(StreamService.EXTRA_AUDIO_RATE, if (audio) dropdownValue(audioRate, AudioStreamer.DEFAULT_RATE) else 0)
            .putExtra(StreamService.EXTRA_ARRIVAL_STAMPS, arrivalSwitch.isChecked)
        if (device != null) {
            prefs.edit {
                putString("lastAddress", device.address)
                putString("lastName", device.name)
            }
            intent.putExtra(StreamService.EXTRA_POLAR_ADDRESS, device.address)
                .putExtra(StreamService.EXTRA_POLAR_NAME, device.name)
                .putExtra(StreamService.EXTRA_ECG, ecgSwitch.isChecked)
                .putExtra(StreamService.EXTRA_ACC, accSwitch.isChecked)
                .putExtra(StreamService.EXTRA_ACC_RATE, dropdownValue(accRate, 200))
                .putExtra(StreamService.EXTRA_ACC_RANGE, dropdownValue(accRange, 8))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(s: StreamUiState) {
        updateControls(s)
        // While streaming, the phone's IP address is the first thing on screen.
        findViewById<View>(R.id.addressCard).visibility = if (s.running) View.VISIBLE else View.GONE
        if (s.running && !wasRunning) {
            showNetwork()
            findViewById<NestedScrollView>(R.id.scroll).smoothScrollTo(0, 0)
        }
        wasRunning = s.running
        findViewById<TextView>(R.id.statusText).text = s.status
        findViewById<TextView>(R.id.detailText).text = buildList {
            if (s.phoneSensors > 0) add("${s.phoneSensors} phone sensors · ${s.phoneSamples} samples")
            if (s.audio) add("Audio · ${s.audioSamples} samples")
            if (s.running && s.polarDevice != null) {
                add(
                    listOfNotNull(
                        "${s.polarDevice}: ${s.polarStatus ?: "…"}",
                        s.battery?.let { "battery $it%" },
                        s.ecgSamples.takeIf { it > 0 }?.let { "ECG $it" + lossText(s.ecgLoss) },
                        s.accSamples.takeIf { it > 0 }?.let { "ACC $it" + lossText(s.accLoss) },
                    ).joinToString(" · ")
                )
            }
        }.joinToString("\n")
        findViewById<TextView>(R.id.outletText).text = s.outlets.joinToString("\n") {
            (if (it.hasConsumers) "● " else "○ ") + it.name + if (it.hasConsumers) "  (consumer connected)" else ""
        }
        findViewById<TextView>(R.id.messageText).text = s.messages.joinToString("\n")

        val sensorPreviews = if (s.running) StreamState.sensorPreviews else emptyList()
        showSensorPreviews(sensorPreviews)
        val polar = s.running && s.polarDevice != null
        findViewById<View>(R.id.audioView).visibility = if (s.running && s.audio) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ecgView).visibility = if (polar && ecgSwitch.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.polarAccView).visibility = if (polar && accSwitch.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.previewCard).visibility =
            if (sensorPreviews.isNotEmpty() || (s.running && s.audio) || polar) View.VISIBLE else View.GONE
    }

    /** One plot per streaming phone sensor; rebuilt only when the set of sensors changes. */
    private fun showSensorPreviews(previews: List<SensorPreview>) {
        val keys = previews.map { it.key }
        if (keys == shownPreviewKeys) return
        shownPreviewKeys = keys
        val container = findViewById<LinearLayout>(R.id.sensorPreviews)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        previews.forEach { p ->
            container.addView(
                SignalView(this).apply { bind(p.buffer, p.label, p.samples) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (90 * density).toInt()).apply {
                    topMargin = (8 * density).toInt()
                },
            )
        }
    }

    private fun updateControls(s: StreamUiState) {
        val idle = !s.running
        sensorSwitches.values.forEach { it.isEnabled = idle }
        listOf(R.id.selectAll, R.id.selectNone, R.id.sensorRateLayout).forEach { findViewById<View>(it).isEnabled = idle }
        sensorRate.isEnabled = idle

        audioSwitch.isEnabled = idle
        findViewById<View>(R.id.audioRateLayout).isEnabled = idle && audioSwitch.isChecked
        audioRate.isEnabled = idle && audioSwitch.isChecked

        polarSwitch.isEnabled = idle
        findViewById<View>(R.id.polarOptions).visibility = if (polarSwitch.isChecked) View.VISIBLE else View.GONE
        listOf(ecgSwitch, accSwitch).forEach { it.isEnabled = idle }
        findViewById<View>(R.id.accRateLayout).isEnabled = idle && accSwitch.isChecked
        findViewById<View>(R.id.accRangeLayout).isEnabled = idle && accSwitch.isChecked
        accRate.isEnabled = idle && accSwitch.isChecked
        accRange.isEnabled = idle && accSwitch.isChecked
        scanButton.isEnabled = idle
        arrivalSwitch.isEnabled = idle
        findViewById<TextView>(R.id.arrivalHint).setText(
            if (arrivalSwitch.isChecked) R.string.timestamps_arrival_hint else R.string.timestamps_measured_hint
        )
        deviceAdapter.enabled = idle
        updateScanUi()

        streamButton.setText(if (s.running) R.string.stop_streaming else R.string.start_streaming)
    }

    private fun copyKnownPeers() {
        val addresses = LanAddresses.list()
        if (addresses.isEmpty()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("KnownPeers", LanAddresses.knownPeersLine(addresses)))
        // Android 13+ shows its own confirmation when something is copied.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar(getString(R.string.copied))
    }

    private fun snackbar(text: String) =
        Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_LONG).show()

    // --- device list ----------------------------------------------------------------------------

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.Holder>() {
        var enabled = true
            set(value) {
                if (field != value) { field = value; notifyDataSetChanged() }
            }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findViewById(R.id.radio)
            val name: TextView = v.findViewById(R.id.name)
            val address: TextView = v.findViewById(R.id.address)
            val rssi: TextView = v.findViewById(R.id.rssi)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

        override fun getItemCount() = devices.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val d = devices[position]
            h.name.text = d.name
            h.address.text = d.address
            h.rssi.text = if (d.rssi != 0) "${d.rssi} dBm" else ""
            h.radio.isChecked = d.address == selected
            h.itemView.isEnabled = enabled
            h.radio.isEnabled = enabled
            h.itemView.setOnClickListener {
                if (!enabled) return@setOnClickListener
                selected = d.address
                notifyDataSetChanged()
            }
        }
    }
}

/** " (73 lost in 1 gap)" after a sample count, or nothing when no Bluetooth packets were lost. */
private fun lossText(loss: PacketLoss) = if (loss.gaps == 0) "" else " ($loss)"
