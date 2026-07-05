package com.charles.meshtalk.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.charles.meshtalk.app.MainActivity
import com.charles.meshtalk.app.crypto.Identity
import com.charles.meshtalk.app.crypto.hexToBytes
import com.charles.meshtalk.app.hasAllPermissions
import com.charles.meshtalk.app.requiredBluetoothPermissions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service owning the whole BLE mesh: advertises + runs a GATT
 * server (peripheral role), scans + connects to other peripherals (central
 * role), and relays packets through [MeshEngine]. No pairing is involved:
 * connections are transient GATT links used purely to exchange packets.
 */
class BleMeshService : Service() {

    companion object {
        private const val TAG = "BleMeshService"
        val SERVICE_UUID: UUID = UUID.fromString("5f8a1000-3a10-4f61-9c1e-9a6b2f1e7c00")
        val CHAR_UUID: UUID = UUID.fromString("5f8a1001-3a10-4f61-9c1e-9a6b2f1e7c00")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val NOTIFICATION_CHANNEL_ID = "mesh_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_CHUNK_PAYLOAD = 150
        // Bluetooth Core Spec caps a GATT attribute value at 512 bytes regardless of ATT MTU;
        // notifyCharacteristicChanged throws IllegalArgumentException (crashing the process,
        // since it's called from a callback with no surrounding try/catch) if exceeded.
        private const val MAX_ATTRIBUTE_VALUE_BYTES = 512
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleMeshService = this@BleMeshService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    // Raw RSSI (dBm) per BLE address from scan results, resolved to peer identity via
    // addressToPeerKey — powers the "Find" proximity screen. More negative = further away.
    private val addressRssi = ConcurrentHashMap<String, Int>()
    private val _peerRssi = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerRssi: StateFlow<Map<String, Int>> = _peerRssi.asStateFlow()

    private var trackingBeaconEnabled = false

    private var identity: Identity? = null
    private var meshEngine: MeshEngine? = null

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private class LinkQueue {
        val pending = ArrayDeque<ByteArray>()
        var busy = false
    }

    // Each connection gets its own Reassembler: the same logical packet can arrive over
    // multiple simultaneous links (BLE address rotation means one physical peer can show up
    // as two separate connections), each fragmented independently per-link MTU. A shared
    // reassembler would interleave chunks from different links' fragmentations and corrupt data.
    private data class CentralLink(
        val device: BluetoothDevice,
        var mtu: Int = 23,
        val queue: LinkQueue = LinkQueue(),
        val reassembler: Reassembler = Reassembler()
    )
    private val centralLinks = ConcurrentHashMap<String, CentralLink>() // devices connected to our GATT server

    private data class PeripheralLink(
        val gatt: BluetoothGatt,
        var mtu: Int = 23,
        val queue: LinkQueue = LinkQueue(),
        val reassembler: Reassembler = Reassembler()
    )
    private val peripheralLinks = ConcurrentHashMap<String, PeripheralLink>() // gatt clients we opened
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()

    // BLE address rotation means one physical peer can hold two simultaneous connections
    // (one where it's central, one where it's peripheral) under two different addresses.
    // Once a link's first ANNOUNCE arrives we learn its real identity, so peer counting can
    // dedupe by that instead of by raw address.
    private val addressToPeerKey = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Mesh networking", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        val notification = buildNotification("Starting mesh…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MeshTalk")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopMesh()
        super.onDestroy()
    }

