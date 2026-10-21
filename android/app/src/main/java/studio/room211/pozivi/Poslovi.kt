package studio.room211.pozivi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Poslovi {
    private const val ODRZAVANJE = "odrzavanje"

    /** Prepis čeka internet u redu poslova, pa dugme radi i bez mreže. */
    fun prepisi(context: Context, id: String) {
        Pozivi.izmeni(context, id) { it.copy(status = Poziv.STATUS_CEKA, greska = "") }
        val mreza = if (Podesavanja(context).samoWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
        val posao = OneTimeWorkRequestBuilder<PrepisPosao>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(mreza).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("prepis-$id", ExistingWorkPolicy.REPLACE, posao)
    }

    fun otkazi(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("prepis-$id")
        Pozivi.izmeni(context, id) { if (it.status == Poziv.STATUS_GOTOVO) it else it.copy(status = Poziv.STATUS_NIJE) }
    }

    /** Svakog sata: novi sistemski snimci i provera ažuriranja. */
    fun zakaziOdrzavanje(context: Context) {
        val posao = PeriodicWorkRequestBuilder<OdrzavanjePosao>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(ODRZAVANJE, ExistingPeriodicWorkPolicy.KEEP, posao)
    }

    fun skenirajUskoro(context: Context) {
        val posao = OneTimeWorkRequestBuilder<OdrzavanjePosao>()
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setInputData(workDataOf("samoSken" to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sken", ExistingWorkPolicy.REPLACE, posao)
    }

    /** Novi sistemski snimci; samoprepis samo za skorašnje, da stari ne potroše kvotu. */
    fun skeniraj(context: Context): List<Poziv> {
        val novi = Pozivi.dodaj(context, SistemskiSnimci.pronadji(context))
        if (Podesavanja(context).automatskiPrepis) {
            val granica = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            novi.filter { it.vreme > granica }.forEach { prepisi(context, it.id) }
        }
        return novi
    }
}

class OdrzavanjePosao(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Poslovi.skeniraj(applicationContext)
        if (!inputData.getBoolean("samoSken", false)) {
            runCatching { Azuriranje.proveriUPozadini(applicationContext) }
                .onFailure { Log.w("Odrzavanje", "Ažuriranje: ${it.message}") }
        }
        return Result.success()
    }
}

class PrepisPosao(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: return Result.failure()
        val poziv = Pozivi.nadji(ctx, id) ?: return Result.failure()
        Pozivi.izmeni(ctx, id) { it.copy(status = Poziv.STATUS_RADI, greska = "") }
        val privremeni = mutableListOf<File>()
        return try {
            val (zvuk, mime) = pripremi(ctx, Uri.parse(poziv.uri), privremeni)
            val p = Podesavanja(ctx)
            val tekst = Gemini.prepisi(p.geminiKljuc, p.model, zvuk, mime)
            Pozivi.izmeni(ctx, id) { it.copy(status = Poziv.STATUS_GOTOVO, tekst = tekst, greska = "") }
            javi(ctx, poziv, "Prepis je gotov")
            Result.success()
        } catch (e: Exception) {
            val ponovi = (e is IOException || (e is Gemini.GreskaGemini && e.ponovi)) && runAttemptCount < 5
            val poruka = e.message ?: e.javaClass.simpleName
            Pozivi.izmeni(ctx, id) {
                it.copy(status = if (ponovi) Poziv.STATUS_CEKA else Poziv.STATUS_GRESKA, greska = poruka)
            }
            if (!ponovi) javi(ctx, poziv, "Prepis nije uspeo: $poruka")
            if (ponovi) Result.retry() else Result.failure()
        } finally {
            privremeni.forEach { it.delete() }
        }
    }

    private fun pripremi(ctx: Context, uri: Uri, privremeni: MutableList<File>): Pair<File, String> {
        val mime = vrsta(ctx, uri)
        if (mime in Gemini.PODRZANI) {
            if (uri.scheme == "file") return File(uri.path!!) to mime
            val kopija = File(ctx.cacheDir, "prepis-${System.nanoTime()}").also { privremeni += it }
            ctx.contentResolver.openInputStream(uri)?.use { ulaz -> kopija.outputStream().use { ulaz.copyTo(it) } }
                ?: throw IOException("Snimak više ne postoji.")
            return kopija to mime
        }
        val wav = File(ctx.cacheDir, "prepis-${System.nanoTime()}.wav").also { privremeni += it }
        return WavPretvarac.pretvori(ctx, uri, wav) to "audio/wav"
    }

    private fun vrsta(ctx: Context, uri: Uri): String {
        val tip = if (uri.scheme == "content") ctx.contentResolver.getType(uri) else null
        if (!tip.isNullOrBlank() && tip != "application/octet-stream") return tip
        return when (uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase()) {
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }
    }

    private fun javi(ctx: Context, poziv: Poziv, poruka: String) {
        val otvori = PendingIntent.getActivity(
            ctx, poziv.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_POZIV, poziv.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, Obavestenja.KANAL_PREPIS)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(poziv.naziv)
            .setContentText(poruka)
            .setContentIntent(otvori)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(poziv.id.hashCode(), n) }
    }
}
