# Ultimate Vibration Analyzer — 1.0 preview

Android 8+ / Kotlin / Jetpack Compose Material 3. Kehityshaara päivittää alkuperäisen Vibration Monitor -sovelluksen. PersonalHealth-workflow ja main-haara säilyvät ennallaan.

## Käyttö

1. Avaa Mittaus. Anna nimi, kone, piste, suunta, kiinnitys ja RPM.
2. Valitse kesto, pyydetty Fs, Fmax, FFT-koko, akseli, ikkuna ja keskiarvoistus.
3. Kiinnitä puhelin turvallisesti jäykälle alustalle. Aloita mittaus. Pidä sovellus näkyvissä.
4. Valmis tai käyttäjän pysäyttämä mittaus tallentuu automaattisesti.
5. Avaa Analyysi: aikataso, FFT, PSD, tunnusluvut, laatu ja perustellut alustavat havainnot.
6. Historiassa voi avata, nimetä, hakea, poistaa vahvistuksella tai verrata toista mittausta.
7. Varmuuskopioi historia ennen APK-päivitystä tai sovelluksen poistoa.

## Toteutettu

- Tumma/vaalea suomenkielinen käyttöliittymä, navigaatio, koneiden perustietorekisteri.
- Nimi, kone, piste, suunta, kiinnitys, muistiinpanot ja mittausasetukset muistetaan.
- Raaka Android-kiihtyvyysanturi erillisellä HandlerThread-säikeellä; anturin nimi, valmistaja, alue, resoluutio, viiveet ja virrankulutus näkyvät.
- Todellinen Fs, ajoitusvaihtelu, näytekatkokset ja mahdollisen yliohjauksen tunnistus.
- Aikaleimapohjainen lineaarinen uudelleennäytteistys, akselikohtainen DC-poisto.
- X/Y/Z ja XYZ-neliösummaspektri (ei FFT:tä epälineaarisesta vektorin pituudesta).
- Radix-2 FFT, Hann/Hamming/Blackman/Flat Top/Rectangular, lineaarinen/RMS/eksponentiaalinen amplitudikeskiarvoistus, 50 % limitys.
- Yksipuolinen koherentilla vahvistuksella korjattu amplitudispektri; tehoikkunalla normalisoitu Welch PSD. DC/Nyquist eivät kaksinkertaistu.
- RMS, Peak, P-P, crest factor, kurtosis, skewness. Akselin DC-poistetun signaalin RMS vastaa populaation keskihajontaa.
- Kaistarajattu nopeuden RMS PSD:stä; nopeus- ja siirtymäspektrit ovat taajuustason arvioita. Matalat taajuudet rajataan pois.
- Zoom/panorointi, kaksi napautuskursoria ja erotus, harmoniset, huippulista, Hz/CPM, log10-amplitudi ja Peak/RMS-esitys.
- Epätasapainon, linjausvirheen ja väljyyden alustavat sääntöpohjaiset havainnot, perustelut, vaihtoehdot ja jatkotoimet. Ei keksittyjä varmuusprosentteja.
- SQLiteOpenHelper-tietokanta (ei Room) ja append-only-binääriset raakajournalit. Näytteet flush/fsync noin 250 ms välein.
- Prosessikatkoksen jälkeen keskeneräinen mittaus merkitään keskeytyneeksi. Osittainen viimeinen näyte ohitetaan luettaessa.
- Analyysiversiohistoria säilyy uudelleenanalyysissä. Alkuperäinen raakadata ei muutu.
- Saman pisteen/asetusten kiihtyvyys-RMS-trendi, kahden spektrin päällekkäisvertailu ja metatietojen eroista varoitus.
- CSV-signaali, CSV-FFT, analyysi-JSON, PNG-spektri, monisivuinen PDF-raportti.
- JSON-varmuuskopio ja palautus uusilla tunnisteilla, mukana raakadata ja analyysiversiot. Vienti ei muuta alkuperäisiä.
- Tekninen ohje sekä selvästi SIMULOITU-merkityt 1X, 1X+2X ja harmoniset opetussignaalit.
- SampleSource-rajapinta myöhempiä anturiprotokollia varten.

## Rajaukset — ei väitetä valmiiksi

Tämä on toiminnallinen esiversio, ei validoitu ammattimittari eikä koko alkuperäisen laajan vaatimuslistan valmis toteutus.

