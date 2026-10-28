#!/usr/bin/env python3
"""Play a recorder raw Opus file.

The file is concatenated frames, not an Ogg container:

    [1-byte payload length][Opus payload] ...

Payload length is 20..80. The stream does not store sample rate or channel
count; both default to the recorder profile (16 kHz, mono) and can be overridden.
Requires libopus to decode. Playback uses aplay, paplay, or ffplay.
--ogg writes an Ogg Opus file that ffmpeg and ffplay can open directly.
"""

import argparse
import ctypes
import ctypes.util
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import wave

DEFAULT_SAMPLE_RATE = 16_000
DEFAULT_CHANNELS = 1
OPUS_SAMPLE_RATES = (8_000, 12_000, 16_000, 24_000, 48_000)
MIN_PAYLOAD_BYTES = 20
MAX_PAYLOAD_BYTES = 80
OGG_SIGNATURE = b"OggS"
OGG_PACKETS_PER_PAGE = 50
OGG_CRC_TABLE = []
for _index in range(256):
    _crc = _index << 24
    for _ in range(8):
        if _crc & 0x80000000:
            _crc = ((_crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
        else:
            _crc = (_crc << 1) & 0xFFFFFFFF
    OGG_CRC_TABLE.append(_crc)

OPUS_ERRORS = {
    -1: "bad argument",
    -2: "buffer too small",
    -3: "internal error",
    -4: "invalid packet",
    -5: "unimplemented",
    -6: "invalid state",
    -7: "allocation failed",
}


def load_opus():
    candidates = []
    found = ctypes.util.find_library("opus")
    if found:
        candidates.append(found)
    candidates.extend(("libopus.so.0", "libopus.so", "libopus.dylib", "opus"))
    for name in candidates:
        try:
            lib = ctypes.CDLL(name)
        except OSError:
            continue
        lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
        lib.opus_decoder_create.restype = ctypes.c_void_p
        lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
        lib.opus_decoder_destroy.restype = None
        lib.opus_decode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_ubyte),
            ctypes.c_int32,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.c_int,
            ctypes.c_int,
        ]
        lib.opus_decode.restype = ctypes.c_int
        return lib
    sys.exit("libopus was not found. Install it (for example: apt install libopus0).")


def iter_frames(path):
    with open(path, "rb") as source:
        signature = source.read(len(OGG_SIGNATURE))
        if signature == OGG_SIGNATURE:
            sys.exit(f"{path} is Ogg Opus. Play that file with ffplay instead of this script.")
        source.seek(0)
        offset = 0
        frames = 0
        while True:
            length_byte = source.read(1)
            if not length_byte:
                break
            length = length_byte[0]
            if length < MIN_PAYLOAD_BYTES or length > MAX_PAYLOAD_BYTES:
                sys.exit(
                    f"Invalid Opus frame length {length} at byte {offset} "
                    f"after {frames} complete frame(s)"
                )
            payload = source.read(length)
            if len(payload) != length:
                missing = length - len(payload)
                sys.exit(
                    f"Truncated Opus frame at byte {offset} with {missing} payload byte(s) missing"
                )
            yield payload
            frames += 1
            offset += 1 + length
        if frames == 0:
            sys.exit(f"{path} contains no Opus frames")


def packet_samples_48k(packet):
    """Opus packet duration in 48 kHz samples, from the TOC byte."""
    if not packet:
        sys.exit("Empty Opus packet")
    toc = packet[0]
    if toc & 0x80:
        samples = (48_000 << ((toc >> 3) & 0x3)) // 400
    elif (toc & 0x60) == 0x60:
        samples = 48_000 // 50 if toc & 0x08 else 48_000 // 100
    else:
        size_code = (toc >> 3) & 0x3
        samples = 48_000 * 60 // 1000 if size_code == 3 else (48_000 << size_code) // 100
    count = toc & 0x3
    if count == 0:
        frames = 1
    elif count != 3:
        frames = 2
    elif len(packet) < 2 or (packet[1] & 0x3F) == 0:
        sys.exit("Invalid Opus packet frame count")
    else:
        frames = packet[1] & 0x3F
    return samples * frames


def ogg_crc(data):
    crc = 0
    for byte in data:
        crc = ((crc << 8) ^ OGG_CRC_TABLE[((crc >> 24) & 0xFF) ^ byte]) & 0xFFFFFFFF
    return crc


def ogg_page(packets, serial, sequence, granule, bos=False, eos=False):
    segments = []
    body = bytearray()
    for packet in packets:
        offset = 0
        while len(packet) - offset >= 255:
            segments.append(255)
            body.extend(packet[offset:offset + 255])
            offset += 255
        segments.append(len(packet) - offset)
        body.extend(packet[offset:])
    if not segments or len(segments) > 255:
        sys.exit("Ogg page segment count is outside 1..255")
    header_type = (0x02 if bos else 0) | (0x04 if eos else 0)
    page = bytearray(b"OggS")
    page += struct.pack("<BB", 0, header_type)
    page += struct.pack("<Q", granule)
    page += struct.pack("<II", serial, sequence)
    page += struct.pack("<I", 0)
    page += struct.pack("<B", len(segments))
    page += bytes(segments)
    page += body
    page[22:26] = struct.pack("<I", ogg_crc(page))
    return page


