package it.federicoraponi.watchdeck.video

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val BUFFER_COUNT = 16
private const val SYNC_FIRST = 0xFF
private const val SYNC_SECOND_MASK = 0xFE
private const val SYNC_SECOND = 0xF8
private const val BLOCK_SIZE_IN_16_BITS = 7
private const val VERBATIM_SUBFRAME = 0x02
private const val FIXED_HEADER_BYTES = 4

/**
 * Reads one FLAC frame as go2rtc writes it for cameras with G.711 audio: a single channel of uncompressed 16-bit
 * samples ("verbatim"). Android's decoders reject this stream, among other things because go2rtc numbers the
 * samples with a UTF-8 encoder that breaks after a few seconds. The number and the checksums are not needed here.
 *
 * Writes the samples to [output] in little-endian order, or returns what is wrong with the frame.
 */
private fun readVerbatimFrame(
    frame: ByteBuffer,
    output: (Int) -> ByteBuffer,
): String? {
    val start = frame.position()
    val size = frame.remaining()

    fun byte(index: Int) = frame.get(start + index).toInt() and 0xFF

    if (size < FIXED_HEADER_BYTES + 1) return "frame too short"
    if (byte(0) != SYNC_FIRST || byte(1) and SYNC_SECOND_MASK != SYNC_SECOND) return "not a FLAC frame"
    if (byte(2) shr 4 != BLOCK_SIZE_IN_16_BITS) return "unexpected block size coding"
    // The sample number is coded like a UTF-8 character: its first byte tells how many bytes it takes.
    val first = byte(FIXED_HEADER_BYTES)
    val numberBytes = if (first < 0x80) 1 else Integer.numberOfLeadingZeros(first.inv() and 0xFF) - 24
    var index = FIXED_HEADER_BYTES + numberBytes
    // Block size (2 bytes), header checksum, subframe header.
    if (index + 4 > size) return "frame header too short"
    val samples = ((byte(index) shl 8) or byte(index + 1)) + 1
    index += 3
    if (byte(index) != VERBATIM_SUBFRAME) return "only uncompressed audio is supported"
    index += 1
    if (index + samples * 2 > size) return "frame shorter than its samples"
    val pcm = output(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    val source = frame.duplicate().order(ByteOrder.BIG_ENDIAN)
    source.position(start + index)
    repeat(samples) { pcm.putShort(source.short) }
    pcm.flip()
    return null
}

@OptIn(UnstableApi::class)
private class VerbatimFlacDecoder(
    val sampleRate: Int,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        arrayOfNulls(BUFFER_COUNT),
        arrayOfNulls(BUFFER_COUNT),
    ) {
    override fun getName(): String = "go2rtc-verbatim-flac"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("Audio decoding failed", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): DecoderException? {
        val frame = inputBuffer.data ?: return DecoderException("Empty audio frame")
        val problem = readVerbatimFrame(frame) { size -> outputBuffer.init(inputBuffer.timeUs, size) }
        return problem?.let(::DecoderException)
    }
}

@OptIn(UnstableApi::class)
private class VerbatimFlacRenderer(
    handler: Handler,
    listener: AudioRendererEventListener,
    sink: AudioSink,
) : DecoderAudioRenderer<VerbatimFlacDecoder>(handler, listener, sink) {
    override fun getName(): String = "VerbatimFlacRenderer"

    override fun supportsFormatInternal(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_FLAC) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ) = VerbatimFlacDecoder(format.sampleRate)

    override fun getOutputFormat(decoder: VerbatimFlacDecoder): Format =
        Format
            .Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(1)
            .setSampleRate(decoder.sampleRate)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
}

/** The players of the live stream: Android's decoders, plus ours first for go2rtc's FLAC. */
@OptIn(UnstableApi::class)
class LiveRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(VerbatimFlacRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
