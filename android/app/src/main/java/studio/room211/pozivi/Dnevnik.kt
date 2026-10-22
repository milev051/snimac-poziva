package studio.room211.pozivi

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kratak dnevnik snimanja koji se vidi u aplikaciji. Telefon obično nije
 * povezan sa računarom, pa je ovo jedini način da se vidi zašto poziv
 * nije snimljen.
 */
object Dnevnik {
    private const val NAJVISE = 60

    private fun fajl(context: Context) = File(context.filesDir, "dnevnik.txt")

    @Synchronized
    fun upisi(context: Context, tekst: String) {
        Log.i("Pozivi", tekst)
        val red = SimpleDateFormat("d.M. HH:mm:ss", Locale.US).format(Date()) + "  " + tekst
        val f = fajl(context)
        val redovi = (if (f.exists()) f.readLines() else emptyList()) + red
        f.writeText(redovi.takeLast(NAJVISE).joinToString("\n"))
    }

    @Synchronized
    fun procitaj(context: Context): String {
        val f = fajl(context)
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else ""
    }

    @Synchronized
    fun obrisi(context: Context) {
        fajl(context).delete()
    }
}
