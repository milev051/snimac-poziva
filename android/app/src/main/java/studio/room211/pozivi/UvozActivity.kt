package studio.room211.pozivi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Snimak podeljen iz druge aplikacije ide na spisak poziva. */
class UvozActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            finish()
            return
        }
        val app = applicationContext
        thread {
            val ok = runCatching { Uvoz.kopiraj(app, uri) }.isSuccess
            runOnUiThread {
                Toast.makeText(app, if (ok) "Snimak je dodat u Pozive" else "Snimak nije mogao da se uveze", Toast.LENGTH_SHORT).show()
                if (ok) startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }
}

object Uvoz {
    fun kopiraj(context: Context, uri: Uri): Poziv {
        val cr = context.contentResolver
        var ime = "snimak"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) ime = it.getString(0) ?: ime
        }
        val folder = SnimacServis.folderSnimaka(context).apply { mkdirs() }
        val bezbedno = ime.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        val cilj = File(folder, "uvoz_${System.currentTimeMillis()}_$bezbedno")
        cr.openInputStream(uri)?.use { ulaz -> cilj.outputStream().use { ulaz.copyTo(it) } }
            ?: error("Snimak nije dostupan")
        val trajanje = runCatching {
            android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(cilj.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            }
        }.getOrNull() ?: 0L
        val poziv = Poziv(
            id = "uvoz:${cilj.name}",
            uri = Uri.fromFile(cilj).toString(),
            naziv = SistemskiSnimci.naziv(ime),
            vreme = System.currentTimeMillis(),
            trajanjeMs = trajanje,
            izvor = Poziv.IZVOR_UVOZ,
        )
        Pozivi.dodaj(context, listOf(poziv))
        return poziv
    }
}
