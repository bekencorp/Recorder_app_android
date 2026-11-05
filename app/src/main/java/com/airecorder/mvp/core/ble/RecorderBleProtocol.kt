package com.airecorder.mvp.core.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object RecorderBleProtocol {
    val serviceUuid = shortUuid(0xA001)
    val statusUuid = shortUuid(0xA101)
    val commandUuid = shortUuid(0xA102)
    val liveAudioUuid = shortUuid(0xA103)

    const val TYPE_RESPONSE = 0x01
    const val TYPE_EVENT = 0x02
    const val TYPE_STATUS_PUSH = 0x03
    const val STATUS_OK = 0x00
    const val STATUS_INVALID_STATE = 0x02
    const val DEFAULT_FTP_PORT = 21
    const val LIVE_OPUS_MIN_PAYLOAD_BYTES = 20
    const val LIVE_OPUS_MAX_PAYLOAD_BYTES = 80

    enum class Command(val value: Int) {
        START_RECORD(0x01), STOP_RECORD(0x02), START_BLE_STREAM(0x05),
        STOP_BLE_STREAM(0x06), GET_STATUS(0x07), SET_SCENE(0x08),
        SET_TIME(0x09), GET_TIME(0x0A), START_WIFI_AP(0x0B), STOP_WIFI_AP(0x0C)
    }

    enum class Scene(val value: Int) { NORMAL(0x00), PHONE_CALL(0x01) }

    fun commandFrame(command: Command, sequence: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(sequence in 0..255) { "Sequence must fit in one byte" }
        require(payload.size <= 0xFFFF) { "Command payload is too large" }
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(command.value.toByte())
            put(sequence.toByte())
            putShort(payload.size.toShort())
            put(payload)
        }.array()
    }

    fun setTimePayload(unixSeconds: Long): ByteArray {
        require(unixSeconds in 0..0xFFFF_FFFFL) { "Unix time is outside the firmware uint32 range" }
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(unixSeconds.toInt()).array()
    }

    fun parseNotification(bytes: ByteArray): Notification {
        require(bytes.size >= 5) { "Notification is shorter than the 5-byte header" }
        val length = u16le(bytes, 3)
        require(bytes.size == 5 + length) { "Notification length mismatch" }
        val type = bytes[0].toInt() and 0xFF
        val code = bytes[1].toInt() and 0xFF
        val status = bytes[2].toInt() and 0xFF
        val data = bytes.copyOfRange(5, bytes.size)
        return when (type) {
            TYPE_RESPONSE -> {
                require(data.size >= 2) { "Response is missing seq/reserved prefix" }
                Response(code, status, data[0].toInt() and 0xFF, data.copyOfRange(2, data.size))
            }
            TYPE_EVENT -> Event(code, status, data)
            TYPE_STATUS_PUSH -> StatusPush(parseStatus(data), data)
            else -> Unknown(type, code, status, data)
        }
    }

    fun parseStatus(bytes: ByteArray): DeviceStatus {
        require(bytes.size == 16) { "Status body must be exactly 16 bytes" }
        return DeviceStatus(
            recording = bytes[0].toInt() and 0xFF,
            scene = bytes[1].toInt() and 0xFF,
            bleStreaming = bytes[2].toInt() and 0xFF == 1,
            timeSynced = bytes[3].toInt() and 0xFF == 1,
            freePercent = bytes[4].toInt() and 0xFF,
            batteryMillivolts = u16le(bytes, 5),
            recordingSeconds = u32le(bytes, 7),
            rtcUnixSeconds = u32le(bytes, 11),
            deviceState = bytes[15].toInt() and 0xFF
        )
    }

    /**
     * Every A103 notification is one complete raw Opus frame, prefixed by a one-byte payload length.
     * The same framing is used by recorder .opus files.
     */
    fun parseLiveOpusFrame(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "Live Opus frame is missing its length byte" }
        val payloadLength = bytes[0].toInt() and 0xFF
        require(payloadLength in LIVE_OPUS_MIN_PAYLOAD_BYTES..LIVE_OPUS_MAX_PAYLOAD_BYTES) {
            "Live Opus payload length $payloadLength is outside the recorder range"
        }
        require(bytes.size == payloadLength + 1) {
            "Live Opus frame length mismatch: expected ${payloadLength + 1} bytes, received ${bytes.size}"
        }
        return bytes.copyOfRange(1, bytes.size)
    }

    fun parseWifiCredentials(bytes: ByteArray): WifiCredentials {
        var offset = 0
        fun readUtf8(): String {
            require(offset < bytes.size) { "Truncated WiFi credential TLV" }
            val length = bytes[offset].toInt() and 0xFF
            offset += 1
            require(offset + length <= bytes.size) { "Truncated WiFi credential value" }
            return bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8).also { offset += length }
        }
        val ssid = readUtf8()
        val wifiPassword = readUtf8()
        val ftpUser = readUtf8()
        val ftpPassword = readUtf8()
        require(ssid.isNotBlank()) { "WiFi SSID is missing from the BLE credential TLV" }
        require(wifiPassword.isNotBlank()) { "WiFi password is missing from the BLE credential TLV" }
        require(ftpUser.isNotBlank()) { "FTP user is missing from the BLE credential TLV" }
        require(ftpPassword.isNotBlank()) { "FTP password is missing from the BLE credential TLV" }
        require(offset + 4 == bytes.size) { "WiFi credential TLV has an invalid IPv4 tail" }
        val ip = bytes.copyOfRange(offset, offset + 4).joinToString(".") { (it.toInt() and 0xFF).toString() }
        require(ip != "0.0.0.0") { "WiFi host is missing from the BLE credential TLV" }
        // The credential TLV has no port field. Phase-one FTP always uses TCP 21.
        return WifiCredentials(ssid, wifiPassword, ftpUser, ftpPassword, ip, DEFAULT_FTP_PORT)
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun shortUuid(value: Int) = java.util.UUID.fromString(
        "0000${value.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb"
    )
}

sealed interface Notification
data class Response(val command: Int, val status: Int, val sequence: Int, val payload: ByteArray) : Notification
data class Event(val event: Int, val status: Int, val payload: ByteArray) : Notification
data class StatusPush(val status: DeviceStatus, val body: ByteArray) : Notification
data class Unknown(val type: Int, val code: Int, val status: Int, val payload: ByteArray) : Notification

data class DeviceStatus(
    val recording: Int,
    val scene: Int,
    val bleStreaming: Boolean,
    val timeSynced: Boolean,
    val freePercent: Int,
    val batteryMillivolts: Int,
    val recordingSeconds: Long,
    val rtcUnixSeconds: Long,
    val deviceState: Int
)

data class WifiCredentials(
    val ssid: String,
    val wifiPassword: String,
    val ftpUser: String,
    val ftpPassword: String,
    val host: String,
    val port: Int = RecorderBleProtocol.DEFAULT_FTP_PORT
)
