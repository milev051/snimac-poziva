package studio.room211.pozivi

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
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
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Snima pozive. Android od verzije 10 ne daje mikrofon aplikaciji van sistema
 * dok poziv traje, osim servisu pristupačnosti, pa snimanje živi ovde.
 * Događaje sa ekrana ne čita.
 */
class SnimacServis : AccessibilityService() {

    private val glavna = Handler(Looper.getMainLooper())
    private var telefon: TelephonyManager? = null
    private var povratni: Any? = null
    private var snimac: AudioRecord? = null
    private var nit: Thread? = null
    @Volatile private var radi = false
    private var fajl: File? = null
    private var pocetak = 0L
    private var izvorIndeks = 0
    @Volatile private var najjace = 0
    private var merenja = 0
    private var izvori = MIKROFONI

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
                val nacin = Podesavanja(this).nacin
                if (nacin == Podesavanja.NACIN_TELEFON) {
                    Dnevnik.upisi(this, "Poziv počeo; snima ugrađeni snimač telefona")
                    return
                }
                izvori = if (nacin == Podesavanja.NACIN_MIKROFON) MIKROFONI else INTERNI + MIKROFONI
                Dnevnik.upisi(this, "Poziv počeo")
                // Obaveštenje odmah drži proces živim, pre nego što izvor proradi.
                prikaziObavestenje()
                pocni(0)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (pocetak != 0L) Dnevnik.upisi(this, "Poziv završen")
                zavrsi()
                pocetak = 0L
                // Sistemski snimač upisuje fajl par sekundi posle kraja poziva.
                Poslovi.skenirajUskoro(this)
            }
        }
    }

    /** Pokreće snimač sa prvim izvorom zvuka koji radi, počevši od izvora [od]. */
    private fun pocni(od: Int) {
        if (snimac != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Dnevnik.upisi(this, "Nema dozvole za mikrofon, poziv se ne snima")
            return
        }
        val folder = folderSnimaka(this).apply { mkdirs() }
        val bafer = maxOf(AudioRecord.getMinBufferSize(HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), HZ) * 2
        for (i in od until izvori.size) {
            val (izvor, naziv) = izvori[i]
            val izlaz = File(folder, "poziv_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(pocetak)) + "_$i.wav")
            var r: AudioRecord? = null
            try {
                r = AudioRecord(izvor, HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bafer)
                check(r.state == AudioRecord.STATE_INITIALIZED) { "nije inicijalizovan" }
                // Sistemsko izjednačavanje glasnoće, gde ga telefon ima.
                if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(r.audioSessionId)?.enabled = true }
                r.startRecording()
                check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "odbijen" }
                snimac = r
                fajl = izlaz
                izvorIndeks = i
                najjace = 0
                merenja = 0
                radi = true
                nit = Thread({ upisuj(r, izlaz) }, "snimanje").apply { start() }
                Dnevnik.upisi(this, "Snimanje počelo: $naziv")
                glavna.postDelayed(meri, 500)
                return
            } catch (e: Exception) {
                Dnevnik.upisi(this, "$naziv nije dostupan: ${e.message ?: e.javaClass.simpleName}")
                runCatching { r?.release() }
                izlaz.delete()
            }
        }
        Dnevnik.upisi(this, "Nijedan izvor zvuka nije pokrenut, poziv se ne snima")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /** Sirov zvuk ide u fajl iza praznog WAV zaglavlja; zaglavlje se upiše na kraju. */
    private fun upisuj(r: AudioRecord, izlaz: File) {
        val uzorci = ShortArray(HZ / 10)
        izlaz.outputStream().buffered(64 * 1024).use { out ->
            out.write(ByteArray(44))
            while (radi) {
                val n = r.read(uzorci, 0, uzorci.size)
                if (n <= 0) { if (n < 0) break else continue }
                var vrh = 0
                for (k in 0 until n) vrh = maxOf(vrh, abs(uzorci[k].toInt()))
                if (vrh > najjace) najjace = vrh
                out.write(Pojacanje.uBajtove(uzorci, n))
            }
        }
    }

    /** Zaustavlja nit i snimač; fajl ostaje na disku. */
    private fun zaustavi(): Boolean {
        glavna.removeCallbacks(meri)
        val r = snimac ?: return false
        snimac = null
        radi = false
        runCatching { r.stop() }
        runCatching { nit?.join(2_000) }
        nit = null
        runCatching { r.release() }
        return true
    }

    /**
     * Android tokom poziva često daje aplikaciji samo nule umesto zvuka.
     * Ako izvor posle 3 sekunde nije dao ni šum, probava se sledeći.
     */
    private val meri: Runnable = object : Runnable {
        override fun run() {
            if (snimac == null) return
            merenja++
            if (merenja == 6 && najjace == 0) {
                val naziv = izvori[izvorIndeks].second
                if (izvorIndeks + 1 < izvori.size) {
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
        zaustavi()
        fajl?.delete()
        fajl = null
    }

    private fun zavrsi() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (!zaustavi()) return
        val f = fajl ?: return
        fajl = null
        if (f.length() < 16_000) {
            Dnevnik.upisi(this, "Snimak je prazan (${f.length()} B), obrisan")
            f.delete()
            return
        }
        val trajanje = System.currentTimeMillis() - pocetak
        val vreme = pocetak
        val vrh = najjace
        thread(name = "pojacanje") {
            val k = runCatching { Pojacanje.obradi(f, HZ) }
                .onFailure { Dnevnik.upisi(this, "Pojačanje nije uspelo: ${it.message}") }
                .getOrDefault(1f)
            Dnevnik.upisi(this, "Snimak sačuvan: ${f.length() / 1024} KB, najjači zvuk $vrh" +
                (if (vrh == 0) " (tišina)" else "") + ", pojačano ${"%.1f".format(Locale.US, k)}×")
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
         * Zvuk poziva iz telefona, oba smera pa samo sagovornik. Android ga
         * zvanično daje samo sistemskim aplikacijama; neki telefoni ga ipak
         * puste servisu pristupačnosti, a drugi „uspeju“ i daju tišinu, koju
         * merenje jačine prepozna i pređe na mikrofon.
         */
        private val INTERNI = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "Zvuk poziva (oba smera)",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "Zvuk poziva (sagovornik)",
        )

        /** VOICE_COMMUNICATION je poslednji jer poništavanje eha briše sagovornika sa zvučnika. */
        private val MIKROFONI = listOf(
            MediaRecorder.AudioSource.MIC to "Mikrofon",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Mikrofon bez obrade",
            MediaRecorder.AudioSource.CAMCORDER to "Mikrofon kamere",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Mikrofon za razgovor",
        )
        private const val HZ = 16_000
        fun folderSnimaka(context: android.content.Context) =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "snimci")
    }
}
