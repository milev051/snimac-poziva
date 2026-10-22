# Pozivi

> **English:** Android app that records phone calls and, on demand, transcribes
> them with Gemini (speaker-labelled, offline-queued). It also picks up the
> phone's own call recordings. Every push to `main` builds a signed APK on
> GitHub Actions; the app offers the update from GitHub Releases.

Snima telefonske pozive i prepisuje ih u tekst kad to izabereš.

## Kako dobija zvuk

| izvor | šta čuje | uslov |
|---|---|---|
| **Snimač telefona** (Samsung: Telefon → ⋮ → Podešavanja → Snimanje poziva) | oba smera, čisto | telefon ga ima; aplikacija sama nalazi snimke u `Recordings/Call` |
| **Sopstveno snimanje** (servis pristupačnosti) | tebe jasno, sagovornika samo na zvučniku | uključena Pristupačnost → Pozivi |
| **Uvoz** („Dodaj snimak“ ili Podeli → Pozivi) | šta god je na snimku | — |

U podešavanjima biraš **način snimanja**:

- **Zvuk poziva iz telefona, pa mikrofon** (podrazumevano): prvo `VOICE_CALL`
  (oba smera) i `VOICE_DOWNLINK` (sagovornik). Android ih zvanično daje samo
  sistemskim aplikacijama, pa na većini telefona ne rade ili daju tišinu;
  tada snimanje samo pređe na mikrofon.
- **Samo mikrofon**: mikrofon, mikrofon bez obrade, mikrofon kamere,
  mikrofon za razgovor.
- **Samo ugrađeno snimanje telefona**: aplikacija ne snima, nego pronalazi
  snimke telefonskog snimača. Dugme „Otvori podešavanja poziva u Telefonu“
  vodi do mesta gde se on uključuje.

Snimanje kreće odmah kad poziv počne (javljanje ili biranje broja). Izvor
koji 3 sekunde daje samo nule zamenjuje sledeći. Kad nema zasebnih traka,
govornike razdvaja Gemini, po glasu: `Ja:` i `Sagovornik:`, uz vreme `[mm:ss]`.
Sve to se upisuje u karticu **Dnevnik snimanja** u aplikaciji.

## Prepis

- Ne radi se tokom poziva. Dugme **Prepiši** na svakom pozivu; bez interneta
  prepis čeka u redu i kreće čim se veza vrati (WorkManager).
- Treba **Gemini API ključ** (besplatan: aistudio.google.com/apikey), upisuje se u
  aplikaciji. Model je stalan: `gemini-flash-latest`.
- m4a/amr se pre slanja pretvaraju u WAV 16 kHz mono; veći od 14 MB idu preko
  Files API-ja.

## Ažuriranje

Svaki push na `main` koji menja `android/` pokreće `.github/workflows/izdanje.yml`:
testovi, APK potpisan stalnim ključem, izdanje `v1.<broj>` na GitHub-u.
Aplikacija pri otvaranju proveri izdanja. Dugme „Preuzmi i instaliraj“ je
aktivno samo kad postoji novija verzija; samo ažuriranje ne postoji.

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
