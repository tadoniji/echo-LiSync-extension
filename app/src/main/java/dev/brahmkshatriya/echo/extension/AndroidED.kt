package dev.brahmkshatriya.echo.extension

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
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
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("PrivateApi", "MissingPermission")
class AndroidED : EDExtension() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var bluetoothJob: Job? = null
    
    // Gestion de la salle
    private val connectedClients = ConcurrentHashMap<String, BluetoothSocket>()
    private val clientNames = ConcurrentHashMap<String, String>()
    private var guestSocket: BluetoothSocket? = null
    
    private val discoveredRooms = mutableMapOf<String, String>()
    private var _settings: Settings? = null
    private var lastTrack: TrackDetails? = null
    private var isScanning = false

    private fun getApp(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
    }.getOrNull()

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val context = getApp() ?: return null
        return (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val serviceUuid = UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c4")
    private val bleUuid = android.os.ParcelUuid(UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c5"))

    private fun hasPermissions(): Boolean {
        val context = getApp() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == 0 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == 0 &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == 0
        } else {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == 0
        }
    }

    override suspend fun onInitialize() { Log.d("LiSync", "Prêt") }

    private fun startBluetooth() {
        val adapter = getBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled || !hasPermissions()) return
        bluetoothJob?.cancel()
        bluetoothJob = scope.launch {
            if (isHost) startHostServer(adapter) else startGuestScan(adapter)
        }
    }

    private fun startHostServer(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(bleUuid).build()
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.i("LiSync", "Hôte Actif") }
        })
        scope.launch {
            runCatching {
                val server = adapter.listenUsingRfcommWithServiceRecord("EchoJam", serviceUuid)
                while (true) {
                    val socket = server.accept() ?: break
                    val deviceId = socket.remoteDevice.address
                    connectedClients[deviceId] = socket
                    clientNames[deviceId] = socket.remoteDevice.name ?: "Inconnu"
                    handleHostComm(socket)
                }
            }
        }
    }

    private fun startGuestScan(adapter: BluetoothAdapter) {
        if (isScanning) return
        isScanning = true
        val scanner = adapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.serviceUuids?.contains(bleUuid) == true) {
                    discoveredRooms[result.device.address] = result.scanRecord?.deviceName ?: result.device.name ?: "Salle"
                }
            }
        }
        scanner?.startScan(callback)
        scope.launch { delay(10000); scanner?.stopScan(callback); isScanning = false }
    }

    private fun handleHostComm(socket: BluetoothSocket) {
        val deviceId = socket.remoteDevice.address
        scope.launch {
            runCatching {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes <= 0) break
                    if (String(buffer, 0, bytes) == "NEED_INFO") {
                        lastTrack?.let {
                            val info = "INFO|${it.track.title}|${it.track.artists.firstOrNull()?.name ?: ""}"
                            socket.outputStream.write(info.toByteArray())
                        }
                    }
                }
            }
            connectedClients.remove(deviceId)
            clientNames.remove(deviceId)
        }
    }

    private fun connectToJam(address: String) {
        if (address == "none") return
        scope.launch {
            runCatching {
                val socket = getBluetoothAdapter()?.getRemoteDevice(address)?.createRfcommSocketToServiceRecord(serviceUuid)
                socket?.connect()
                guestSocket = socket
                val input = socket?.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = input?.read(buffer) ?: break
                    if (bytes > 0) processGuestMessage(String(buffer, 0, bytes))
                }
            }
            guestSocket = null
        }
    }

    private fun processGuestMessage(msg: String) {
        val parts = msg.split("|")
        if (parts.size < 2) return
        when (parts[0]) {
            "TRACK" -> {
                if (!launchUri("echo://track/${Uri.encode(parts[1])}")) {
                    scope.launch { runCatching { guestSocket?.outputStream?.write("NEED_INFO".toByteArray()) } }
                }
            }
            "INFO" -> launchUri("echo://search?query=${Uri.encode("${parts[1]} ${parts[2]}")}")
            "STATE" -> launchUri("echo://${if (parts[1] == "PLAY") "play" else "pause"}")
        }
    }

    private fun launchUri(uri: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApp()?.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {
        lastTrack = details
        if (roomEnabled && isHost) {
            val msg = "TRACK|${details?.track?.id}"
            connectedClients.values.forEach { runCatching { it.outputStream.write(msg.toByteArray()) } }
        }
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        if (roomEnabled && isHost) {
            val msg = "STATE|${if (isPlaying) "PLAY" else "PAUSE"}"
            connectedClients.values.forEach { runCatching { it.outputStream.write(msg.toByteArray()) } }
        }
    }

    override fun setSettings(settings: Settings) {
        if (settings.getBoolean("fix_perms") == true) runCatching {
            getApp()?.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getApp()?.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (settings.getBoolean("refresh_scan") == true) startBluetooth()
        
        _settings = settings
        if (roomEnabled) startBluetooth() else {
            bluetoothJob?.cancel()
            connectedClients.values.forEach { runCatching { it.close() } }
            connectedClients.clear()
            runCatching { guestSocket?.close() }
        }
    }

    override suspend fun getSettingItems(): List<Setting> {
        val adapter = getBluetoothAdapter()
        val btOk = adapter?.isEnabled == true
        val permOk = hasPermissions()
        val status = when {
            !btOk -> "❌ Bluetooth désactivé"
            !permOk -> "⚠️ Permissions manquantes"
            else -> "✅ Prêt"
        }
        val guests = if (clientNames.isEmpty()) "Aucun" else clientNames.values.joinToString(", ")
        val rooms = if (discoveredRooms.isEmpty()) listOf("Lancer une recherche...") else discoveredRooms.values.toList()
        val roomIds = if (discoveredRooms.isEmpty()) listOf("none") else discoveredRooms.keys.toList()

        return listOf(
            SettingCategory("Statut : $status", "st", mutableListOf(
                SettingSwitch("Réparer les permissions", "fix_perms", "Ouvre les réglages Android", false),
                SettingSwitch(if (isScanning) "Recherche..." else "Rechercher des salles", "refresh_scan", "Scan manuel", false)
            )),
            SettingCategory("Ma Salle", "sync", mutableListOf(
                SettingSwitch("Activer LiSync", "room_enabled", "Session en cours", false),
                SettingSwitch("Mode Hôte", "is_host", "Diffuser ma musique", true),
                SettingList("Rejoindre une salle", "join_address", "Salles trouvées", rooms, roomIds, 0)
            )),
            SettingCategory("Contrôle Hôte", "ctrl", mutableListOf(
                SettingSwitch("Salle Privée", "lock", "Invités connectés : $guests", false)
            ))
        )
    }

    private val roomEnabled get() = _settings?.getBoolean("room_enabled") ?: false
    private val isHost get() = _settings?.getBoolean("is_host") ?: true
    companion object { private const val JOIN_ADDRESS = "join_address" }
}