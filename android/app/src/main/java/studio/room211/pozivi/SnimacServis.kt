package studio.room211.pozivi

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snima pozive. Android od verzije 10 ne daje mikrofon aplikaciji van sistema
 * dok poziv traje, osim servisu pristupačnosti, pa snimanje živi ovde.
 * Događaje sa ekrana ne čita.
 */
class SnimacServis : AccessibilityService() {

    private val glavna = Handler(Looper.getMainLooper())
    private var telefon: TelephonyManager? = null
    private var povratni: Any? = null
    private var snimac: MediaRecorder? = null
    private var fajl: File? = null
    private var pocetak = 0L
    private var izvorIndeks = 0
    private var najjace = 0
    private var merenja = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Dnevnik.upisi(this, "Servis pristupačnosti je pokrenut")
        Obavestenja.kanali(this)
        pratiPozive()
        Poslovi.zakaziOdrzavanje(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Dnevnik.upisi(this, "Servis pristupačnosti je zaustavljen")
        glavna.removeCallbacks(pokreni)
        zavrsi()
        prestaniPracenje()
        super.onDestroy()
    }

    private fun pratiPozive() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Dnevnik.upisi(this, "Nema dozvole za stanje telefona, pozivi se ne prate")
            return
        }
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        telefon = tm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = promena(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            povratni = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = promena(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            povratni = l
        }
    }

    private fun prestaniPracenje() {
        val tm = telefon ?: return
        val p = povratni
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (p is TelephonyCallback) tm.unregisterTelephonyCallback(p)
        } else if (p is PhoneStateListener) {
            @Suppress("DEPRECATION")
            tm.listen(p, PhoneStateListener.LISTEN_NONE)
        }
        povratni = null
    }

    private fun promena(stanje: Int) {
        when (stanje) {
            TelephonyManager.CALL_STATE_RINGING -> Dnevnik.upisi(this, "Poziv zvoni")
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (pocetak != 0L) return
                pocetak = System.currentTimeMillis()
                if (!Podesavanja(this).snimajSam) {
                    Dnevnik.upisi(this, "Poziv počeo; snimanje u aplikaciji je isključeno")
                    return
                }
                Dnevnik.upisi(this, "Poziv počeo")
                // Telefon prvo prebaci zvuk na slušalicu; snimač pokrenut odmah
                // na nekim Samsungovim modelima dobija samo tišinu.
                glavna.postDelayed(pokreni, 1_000)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                glavna.removeCallbacks(pokreni)
                if (pocetak != 0L) Dnevnik.upisi(this, "Poziv završen")
                zavrsi()
                pocetak = 0L
                // Sistemski snimač upisuje fajl par sekundi posle kraja poziva.
                Poslovi.skenirajUskoro(this)
            }
        }
    }

    private val pokreni = Runnable { pocni(0) }

    /** Pokreće snimač sa prvim izvorom zvuka koji radi, počevši od izvora [od]. */
    private fun pocni(od: Int) {
        if (snimac != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Dnevnik.upisi(this, "Nema dozvole za mikrofon, poziv se ne snima")
            return
        }
        val folder = folderSnimaka(this).apply { mkdirs() }
        for (i in od until IZVORI.size) {
            val (izvor, naziv) = IZVORI[i]
            val izlaz = File(folder, "poziv_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(pocetak)) + "_$i.aac")
            val r = MediaRecorder(this)
            try {
                r.setAudioSource(izvor)
                r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000)
                r.setAudioEncodingBitRate(32_000)
                r.setOutputFile(izlaz.absolutePath)
                r.prepare()
                r.start()
                r.maxAmplitude // prvo čitanje samo nulira merenje
                snimac = r
                fajl = izlaz
                izvorIndeks = i
                najjace = 0
                merenja = 0
                prikaziObavestenje()
                Dnevnik.upisi(this, "Snimanje počelo: $naziv")
                glavna.postDelayed(meri, 500)
                return
            } catch (e: Exception) {
                Dnevnik.upisi(this, "$naziv nije dostupan: ${e.message ?: e.javaClass.simpleName}")
                runCatching { r.release() }
                izlaz.delete()
            }
        }
        Dnevnik.upisi(this, "Nijedan izvor zvuka nije pokrenut, poziv se ne snima")
    }

    /**
     * Android tokom poziva često daje aplikaciji samo nule umesto zvuka.
     * Ako izvor posle 4 sekunde nije dao ni šum, probava se sledeći.
     */
    private val meri: Runnable = object : Runnable {
        override fun run() {
            val r = snimac ?: return
            najjace = maxOf(najjace, runCatching { r.maxAmplitude }.getOrDefault(0))
            merenja++
            if (merenja == 8 && najjace == 0) {
                val naziv = IZVORI[izvorIndeks].second
                if (izvorIndeks + 1 < IZVORI.size) {
                    Dnevnik.upisi(this@SnimacServis, "$naziv daje samo tišinu, probam sledeći izvor")
                    val sledeci = izvorIndeks + 1
                    odbaci()
                    pocni(sledeci)
                    return
                }
                Dnevnik.upisi(this@SnimacServis, "Svi izvori daju tišinu: telefon ne pušta mikrofon aplikacijama tokom poziva")
            }
            glavna.postDelayed(this, 500)
        }
    }

    /** Zaustavlja snimač i briše fajl, bez upisa u spisak poziva. */
    private fun odbaci() {
        glavna.removeCallbacks(meri)
        val r = snimac ?: return
        snimac = null
        runCatching { r.stop() }
        runCatching { r.release() }
        fajl?.delete()
        fajl = null
    }

    private fun zavrsi() {
        glavna.removeCallbacks(meri)
        val r = snimac ?: return
        snimac = null
        val f = fajl
        fajl = null
        val ispravno = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (f == null) return
        if (!ispravno || f.length() < 2_000) {
            Dnevnik.upisi(this, "Snimak je prazan (${f.length()} B), obrisan")
            f.delete()
            return
        }
        Dnevnik.upisi(this, "Snimak sačuvan: ${f.length() / 1024} KB, najjači zvuk $najjace" +
            if (najjace == 0) " (tišina)" else "")
        val trajanje = System.currentTimeMillis() - pocetak
        val vreme = pocetak
        // Dnevnik poziva dobije novi red tek posle kraja poziva.
        glavna.postDelayed({
            val poziv = Poziv(
                id = "app:${f.name}",
                uri = android.net.Uri.fromFile(f).toString(),
                naziv = sagovornik(vreme) ?: "Poziv",
                vreme = vreme,
                trajanjeMs = trajanje,
                izvor = Poziv.IZVOR_APLIKACIJA,
            )
            Pozivi.dodaj(this, listOf(poziv))
        }, 2_500)
    }

    /** Ime ili broj iz dnevnika poziva, ako je dozvola data. */
    private fun sagovornik(okoVremena: Long): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf((okoVremena - 120_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) null
                else c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)
            }
        }.getOrNull()
    }

    private fun prikaziObavestenje() {
        val n = Notification.Builder(this, Obavestenja.KANAL_SNIMANJE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Snima se poziv")
            .setOngoing(true)
            .build()
        // Ne mora da uspe: snimanje radi i bez obaveštenja, ali ono drži proces živim.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Obavestenja.ID_SNIMANJE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Obavestenja.ID_SNIMANJE, n)
            }
        }.onFailure { Dnevnik.upisi(this, "Obaveštenje o snimanju nije prikazano: ${it.message}") }
    }

    companion object {
        /**
         * Redosled izvora: VOICE_CALL se ne nudi jer ga Android daje samo
         * sistemskim aplikacijama, a na nekim telefonima „uspe“ i snima tišinu.
         * VOICE_COMMUNICATION je poslednji jer poništavanje eha briše
         * sagovornika sa zvučnika.
         */
        private val IZVORI = listOf(
            MediaRecorder.AudioSource.MIC to "Mikrofon",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Mikrofon bez obrade",
            MediaRecorder.AudioSource.CAMCORDER to "Mikrofon kamere",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Mikrofon za razgovor",
        )
        fun folderSnimaka(context: android.content.Context) =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "snimci")
    }
}
