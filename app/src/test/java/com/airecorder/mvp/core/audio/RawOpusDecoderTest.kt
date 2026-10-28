package com.airecorder.mvp.core.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class RawOpusDecoderTest {
    @Test fun decodesRawFramesToPcmWav() {
        val frameCount = 25
        val pcm = sine(frameCount * RawOpusFrames.SAMPLES_PER_FRAME)
        val encoded = encodeFrames(pcm)
        val source = File.createTempFile("recorder-opus", ".opus")
        val destination = File.createTempFile("recorder-opus", ".wav")
        source.writeBytes(encoded)

        try {
            ConcentusRawOpusDecoder().decodeToWav(source, destination)
            val wav = destination.readBytes()
            val header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", wav.decodeToString(0, 4))
            assertEquals("WAVE", wav.decodeToString(8, 12))
            assertEquals(1.toShort(), header.getShort(20))
            assertEquals(RawOpusFrames.CHANNELS.toShort(), header.getShort(22))
            assertEquals(RawOpusFrames.SAMPLE_RATE, header.getInt(24))
            assertEquals(16.toShort(), header.getShort(34))
            val dataBytes = header.getInt(40)
            assertEquals(frameCount * RawOpusFrames.SAMPLES_PER_FRAME * 2, dataBytes)
            assertEquals(44 + dataBytes, wav.size)

            val decoded = ShortArray(dataBytes / 2)
            val samples = ByteBuffer.wrap(wav, 44, dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (index in decoded.indices) decoded[index] = samples.short
            val audible = decoded.drop(RawOpusFrames.SAMPLES_PER_FRAME * 2).map { it.toInt() }
            val rms = sqrt(audible.sumOf { it.toLong() * it }.toDouble() / audible.size)
            assertTrue("Decoded audio RMS $rms is too quiet", rms > 2_000.0)
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOggOpusContainer() {
        val source = File.createTempFile("recorder-ogg", ".opus")
        val destination = File.createTempFile("recorder-ogg", ".wav")
        try {
            source.writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            ConcentusRawOpusDecoder().decodeToWav(source, destination)
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedFrame() {
        val source = File.createTempFile("recorder-truncated", ".opus")
        val destination = File.createTempFile("recorder-truncated", ".wav")
        try {
            source.writeBytes(byteArrayOf(20, 1, 2))
            ConcentusRawOpusDecoder().decodeToWav(source, destination)
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test fun readsTheSameFramesValidationAccepts() {
        val bytes = byteArrayOf(20) + ByteArray(20) { 1 } + byteArrayOf(21) + ByteArray(21) { 2 }
        val source = ByteArrayInputStream(bytes).buffered()
        val first = RawOpusFrames.nextFrame(source, 0, 0)
        val second = RawOpusFrames.nextFrame(source, 1, requireNotNull(first).nextOffset)
        assertEquals(20, first.payload.size)
        assertEquals(21, requireNotNull(second).payload.size)
        assertEquals(null, RawOpusFrames.nextFrame(source, 2, second.nextOffset))
    }

    private fun sine(sampleCount: Int): ShortArray = ShortArray(sampleCount) { index ->
        (sin(2.0 * PI * 440.0 * index / RawOpusFrames.SAMPLE_RATE) * 12_000.0).toInt().toShort()
    }

    private fun encodeFrames(pcm: ShortArray): ByteArray {
        val encoder = OpusEncoder(RawOpusFrames.SAMPLE_RATE, RawOpusFrames.CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO)
        encoder.setBitrate(16_000)
        encoder.setUseVBR(false)
        val packet = ByteArray(RawOpusFrames.MAX_PAYLOAD_BYTES)
        val output = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < pcm.size) {
            val length = encoder.encode(pcm, offset, RawOpusFrames.SAMPLES_PER_FRAME, packet, 0, packet.size)
            check(length in RawOpusFrames.MIN_PAYLOAD_BYTES..RawOpusFrames.MAX_PAYLOAD_BYTES) {
                "Test packet length $length is outside the recorder frame range"
            }
            output.write(length)
            output.write(packet, 0, length)
            offset += RawOpusFrames.SAMPLES_PER_FRAME
        }
        return output.toByteArray()
    }
}
