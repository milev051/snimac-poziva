package studio.room211.pozivi

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prepis celog snimka jednim pozivom Gemini API-ja. Model sam razdvaja
 * govornike po glasu, jer telefon ne daje dva smera kao zasebne trake.
 */
object Gemini {
    const val PODRAZUMEVANI_MODEL = "gemini-flash-latest"
    private const val OSNOVA = "https://generativelanguage.googleapis.com"

    /** Ispod ove veličine zvuk ide u sam zahtev; veći ide preko Files API-ja. */
    private const val NAJVISE_U_ZAHTEVU = 14L * 1024 * 1024

    /** Formati koje Gemini prima; ostalo se pre slanja pretvara u WAV. */
    val PODRZANI = setOf("audio/wav", "audio/x-wav", "audio/mp3", "audio/mpeg", "audio/aac", "audio/ogg", "audio/flac", "audio/aiff")

    val UPUTSTVO = """
        Ovo je snimak telefonskog razgovora. Prepiši ga doslovno, na jeziku na kome se govori; srpski piši latinicom.
        Označi govornike: „Ja:“ je vlasnik telefona, glas koji je najbliži i najjasniji. „Sagovornik:“ je druga strana; ako ih ima više, „Sagovornik 1:“, „Sagovornik 2:“.
        Svaka promena govornika ide u nov red i počinje vremenom [mm:ss].
        Ne dodaji sažetak, komentar ni uvod. Nerazumljiv deo označi sa [nerazumljivo]. Ako na snimku nema govora, vrati samo: (nema govora)
    """.trimIndent()

    class GreskaGemini(poruka: String, val ponovi: Boolean) : Exception(poruka)

    fun prepisi(kljuc: String, model: String, zvuk: File, mime: String): String {
        if (kljuc.isBlank()) throw GreskaGemini("Upiši Gemini API ključ u podešavanjima.", ponovi = false)
        val deo = if (zvuk.length() <= NAJVISE_U_ZAHTEVU) {
            JSONObject().put("inline_data", JSONObject()
                .put("mime_type", mime)
                .put("data", Base64.encodeToString(zvuk.readBytes(), Base64.NO_WRAP)))
        } else {
            val uri = posalji(kljuc, zvuk, mime)
            JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", uri))
        }
        val telo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", UPUTSTVO))
                .put(deo))))
            .put("generationConfig", JSONObject().put("temperature", 0))
        val odgovor = zahtev("$OSNOVA/v1beta/models/$model:generateContent", kljuc, "POST", telo.toString().toByteArray())
        return tekstIzOdgovora(odgovor)
    }

    internal fun tekstIzOdgovora(json: String): String {
        val koren = JSONObject(json)
        val kandidat = koren.optJSONArray("candidates")?.optJSONObject(0)
        if (kandidat == null) {
            val razlog = koren.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw GreskaGemini("Gemini nije vratio prepis${if (razlog.isNotBlank()) " ($razlog)" else ""}.", ponovi = false)
        }
        val delovi = kandidat.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val tekst = (0 until delovi.length()).joinToString("") { delovi.getJSONObject(it).optString("text") }.trim()
        if (tekst.isBlank()) throw GreskaGemini("Prepis je prazan (${kandidat.optString("finishReason")}).", ponovi = true)
        return tekst
    }

    /** Files API, jednokratno otpremanje; vraća URI fajla kad postane aktivan. */
    private fun posalji(kljuc: String, zvuk: File, mime: String): String {
        val start = URL("$OSNOVA/upload/v1beta/files").openConnection() as HttpURLConnection
        val adresa = try {
            start.requestMethod = "POST"
            start.doOutput = true
            start.connectTimeout = 20_000
            start.readTimeout = 60_000
            start.setRequestProperty("x-goog-api-key", kljuc)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", zvuk.length().toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            start.setRequestProperty("Content-Type", "application/json")
            start.outputStream.use { it.write("""{"file":{"display_name":"poziv"}}""".toByteArray()) }
            proveri(start)
            start.getHeaderField("x-goog-upload-url") ?: throw GreskaGemini("Gemini nije dao adresu za otpremanje.", ponovi = true)
        } finally {
            start.disconnect()
        }
        val up = URL(adresa).openConnection() as HttpURLConnection
        val fajl = try {
            up.requestMethod = "POST"
            up.doOutput = true
            up.setFixedLengthStreamingMode(zvuk.length())
            up.connectTimeout = 20_000
            up.readTimeout = 300_000
            up.setRequestProperty("X-Goog-Upload-Offset", "0")
            up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            up.outputStream.use { izlaz -> zvuk.inputStream().use { it.copyTo(izlaz, 64 * 1024) } }
            JSONObject(proveri(up)).getJSONObject("file")
        } finally {
            up.disconnect()
        }
        var stanje = fajl
        for (pokusaj in 1..30) {
            if (stanje.optString("state") != "PROCESSING") break
            Thread.sleep(2_000)
            stanje = JSONObject(zahtev("$OSNOVA/v1beta/${fajl.getString("name")}", kljuc, "GET", null))
        }
        if (stanje.optString("state") == "FAILED") throw GreskaGemini("Gemini nije mogao da obradi snimak.", ponovi = false)
        return stanje.getString("uri")
    }

    private fun zahtev(adresa: String, kljuc: String, metod: String, telo: ByteArray?): String {
        val veza = URL(adresa).openConnection() as HttpURLConnection
        try {
            veza.requestMethod = metod
            veza.connectTimeout = 20_000
            veza.readTimeout = 300_000
            veza.setRequestProperty("x-goog-api-key", kljuc)
            if (telo != null) {
                veza.doOutput = true
                veza.setRequestProperty("Content-Type", "application/json")
                veza.setFixedLengthStreamingMode(telo.size)
                veza.outputStream.use { it.write(telo) }
            }
            return proveri(veza)
        } finally {
            veza.disconnect()
        }
    }

    private fun proveri(veza: HttpURLConnection): String {
        val kod = veza.responseCode
        if (kod in 200..299) return veza.inputStream.bufferedReader().use { it.readText() }
        val telo = veza.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        throw GreskaGemini(porukaGreske(kod, telo), ponovi = kod == 429 || kod >= 500)
    }

    internal fun porukaGreske(kod: Int, telo: String): String {
        val poruka = runCatching { JSONObject(telo).getJSONObject("error").getString("message") }.getOrDefault("")
        return when (kod) {
            400 -> "Gemini je odbio zahtev: ${poruka.ifBlank { "HTTP 400" }}"
            401, 403 -> "Gemini ključ nije ispravan ili nema dozvolu."
            404 -> "Model nije pronađen. Proveri naziv modela u podešavanjima."
            429 -> "Gemini kvota je potrošena; prepis će biti ponovljen kasnije."
            else -> "Gemini greška HTTP $kod${if (poruka.isNotBlank()) ": $poruka" else ""}"
        }
    }
}