    // If the OS kills this service (e.g. low memory) while the app isn't in the foreground,
    // START_STICKY gets it restarted with a null intent; self-resume from the persisted
    // identity so the mesh keeps running without needing the Activity to relaunch first.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (meshEngine == null && Identity.exists(applicationContext) &&
            hasAllPermissions(applicationContext, requiredBluetoothPermissions())
        ) {
            startMesh("")
        }
        return START_STICKY
    }

    /** Idempotent: call once identity/nickname and Bluetooth permissions are ready. */
    @SuppressLint("MissingPermission")
    fun startMesh(nickname: String) {
        if (meshEngine != null) return
        val id = Identity.loadOrCreate(applicationContext, nickname)
        identity = id
        meshEngine = MeshEngine(id)

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter unavailable/disabled")
            return
        }

        setupGattServer(bluetoothManager)
        startAdvertising(adapter)
        startScanning(adapter)
        updateNotification("Online as ${id.nickname}")
    }

    @SuppressLint("MissingPermission")
    private fun stopMesh() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        peripheralLinks.values.forEach { it.gatt.close() }
        peripheralLinks.clear()
        gattServer?.close()
        gattServer = null
        centralLinks.clear()
    }

    // ---------------------------------------------------------------------
    // GATT server (peripheral role)
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun setupGattServer(bluetoothManager: BluetoothManager) {
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                centralLinks[device.address] = CentralLink(device)
                refreshPeerCount()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                centralLinks.remove(device.address)
                addressToPeerKey.remove(device.address)
                refreshPeerCount()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            centralLinks[device.address]?.mtu = mtu
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            val link = centralLinks[device.address]
            if (link == null) {
                Log.w(TAG, "onCharacteristicWriteRequest: no centralLink for ${device.address}")
                return
            }
            onChunkReceived(value, link.reassembler, device.address)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (descriptor.uuid == CCCD_UUID) {
                val link = centralLinks[device.address]
                if (link == null) {
                    Log.w(TAG, "onDescriptorWriteRequest: no centralLink for ${device.address}")
                    return
                }
                val toSend = meshEngine?.packetsForNewPeer() ?: emptyList()
                Log.d(TAG, "onDescriptorWriteRequest: CCCD enabled by ${device.address}, sending ${toSend.size} packets")
                toSend.forEach { enqueueToCentral(link, it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = centralLinks[device.address] ?: return
            sendNextQueuedChunk(link)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueToCentral(link: CentralLink, packetBytes: ByteArray) {
        val chunkSize = (link.mtu - 3).coerceAtMost(MAX_ATTRIBUTE_VALUE_BYTES)
            .takeIf { it > Fragmenter.CHUNK_HEADER_BYTES + 4 } ?: DEFAULT_CHUNK_PAYLOAD
        // messageId sits at a fixed offset in the wire format: version(1)+type(1)+ttl(1) then 16 bytes of id.
        val packetId = packetBytes.copyOfRange(3, 19)
        val chunks = Fragmenter.fragment(packetId, packetBytes, chunkSize)
        synchronized(link.queue) {
            link.queue.pending.addAll(chunks)
            if (!link.queue.busy) sendNextQueuedChunk(link)
        }
    }

    // The GATT server exposes a single shared BluetoothGattCharacteristic object across all
    // connected centrals; setValue() + notifyCharacteristicChanged() must be atomic relative
    // to other centrals' sends, or one send's value can be overwritten before its notify fires.
    private val notifyLock = Any()

    @SuppressLint("MissingPermission")
    private fun sendNextQueuedChunk(link: CentralLink) {
        val next: ByteArray = synchronized(link.queue) {
            val polled = link.queue.pending.poll()
            if (polled == null) {
                link.queue.busy = false
                return
            }
            link.queue.busy = true
            polled
        }
        Log.d(TAG, "sendNextQueuedChunk: notifying ${link.device.address} with ${next.size} bytes")
        val char = characteristic ?: return
        try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: value is passed explicitly per-call, no shared mutable characteristic state.
            val status = gattServer?.notifyCharacteristicChanged(link.device, char, false, next)
            Log.d(TAG, "sendNextQueuedChunk: notifyCharacteristicChanged(value) status=$status")
        } else {
            synchronized(notifyLock) {
                char.setValue(next)
                val ok = gattServer?.notifyCharacteristicChanged(link.device, char, false)
                Log.d(TAG, "sendNextQueuedChunk: notifyCharacteristicChanged returned $ok")
            }
        }
        } catch (e: Exception) {
            Log.w(TAG, "sendNextQueuedChunk: notify failed, dropping chunk: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Scanning + GATT client (central role)
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: android.bluetooth.BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        // Tracking beacon mode advertises as fast as the radio allows, at the cost of battery,
        // so peers looking for us via the Find screen get more frequent/reliable RSSI updates.
        val mode = if (trackingBeaconEnabled) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        } else {
            AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    /** Restarts advertising with a faster interval so this device is easier to locate via RSSI. */
    @SuppressLint("MissingPermission")
    fun setTrackingBeaconEnabled(enabled: Boolean) {
        if (trackingBeaconEnabled == enabled) return
        trackingBeaconEnabled = enabled
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: return
        if (meshEngine == null) return // not started yet; startMesh() will pick up the new mode
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        startAdvertising(adapter)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed to start: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(adapter: android.bluetooth.BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Record RSSI even for already-connected peers — this is the only signal the
            // Find screen has, and connection state shouldn't stop it from updating.
            addressRssi[device.address] = result.rssi
            refreshPeerRssi()
            if (peripheralLinks.containsKey(device.address)) return
            if (!connectingAddresses.add(device.address)) return
            device.connectGatt(this@BleMeshService, false, gattClientCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    peripheralLinks[gatt.device.address] = PeripheralLink(gatt)
                    gatt.requestMtu(512)
                    // Prioritize throughput/latency over the connection's power usage: multi-chunk
                    // image/file transfers are much more likely to span a disconnect otherwise.
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peripheralLinks.remove(gatt.device.address)
                    connectingAddresses.remove(gatt.device.address)
                    addressToPeerKey.remove(gatt.device.address)
                    gatt.close()
                    refreshPeerCount()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            peripheralLinks[gatt.device.address]?.mtu = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            val char = service?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.w(TAG, "onServicesDiscovered: service/characteristic not found on ${gatt.device.address}, status=$status")
                return
            }
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                gatt.writeDescriptor(cccd)
            }
            refreshPeerCount()
            val toSend = meshEngine?.packetsForNewPeer() ?: emptyList()
            Log.d(TAG, "onServicesDiscovered: ${gatt.device.address}, sending ${toSend.size} packets")
            toSend.forEach { enqueueToPeripheral(gatt, char, it) }
        }

        // Android < 13 (API < 33, e.g. the Kindle) only ever calls this deprecated 2-arg
        // overload; API 33+ calls the 3-arg one below instead. Both are needed.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val link = peripheralLinks[gatt.device.address] ?: return
            onChunkReceived(characteristic.value ?: return, link.reassembler, gatt.device.address)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val link = peripheralLinks[gatt.device.address] ?: return
            onChunkReceived(value, link.reassembler, gatt.device.address)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val link = peripheralLinks[gatt.device.address] ?: return
            sendNextQueuedChunkToPeripheral(gatt, characteristic, link)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueToPeripheral(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, packetBytes: ByteArray) {
        val link = peripheralLinks[gatt.device.address] ?: return
        val chunkSize = (link.mtu - 3).coerceAtMost(MAX_ATTRIBUTE_VALUE_BYTES)
            .takeIf { it > Fragmenter.CHUNK_HEADER_BYTES + 4 } ?: DEFAULT_CHUNK_PAYLOAD
        val packetId = packetBytes.copyOfRange(3, 19)
        val chunks = Fragmenter.fragment(packetId, packetBytes, chunkSize)
        synchronized(link.queue) {
            link.queue.pending.addAll(chunks)
            if (!link.queue.busy) sendNextQueuedChunkToPeripheral(gatt, char, link)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextQueuedChunkToPeripheral(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, link: PeripheralLink) {
        synchronized(link.queue) {
            val next = link.queue.pending.poll()
            if (next == null) {
                link.queue.busy = false
                return
            }
            link.queue.busy = true
            Log.d(TAG, "sendNextQueuedChunkToPeripheral: writing ${next.size} bytes to ${gatt.device.address}")
            try {
                char.setValue(next)
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val ok = gatt.writeCharacteristic(char)
                Log.d(TAG, "sendNextQueuedChunkToPeripheral: writeCharacteristic returned $ok")
            } catch (e: Exception) {
                Log.w(TAG, "sendNextQueuedChunkToPeripheral: write failed, dropping chunk: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Shared incoming/outgoing packet handling
    // ---------------------------------------------------------------------

    private fun onChunkReceived(chunk: ByteArray, reassembler: Reassembler, fromAddress: String) {
        Log.d(TAG, "onChunkReceived: ${chunk.size} bytes")
        val full = reassembler.addChunk(chunk)
        if (full == null) {
            Log.d(TAG, "onChunkReceived: chunk buffered, packet not complete yet")
            return
        }
        Log.d(TAG, "onChunkReceived: packet reassembled, ${full.size} bytes")
        val result = meshEngine?.handleIncoming(full)
        if (result == null) {
            Log.w(TAG, "onChunkReceived: meshEngine not ready")
            return
        }
        Log.d(TAG, "onChunkReceived: event=${result.event} relay=${result.relayBytes != null}")
        val event = result.event
        if (event is MeshEvent.PeerAnnounced) {
            addressToPeerKey[fromAddress] = event.signingPubKeyHex
            refreshPeerCount()
            refreshPeerRssi()
        }
        event?.let { _events.tryEmit(it) }
        result.relayBytes?.let { relay -> broadcastToAllLinks(relay) }
    }

    @SuppressLint("MissingPermission")
    private fun broadcastToAllLinks(packetBytes: ByteArray) {
        Log.d(TAG, "broadcastToAllLinks: ${packetBytes.size} bytes to ${centralLinks.size} centralLinks + ${peripheralLinks.size} peripheralLinks")
        centralLinks.values.forEach { enqueueToCentral(it, packetBytes) }
        peripheralLinks.forEach { (address, link) ->
            val char = link.gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                Log.w(TAG, "broadcastToAllLinks: no characteristic found for peripheral link $address")
                return@forEach
            }
            enqueueToPeripheral(link.gatt, char, packetBytes)
        }
    }

    private fun refreshPeerCount() {
        // Resolve each connected address to its peer identity where known (learned from
        // ANNOUNCE); addresses with no known identity yet fall back to counting as themselves.
        // This collapses the same physical peer's two role-based connections into one.
        val distinct = HashSet<String>()
        (centralLinks.keys.asSequence() + peripheralLinks.keys.asSequence()).forEach { address ->
            distinct.add(addressToPeerKey[address] ?: address)
        }
        _connectedPeerCount.value = distinct.size
    }

    /** Resolves raw per-address RSSI to per-peer-identity RSSI for the Find screen; unresolved
     * addresses (no ANNOUNCE seen yet) are dropped since there's no peer to attribute them to. */
    private fun refreshPeerRssi() {
        val result = HashMap<String, Int>()
        addressRssi.forEach { (address, rssi) ->
            val peerKey = addressToPeerKey[address] ?: return@forEach
            val existing = result[peerKey]
            if (existing == null || rssi > existing) result[peerKey] = rssi
        }
        _peerRssi.value = result
    }

    /** Returns the sent message's id (hex) so callers can record it locally, or null on failure. */
    fun sendPublicContent(content: MessageContent): String? {
        val bytes = meshEngine?.createPublicMessage(content) ?: return null
        broadcastToAllLinks(bytes)
        return PacketCodec.deserialize(bytes)?.messageIdHex
    }

    /** Returns null if we don't yet know the recipient's agreement key (no ANNOUNCE seen yet). */
    fun sendDirectContent(recipientSigningPubKeyHex: String, content: MessageContent): String? {
        val bytes = meshEngine?.createDirectMessage(recipientSigningPubKeyHex, content) ?: return null
        broadcastToAllLinks(bytes)
        return PacketCodec.deserialize(bytes)?.messageIdHex
    }

    fun knowsAgreementKeyFor(recipientSigningPubKeyHex: String): Boolean =
        meshEngine?.agreementKeyFor(recipientSigningPubKeyHex) != null

    /** [recipientSigningPubKeyHex] null means broadcast (public message receipt, everyone records it). */
    fun sendReadReceipt(originalMessageIdHex: String, recipientSigningPubKeyHex: String?) {
        val engine = meshEngine ?: return
        val recipientKey = recipientSigningPubKeyHex?.hexToBytes() ?: BROADCAST_KEY
        val bytes = engine.createReadReceipt(originalMessageIdHex, recipientKey)
        broadcastToAllLinks(bytes)
    }

    /** [recipientSigningPubKeyHex] null means broadcast (public feed typing signal). */
    fun sendTypingIndicator(recipientSigningPubKeyHex: String?) {
        val engine = meshEngine ?: return
        val recipientKey = recipientSigningPubKeyHex?.hexToBytes() ?: BROADCAST_KEY
        val bytes = engine.createTypingIndicator(recipientKey)
        broadcastToAllLinks(bytes)
    }

    fun myPublicKeyHex(): String? = identity?.publicKeyHex
    fun myNickname(): String? = identity?.nickname
}