def write_ogg_opus(packets, path, sample_rate, channels):
    """Wrap raw Opus payloads in an Ogg Opus stream (OpusHead + OpusTags)."""
    serial = int.from_bytes(os.urandom(4), "little")
    head = struct.pack("<8sBBHIhB", b"OpusHead", 1, channels, 0, sample_rate, 0, 0)
    vendor = b"play_raw_opus"
    tags = b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)
    pages = [
        ogg_page([head], serial, 0, 0, bos=True),
        ogg_page([tags], serial, 1, 0),
    ]
    sequence = 2
    granule = 0
    batch = []
    for index, packet in enumerate(packets):
        batch.append(packet)
        granule += packet_samples_48k(packet)
        if len(batch) == OGG_PACKETS_PER_PAGE or index == len(packets) - 1:
            pages.append(ogg_page(batch, serial, sequence, granule, eos=index == len(packets) - 1))
            sequence += 1
            batch = []
    with open(path, "wb") as output:
        for page in pages:
            output.write(page)
    return granule / 48_000


def play_wav(path):
    """Play a finished WAV file and return only after the player exits."""
    if shutil.which("aplay"):
        command = ["aplay", "-q", path]
    elif shutil.which("paplay"):
        command = ["paplay", path]
    elif shutil.which("ffplay"):
        command = ["ffplay", "-nodisp", "-autoexit", "-loglevel", "error", path]
    else:
        sys.exit("No player found (aplay, paplay, or ffplay). Use --wav to write a WAV file.")
    player = subprocess.Popen(command)
    try:
        status = player.wait()
    except KeyboardInterrupt:
        player.terminate()
        try:
            player.wait(timeout=1)
        except subprocess.TimeoutExpired:
            player.kill()
            player.wait()
        sys.exit(130)
    if status != 0:
        sys.exit(f"Player exited with status {status}")


def decode_and_output(path, wav_path, ogg_path, play, sample_rate, channels):
    need_pcm = play or wav_path
    lib = None
    decoder = None
    pcm = None
    max_frame_samples = sample_rate * 120 // 1000
    if need_pcm:
        lib = load_opus()
        error = ctypes.c_int()
        decoder = lib.opus_decoder_create(sample_rate, channels, ctypes.byref(error))
        if not decoder or error.value != 0:
            sys.exit(f"opus_decoder_create failed ({error.value})")
        pcm = (ctypes.c_int16 * (max_frame_samples * channels))()

    temporary = None
    if play and not wav_path:
        temporary = tempfile.NamedTemporaryFile(prefix="raw-opus-", suffix=".wav", delete=False)
        temporary.close()
        wav_path = temporary.name
    wav = wave.open(wav_path, "wb") if wav_path else None
    frames = 0
    samples = 0
    payloads = []
    try:
        try:
            if wav:
                wav.setnchannels(channels)
                wav.setsampwidth(2)
                wav.setframerate(sample_rate)
            for payload in iter_frames(path):
                if ogg_path:
                    payloads.append(payload)
                if decoder:
                    packet = (ctypes.c_ubyte * len(payload)).from_buffer_copy(payload)
                    decoded = lib.opus_decode(decoder, packet, len(payload), pcm, max_frame_samples, 0)
                    if decoded <= 0:
                        reason = OPUS_ERRORS.get(decoded, "decode failed")
                        sys.exit(f"Unable to decode Opus frame {frames}: {reason} ({decoded})")
                    wav.writeframes(ctypes.string_at(ctypes.addressof(pcm), decoded * channels * 2))
                    samples += decoded
                frames += 1
        finally:
            if lib and decoder:
                lib.opus_decoder_destroy(decoder)
            if wav:
                wav.close()

        if frames == 0:
            sys.exit(f"{path} contains no Opus frames")
        if decoder:
            duration = samples / sample_rate
            print(
                f"decoded {frames} frame(s), {duration:.2f} s, {sample_rate} Hz, {channels} ch",
                file=sys.stderr,
            )
        if temporary is None and wav_path:
            print(f"wrote {wav_path}", file=sys.stderr)
        if ogg_path:
            duration = write_ogg_opus(payloads, ogg_path, sample_rate, channels)
            print(
                f"wrote {ogg_path} ({frames} frame(s), {duration:.2f} s, {sample_rate} Hz, {channels} ch)",
                file=sys.stderr,
            )
        if play:
            play_wav(wav_path)
    finally:
        if temporary is not None:
            os.unlink(temporary.name)


def main():
    parser = argparse.ArgumentParser(
        description="Play a recorder raw Opus file. Defaults match the recorder: 16 kHz, mono."
    )
    parser.add_argument("file", help="raw .opus file: [1-byte length][payload]...")
    parser.add_argument(
        "--sample-rate",
        type=int,
        default=DEFAULT_SAMPLE_RATE,
        choices=OPUS_SAMPLE_RATES,
        help="output sample rate in Hz (default: %(default)s). Raw Opus does not store this.",
    )
    parser.add_argument(
        "--channels",
        type=int,
        default=DEFAULT_CHANNELS,
        choices=(1, 2),
        help="channel count (default: %(default)s). Raw Opus does not store this.",
    )
    parser.add_argument("--wav", metavar="PATH", help="write decoded 16-bit PCM WAV to this path")
    parser.add_argument(
        "--ogg",
        metavar="PATH",
        help="write Ogg Opus (OpusHead) so ffmpeg/ffplay can play it directly",
    )
    parser.add_argument("--no-play", action="store_true", help="do not play; requires --wav or --ogg")
    args = parser.parse_args()
    if args.no_play and not args.wav and not args.ogg:
        parser.error("--no-play requires --wav or --ogg")
    decode_and_output(
        args.file, args.wav, args.ogg, not args.no_play, args.sample_rate, args.channels
    )


if __name__ == "__main__":
    main()
