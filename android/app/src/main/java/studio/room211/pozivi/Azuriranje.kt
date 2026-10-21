package studio.room211.pozivi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ažuriranje iz GitHub izdanja. Svaki push na main pravi novo izdanje
 * (.github/workflows/izdanje.yml), a aplikacija ga sama nađe, preuzme i
 * instalira. Od Androida 12 aplikacija koja ažurira samu sebe ne traži
 * potvrdu; stariji sistem i prva instalacija traže jedan dodir.
 */
object Azuriranje {
    const val REPO = "milev051/snimac-poziva"
    private const val IZDANJA = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RAZMAK_PROVERE = 6L * 60 * 60 * 1000

    data class Izdanje(val oznaka: String, val naslov: String, val adresaApk: String)

    fun trenutnaVerzija(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    fun poslednje(): Izdanje {
        val veza = URL(IZDANJA).openConnection() as HttpURLConnection
        val telo = try {
            veza.connectTimeout = 12_000
            veza.readTimeout = 20_000
            veza.setRequestProperty("Accept", "application/vnd.github+json")
            veza.setRequestProperty("User-Agent", "Pozivi-Android")
            val kod = veza.responseCode
            if (kod == 404) error("Još nema objavljenog izdanja")
            if (kod !in 200..299) error("GitHub je vratio HTTP $kod")
            veza.inputStream.bufferedReader().use { it.readText() }
        } finally {
            veza.disconnect()
        }
        return izOdgovora(telo)
    }

    internal fun izOdgovora(json: String): Izdanje {
        val koren = JSONObject(json)
        val oznaka = koren.optString("tag_name").trim()
        require(oznaka.isNotBlank()) { "Izdanje nema oznaku verzije" }
        val prilozi = koren.optJSONArray("assets")
        var adresa = ""
        if (prilozi != null) for (i in 0 until prilozi.length()) {
            val p = prilozi.getJSONObject(i)
            if (p.optString("name").endsWith(".apk", ignoreCase = true)) {
                adresa = p.optString("browser_download_url")
                break
            }
        }
        require(adresa.isNotBlank()) { "Izdanje $oznaka nema APK" }
        return Izdanje(oznaka, koren.optString("name").ifBlank { oznaka }, adresa)
    }

    /** „1.9“ je starije od „1.11“, pa se poredi broj po broj. */
    internal fun novije(trenutna: String, izdanje: String): Boolean {
        val a = brojevi(trenutna)
        val b = brojevi(izdanje)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun brojevi(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '_').mapNotNull { d -> d.takeWhile { it.isDigit() }.toIntOrNull() }

    fun preuzmi(context: Context, adresa: String, napredak: (Int) -> Unit = {}): File {
        val veza = URL(adresa).openConnection() as HttpURLConnection
        try {
            veza.connectTimeout = 15_000
            veza.readTimeout = 60_000
            veza.instanceFollowRedirects = true
            veza.setRequestProperty("User-Agent", "Pozivi-Android")
            if (veza.responseCode !in 200..299) error("Preuzimanje je vratilo HTTP ${veza.responseCode}")
            val ukupno = veza.contentLength.toLong()
            val fajl = File(context.cacheDir, "azuriranje.apk")
            veza.inputStream.use { ulaz ->
                fajl.outputStream().use { izlaz ->
                    val bafer = ByteArray(64 * 1024)
                    var preneto = 0L
                    while (true) {
                        val n = ulaz.read(bafer)
                        if (n < 0) break
                        izlaz.write(bafer, 0, n)
                        preneto += n
                        if (ukupno > 0) napredak((preneto * 100 / ukupno).toInt())
                    }
                }
            }
            return fajl
        } finally {
            veza.disconnect()
        }
    }

    fun smeDaInstalira(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Instalacija kroz PackageInstaller sesiju, da bi ažuriranje moglo bez potvrde. */
    fun instaliraj(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { sesija ->
            sesija.openWrite("pozivi.apk", 0, apk.length()).use { izlaz ->
                apk.inputStream().use { it.copyTo(izlaz) }
                sesija.fsync(izlaz)
            }
            val povratak = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstalacijaPrijemnik::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            sesija.commit(povratak.intentSender)
        }
    }

    /** Iz pozadinskog posla: najviše jednom u 6 sati, sve bez dodira ako sistem dozvoli. */
    fun proveriUPozadini(context: Context) {
        val p = Podesavanja(context)
        if (!p.proveravajAzuriranje) return
        if (System.currentTimeMillis() - p.poslednjaProveraAzuriranja < RAZMAK_PROVERE) return
        val izdanje = poslednje()
        p.poslednjaProveraAzuriranja = System.currentTimeMillis()
        if (!novije(trenutnaVerzija(context), izdanje.oznaka)) return
        if (!smeDaInstalira(context)) {
            javi(context, "Nova verzija ${izdanje.oznaka}", "Otvori aplikaciju i dozvoli instaliranje ažuriranja.",
                Intent(context, MainActivity::class.java))
            return
        }
        instaliraj(context, preuzmi(context, izdanje.adresaApk))
    }

    fun javi(context: Context, naslov: String, tekst: String, akcija: Intent) {
        Obavestenja.kanali(context)
        val pi = PendingIntent.getActivity(context, 7, akcija.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, Obavestenja.KANAL_AZURIRANJE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(naslov)
            .setContentText(tekst)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(Obavestenja.ID_AZURIRANJE, n) }
    }
}

/** Odgovor sistemskog instalatera. */
class InstalacijaPrijemnik : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val potvrda = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                if (MainActivity.vidljiva) {
                    context.startActivity(potvrda.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Azuriranje.javi(context, "Ažuriranje je spremno", "Dodirni da instaliraš novu verziju.", potvrda)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val poruka = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Log.w("Azuriranje", "Instalacija nije uspela ($status): $poruka")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Azuriranje.javi(context, "Ažuriranje nije uspelo", poruka.ifBlank { "Status $status" },
                        Intent(context, MainActivity::class.java))
                }
            }
        }
    }
}

/** Posle restarta telefona i posle ažuriranja ponovo zakazuje poslove. */
class PokretanjePrijemnik : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Poslovi.zakaziOdrzavanje(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Azuriranje.javi(context, "Pozivi su ažurirani", "Verzija ${Azuriranje.trenutnaVerzija(context)}",
                Intent(context, MainActivity::class.java))
        }
    }
}
