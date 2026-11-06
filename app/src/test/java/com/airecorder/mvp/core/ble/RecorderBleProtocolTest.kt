package com.airecorder.mvp.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderBleProtocolTest {
    @Test fun commandFrameUsesLittleEndianPayloadLength() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x2A, 0x01, 0x00, 0x00),
            RecorderBleProtocol.commandFrame(RecorderBleProtocol.Command.START_RECORD, 0x2A, byteArrayOf(0))
        )
    }

    @Test fun parsesStatusPushWithoutResponseSequencePrefix() {
        val status = byteArrayOf(1, 0, 1, 1, 80, 0x68, 0x10, 0x3C, 0, 0, 0, 1, 0, 0, 0, 1)
        val packet = byteArrayOf(3, 0, 0, 16, 0) + status
        val notification = RecorderBleProtocol.parseNotification(packet) as StatusPush
        assertEquals(1, notification.status.recording)
        assertEquals(4200, notification.status.batteryMillivolts)
        assertEquals(60L, notification.status.recordingSeconds)
        assertArrayEquals(status, notification.body)
    }

    @Test fun parsesWifiCredentialTlvFromNegotiatedPayload() {
        fun field(value: String) = byteArrayOf(value.length.toByte()) + value.toByteArray()
        val payload = field("recorder-ap") +
            field("wifi-from-ble") +
            field("ftp-from-ble") +
            field("ftp-secret-from-ble") +
            byteArrayOf(192.toByte(), 168.toByte(), 4, 1)
        val parsed = RecorderBleProtocol.parseWifiCredentials(payload)
        assertEquals("recorder-ap", parsed.ssid)
        assertEquals("wifi-from-ble", parsed.wifiPassword)
        assertEquals("ftp-from-ble", parsed.ftpUser)
        assertEquals("ftp-secret-from-ble", parsed.ftpPassword)
        assertEquals("192.168.4.1", parsed.host)
        assertEquals(21, parsed.port)
    }

    @Test fun rejectsWifiCredentialsWhenPasswordIsMissing() {
        fun field(value: String) = byteArrayOf(value.length.toByte()) + value.toByteArray()
        val payload = field("recorder-ap") +
            field("") +
            field("ftp-from-ble") +
            field("ftp-secret-from-ble") +
            byteArrayOf(192.toByte(), 168.toByte(), 4, 1)
        try {
            RecorderBleProtocol.parseWifiCredentials(payload)
            throw AssertionError("Expected missing WiFi password to fail")
        } catch (error: IllegalArgumentException) {
            assertEquals("WiFi password is missing from the BLE credential TLV", error.message)
        }
    }

    @Test fun parsesOneLengthPrefixedLiveOpusFrame() {
        val payload = ByteArray(20) { it.toByte() }
        assertArrayEquals(payload, RecorderBleProtocol.parseLiveOpusFrame(byteArrayOf(20) + payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLiveOpusFrameWithMismatchedLength() {
        RecorderBleProtocol.parseLiveOpusFrame(byteArrayOf(20) + ByteArray(19))
    }
}
