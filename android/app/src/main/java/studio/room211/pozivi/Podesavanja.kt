package studio.room211.pozivi

import android.content.Context

/** Podešavanja ostaju u SharedPreferences i preživljavaju ažuriranje. */
class Podesavanja(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("podesavanja", Context.MODE_PRIVATE)

    var geminiKljuc: String
        get() = p.getString("gemini_kljuc", "").orEmpty()
        set(v) = p.edit().putString("gemini_kljuc", v.trim()).apply()

    /** Sopstveno snimanje preko servisa pristupačnosti. */
    var snimajSam: Boolean
        get() = p.getBoolean("snimaj_sam", true)
        set(v) = p.edit().putBoolean("snimaj_sam", v).apply()
}
