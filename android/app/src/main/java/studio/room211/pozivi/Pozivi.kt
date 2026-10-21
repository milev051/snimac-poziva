package studio.room211.pozivi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Jedan snimljen poziv i njegov prepis. */
data class Poziv(
    val id: String,
    val uri: String,
    val naziv: String,
    val vreme: Long,
    val trajanjeMs: Long,
    val izvor: String,
    val status: String = STATUS_NIJE,
    val tekst: String = "",
    val greska: String = "",
) {
    fun uJson() = JSONObject()
        .put("id", id).put("uri", uri).put("naziv", naziv).put("vreme", vreme)
        .put("trajanjeMs", trajanjeMs).put("izvor", izvor).put("status", status)
        .put("tekst", tekst).put("greska", greska)

    companion object {
        const val IZVOR_SISTEM = "sistem"
        const val IZVOR_APLIKACIJA = "aplikacija"
        const val IZVOR_UVOZ = "uvoz"

        const val STATUS_NIJE = "nije"
        const val STATUS_CEKA = "ceka"
        const val STATUS_RADI = "radi"
        const val STATUS_GOTOVO = "gotovo"
        const val STATUS_GRESKA = "greska"

        fun izJsona(o: JSONObject) = Poziv(
            id = o.getString("id"),
            uri = o.optString("uri"),
            naziv = o.optString("naziv"),
            vreme = o.optLong("vreme"),
            trajanjeMs = o.optLong("trajanjeMs"),
            izvor = o.optString("izvor", IZVOR_SISTEM),
            status = o.optString("status", STATUS_NIJE),
            tekst = o.optString("tekst"),
            greska = o.optString("greska"),
        )
    }
}

/**
 * Spisak poziva u jednom JSON fajlu. Poziva ima stotinak, pa baza ne treba;
 * pristup je zaključan jer pišu i ekran i pozadinski poslovi.
 */
object Pozivi {
    private val brava = Any()
    private val slusaoci = mutableSetOf<() -> Unit>()

    private fun fajl(context: Context) = File(context.applicationContext.filesDir, "pozivi.json")

    fun svi(context: Context): List<Poziv> = synchronized(brava) { procitaj(context) }
        .sortedByDescending { it.vreme }

    fun nadji(context: Context, id: String): Poziv? = svi(context).firstOrNull { it.id == id }

    /** Dodaje nove pozive; postojećima ne dira status ni prepis. */
    fun dodaj(context: Context, novi: List<Poziv>): List<Poziv> {
        val dodati = synchronized(brava) {
            val svi = procitaj(context).associateBy { it.id }.toMutableMap()
            val dodati = novi.filter { it.id !in svi }
            dodati.forEach { svi[it.id] = it }
            if (dodati.isNotEmpty()) upisi(context, svi.values.toList())
            dodati
        }
        if (dodati.isNotEmpty()) javi()
        return dodati
    }

    fun izmeni(context: Context, id: String, promena: (Poziv) -> Poziv) {
        synchronized(brava) {
            val svi = procitaj(context).map { if (it.id == id) promena(it) else it }
            upisi(context, svi)
        }
        javi()
    }

    fun ukloni(context: Context, id: String) {
        synchronized(brava) { upisi(context, procitaj(context).filterNot { it.id == id }) }
        javi()
    }

    fun slusaj(slusalac: () -> Unit) = synchronized(slusaoci) { slusaoci += slusalac }
    fun prestani(slusalac: () -> Unit) = synchronized(slusaoci) { slusaoci -= slusalac }
    private fun javi() = synchronized(slusaoci) { slusaoci.toList() }.forEach { it() }

    private fun procitaj(context: Context): List<Poziv> {
        val f = fajl(context)
        if (!f.exists()) return emptyList()
        return runCatching { izTeksta(f.readText()) }.getOrDefault(emptyList())
    }

    private fun upisi(context: Context, pozivi: List<Poziv>) {
        val f = fajl(context)
        val privremen = File(f.parentFile, f.name + ".tmp")
        privremen.writeText(uTekst(pozivi))
        privremen.renameTo(f)
    }

    internal fun izTeksta(tekst: String): List<Poziv> {
        val niz = JSONArray(tekst)
        return (0 until niz.length()).map { Poziv.izJsona(niz.getJSONObject(it)) }
    }

    internal fun uTekst(pozivi: List<Poziv>): String =
        JSONArray().apply { pozivi.forEach { put(it.uJson()) } }.toString()
}