- Ei fyysisellä puhelimella, kalibraattorilla tai teollisuuskoneella tehtyä validointia.
- Laakerien envelope/BPFO/BPFI/BSF/FTF-diagnoosi, hammaspyörä-, hihna-, kavitaatio- ja sähkövikadiagnoosi puuttuvat. Puhelimen analogista kaistaa ei voida päätellä Nyquist-rajasta.
- Ei tachopulssiin perustuvaa order tracking -analyysiä, kierrosluvun automaattiarviota, cepstrumia, spektrogrammia tai waterfallia.
- Ei validoituja ISO 20816 -luokkia, automaattisia vakavuusarvioita eikä konekohtaisia hälytysrajoja.
- Koneiden lisätiedot ovat vapaatekstiä; tehdas/linja/pistehierarkia, pistekohtaiset referenssit, laakerigeometria ja rakenteiset tekniset kentät puuttuvat.
- Trendin päämittari on kiihtyvyyden RMS. Ei 1X/2X-, laakeritaajuus- tai kaistatrendejä.
- Vain yksi nimettömänä tallennettava asetusprofiili, kaksi spektriä vertailussa, suomenkielinen käyttöliittymä. Englanninkielinen lokalisointi puuttuu.
- Ei ulkoisen USB/Bluetooth-anturin protokollatoteutusta, ei taustalla jatkuvaa mittausta.
- Ei signaalin automaattista kiinnitys-/liikevalidointia tai varmennettua kohinatasomittausta. Näytelaatuhavainnot eivät todista kiinnitystä hyväksi.
- Suuret JSON-varmuuskopiot käsitellään muistissa. Mittaushistorian kasvaessa tarvitaan suoratoistava ZIP-varmuuskopio.
- SQLite-tietokanta ja raakajournal eivät ole yksi yhteinen atominen tiedosto; metatieto luodaan ennen keruuta ja tulokset transaktiossa. Prosessikatko voi jättää viimeisen 250 ms puskurin tallentamatta. Levyvian yhteydessä täydellistä säilymistä ei luvata.
- Nykyinen tietokantaversio on 1; tuleva versiomuutos vaatii eksplisiittisen migraation. Destruktiivista fallbackia ei ole.

## APK ja päivitys

GitHub Actions **Ultimate Vibration Analyzer APK** ajaa `gradle testDebugUnitTest lintDebug assembleDebug` (Java 17, Gradle 8.9, Android SDK 35). APK löytyy onnistuneen ajon Artifacts-kohdasta: `UltimateVibrationAnalyzer-debug-apk`.

Lataa ZIP GitHubiin kirjautuneena, pura se ja avaa `app-debug.apk` Androidin tiedostonhallinnassa. Salli APK:n asentaminen kyseisestä sovelluksesta Androidin pyytäessä.

Sovellustunniste säilyy `com.example.vibrationmonitor.v02`. Aikaisemman debug-APK:n allekirjoitusavain ei ole saatavilla, joten vanhan asennuksen päälle asentamista ei voida taata. Älä poista vanhaa sovellusta tai sen tietoja ennen tarvittavien vientien varmistamista. Julkaisuallekirjoitusta ei ole määritetty; yksityisiä avaimia ei tallenneta repositoryyn.

Alkuperäisessä tarkastetussa VibrationActivity-versiossa mittaukset olivat muistissa, eikä sille löytynyt pysyvää mittaustietokantaa. Vanhojen CSV-vientien tuontia ei ole toteutettu.

## Arkkitehtuuri

- `ultimate/Capture.kt`: SampleSource, Android-anturi, journalointi.
- `ultimate/Dsp.kt`: puhdas Kotlin FFT/PSD, uudelleennäytteistys, tunnusluvut ja diagnoosisäännöt.
- `ultimate/Store.kt`: SQLite, raakadata, versiot, varmuuskopio/palautus.
- `ultimate/UltimateActivity.kt`: Compose-näkymät ja lifecycle-coroutine-ohjaus. Ei vielä erillistä ViewModel/StateFlow-kerrosta.
- `ultimate/Reports.kt`: PNG/PDF-vienti.

Aikaisemmat Activityt säilyvät lähdekoodissa vertailua varten, mutta launcheri käyttää UltimateActivitya.

## Testit

`DspTest`: 50/100 Hz, amplitudi, kaikki ikkunat, kahden sinin RMS ja PSD, XYZ ilman taajuuden kaksinkertaistumista, katkokset, yliohjaus, painovoima, alustavat vikakuviot, kohina, impulssi, chirp, moduloitu kantotaajuus, virheelliset aikaleimat, Nyquist-skaalaus ja jitter.

`StoreTest` (Robolectric / Android API 28): nimen/asetusten pysyvyys, keskeytyneen journalin palautuminen, katkennut viimeinen näyte, varmuuskopio/palautus ja analyysiversiot, rajattu poisto, virheellisen palautuksen atomisuus sekä nimeäminen raakadataa muuttamatta.

Testien olemassaolo ei ole testitulos. Tarkista onnistuneen CI-ajon raportit. Synteettinen moduloitu signaali ei validoi laakeridiagnoosia. Android-laitteen UI-, anturi-, pitkäkestoisuus- ja asennustestit ovat edelleen tehtävä.
