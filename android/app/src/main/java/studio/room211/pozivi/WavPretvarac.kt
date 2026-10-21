package studio.room211.pozivi

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemini ne prima m4a i amr, a sistemski snimači prave baš njih. Zato se
 * takav snimak dekodira u WAV, 16 kHz mono, i to tokom čitanja, bez držanja
 * celog poziva u memoriji.
 */
object WavPretvarac {
    private const val CILJ_HZ = 16_000
    private const val CEKANJE_US = 10_000L

    fun pretvori(context: Context, uri: Uri, izlaz: File): File {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        RandomAccessFile(izlaz, "rw").use { wav ->
            wav.setLength(0)
            wav.write(ByteArray(44))
            try {
                extractor.setDataSource(context, uri, emptyMap())
                val traka = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("Snimak nema zvuk.")
                val format = extractor.getTrackFormat(traka)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var hz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var kanali = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var kodiranje = AudioFormat.ENCODING_PCM_16BIT
                extractor.selectTrack(traka)
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(format, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                val prevodilac = Uzorkovanje(CILJ_HZ)
                var ulazGotov = false
                var kraj = false
                while (!kraj) {
                    if (!ulazGotov) {
                        val i = c.dequeueInputBuffer(CEKANJE_US)
                        if (i >= 0) {
                            val buf = c.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                ulazGotov = true
                            } else {
                                c.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, CEKANJE_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            hz = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            kanali = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) kodiranje = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        o >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buf = c.getOutputBuffer(o)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val mono = uMono(buf.order(ByteOrder.LITTLE_ENDIAN), kanali, kodiranje)
                                wav.write(prevodilac.dodaj(mono, hz))
                            }
                            kraj = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            c.releaseOutputBuffer(o, false)
                        }
                    }
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
            val podaci = (wav.length() - 44).toInt()
            wav.seek(0)
            wav.write(zaglavlje(podaci, CILJ_HZ))
        }
        return izlaz
    }

    private fun uMono(buf: ByteBuffer, kanali: Int, kodiranje: Int): ShortArray {
        val float = kodiranje == AudioFormat.ENCODING_PCM_FLOAT
        val uzoraka = buf.remaining() / if (float) 4 else 2
        val okvira = uzoraka / kanali.coerceAtLeast(1)
        return ShortArray(okvira) {
            var zbir = 0
            repeat(kanali) {
                zbir += if (float) (buf.getFloat().coerceIn(-1f, 1f) * 32767f).toInt() else buf.getShort().toInt()
            }
            (zbir / kanali).toShort()
        }
    }

    /** Prosto preuzorkovanje koje pamti položaj između bafera dekodera. */
    internal class Uzorkovanje(private val cilj: Int) {
        private var polozaj = 0.0
        fun dodaj(ulaz: ShortArray, izvor: Int): ByteArray {
            val korak = izvor.toDouble() / cilj
            val izlaz = ByteBuffer.allocate((ulaz.size / korak + 2).toInt() * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (polozaj < ulaz.size) {
                izlaz.putShort(ulaz[polozaj.toInt()])
                polozaj += korak
            }
            polozaj -= ulaz.size
            return izlaz.array().copyOf(izlaz.position())
        }
    }

    internal fun zaglavlje(bajtova: Int, hz: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + bajtova); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(hz); putInt(hz * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bajtova)
    }.array()
}
