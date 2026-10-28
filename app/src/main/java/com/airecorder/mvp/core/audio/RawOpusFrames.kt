package com.airecorder.mvp.core.audio

import java.io.File
import java.io.InputStream

/** The recorder's files are raw framed Opus, not Ogg Opus containers. */
object RawOpusFrames {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val SAMPLES_PER_FRAME = 320
    const val MIN_PAYLOAD_BYTES = 20
    const val MAX_PAYLOAD_BYTES = 80
    private const val FIXED_SLOT_PAYLOAD_BYTES = 80
    private const val FIXED_SLOT_BYTES = FIXED_SLOT_PAYLOAD_BYTES + 1
    private const val MIN_FIXED_SLOT_RECOVERY_PERCENT = 95L

    data class Validation(val frames: Int, val payloadBytes: Long) {
        val durationMillis: Long = frames * 20L
    }

    data class FixedSlotRecovery(
        val recoveredFile: File,
        val validation: Validation,
        val discardedFrames: Int
    )

    fun validate(file: File): Validation = file.inputStream().use(::validate)

    fun validate(input: InputStream): Validation {
        val source = input.buffered()
        rejectOggContainer(source)
        var frames = 0
        var payloadBytes = 0L
        var offset = 0L
        while (true) {
            val frame = nextFrame(source, frames, offset) ?: break
            frames += 1
            payloadBytes += frame.payload.size
            offset = frame.nextOffset
        }
        require(frames > 0) { "Recording contains no Opus frames" }
        return Validation(frames, payloadBytes)
    }

    internal fun rejectOggContainer(source: InputStream) {
        if (!source.markSupported()) return
        source.mark(OGG_SIGNATURE.size)
        val signature = ByteArray(OGG_SIGNATURE.size)
        val signatureBytes = source.read(signature)
        source.reset()
        require(!(signatureBytes == OGG_SIGNATURE.size && signature.contentEquals(OGG_SIGNATURE))) {
            "Recorder file is Ogg Opus, but this recorder profile requires raw framed Opus"
        }
    }

    /** Returns the next length-prefixed payload, or null at a clean end of stream. */
    internal fun nextFrame(source: InputStream, framesSoFar: Int, offset: Long): OpusFrame? {
        val frameOffset = offset
        val length = source.read()
        if (length == -1) return null
        var nextOffset = offset + 1
        require(length in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) {
            "Invalid Opus frame length $length at byte $frameOffset after $framesSoFar complete frame(s)"
        }
        val payload = ByteArray(length)
        var filled = 0
        while (filled < payload.size) {
            val read = source.read(payload, filled, payload.size - filled)
            require(read != -1) {
                "Truncated Opus frame at byte $frameOffset with ${payload.size - filled} payload byte(s) missing"
            }
            filled += read
            nextOffset += read
        }
        return OpusFrame(payload, nextOffset)
    }

    /**
     * Some early board firmware writes fixed 81-byte slots but intermittently corrupts the
     * one-byte 80-byte-payload length prefix. Recover only this highly specific shape and drop
     * the damaged slots rather than inventing unknown Opus packets.
     */
    fun recoverFixed80ByteSlots(source: File): FixedSlotRecovery? {
        if (!source.isFile || source.length() == 0L || source.length() % FIXED_SLOT_BYTES != 0L) return null

        val totalSlots = source.length() / FIXED_SLOT_BYTES
        val recovered = File(source.parentFile, "${source.name}.recovered")
        recovered.delete()
        val slot = ByteArray(FIXED_SLOT_BYTES)
        var acceptedSlots = 0L

        source.inputStream().buffered().use { input ->
            recovered.outputStream().buffered().use { output ->
                repeat(totalSlots.toInt()) {
                    input.readFully(slot)
                    if ((slot[0].toInt() and 0xFF) == FIXED_SLOT_PAYLOAD_BYTES) {
                        output.write(slot)
                        acceptedSlots += 1
                    }
                }
            }
        }

        if (acceptedSlots == 0L || acceptedSlots * 100L < totalSlots * MIN_FIXED_SLOT_RECOVERY_PERCENT) {
            recovered.delete()
            return null
        }

        return try {
            FixedSlotRecovery(
                recoveredFile = recovered,
                validation = validate(recovered),
                discardedFrames = (totalSlots - acceptedSlots).toInt()
            )
        } catch (_: IllegalArgumentException) {
            recovered.delete()
            null
        }
    }

    private val OGG_SIGNATURE = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
}

private fun InputStream.readFully(target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val read = read(target, offset, target.size - offset)
        require(read != -1) { "Unexpected end of fixed recorder slot" }
        offset += read
    }
}

/** Decodes a recorder raw-Opus file to 16-bit PCM WAV at [RawOpusFrames.SAMPLE_RATE]. */
interface RawOpusDecoder {
    fun decodeToWav(source: File, destination: File): File
}

internal data class OpusFrame(val payload: ByteArray, val nextOffset: Long)
