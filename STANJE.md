# Stanje
_2026-10-22_
Cilj: Android aplikacija „Pozivi“ za snimanje poziva i prepis preko Geminija.
Urađeno (1.3): snimanje kreće odmah na početku poziva (bez kašnjenja od 1 s), obaveštenje se pokazuje odmah.
Urađeno (1.3): izbor „Način snimanja“: zvuk poziva iz telefona (VOICE_CALL, VOICE_DOWNLINK) pa mikrofon; samo mikrofon; samo ugrađeno snimanje telefona. Dugme za podešavanja poziva u Telefonu.
Urađeno (1.4): korisnik javio da je snimak pretih. Snimanje prešlo na AudioRecord → WAV, posle poziva automatsko pojačanje do 24× sa mekim sečenjem vrhova.
Tišina se prepoznaje posle 3 s (ranije 4 s) i prelazi se na sledeći izvor.
Ranije (1.2): dnevnik snimanja, dugme za ažuriranje samo kad ima novije verzije, uklonjene suvišne opcije.
Sledeći korak: korisnik instalira novu verziju, primi poziv i pošalje „Dnevnik snimanja“.
Otvoreno: da li VOICE_CALL na korisnikovom Samsungu daje zvuk ili tišinu; da li servis pristupačnosti hvata početak poziva.
Putanje: android/app/src/main/java/studio/room211/pozivi/SnimacServis.kt, Podesavanja.kt, MainActivity.kt
