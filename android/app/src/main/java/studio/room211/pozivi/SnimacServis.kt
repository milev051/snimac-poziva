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
import android.util.Log
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Obavestenja.kanali(this)
        pratiPozive()
        Poslovi.zakaziOdrzavanje(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        zavrsi()
        prestaniPracenje()
        super.onDestroy()
    }

    private fun pratiPozive() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Nema dozvole za stanje poziva")
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
            TelephonyManager.CALL_STATE_OFFHOOK -> if (Podesavanja(this).snimajSam) pocni()
            TelephonyManager.CALL_STATE_IDLE -> {
                zavrsi()
                // Sistemski snimač upisuje fajl par sekundi posle kraja poziva.
                Poslovi.skenirajUskoro(this)
            }
        }
    }

    private fun pocni() {
        if (snimac != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val folder = folderSnimaka(this).apply { mkdirs() }
        val ime = "poziv_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".aac"
        val izlaz = File(folder, ime)
        for (izvor in Podesavanja.audioIzvori(Podesavanja(this).izvorZvuka)) {
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
                snimac = r
                fajl = izlaz
                pocetak = System.currentTimeMillis()
                prikaziObavestenje()
                Log.i(TAG, "Snimanje počelo, izvor $izvor")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Izvor $izvor nije dostupan: ${e.message}")
                runCatching { r.release() }
                izlaz.delete()
            }
        }
    }

    private fun zavrsi() {
        val r = snimac ?: return
        snimac = null
        val f = fajl
        fajl = null
        val ispravno = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (f == null) return
        if (!ispravno || f.length() < 2_000) {
            f.delete()
            return
        }
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
            if (Podesavanja(this).automatskiPrepis) Poslovi.prepisi(this, poziv.id)
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
        }.onFailure { Log.w(TAG, "Obaveštenje o snimanju: ${it.message}") }
    }

    companion object {
        private const val TAG = "SnimacServis"
        fun folderSnimaka(context: android.content.Context) =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "snimci")
    }
}
