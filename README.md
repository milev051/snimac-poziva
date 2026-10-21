# Pozivi

> **English:** Android app that records phone calls and, on demand, transcribes
> them with Gemini (speaker-labelled, offline-queued). It also picks up the
> phone's own call recordings. Every push to `main` builds a signed APK on
> GitHub Actions, and the app updates itself from GitHub Releases.

Snima telefonske pozive i prepisuje ih u tekst kad to izabereš.

## Kako dobija zvuk

| izvor | šta čuje | uslov |
|---|---|---|
| **Snimač telefona** (Samsung: Telefon → ⋮ → Podešavanja → Snimanje poziva) | oba smera, čisto | telefon ga ima; aplikacija sama nalazi snimke u `Recordings/Call` |
| **Sopstveno snimanje** (servis pristupačnosti) | tebe jasno, sagovornika samo na zvučniku | uključena Pristupačnost → Pozivi |
| **Uvoz** („Dodaj snimak“ ili Podeli → Pozivi) | šta god je na snimku | — |

Android od verzije 10 aplikacijama van sistema ne daje zvuk poziva
(`VOICE_CALL`), pa ni oba smera kao **zasebne trake**. Opcija „Probaj oba
smera“ to ipak pokuša i prelazi na mikrofon ako sistem odbije. Govornike
zato razdvaja Gemini, po glasu: `Ja:` i `Sagovornik:`, uz vreme `[mm:ss]`.

## Prepis

- Ne radi se tokom poziva. Dugme **Prepiši** na svakom pozivu; bez interneta
  prepis čeka u redu i kreće čim se veza vrati (WorkManager).
- Opciono: sam prepiši nov snimak (samo snimci mlađi od dana, da stari ne
  potroše kvotu) i samo preko Wi-Fi mreže.
- Treba **Gemini API ključ** (besplatan: aistudio.google.com/apikey), upisuje se u
  aplikaciji. Model je podesiv, podrazumevano `gemini-flash-latest`.
- m4a/amr se pre slanja pretvaraju u WAV 16 kHz mono; veći od 14 MB idu preko
  Files API-ja.

## Ažuriranje

Svaki push na `main` koji menja `android/` pokreće `.github/workflows/izdanje.yml`:
testovi, APK potpisan stalnim ključem, izdanje `v1.<broj>` na GitHub-u.
Aplikacija proverava izdanja pri otvaranju i u pozadini svakih 6 sati, preuzme
APK i instalira ga kroz PackageInstaller. Na Androidu 12+ ažuriranje ide bez
dodira; ako sistem ipak traži potvrdu, stiže obaveštenje.

Ključ za potpis je van repoa: `~/.config/snimac-poziva/` (lokalno) i tajne
`POTPIS_BASE64`, `POTPIS_LOZINKA` na GitHub-u. **Ako se ključ izgubi, telefon
neće primiti ažuriranje** dok se aplikacija ne obriše i instalira iznova.

## Prva instalacija

1. Na telefonu otvori github.com/milev051/snimac-poziva/releases i preuzmi APK.
2. Dozvoli instalaciju iz izvora kojim ga otvaraš.
3. U aplikaciji odobri dozvole. Za Pristupačnost na Androidu 13+ prvo:
   Podešavanja → Aplikacije → Pozivi → ⋮ → **Dozvoli ograničena podešavanja**.

## Build

```bash
cd android
./build.sh          # testovi + release APK
./build.sh install  # i instalacija preko adb
```

## Pravno

Snimanje razgovora bez znanja sagovornika može biti krivično delo (KZ Srbije,
čl. 143) a objavljivanje snimka posebno (čl. 145). Reci sagovorniku da snimaš.
