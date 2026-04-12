package dev.brahmkshatriya.echo.extension

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@SuppressLint("PrivateApi", "MissingPermission")
class AndroidED : EDExtension() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var bluetoothJob: Job? = null
    private var clientSocket: BluetoothSocket? = null
    private val discoveredRooms = mutableMapOf<String, String>()
    private var _settings: Settings? = null

    private fun getApp(): Application? {
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
        }.getOrNull()
    }

    private val serviceUuid = UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c4")
    private val bleUuid = ParcelUuid(UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c5"))

    override suspend fun onInitialize() {
        Log.d("LiSync", "Extension Initialized")
    }

    private fun startBluetooth() {
        val app = getApp() ?: return
        val adapter = runCatching {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

        if (adapter == null || !adapter.isEnabled) {
            Log.w("LiSync", "Bluetooth adapter not ready")
            return
        }

        bluetoothJob?.cancel()
        bluetoothJob = scope.launch {
            delay(1000) // Petit délai pour laisser Echo respirer
            try {
                if (isHost) startHost(adapter) else startScan(adapter)
            } catch (e: Exception) {
                Log.e("LiSync", "Bluetooth error", e)
            }
        }
    }

    private fun startHost(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(bleUuid).build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("LiSync", "Advertising started")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("LiSync", "Advertising failed: $errorCode")
            }
        })

        scope.launch {
            runCatching {
                val server = adapter.listenUsingRfcommWithServiceRecord("EchoJam", serviceUuid)
                while (true) {
                    val socket = server.accept() ?: break
                    handleConnection(socket)
                }
            }
        }
    }

    private fun startScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        discoveredRooms.clear()
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.serviceUuids?.contains(bleUuid) == true) {
                    val name = result.scanRecord?.deviceName ?: result.device.name ?: "Nearby Jam"
                    discoveredRooms[result.device.address] = name
                }
            }
        })
    }

    private fun handleConnection(socket: BluetoothSocket) {
        clientSocket = socket
        scope.launch {
            runCatching {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes <= 0) break
                    Log.i("LiSync", "Data received")
                }
            }
            clientSocket = null
        }
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {
        if (roomEnabled && isHost && details != null) {
            runCatching { clientSocket?.outputStream?.write("TRACK|${details.track.id}".toByteArray()) }
        }
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        if (roomEnabled && isHost && details != null) {
            runCatching { clientSocket?.outputStream?.write("STATE|${if (isPlaying) "PLAY" else "PAUSE"}".toByteArray()) }
        }
    }

    override fun setSettings(settings: Settings) {
        _settings = settings
        // On ne lance pas startBluetooth directement ici pour éviter le crash
        // L'utilisateur devra l'activer dans les réglages
    }

    override suspend fun getSettingItems(): List<Setting> {
        val names = if (discoveredRooms.isEmpty()) listOf("No Rooms Found") else discoveredRooms.values.toList()
        val ids = if (discoveredRooms.isEmpty()) listOf("none") else discoveredRooms.keys.toList()

        return listOf(
            SettingCategory("Echo Jam Room", "sync", mutableListOf(
                SettingSwitch("Enable Jam Room", ROOM_ENABLED, "Connect with people nearby", false),
                SettingSwitch("Host a Jam", IS_HOST, "Make your music public", true),
                SettingList("Select Room", JOIN_ADDRESS, "Nearby Rooms", names, ids, 0)
            ))
        )
    }

    private val roomEnabled get() = _settings?.getBoolean(ROOM_ENABLED) ?: false
    private val isHost get() = _settings?.getBoolean(IS_HOST) ?: true

    companion object {
        private const val ROOM_ENABLED = "room_enabled"
        private const val IS_HOST = "is_host"
        private const val JOIN_ADDRESS = "join_address"
    }
}