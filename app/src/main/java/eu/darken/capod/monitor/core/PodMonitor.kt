package eu.darken.capod.monitor.core

import android.bluetooth.le.ScanSettings
import eu.darken.capod.common.bluetooth.BleScanner
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.ScannerMode
import eu.darken.capod.pods.core.PodDevice
import eu.darken.capod.pods.core.PodFactory
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodMonitor @Inject constructor(
    private val bleScanner: BleScanner,
    private val podFactory: PodFactory,
    private val generalSettings: GeneralSettings,
    private val bluetoothManager: BluetoothManager2
) {

    private val deviceCache = mutableMapOf<PodDevice.Id, PodDevice>()

    val devices: Flow<List<PodDevice>> = generalSettings.scannerMode.flow
        .flatMapLatest {
            val mode = when (it) {
                ScannerMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                ScannerMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                ScannerMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            bluetoothManager.isBluetoothEnabled.flatMapLatest { isBluetoothEnabled ->
                if (isBluetoothEnabled) {
                    bleScanner.scan(
                        mode = mode
                    )
                } else {
                    log(TAG, WARN) { "Bluetooth is current disabled" }
                    emptyFlow()
                }
            }
        }
        .map { result ->
            // For each address we only want the newest result, upstream may batch data
            result.groupBy { it.device.address }
                .values
                .map { sameAdrDevs ->
                    val newest = sameAdrDevs.maxByOrNull { it.timestampNanos }!!
                    log(TAG, VERBOSE) { "Discarding stale results: ${sameAdrDevs.minus(newest)}" }
                    newest
                }
        }
        .onStart { emptyList<PodDevice>() }
        .map { scanResults ->
            val newPods = scanResults
                .sortedByDescending { it.rssi }
                .mapNotNull { podFactory.createPod(it) }

            val pods = mutableMapOf<PodDevice.Id, PodDevice>()

            pods.putAll(deviceCache)
            pods.putAll(newPods.map { it.identifier to it })

            val now = Instant.now()
            pods.toList().forEach { (key, value) ->
                if (Duration.between(value.lastSeenAt, now) > Duration.ofSeconds(10)) {
                    log(TAG, VERBOSE) { "Removing stale device from cache: $value" }
                    pods.remove(key)
                }
            }
            deviceCache.clear()
            deviceCache.putAll(pods)

            pods.values.toList()
        }

    val mainDevice: Flow<PodDevice?>
        get() = devices.map { devices ->
            devices.maxByOrNull { it.rssi }?.let {
                if (it.rssi > -85) it else null
            }
        }

    companion object {
        private val TAG = logTag("Monitor", "PodMonitor")
    }
}