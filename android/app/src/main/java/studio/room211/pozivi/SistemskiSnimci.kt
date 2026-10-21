package studio.room211.pozivi

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * Snimci koje pravi telefonska aplikacija (Samsung „Snimanje poziva“ i slične).
 * Taj snimač dobija zvuk oba smera, što aplikacija van sistema ne može,
 * pa je ovo najpouzdaniji izvor kad ga telefon ima.
 */
object SistemskiSnimci {

    private val FOLDERI = listOf("Recordings/Call/%", "Call/%", "%/Call recordings/%", "Recordings/Call recordings/%")

    fun pronadji(context: Context): List<Poziv> {
        val kolone = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        val uslov = FOLDERI.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" }
        val izvor = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val nadjeni = mutableListOf<Poziv>()
        runCatching {
            context.contentResolver.query(izvor, kolone, uslov, FOLDERI.toTypedArray(), null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val ime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val datum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val trajanje = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val mediaId = c.getLong(id)
                    nadjeni += Poziv(
                        id = "sistem:$mediaId",
                        uri = ContentUris.withAppendedId(izvor, mediaId).toString(),
                        naziv = naziv(c.getString(ime).orEmpty()),
                        vreme = c.getLong(datum) * 1000,
                        trajanjeMs = c.getLong(trajanje),
                        izvor = Poziv.IZVOR_SISTEM,
                    )
                }
            }
        }
        return nadjeni
    }

    private val PREFIKSI = listOf(
        "call recording", "snimak poziva", "snimanje poziva", "снимак позива", "aufnahme", "zapis poziva",
    )
    private val DATUM_NA_KRAJU = Regex("[_ -]+\\d{6,8}[_ -]+\\d{4,6}$")

    /** „Call recording Marko_241021_101500.m4a“ → „Marko“. */
    internal fun naziv(imeFajla: String): String {
        var s = imeFajla.substringBeforeLast('.')
        PREFIKSI.firstOrNull { s.lowercase().startsWith(it) }?.let { s = s.substring(it.length) }
        s = s.replace(DATUM_NA_KRAJU, "").trim(' ', '_', '-')
        return s.ifBlank { imeFajla.substringBeforeLast('.') }
    }
}
