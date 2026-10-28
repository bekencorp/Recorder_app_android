package com.airecorder.mvp.core.audio

import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the recorder's length-prefixed Opus frames with Concentus, a Java port of libopus.
 * Output is 16-bit little-endian PCM WAV at 16 kHz mono, matching [RawOpusFrames].
 */
class ConcentusRawOpusDecoder : RawOpusDecoder {
    override fun decodeToWav(source: File, destination: File): File {
        require(source.isFile) { "Recording file is missing" }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.partial")
        partial.delete()
        try {
            source.inputStream().buffered().use { input ->
                RawOpusFrames.rejectOggContainer(input)
                val decoder = OpusDecoder(RawOpusFrames.SAMPLE_RATE, RawOpusFrames.CHANNELS)
                val pcm = ShortArray(MAX_FRAME_SAMPLES)
                RandomAccessFile(partial, "rw").use { output ->
                    output.setLength(0)
                    output.write(ByteArray(WAV_HEADER_BYTES))
                    var pcmBytes = 0
                    var frames = 0
                    var offset = 0L
                    while (true) {
                        val frame = RawOpusFrames.nextFrame(input, frames, offset) ?: break
                        val samples = try {
                            decoder.decode(frame.payload, 0, frame.payload.size, pcm, 0, pcm.size, false)
                        } catch (error: OpusException) {
                            throw IllegalArgumentException(
                                "Unable to decode Opus frame $frames at byte $offset",
                                error
                            )
                        }
                        require(samples > 0) { "Opus frame $frames at byte $offset produced no audio" }
                        writePcm(output, pcm, samples)
                        pcmBytes += samples * Short.SIZE_BYTES
                        frames += 1
                        offset = frame.nextOffset
                    }
                    require(frames > 0) { "Recording contains no Opus frames" }
                    output.seek(0)
                    output.write(wavHeader(pcmBytes))
                }
            }
            if (destination.exists() && !destination.delete()) {
                error("Couldn't replace ${destination.absolutePath}")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            return destination
        } catch (error: Exception) {
            partial.delete()
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalArgumentException("Couldn't decode ${source.name}", error)
        }
    }

    private fun writePcm(output: RandomAccessFile, pcm: ShortArray, samples: Int) {
        val bytes = ByteBuffer.allocate(samples * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until samples) bytes.putShort(pcm[index])
        output.write(bytes.array())
    }

    private fun wavHeader(pcmDataBytes: Int): ByteArray {
        val channels = RawOpusFrames.CHANNELS
        val sampleRate = RawOpusFrames.SAMPLE_RATE
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(RIFF)
            putInt(36 + pcmDataBytes)
            put(WAVE)
            put(FMT)
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put(DATA)
            putInt(pcmDataBytes)
        }.array()
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val MAX_FRAME_SAMPLES = RawOpusFrames.SAMPLE_RATE * 120 / 1000
        val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        val DATA = "data".toByteArray(Charsets.US_ASCII)
    }
}
