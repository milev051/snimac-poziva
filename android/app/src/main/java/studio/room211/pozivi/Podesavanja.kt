package studio.room211.pozivi

import android.content.Context
import android.media.MediaRecorder

/** Podešavanja ostaju u SharedPreferences i preživljavaju ažuriranje. */
class Podesavanja(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("podesavanja", Context.MODE_PRIVATE)

    var geminiKljuc: String
        get() = p.getString("gemini_kljuc", "").orEmpty()
        set(v) = p.edit().putString("gemini_kljuc", v.trim()).apply()

    var model: String
        get() = p.getString("model", "").orEmpty().ifBlank { Gemini.PODRAZUMEVANI_MODEL }
        set(v) = p.edit().putString("model", v.trim()).apply()

    /** Sopstveno snimanje preko servisa pristupačnosti. */
    var snimajSam: Boolean
        get() = p.getBoolean("snimaj_sam", true)
        set(v) = p.edit().putBoolean("snimaj_sam", v).apply()

    var izvorZvuka: String
        get() = p.getString("izvor_zvuka", IZVOR_MIKROFON).orEmpty()
        set(v) = p.edit().putString("izvor_zvuka", v).apply()

    /** Nov snimak se prepisuje sam čim ima interneta; inače samo na dugme. */
    var automatskiPrepis: Boolean
        get() = p.getBoolean("automatski_prepis", false)
        set(v) = p.edit().putBoolean("automatski_prepis", v).apply()

    var samoWifi: Boolean
        get() = p.getBoolean("samo_wifi", false)
        set(v) = p.edit().putBoolean("samo_wifi", v).apply()

    var proveravajAzuriranje: Boolean
        get() = p.getBoolean("proveravaj_azuriranje", true)
        set(v) = p.edit().putBoolean("proveravaj_azuriranje", v).apply()

    var poslednjaProveraAzuriranja: Long
        get() = p.getLong("poslednja_provera", 0L)
        set(v) = p.edit().putLong("poslednja_provera", v).apply()

    companion object {
        const val IZVOR_MIKROFON = "mic"
        const val IZVOR_PREPOZNAVANJE = "prepoznavanje"
        const val IZVOR_POZIV = "poziv"

        /**
         * VOICE_CALL bi dao oba smera, ali Android ga daje samo sistemskim
         * aplikacijama; ako ne uspe, snimanje prelazi na mikrofon.
         * VOICE_COMMUNICATION se namerno ne nudi: poništavanje eha briše
         * sagovornika sa zvučnika.
         */
        fun audioIzvori(izbor: String): List<Int> = when (izbor) {
            IZVOR_POZIV -> listOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.MIC)
            IZVOR_PREPOZNAVANJE -> listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            else -> listOf(MediaRecorder.AudioSource.MIC)
        }
    }
}
