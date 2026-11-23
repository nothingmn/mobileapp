package com.anopticlabs.gravel.ring.protocol

private const val TAG_PCM = 0x50
private const val TAG_COMPRESSED = 0x51
private const val TAG_PART = 0x52
private const val TAG_CLOCK = 0x02
private const val TAG_CLOCK_OFFSET = 0x35

/** The highest sample rate accepted from the ring; a plausibility bound, far above what it records at. */
const val MAX_RING_SAMPLE_RATE = 192_000

/** How many payload bytes of an unknown record are kept for the log: enough to recognise an encoding, not the audio. */
private const val UNKNOWN_RECORD_HEAD = 8

/** Audio from one collection: unsigned 16-bit [samples] at [sampleRate] Hz. */
class RingAudio internal constructor(val sampleRate: Int, val samples: IntArray)

/**
 * A record in a collection that this client does not use: its [tag], its payload [length] and the first few payload
 * bytes, [head]. Kept only so a sync can say what the ring sent; nothing reads it as a value and nothing acts on it.
 */
class RingUnknownRecord(val tag: Int, val length: Long, val head: ByteArray)


/**
 * One collection of a recording. Collections sharing [startIndex] are the parts of one recording, in the
 * order they were read; the [final] one closes it. [ringClock] and [clockOffset] date it, see [capturedAtUnixSeconds].
 * [unknownRecords] are the records the parser skipped, kept for the log alone.
 */
class RingCollection internal constructor(
    val audio: RingAudio,
    val startIndex: Long,
    val final: Boolean,
    val ringClock: Long?,
    val clockOffset: Long?,
    val unknownRecords: List<RingUnknownRecord> = emptyList(),
) {
    /**
     * The Unix time the collection carries. Before the phone sets the ring's clock, the clock counts from zero
     * and the offset holds the difference; after, the offset is all ones and the clock is Unix time.
     */
    val capturedAtUnixSeconds: Long?
        get() = if (ringClock == null || clockOffset == null) null else (ringClock + clockOffset + 1) and U32_MAX
}

/**
 * Parses the answer to [RingRequest.ReadCollection]: a 24-bit length equal to the collection's size and a
 * zero byte, then records of a tag, a length (32-bit for audio, else 16-bit) and a payload. Records this
 * client does not use are skipped, and kept in [RingCollection.unknownRecords] so a sync can log them: the
 * parser never guesses what a record means. A collection needs one audio record and one part record. Throws
 * [RingProtocolException] when the bytes do not have this shape; parsing is deterministic, so the same bytes
 * are refused the same way every time.
 */
fun parseRingCollection(data: ByteArray): RingCollection {
    if (data.size < 4 || data.size > MAX_COLLECTION_SIZE) throw RingProtocolException("a collection of ${data.size} bytes")
    if (data.u8(3) != 0) throw RingProtocolException("an unsupported collection header")
    if (data.u24(0) != data.size) throw RingProtocolException("a collection of ${data.size} bytes declaring ${data.u24(0)}")
    var audio: RingAudio? = null
    var part: Pair<Long, Boolean>? = null
    var clock: Long? = null
    var offset: Long? = null
    val seen = mutableSetOf<Int>()
    val unknown = mutableListOf<RingUnknownRecord>()
    var at = 4
    while (at < data.size) {
        val tag = data.u8(at)
        val wide = tag == TAG_PCM || tag == TAG_COMPRESSED
        val header = if (wide) 5 else 3
        if (data.size - at < header) throw RingProtocolException("a record header past the end")
        val length = if (wide) data.u32(at + 1) else data.u16(at + 1).toLong()
        val start = at + header
        if (length > data.size - start) throw RingProtocolException("record 0x${tag.toString(16)} runs past the end")
        val end = start + length.toInt()
        val used = tag == TAG_PART || tag == TAG_CLOCK || tag == TAG_CLOCK_OFFSET || wide
        if (used && !seen.add(if (wide) TAG_COMPRESSED else tag)) {
            throw RingProtocolException("a second record 0x${tag.toString(16)}")
        }
        when (tag) {
            TAG_COMPRESSED -> audio = compressedAudio(data, start, end)
            TAG_PCM -> audio = pcmAudio(data, start, end)
            TAG_PART -> {
                requireLength(tag, length, 6)
                part = data.u32(start) to (data.u8(start + 5) != 0)
            }
            TAG_CLOCK -> {
                requireLength(tag, length, 4)
                clock = data.u32(start)
            }
            TAG_CLOCK_OFFSET -> {
                requireLength(tag, length, 4)
                offset = data.u32(start)
            }
            else -> unknown += RingUnknownRecord(tag, length, data.copyOfRange(start, minOf(end, start + UNKNOWN_RECORD_HEAD)))
        }
        at = end
    }
    val (startIndex, final) = part ?: throw RingProtocolException("a collection without a part record")
    return RingCollection(
        audio ?: throw RingProtocolException("a collection without audio"),
        startIndex,
        final,
        clock,
        offset,
        unknown,
    )
}

private fun requireLength(tag: Int, length: Long, least: Int) {
    if (length < least) throw RingProtocolException("record 0x${tag.toString(16)} of $length bytes")
}

private fun sampleRate(value: Long): Int {
    if (value !in 1..MAX_RING_SAMPLE_RATE.toLong()) throw RingProtocolException("a sample rate of $value")
    return value.toInt()
}

private fun compressedAudio(data: ByteArray, start: Int, end: Int): RingAudio {
    requireLength(TAG_COMPRESSED, (end - start).toLong(), 9)
    val rate = sampleRate(data.u32(start + 5))
    return RingAudio(rate, decodeRingAudio(data.u8(start), data.u32(start + 1), data, start + 9, end))
}

/** Signed 16-bit samples, moved to the unsigned scale the compressed audio decodes to. */
private fun pcmAudio(data: ByteArray, start: Int, end: Int): RingAudio {
    requireLength(TAG_PCM, (end - start).toLong(), 4)
    if ((end - start - 4) % 2 != 0) throw RingProtocolException("an odd number of PCM bytes")
    val rate = sampleRate(data.u32(start))
    val samples = IntArray((end - start - 4) / 2) { (data.u16(start + 4 + 2 * it).toShort() + 32768) }
    return RingAudio(rate, samples)
}
