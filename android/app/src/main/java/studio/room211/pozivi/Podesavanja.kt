package studio.room211.pozivi

import android.content.Context

/** Podešavanja ostaju u SharedPreferences i preživljavaju ažuriranje. */
class Podesavanja(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("podesavanja", Context.MODE_PRIVATE)

    var geminiKljuc: String
        get() = p.getString("gemini_kljuc", "").orEmpty()
        set(v) = p.edit().putString("gemini_kljuc", v.trim()).apply()

    /**
     * Odakle aplikacija uzima zvuk: [NACIN_INTERNO], [NACIN_MIKROFON] ili
     * [NACIN_TELEFON]. Stari prekidač „Snimaj sam“ isključen znači [NACIN_TELEFON].
     */
    var nacin: String
        get() = p.getString("nacin_snimanja", null)
            ?: if (p.getBoolean("snimaj_sam", true)) NACIN_INTERNO else NACIN_TELEFON
        set(v) = p.edit().putString("nacin_snimanja", v).apply()

    /** Sopstveno snimanje preko servisa pristupačnosti. */
    val snimajSam: Boolean get() = nacin != NACIN_TELEFON

    companion object {
        /** Zvuk poziva iz telefona (oba smera), a mikrofon ako telefon to ne pusti. */
        const val NACIN_INTERNO = "interno"
        const val NACIN_MIKROFON = "mikrofon"
        /** Snima samo ugrađeni snimač telefona; aplikacija pronalazi njegove snimke. */
        const val NACIN_TELEFON = "telefon"
    }
}
