package studio.room211.pozivi

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Tokom poziva mikrofon dobija tih zvuk, a sagovornik iz slušalice je još
 * tiši. Posle poziva se snimak pojača tako da glasni delovi budu blizu
 * punog nivoa; retki pucketi ne određuju pojačanje, a vrhovi se meko seku.
 */
object Pojacanje {
    const val NAJVISE = 24f
    private const val CILJ = 26_000f
    private const val BLOK = 64 * 1024

    /** Nivo ispod kog je 99,9 % uzoraka, iz histograma apsolutnih vrednosti. */
    internal fun vrh(histogram: IntArray): Int {
        val ukupno = histogram.fold(0L) { a, b -> a + b }
        if (ukupno == 0L) return 0
        val granica = ukupno - ukupno / 1000
        var zbir = 0L
        histogram.forEachIndexed { i, n ->
            zbir += n
            if (zbir >= granica) return i
        }
        return histogram.size - 1
    }

    internal fun faktor(vrh: Int): Float = if (vrh <= 0) 1f else (CILJ / vrh).coerceIn(1f, NAJVISE)

    /** Pojačan uzorak; iznad 80 % punog nivoa ide meko sečenje umesto krčanja. */
    internal fun primeni(uzorak: Short, faktor: Float): Short {
        val x = uzorak * faktor / 32767f
        val prag = 0.8f
        val y = if (abs(x) <= prag) x
        else Math.copySign(prag + (1 - prag) * tanh(((abs(x) - prag) / (1 - prag)).toDouble()).toFloat(), x)
        return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Upisuje WAV zaglavlje i pojačava snimak u mestu. Vraća primenjeni faktor. */
    fun obradi(wav: File, hz: Int): Float {
        RandomAccessFile(wav, "rw").use { f ->
            val podaci = (f.length() - 44).coerceAtLeast(0).toInt() and 1.inv()
            f.seek(0)
            f.write(WavPretvarac.zaglavlje(podaci, hz))
            val histogram = IntArray(32_769)
            val blok = ByteArray(BLOK)
            prodji(f, podaci, blok) { b, n ->
                for (i in 0 until n step 2) histogram[abs(uzorak(b, i).toInt())]++
                false
            }
            val k = faktor(vrh(histogram))
            if (k > 1.05f) prodji(f, podaci, blok) { b, n ->
                for (i in 0 until n step 2) {
                    val s = primeni(uzorak(b, i), k).toInt()
                    b[i] = s.toByte(); b[i + 1] = (s shr 8).toByte()
                }
                true
            }
            return k
        }
    }

    private fun uzorak(b: ByteArray, i: Int) = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort()

    private fun prodji(f: RandomAccessFile, podaci: Int, blok: ByteArray, obrada: (ByteArray, Int) -> Boolean) {
        var pos = 44L
        val kraj = 44L + podaci
        while (pos < kraj) {
            val n = minOf(blok.size.toLong(), kraj - pos).toInt()
            f.seek(pos)
            f.readFully(blok, 0, n)
            if (obrada(blok, n)) { f.seek(pos); f.write(blok, 0, n) }
            pos += n
        }
    }

    /** 16-bitni uzorci u bajtove, little-endian. */
    fun uBajtove(uzorci: ShortArray, n: Int): ByteArray =
        ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).apply { for (i in 0 until n) putShort(uzorci[i]) }.array()
}
