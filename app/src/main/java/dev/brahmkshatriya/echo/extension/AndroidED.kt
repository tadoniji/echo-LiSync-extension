package dev.brahmkshatriya.echo.extension

import android.Manifest
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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("PrivateApi", "MissingPermission")
class AndroidED : EDExtension() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var bluetoothJob: Job? = null

    // Gestion des invités et de leurs noms pour le contrôle
    private val connectedClients = ConcurrentHashMap<String, BluetoothSocket>()
    private val clientNames = ConcurrentHashMap<String, String>()
    private var guestSocket: BluetoothSocket? = null

    private val discoveredRooms = ConcurrentHashMap<String, String>()
    private var _settings: Settings? = null
    private var lastTrackDetails: TrackDetails? = null

    private fun getApp(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application
    }.getOrNull()

    private val serviceUuid = UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c4")
    private val bleUuid = ParcelUuid(UUID.fromString("4e532b6e-402a-4464-918b-57a554a938c5"))

    override suspend fun onInitialize() {
        Log.d("LiSync", "Service pret")
    }

    private fun hasPermissions(): Boolean {
        val context = getApp() ?: return false
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val context = getApp() ?: return
        val intent = Intent()
        intent.setComponent(android.content.ComponentName("dev.brahmkshatriya.echo.extension.LiSync", "dev.brahmkshatriya.echo.extension.PermissionActivity"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun startBluetooth() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        val adapter = (getApp()?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            Log.e("LiSync", "Bluetooth non supporté")
            return
        }
        if (!adapter.isEnabled) {
            Log.w("LiSync", "Bluetooth désactivé")
            return
        }

        bluetoothJob?.cancel()
        bluetoothJob = scope.launch {
            if (isHost) startHostServer(adapter) else startGuestScan(adapter)
        }
    }

    private fun startHostServer(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e("LiSync", "BLE Advertiser non disponible")
        } else {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(bleUuid)
                .build()

            advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.d("LiSync", "Salle Jam Ouverte (BLE)")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e("LiSync", "Echec Advertising BLE : $errorCode")
                    if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        // Réessayer sans le nom de l'appareil si trop gros
                        val fallbackData = AdvertiseData.Builder().addServiceUuid(bleUuid).build()
                        advertiser.startAdvertising(settings, fallbackData, this)
                    }
                }
            })
        }

        scope.launch {
            runCatching {
                val server = adapter.listenUsingRfcommWithServiceRecord("EchoJam", serviceUuid)
                Log.d("LiSync", "Serveur RFCOMM démarré")
                while (true) {
                    val socket = server.accept() ?: break
                    if (allowNewConnections) {
                        val deviceId = socket.remoteDevice.address
                        connectedClients[deviceId] = socket
                        clientNames[deviceId] = socket.remoteDevice.name ?: "Inconnu"
                        Log.i("LiSync", "Nouveau client connecté : $deviceId")
                        handleHostConnection(socket)
                    } else {
                        socket.close()
                    }
                }
            }.onFailure {
                Log.e("LiSync", "Erreur serveur RFCOMM", it)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Salle Echo"
            discoveredRooms[result.device.address] = name
            Log.d("LiSync", "Salle trouvée : $name (${result.device.address})")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("LiSync", "Echec Scan BLE : $errorCode")
        }
    }

    private fun startGuestScan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        discoveredRooms.clear()
        
        val filter = ScanFilter.Builder().setServiceUuid(bleUuid).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d("LiSync", "Démarrage du scan BLE")
        scanner.startScan(listOf(filter), settings, scanCallback)
        
        // Arrêter le scan après 30 secondes pour économiser la batterie
        scope.launch {
            delay(30000)
            scanner.stopScan(scanCallback)
            Log.d("LiSync", "Scan BLE arrêté après timeout")
        }
    }

    private fun handleHostConnection(socket: BluetoothSocket) {
        val deviceId = socket.remoteDevice.address
        scope.launch {
            runCatching {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes <= 0) break
                    val msg = String(buffer, 0, bytes)

                    if (msg == "NEED_INFO" && lastTrackDetails != null) {
                        val track = lastTrackDetails!!.track
                        val info = "INFO|${track.title}|${track.artists.firstOrNull()?.name ?: ""}"
                        socket.outputStream.write(info.toByteArray())
                    }
                }
            }
            connectedClients.remove(deviceId)
            clientNames.remove(deviceId)
            Log.i("LiSync", "Client déconnecté : $deviceId")
        }
    }

    private fun connectToJam(address: String) {
        if (address == "none" || address.isBlank()) return
        Log.i("LiSync", "Tentative de connexion à : $address")
        scope.launch {
            runCatching {
                val adapter = (getApp()?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                val device = adapter?.getRemoteDevice(address) ?: return@launch
                guestSocket?.close()
                val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                socket.connect()
                guestSocket = socket
                Log.i("LiSync", "Connecté à la salle RFCOMM")
                handleGuestConnection(socket)
            }.onFailure {
                Log.e("LiSync", "Erreur connexion à $address", it)
            }
        }
    }

    private fun handleGuestConnection(socket: BluetoothSocket) {
        scope.launch {
            runCatching {
                val input = socket.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes <= 0) break
                    processReceivedMessage(String(buffer, 0, bytes))
                }
            }
            guestSocket = null
            Log.i("LiSync", "Déconnecté de la salle")
        }
    }

    private fun processReceivedMessage(msg: String) {
        val parts = msg.split("|")
        if (parts.isEmpty()) return

        when (parts[0]) {
            "TRACK" -> {
                val trackId = parts[1]
                Log.i("LiSync", "Tentative Sync ID : $trackId")
                if (!launchUri("echo://track/${Uri.encode(trackId)}")) {
                    sendToHost("NEED_INFO")
                }
            }
            "INFO" -> {
                val title = parts.getOrNull(1) ?: ""
                val artist = parts.getOrNull(2) ?: ""
                Log.i("LiSync", "Fallback Recherche : $title")
                launchUri("echo://search?query=${Uri.encode("$title $artist")}")
            }
            "STATE" -> {
                val action = if (parts[1] == "PLAY") "play" else "pause"
                launchUri("echo://$action")
            }
        }
    }

    private fun launchUri(uri: String): Boolean {
        val context = getApp() ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) { false }
    }

    private fun sendToAllInvites(message: String) {
        connectedClients.values.forEach { socket ->
            scope.launch { runCatching { socket.outputStream.write(message.toByteArray()) } }
        }
    }

    private fun sendToHost(message: String) {
        scope.launch { runCatching { guestSocket?.outputStream?.write(message.toByteArray()) } }
    }

    override suspend fun onTrackChanged(details: TrackDetails?) {
        lastTrackDetails = details
        if (roomEnabled && isHost && details != null) {
            sendToAllInvites("TRACK|${details.track.id}")
        }
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        if (roomEnabled && isHost && details != null) {
            sendToAllInvites("STATE|${if (isPlaying) "PLAY" else "PAUSE"}")
        }
    }

    override fun setSettings(settings: Settings) {
        val oldRoomEnabled = roomEnabled
        _settings = settings

        if (roomEnabled) {
            val joinAddress = settings.getString(JOIN_ADDRESS)
            if (!isHost && joinAddress != null && joinAddress != "none") {
                if (guestSocket?.remoteDevice?.address != joinAddress) {
                    connectToJam(joinAddress)
                }
            }
            startBluetooth()
        } else if (oldRoomEnabled) {
            bluetoothJob?.cancel()
            connectedClients.values.forEach { runCatching { it.close() } }
            connectedClients.clear()
            runCatching { guestSocket?.close() }
            val adapter = (getApp()?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    override suspend fun getSettingItems(): List<Setting> {
        val names = if (discoveredRooms.isEmpty()) listOf("Scan en cours...") else discoveredRooms.values.toList()
        val ids = if (discoveredRooms.isEmpty()) listOf("none") else discoveredRooms.keys.toList()

        val guestsList = if (clientNames.isEmpty()) "Aucun invité" else clientNames.values.joinToString(", ")

        val syncDescription = if (hasPermissions()) "Synchronisation à proximité" else "⚠️ Permissions manquantes - Cliquer pour corriger"

        return listOf(
            SettingCategory("Salle LiSync Jam", "sync", mutableListOf(
                SettingSwitch("Activer LiSync", ROOM_ENABLED, syncDescription, false),
                SettingSwitch("Être l'Hôte", IS_HOST, "Partager ma lecture", true),
                SettingSwitch("Porte Ouverte", ALLOW_CONN, "Autoriser de nouveaux invités", true),
                SettingList("Rejoindre une salle", JOIN_ADDRESS, "Salles détectées", names, ids, 0)
            )),
            SettingCategory("Contrôle", "control", mutableListOf(
                SettingSwitch("Salle Privée", "lock_room", "Connectés : $guestsList", false)
            ))
        )
    }

    private val roomEnabled get() = _settings?.getBoolean(ROOM_ENABLED) ?: false
    private val isHost get() = _settings?.getBoolean(IS_HOST) ?: true
    private val allowNewConnections get() = _settings?.getBoolean(ALLOW_CONN) ?: true

    companion object {
        private const val ROOM_ENABLED = "room_enabled"
        private const val IS_HOST = "is_host"
        private const val JOIN_ADDRESS = "join_address"
        private const val ALLOW_CONN = "allow_new_conn"
    }
}
