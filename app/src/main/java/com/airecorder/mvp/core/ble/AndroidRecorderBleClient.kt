package com.airecorder.mvp.core.ble

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredRecorder(
    val address: String,
    val name: String,
    val rssi: Int,
    val likelyRecorder: Boolean
)
sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Connecting : BleConnectionState
    data object Connected : BleConnectionState
    data class Failed(val message: String) : BleConnectionState
}

interface RecorderBleClient {
    val connection: StateFlow<BleConnectionState>
    val status: StateFlow<DeviceStatus?>
    val livePreview: StateFlow<LivePreviewState>
    val liveFrames: SharedFlow<LiveOpusFrame>
    fun scan(): Flow<DiscoveredRecorder>
    suspend fun connect(address: String)
    fun disconnect()
    suspend fun setTime(unixSeconds: Long)
    suspend fun getStatus(): DeviceStatus
    suspend fun startRecording(scene: RecorderBleProtocol.Scene)
    suspend fun stopRecording()
    suspend fun startLivePreview()
    suspend fun stopLivePreview()
    suspend fun startWifiHandoff(): WifiCredentials
}

@SuppressLint("MissingPermission")
class AndroidRecorderBleClient(context: Context) : RecorderBleClient {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val sequence = AtomicInteger(0)
    private val commandMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var liveAudioCharacteristic: BluetoothGattCharacteristic? = null
    private var liveNotificationsEnabled = false
    private var liveSetupError: String? = null
    private var mtuNegotiationRequested = false
    private var serviceDiscoveryRequested = false
    private var ready = CompletableDeferred<Unit>()
    private val pending = mutableMapOf<Int, PendingCommand>()
    private val liveLock = Any()
    private var liveFirstFrame: CompletableDeferred<Unit>? = null
    private var liveReceivedFrames = 0L
    private var liveReceivedPayloadBytes = 0L
    private var liveLastFrameAtMillis = 0L
    private var liveLastStatePublishAtMillis = 0L
    @Volatile private var wifiHandoffSequence: Int? = null
    private val _connection = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connection: StateFlow<BleConnectionState> = _connection
    private val _status = MutableStateFlow<DeviceStatus?>(null)
    override val status: StateFlow<DeviceStatus?> = _status
    private val _livePreview = MutableStateFlow<LivePreviewState>(LivePreviewState.Off)
    override val livePreview: StateFlow<LivePreviewState> = _livePreview
    private val _liveFrames = MutableSharedFlow<LiveOpusFrame>(
        extraBufferCapacity = LIVE_FRAME_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val liveFrames: SharedFlow<LiveOpusFrame> = _liveFrames

    override fun scan(): Flow<DiscoveredRecorder> = callbackFlow {
        if (!adapter.isEnabled) {
            close(IllegalStateException("BLE-SCAN-BLUETOOTH-OFF-001 | Turn on Bluetooth, then scan again"))
            return@callbackFlow
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            close(IllegalStateException("BLE-SCAN-PERMISSION-002 | Allow Nearby devices permission for AI Recorder"))
            return@callbackFlow
        }
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed BLE device"
                val hasRecorderService = result.scanRecord?.serviceUuids?.any {
                    it.uuid == RecorderBleProtocol.serviceUuid
                } == true
                val hasRecorderName = name.startsWith("NSRecorder-", ignoreCase = true)
                trySend(
                    DiscoveredRecorder(
                        address = result.device.address,
                        name = name,
                        rssi = result.rssi,
                        likelyRecorder = hasRecorderName || hasRecorderService
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with code $errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("BLE-SCAN-UNAVAILABLE-003 | Bluetooth LE scanning is unavailable on this phone"))
            return@callbackFlow
        }
        try {
            scanner.startScan(null, settings, callback)
        } catch (error: SecurityException) {
            close(IllegalStateException("BLE-SCAN-PERMISSION-002 | Allow Nearby devices permission for AI Recorder", error))
            return@callbackFlow
        } catch (error: Exception) {
            close(IllegalStateException("BLE-SCAN-START-004 | ${error.message ?: error.javaClass.simpleName}", error))
            return@callbackFlow
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override suspend fun connect(address: String) {
        disconnect()
        _connection.value = BleConnectionState.Connecting
        ready = CompletableDeferred()
        mtuNegotiationRequested = false
        serviceDiscoveryRequested = false
        liveAudioCharacteristic = null
        liveNotificationsEnabled = false
        liveSetupError = null
        resetLivePreview()
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(CONNECTION_TIMEOUT_MS) { ready.await() }
    }

    override fun disconnect() {
        Log.i(TAG, "Disconnect requested by app")
        pending.values.forEach { it.response.cancel() }
        pending.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        statusCharacteristic = null
        liveAudioCharacteristic = null
        liveNotificationsEnabled = false
        liveSetupError = null
        mtuNegotiationRequested = false
        serviceDiscoveryRequested = false
        resetLivePreview()
        _connection.value = BleConnectionState.Disconnected
    }

    override suspend fun setTime(unixSeconds: Long) {
        send(RecorderBleProtocol.Command.SET_TIME, RecorderBleProtocol.setTimePayload(unixSeconds))
    }

    override suspend fun getStatus(): DeviceStatus {
        val response = send(RecorderBleProtocol.Command.GET_STATUS)
        return RecorderBleProtocol.parseStatus(response.payload).also { _status.value = it }
    }

    override suspend fun startRecording(scene: RecorderBleProtocol.Scene) {
        send(RecorderBleProtocol.Command.START_RECORD, byteArrayOf(scene.value.toByte()))
        awaitRecordingState(1)
    }

    override suspend fun stopRecording() {
        if (livePreview.value.isActive) stopLivePreview()
        send(RecorderBleProtocol.Command.STOP_RECORD)
        awaitRecordingState(0)
    }

    override suspend fun startWifiHandoff(): WifiCredentials {
        if (livePreview.value.isActive) stopLivePreview()
        return try {
            val response = send(RecorderBleProtocol.Command.START_WIFI_AP)
            RecorderBleProtocol.parseWifiCredentials(response.payload)
        } catch (error: WifiHandoffDisconnectedException) {
            Log.e(TAG, "START_WIFI_AP ended before the recorder returned credentials", error)
            throw IllegalStateException(
                "SYNC-BLE-WIFI-CREDENTIALS-016 | Recorder did not return Wi-Fi or FTP credentials",
                error
            )
        } catch (error: TimeoutCancellationException) {
            Log.e(TAG, "START_WIFI_AP timed out without a response or recorder disconnect", error)
            throw IllegalStateException(
                "SYNC-BLE-WIFI-AP-TIMEOUT-015 | Recorder did not confirm that its WiFi hotspot started",
                error
            )
        }
    }

    override suspend fun startLivePreview() {
        check(_connection.value is BleConnectionState.Connected) { "BLE-LIVE-CONNECT-001 | Recorder is not connected" }
        check(_status.value?.recording == 1) { "BLE-LIVE-RECORDING-001 | Start recording before live preview" }
        when (livePreview.value) {
            LivePreviewState.Starting, is LivePreviewState.Receiving -> return
            LivePreviewState.Stopping -> error("BLE-LIVE-STATE-001 | Live preview is still stopping")
            LivePreviewState.Off, is LivePreviewState.Unavailable -> Unit
        }
        check(liveNotificationsEnabled) {
            liveSetupError ?: "BLE-LIVE-SETUP-001 | This recorder does not provide live audio notifications"
        }

        resetLivePreview()
        val firstFrame = CompletableDeferred<Unit>()
        liveFirstFrame = firstFrame
        _livePreview.value = LivePreviewState.Starting
        var streamStartAccepted = false
        try {
            send(RecorderBleProtocol.Command.START_BLE_STREAM)
            streamStartAccepted = true
            withTimeout(LIVE_FIRST_FRAME_TIMEOUT_MS) { firstFrame.await() }
        } catch (failure: Throwable) {
            if (livePreview.value is LivePreviewState.Stopping || livePreview.value is LivePreviewState.Off) {
                return
            }
            if (streamStartAccepted && _connection.value is BleConnectionState.Connected) {
                runCatching { send(RecorderBleProtocol.Command.STOP_BLE_STREAM) }
                    .onFailure { Log.w(TAG, "Could not stop failed live stream: ${it.message}") }
            }
            val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            _livePreview.value = LivePreviewState.Unavailable("BLE-LIVE-FIRST-FRAME-002 | $detail")
            throw IllegalStateException("BLE-LIVE-FIRST-FRAME-002 | $detail", failure)
        } finally {
            if (liveFirstFrame === firstFrame) liveFirstFrame = null
        }
    }

    override suspend fun stopLivePreview() {
        if (!livePreview.value.isActive) return
        _livePreview.value = LivePreviewState.Stopping
        try {
            send(RecorderBleProtocol.Command.STOP_BLE_STREAM)
            resetLivePreview()
        } catch (failure: Throwable) {
            val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            _livePreview.value = LivePreviewState.Unavailable("BLE-LIVE-STOP-001 | $detail")
            throw failure
        }
    }

    private suspend fun send(command: RecorderBleProtocol.Command, payload: ByteArray = byteArrayOf()): Response = commandMutex.withLock {
        check(_connection.value is BleConnectionState.Connected) { "Recorder is not connected" }
        val currentGatt = requireNotNull(gatt)
        val characteristic = requireNotNull(commandCharacteristic)
        val seq = sequence.getAndIncrement() and 0xFF
        val deferred = CompletableDeferred<Response>()
        pending[seq] = PendingCommand(command, deferred)
        if (command == RecorderBleProtocol.Command.START_WIFI_AP) wifiHandoffSequence = seq
        val frame = RecorderBleProtocol.commandFrame(command, seq, payload)
        try {
            Log.d(TAG, "Writing ${command.name} seq=$seq frame=${frame.toHex()}")
            check(writeCommandWithRetry(currentGatt, characteristic, frame)) { "BLE command write was rejected" }
            val response = withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            check(response.command == command.value) { "Response command does not match request" }
            check(response.status == RecorderBleProtocol.STATUS_OK) { "Recorder rejected $command with status ${response.status}" }
            response
        } finally {
            pending.remove(seq)
            if (wifiHandoffSequence == seq) wifiHandoffSequence = null
        }
    }

    private suspend fun writeCommandWithRetry(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray
    ): Boolean {
        repeat(3) { attempt ->
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = frame
            if (gatt.writeCharacteristic(characteristic)) {
                Log.d(TAG, "Queued ${characteristic.uuid} response write on attempt ${attempt + 1}")
                return true
            }
            Log.w(TAG, "Rejected ${characteristic.uuid} response write on attempt ${attempt + 1}")
            if (attempt < 2) delay(150L * (attempt + 1))
        }

        // Some Android 10 vendors reject a response write immediately after
        // notification setup even when the characteristic advertises both modes.
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = frame
        return gatt.writeCharacteristic(characteristic).also { queued ->
            Log.d(TAG, "Queued ${characteristic.uuid} no-response write=$queued")
        }
    }

    private suspend fun awaitRecordingState(expected: Int) {
        try {
            withTimeout(RECORDING_STATE_TIMEOUT_MS) { status.filterNotNull().first { it.recording == expected } }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            check(getStatus().recording == expected) { "Recorder did not reach recording state $expected" }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Connection state changed: status=$status newState=$newState")
            // Some recorders report the BLE-to-WiFi switch as a non-success GATT
            // disconnect (for example status 19) instead of a clean disconnect.
            if (newState == BluetoothProfile.STATE_DISCONNECTED && completeWifiHandoffAfterDisconnect()) {
                _connection.value = BleConnectionState.Disconnected
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection failed with status $status")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                requestMtuThenDiscover(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (_connection.value is BleConnectionState.Connecting) fail("Recorder disconnected while connecting")
                else {
                    resetLivePreview()
                    _connection.value = BleConnectionState.Disconnected
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiation completed: mtu=$mtu status=$status")
            discoverServices(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT service discovery failed with status $status")
                return
            }
            val service = gatt.getService(RecorderBleProtocol.serviceUuid) ?: return fail("Recorder service A001 was not found")
            commandCharacteristic = service.getCharacteristic(RecorderBleProtocol.commandUuid)
            statusCharacteristic = service.getCharacteristic(RecorderBleProtocol.statusUuid)
            val statusChar = statusCharacteristic ?: return fail("Recorder status characteristic A101 was not found")
            if (commandCharacteristic == null) return fail("Recorder command characteristic A102 was not found")
            if (!gatt.setCharacteristicNotification(statusChar, true)) {
                return fail("Could not register recorder status notification callback")
            }
            val descriptor = statusChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                ?: return fail("Recorder status notification descriptor was not found")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) fail("Could not enable recorder status notifications")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            when (descriptor.characteristic.uuid) {
                RecorderBleProtocol.statusUuid -> {
                    Log.i(TAG, "A101 CCCD write completed: status=$status value=${descriptor.value?.toHex()}")
                    if (status == BluetoothGatt.GATT_SUCCESS) configureLiveAudioNotifications(gatt)
                    else fail("Could not enable recorder status notifications: $status")
                }
                RecorderBleProtocol.liveAudioUuid -> {
                    Log.i(TAG, "A103 CCCD write completed: status=$status value=${descriptor.value?.toHex()}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        liveNotificationsEnabled = true
                        _livePreview.value = LivePreviewState.Off
                    } else {
                        liveSetupError = "BLE-LIVE-SETUP-004 | Could not enable live audio notifications: $status"
                        _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
                    }
                    completeConnectionReady()
                }
                else -> Log.w(TAG, "Ignoring descriptor write for ${descriptor.characteristic.uuid}")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == RecorderBleProtocol.commandUuid) {
                Log.i(TAG, "A102 write completed: status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            handleCharacteristicNotification(characteristic.uuid, bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(characteristic.uuid, value)
        }
    }

    private fun configureLiveAudioNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(RecorderBleProtocol.serviceUuid)
        val liveChar = service?.getCharacteristic(RecorderBleProtocol.liveAudioUuid)
        liveAudioCharacteristic = liveChar
        if (liveChar == null) {
            liveSetupError = "BLE-LIVE-SETUP-001 | Recorder live audio characteristic A103 was not found"
            _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
            completeConnectionReady()
            return
        }
        if (liveChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            liveSetupError = "BLE-LIVE-SETUP-002 | Recorder A103 does not support notifications"
            _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
            completeConnectionReady()
            return
        }
        if (!gatt.setCharacteristicNotification(liveChar, true)) {
            liveSetupError = "BLE-LIVE-SETUP-003 | Could not register the live audio callback"
            _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
            completeConnectionReady()
            return
        }
        val descriptor = liveChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            liveSetupError = "BLE-LIVE-SETUP-004 | Recorder A103 notification descriptor was not found"
            _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
            completeConnectionReady()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            liveSetupError = "BLE-LIVE-SETUP-005 | Could not enable live audio notifications"
            _livePreview.value = LivePreviewState.Unavailable(liveSetupError!!)
            completeConnectionReady()
        }
    }

    private fun completeConnectionReady() {
        _connection.value = BleConnectionState.Connected
        if (!ready.isCompleted) ready.complete(Unit)
    }

    private fun handleCharacteristicNotification(uuid: java.util.UUID, bytes: ByteArray) {
        when (uuid) {
            RecorderBleProtocol.statusUuid -> handleStatusNotification(bytes)
            RecorderBleProtocol.liveAudioUuid -> handleLiveAudioNotification(bytes)
        }
    }

    private fun handleStatusNotification(bytes: ByteArray) {
        Log.i(TAG, "A101 notification: ${bytes.toHex()}")
        runCatching { RecorderBleProtocol.parseNotification(bytes) }
                .onSuccess { notification ->
                    when (notification) {
                        is Response -> {
                            val waiter = pending.remove(notification.sequence)
                            if (waiter == null) {
                                Log.w(TAG, "Ignoring unmatched response: command=${notification.command} seq=${notification.sequence}")
                            } else {
                                waiter.response.complete(notification)
                            }
                        }
                        is StatusPush -> {
                            _status.value = notification.status
                            if (notification.status.recording == 0 && livePreview.value.isActive) {
                                endLivePreviewBecauseRecordingStopped()
                            }
                            completePendingStatusRequest(notification)
                        }
                        else -> Unit
                    }
                }
                .onFailure { error -> Log.e(TAG, "Could not parse A101 notification: ${error.message}", error) }
    }

    private fun handleLiveAudioNotification(bytes: ByteArray) {
        if (livePreview.value !is LivePreviewState.Starting && livePreview.value !is LivePreviewState.Receiving) {
            Log.w(TAG, "Ignoring A103 audio while live preview is not active")
            return
        }
        val payload = runCatching { RecorderBleProtocol.parseLiveOpusFrame(bytes) }
            .getOrElse { error ->
                val message = "BLE-LIVE-FRAME-001 | ${error.message ?: "Invalid A103 frame"}"
                Log.e(TAG, message, error)
                liveFirstFrame?.completeExceptionally(IllegalStateException(message, error))
                _livePreview.value = LivePreviewState.Unavailable(message)
                return
            }
        val now = System.currentTimeMillis()
        _liveFrames.tryEmit(LiveOpusFrame(payload, now))
        val nextState = synchronized(liveLock) {
            liveReceivedFrames += 1
            liveReceivedPayloadBytes += payload.size
            liveLastFrameAtMillis = now
            val state = _livePreview.value
            val shouldPublish = state is LivePreviewState.Starting || now - liveLastStatePublishAtMillis >= LIVE_STATE_PUBLISH_INTERVAL_MS
            if (shouldPublish) {
                liveLastStatePublishAtMillis = now
                LivePreviewState.Receiving(liveReceivedFrames, liveReceivedPayloadBytes, liveLastFrameAtMillis)
            } else null
        }
        if (nextState != null) _livePreview.value = nextState
        liveFirstFrame?.complete(Unit)
    }

    private fun endLivePreviewBecauseRecordingStopped() {
        liveFirstFrame?.completeExceptionally(
            IllegalStateException("BLE-LIVE-RECORDING-002 | The recorder stopped before live audio was ready")
        )
        resetLivePreview()
    }

    private fun completePendingStatusRequest(notification: StatusPush) {
        val entry = pending.entries.firstOrNull { (_, waiter) ->
            waiter.command == RecorderBleProtocol.Command.GET_STATUS
        } ?: return
        val sequence = entry.key
        val waiter = pending.remove(sequence) ?: return
        Log.w(TAG, "Using A101 STATUS_PUSH as GET_STATUS confirmation for seq=$sequence")
        waiter.response.complete(
            Response(
                command = RecorderBleProtocol.Command.GET_STATUS.value,
                status = RecorderBleProtocol.STATUS_OK,
                sequence = sequence,
                payload = notification.body
            )
        )
    }

    private fun completeWifiHandoffAfterDisconnect(): Boolean {
        val sequence = wifiHandoffSequence ?: return false
        val waiter = pending[sequence]
            ?.takeIf { it.command == RecorderBleProtocol.Command.START_WIFI_AP }
            ?: return false
        Log.e(TAG, "Recorder disconnected during WiFi handoff before credentials arrived")
        return waiter.response.completeExceptionally(WifiHandoffDisconnectedException())
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (serviceDiscoveryRequested) return
        serviceDiscoveryRequested = true
        if (!gatt.discoverServices()) fail("Could not start recorder service discovery")
    }

    private fun requestMtuThenDiscover(gatt: BluetoothGatt) {
        if (mtuNegotiationRequested) return
        mtuNegotiationRequested = true
        if (!gatt.requestMtu(TARGET_MTU)) {
            Log.w(TAG, "MTU request was rejected; discovering services with default MTU")
            discoverServices(gatt)
            return
        }

        // A few Android 10 devices do not deliver onMtuChanged when the peer
        // declines the request. Do not leave service discovery blocked forever.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!serviceDiscoveryRequested && this.gatt === gatt) {
                Log.w(TAG, "MTU callback did not arrive; discovering services with current MTU")
                discoverServices(gatt)
            }
        }, MTU_CALLBACK_TIMEOUT_MS)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        resetLivePreview()
        _connection.value = BleConnectionState.Failed(message)
        ready.completeExceptionally(IllegalStateException(message))
    }

    private fun resetLivePreview() {
        liveFirstFrame?.cancel()
        liveFirstFrame = null
        synchronized(liveLock) {
            liveReceivedFrames = 0L
            liveReceivedPayloadBytes = 0L
            liveLastFrameAtMillis = 0L
            liveLastStatePublishAtMillis = 0L
        }
        _livePreview.value = LivePreviewState.Off
    }

    private companion object {
        const val TAG = "RecorderBle"
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val COMMAND_TIMEOUT_MS = 8_000L
        const val RECORDING_STATE_TIMEOUT_MS = 8_000L
        const val TARGET_MTU = 247
        const val MTU_CALLBACK_TIMEOUT_MS = 1_500L
        const val LIVE_FIRST_FRAME_TIMEOUT_MS = 6_000L
        const val LIVE_STATE_PUBLISH_INTERVAL_MS = 250L
        const val LIVE_FRAME_BUFFER_CAPACITY = 64
        val CLIENT_CHARACTERISTIC_CONFIG = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private data class PendingCommand(
        val command: RecorderBleProtocol.Command,
        val response: CompletableDeferred<Response>
    )

    private class WifiHandoffDisconnectedException : IllegalStateException(
        "Recorder switched to WiFi before returning its credentials"
    )
}

private fun ByteArray.toHex(): String = joinToString(separator = " ") { byte ->
    "%02X".format(byte.toInt() and 0xFF)
}
