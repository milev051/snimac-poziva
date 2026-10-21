package studio.room211.pozivi

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var pod: Podesavanja
    private lateinit var dozvole: LinearLayout
    private lateinit var lista: LinearLayout
    private lateinit var azuriranjeDugme: MaterialButton
    private lateinit var azuriranjeTekst: TextView
    private var izdanje: Azuriranje.Izdanje? = null
    private var samoInstaliranjePokusano = false
    private val promena: () -> Unit = { runOnUiThread { osveziListu() } }

    private val traziDozvole = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        osveziDozvole()
        skeniraj()
    }
    private val izaberiSnimak = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) thread {
            val ok = runCatching { Uvoz.kopiraj(applicationContext, uri) }.isSuccess
            runOnUiThread { if (!ok) poruka("Snimak nije mogao da se uveze") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        pod = Podesavanja(this)
        Obavestenja.kanali(this)
        Poslovi.zakaziOdrzavanje(this)

        val koren = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        koren.addView(TextView(this).apply {
            text = "Pozivi"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
            setPadding(dp(4), 0, 0, dp(12))
        })
        dozvole = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        koren.addView(dozvole)
        koren.addView(karticaPoziva())
        koren.addView(karticaPodesavanja())
        koren.addView(karticaAzuriranja())
        setContentView(ScrollView(this).apply { addView(koren); isFillViewport = true })

        if (savedInstanceState == null) {
            val nedostaju = runtimeDozvole().filter { !ima(it) }
            if (nedostaju.isNotEmpty()) traziDozvole.launch(nedostaju.toTypedArray())
        }
        otvoriIzObavestenja(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        otvoriIzObavestenja(intent)
    }

    override fun onResume() {
        super.onResume()
        vidljiva = true
        Pozivi.slusaj(promena)
        osveziDozvole()
        osveziListu()
        skeniraj()
        if (pod.proveravajAzuriranje) proveriAzuriranje(tiho = true)
    }

    override fun onPause() {
        vidljiva = false
        Pozivi.prestani(promena)
        super.onPause()
    }

    private fun otvoriIzObavestenja(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_POZIV) ?: return
        Pozivi.nadji(this, id)?.takeIf { it.tekst.isNotBlank() }?.let { prikaziPrepis(it) }
    }

    private fun skeniraj() {
        if (!ima(dozvolaAudio())) return
        val app = applicationContext
        thread { Poslovi.skeniraj(app) }
    }

    // ---------- dozvole ----------

    private fun dozvolaAudio() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun runtimeDozvole() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(dozvolaAudio())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ima(d: String) = checkSelfPermission(d) == PackageManager.PERMISSION_GRANTED

    private fun pristupacnostUkljucena(): Boolean {
        val ime = ComponentName(this, SnimacServis::class.java).flattenToString()
        val ukljuceni = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return ukljuceni.split(':').any { it.equals(ime, ignoreCase = true) }
    }

    private fun osveziDozvole() {
        dozvole.removeAllViews()
        val stavke = mutableListOf<Triple<String, String, () -> Unit>>()
        val traziSve = { traziDozvole.launch(runtimeDozvole().toTypedArray()) }
        if (!ima(Manifest.permission.RECORD_AUDIO)) stavke += Triple("Mikrofon", "za sopstveno snimanje poziva", traziSve)
        if (!ima(Manifest.permission.READ_PHONE_STATE) || !ima(Manifest.permission.READ_CALL_LOG))
            stavke += Triple("Telefon i dnevnik poziva", "da zna kad poziv počinje i ko je zvao", traziSve)
        if (!ima(dozvolaAudio())) stavke += Triple("Audio fajlovi", "da pronađe snimke telefonskog snimača poziva", traziSve)
        if (Build.VERSION.SDK_INT >= 33 && !ima(Manifest.permission.POST_NOTIFICATIONS))
            stavke += Triple("Obaveštenja", "za gotov prepis i ažuriranje", traziSve)
        if (pod.snimajSam && !pristupacnostUkljucena()) stavke += Triple(
            "Pristupačnost: Pozivi",
            "bez ovoga Android ne daje mikrofon tokom poziva. Ako je prekidač siv: Podešavanja → Aplikacije → Pozivi → ⋮ → Dozvoli ograničena podešavanja.",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!Azuriranje.smeDaInstalira(this)) stavke += Triple("Instaliranje ažuriranja", "da nova verzija sa GitHub-a stigne sama") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        if (stavke.isEmpty()) return
        val (kartica, sadrzaj) = kartica("Nedostaju dozvole")
        stavke.forEach { (naslov, opis, akcija) ->
            sadrzaj.addView(TextView(this).apply { text = naslov; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
            sadrzaj.addView(telo(opis))
            sadrzaj.addView(dugme("Dozvoli") { akcija() })
        }
        dozvole.addView(kartica)
    }

    // ---------- pozivi ----------

    private fun karticaPoziva(): View {
        val (kartica, sadrzaj) = kartica("Snimljeni pozivi")
        sadrzaj.addView(red(
            dugme("Dodaj snimak", tonal = true) { izaberiSnimak.launch(arrayOf("audio/*")) },
            dugme("Osveži", tonal = true) { skeniraj(); osveziListu() },
        ))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sadrzaj.addView(lista)
        return kartica
    }

    private fun osveziListu() {
        if (!::lista.isInitialized) return
        lista.removeAllViews()
        val svi = Pozivi.svi(this)
        if (svi.isEmpty()) {
            lista.addView(telo("Još nema snimaka. Posle prvog poziva snimak se pojavljuje ovde. Ako telefon ima svoje snimanje poziva (Samsung: Telefon → ⋮ → Podešavanja → Snimanje poziva), i ti snimci se pojavljuju sami."))
            return
        }
        val format = SimpleDateFormat("d.M. HH:mm", Locale("sr"))
        svi.forEach { p ->
            val stavka = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(4))
            }
            stavka.addView(TextView(this).apply {
                text = p.naziv
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            })
            val izvor = when (p.izvor) {
                Poziv.IZVOR_SISTEM -> "snimač telefona"
                Poziv.IZVOR_APLIKACIJA -> "mikrofon"
                else -> "uvezen"
            }
            val status = when (p.status) {
                Poziv.STATUS_CEKA -> if (p.greska.isNotBlank()) " · ponovo kasnije: ${p.greska}" else " · čeka internet"
                Poziv.STATUS_RADI -> " · prepisuje se…"
                Poziv.STATUS_GOTOVO -> " · prepisan"
                Poziv.STATUS_GRESKA -> " · greška: ${p.greska}"
                else -> ""
            }
            stavka.addView(telo("${format.format(Date(p.vreme))} · ${trajanje(p.trajanjeMs)} · $izvor$status"))
            val dugmad = mutableListOf(dugme("Pusti", tonal = true) { pusti(p) })
            when (p.status) {
                Poziv.STATUS_GOTOVO -> {
                    dugmad += dugme("Prepis") { prikaziPrepis(p) }
                }
                Poziv.STATUS_CEKA, Poziv.STATUS_RADI -> dugmad += dugme("Otkaži", tonal = true) { Poslovi.otkazi(this, p.id) }
                else -> dugmad += dugme("Prepiši") { prepisi(p) }
            }
            stavka.addView(red(*dugmad.toTypedArray()))
            stavka.setOnLongClickListener { meni(p); true }
            lista.addView(stavka)
        }
    }

    private fun prepisi(p: Poziv) {
        if (pod.geminiKljuc.isBlank()) {
            poruka("Prvo upiši Gemini API ključ u podešavanjima")
            return
        }
        Poslovi.prepisi(this, p.id)
    }

    private fun meni(p: Poziv) {
        val stavke = mutableListOf<Pair<String, () -> Unit>>()
        if (p.status == Poziv.STATUS_GOTOVO) stavke += "Prepiši ponovo" to { prepisi(p) }
        stavke += "Podeli snimak" to { podeliSnimak(p) }
        if (p.izvor != Poziv.IZVOR_SISTEM) stavke += "Obriši snimak" to {
            MaterialAlertDialogBuilder(this).setTitle("Obrisati snimak i prepis?")
                .setPositiveButton("Obriši") { _, _ ->
                    Uri.parse(p.uri).path?.let { File(it).delete() }
                    Pozivi.ukloni(this, p.id)
                }
                .setNegativeButton("Ne", null).show()
        } else if (p.tekst.isNotBlank()) stavke += "Obriši prepis" to {
            Pozivi.izmeni(this, p.id) { it.copy(tekst = "", status = Poziv.STATUS_NIJE) }
        }
        MaterialAlertDialogBuilder(this).setTitle(p.naziv)
            .setItems(stavke.map { it.first }.toTypedArray()) { _, i -> stavke[i].second() }
            .show()
    }

    private fun adresa(p: Poziv): Uri {
        val uri = Uri.parse(p.uri)
        return if (uri.scheme == "file") FileProvider.getUriForFile(this, "$packageName.fajlovi", File(uri.path!!)) else uri
    }

    private fun pusti(p: Poziv) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(adresa(p), "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { poruka("Nema aplikacije za puštanje zvuka") }

    private fun podeliSnimak(p: Poziv) = runCatching {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/*")
            .putExtra(Intent.EXTRA_STREAM, adresa(p)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), p.naziv))
    }

    private fun prikaziPrepis(p: Poziv) {
        val tekst = TextView(this).apply {
            text = p.tekst
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        MaterialAlertDialogBuilder(this).setTitle(p.naziv)
            .setView(ScrollView(this).apply { addView(tekst) })
            .setPositiveButton("Kopiraj") { _, _ ->
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(p.naziv, p.tekst))
            }
            .setNeutralButton("Podeli") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, p.naziv).putExtra(Intent.EXTRA_TEXT, p.tekst), p.naziv))
            }
            .setNegativeButton("Zatvori", null)
            .show()
    }

    // ---------- podešavanja ----------

    private fun karticaPodesavanja(): View {
        val (kartica, s) = kartica("Podešavanja")
        val kljuc = TextInputEditText(this).apply {
            setText(pod.geminiKljuc)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        s.addView(TextInputLayout(this).apply {
            hint = "Gemini API ključ"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(kljuc)
        })
        s.addView(telo("Besplatan ključ se pravi na aistudio.google.com/apikey. Snimak ide Google-u samo kad izabereš prepis."))
        val model = TextInputEditText(this).apply { setText(pod.model) }
        s.addView(TextInputLayout(this).apply { hint = "Gemini model"; addView(model) })
        s.addView(dugme("Sačuvaj") {
            pod.geminiKljuc = kljuc.text?.toString().orEmpty()
            pod.model = model.text?.toString().orEmpty()
            poruka("Sačuvano")
        })

        s.addView(prekidac("Snimaj pozive sam (mikrofon)", pod.snimajSam) { pod.snimajSam = it; osveziDozvole() })
        s.addView(telo("Mikrofon jasno čuje tebe, a sagovornika samo kad je uključen zvučnik. Ako telefon ima svoj snimač poziva, on snima oba smera i aplikacija njegove snimke pronalazi sama; tada ovo možeš isključiti."))
        val izvori = listOf(
            Podesavanja.IZVOR_MIKROFON to "Mikrofon",
            Podesavanja.IZVOR_PREPOZNAVANJE to "Mikrofon bez obrade zvuka",
            Podesavanja.IZVOR_POZIV to "Probaj oba smera (VOICE_CALL), pa mikrofon",
        )
        s.addView(RadioGroup(this).apply {
            izvori.forEachIndexed { i, (kod, naziv) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = naziv
                    isChecked = pod.izvorZvuka == kod
                    setOnCheckedChangeListener { _, c -> if (c) pod.izvorZvuka = izvori[i].first }
                })
            }
        })
        s.addView(prekidac("Prepiši nov snimak sam kad ima interneta", pod.automatskiPrepis) { pod.automatskiPrepis = it })
        s.addView(prekidac("Prepisuj samo preko Wi-Fi mreže", pod.samoWifi) { pod.samoWifi = it })
        s.addView(telo("Prepis se ne radi tokom poziva. Dugme „Prepiši“ radi i bez interneta: prepis čeka i kreće čim se veza vrati."))
        return kartica
    }

    // ---------- ažuriranje ----------

    private fun karticaAzuriranja(): View {
        val (kartica, s) = kartica("Verzija i ažuriranje")
        azuriranjeTekst = telo("Instalirano: ${Azuriranje.trenutnaVerzija(this)}")
        s.addView(azuriranjeTekst)
        azuriranjeDugme = dugme("Proveri ažuriranje") { izdanje?.let { instaliraj(it) } ?: proveriAzuriranje(tiho = false) }
        s.addView(azuriranjeDugme)
        s.addView(prekidac("Proveravaj i instaliraj ažuriranja sam", pod.proveravajAzuriranje) { pod.proveravajAzuriranje = it })
        s.addView(telo("Izdanja: github.com/${Azuriranje.REPO}/releases"))
        return kartica
    }

    private fun proveriAzuriranje(tiho: Boolean) {
        if (!tiho) azuriranjeDugme.text = "Proveravam…"
        thread {
            val r = runCatching { Azuriranje.poslednje() }
            runOnUiThread {
                r.onSuccess {
                    if (Azuriranje.novije(Azuriranje.trenutnaVerzija(this), it.oznaka)) {
                        izdanje = it
                        azuriranjeDugme.text = "Preuzmi i instaliraj ${it.oznaka}"
                        if (tiho && !samoInstaliranjePokusano && Azuriranje.smeDaInstalira(this)) {
                            samoInstaliranjePokusano = true
                            instaliraj(it)
                        }
                    } else {
                        izdanje = null
                        azuriranjeDugme.text = "Imaš najnoviju verziju (${Azuriranje.trenutnaVerzija(this)})"
                    }
                }.onFailure { if (!tiho) azuriranjeDugme.text = "Provera nije uspela: ${it.message}" }
            }
        }
    }

    private fun instaliraj(i: Azuriranje.Izdanje) {
        if (!Azuriranje.smeDaInstalira(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        azuriranjeDugme.isEnabled = false
        thread {
            val r = runCatching {
                val apk = Azuriranje.preuzmi(this, i.adresaApk) { pct -> runOnUiThread { azuriranjeDugme.text = "Preuzimam… $pct%" } }
                Azuriranje.instaliraj(this, apk)
            }
            runOnUiThread {
                azuriranjeDugme.isEnabled = true
                azuriranjeDugme.text = r.fold({ "Instaliram ${i.oznaka}…" }, { "Ažuriranje nije uspelo: ${it.message}" })
            }
        }
    }

    // ---------- gradivni elementi ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun kartica(naslov: String): Pair<MaterialCardView, LinearLayout> {
        val unutra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(TextView(context).apply {
                text = naslov
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
        }
        val k = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
            addView(unutra)
        }
        return k to unutra
    }

    private fun telo(t: String) = TextView(this).apply {
        text = t
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun dugme(t: String, tonal: Boolean = false, akcija: () -> Unit) =
        MaterialButton(this, null, if (tonal) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
            text = t
            setOnClickListener { akcija() }
        }

    private fun red(vararg dugmad: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        dugmad.forEach {
            addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
    }

    private fun prekidac(t: String, pocetno: Boolean, promena: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = t
        isChecked = pocetno
        minHeight = dp(48)
        setOnCheckedChangeListener { _, c -> promena(c) }
    }

    private fun poruka(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun trajanje(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val EXTRA_POZIV = "poziv"
        @Volatile var vidljiva = false
    }
}
