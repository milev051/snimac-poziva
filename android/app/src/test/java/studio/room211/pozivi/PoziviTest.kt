package studio.room211.pozivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoziviTest {
    @Test
    fun nazivIzSamsungFajla() {
        assertEquals("Marko Marković", SistemskiSnimci.naziv("Call recording Marko Marković_241021_101500.m4a"))
        assertEquals("+381641234567", SistemskiSnimci.naziv("Snimak poziva +381641234567_20241021_101500.m4a"))
        assertEquals("Mama", SistemskiSnimci.naziv("Mama_241021_101500.m4a"))
        assertEquals("snimak", SistemskiSnimci.naziv("snimak.mp3"))
    }

    @Test
    fun spisakPrezivljavaUpisICitanje() {
        val p = Poziv("app:a.aac", "file:///a.aac", "Pera", 5L, 61_000L, Poziv.IZVOR_APLIKACIJA,
            Poziv.STATUS_GOTOVO, "Ja: zdravo\nSagovornik: ćao", "")
        assertEquals(listOf(p), Pozivi.izTeksta(Pozivi.uTekst(listOf(p))))
    }

    @Test
    fun verzijePoBrojevima() {
        assertTrue(Azuriranje.novije("1.9", "v1.11"))
        assertFalse(Azuriranje.novije("1.11", "v1.11"))
        assertFalse(Azuriranje.novije("1.12", "v1.11"))
        assertTrue(Azuriranje.novije("1.0", "v1.1"))
    }

    @Test
    fun izdanjeUzimaApk() {
        val json = """{"tag_name":"v1.4","name":"Pozivi 1.4","assets":[
            {"name":"beleske.txt","browser_download_url":"x"},
            {"name":"pozivi-1.4.apk","browser_download_url":"https://github.com/a.apk"}]}"""
        val i = Azuriranje.izOdgovora(json)
        assertEquals("v1.4", i.oznaka)
        assertEquals("https://github.com/a.apk", i.adresaApk)
    }

    @Test
    fun geminiTekstIzDelova() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"[00:00] Ja: Halo"},{"text":"\n[00:02] Sagovornik: Da"}]},"finishReason":"STOP"}]}"""
        assertEquals("[00:00] Ja: Halo\n[00:02] Sagovornik: Da", Gemini.tekstIzOdgovora(json))
    }

    @Test(expected = Gemini.GreskaGemini::class)
    fun geminiBezKandidataJeGreska() {
        Gemini.tekstIzOdgovora("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }

    @Test
    fun porukaGreskeZaKvotu() {
        assertTrue(Gemini.porukaGreske(429, "{}").contains("kvota"))
        assertTrue(Gemini.porukaGreske(400, """{"error":{"message":"bad mime"}}""").contains("bad mime"))
    }

    @Test
    fun preuzorkovanjeIZaglavlje() {
        val u = WavPretvarac.Uzorkovanje(16_000)
        val izlaz = u.dodaj(ShortArray(480) { it.toShort() }, 48_000) + u.dodaj(ShortArray(480), 48_000)
        assertEquals(320 * 2, izlaz.size)
        val z = WavPretvarac.zaglavlje(1000, 16_000)
        assertEquals("RIFF", String(z, 0, 4))
        assertEquals(44, z.size)
    }
}
