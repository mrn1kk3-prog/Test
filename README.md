# Vibration Monitor

Android-sovellus puhelimen kiihtyvyysanturilla tehtävään värähtelymittaukseen ja suuntaa-antavaan kunnonvalvontaan.

## Ominaisuudet

- reaaliaikainen kiihtyvyysmittaus
- automaattisesti mitattu todellinen näytteenottotaajuus
- aikatasosignaali
- FFT-spektri Hann-ikkunalla
- 5 voimakkainta taajuushuippua
- acceleration RMS
- peak ja peak-to-peak
- crest factor
- integroitu velocity RMS -arvio mm/s
- CSV-vienti raakadatasta
- käyttää ensisijaisesti Androidin linear acceleration -anturia
- näyttää Nyquist-taajuuden, jotta käyttäjä näkee mittauksen todellisen taajuusrajan

## Käyttö

1. Kiinnitä puhelin mahdollisimman jäykästi mitattavaan koneeseen.
2. Avaa sovellus ja paina **Aloita mittaus**.
3. Anna mittauksen käydä muutama sekunti.
4. Tarkastele RMS-arvoja, aikatasoa, FFT-spektriä ja hallitsevia taajuuksia.
5. Vie raakadata CSV-tiedostoksi jatkoanalyysiä varten.

## APK

GitHub Actions rakentaa debug-APK:n automaattisesti jokaisesta `main`-haaran päivityksestä. APK löytyy onnistuneen Actions-ajon Artifacts-kohdasta nimellä `VibrationMonitor-debug-apk`.

## Kunnonvalvonnan jatkokehitys

Suunniteltuja ominaisuuksia:

- X/Y/Z-akselien erillinen spektri ja RMS
- valittava taajuuskaista ja band RMS
- 1x/2x/3x RPM order tracking
- konekohtaiset mittauspisteet ja historia
- trendikäyrät ja baseline-vertailu
- säädettävät alert/danger-hälytysrajat
- ISO 20816 -tyylinen vyöhykearviointi käyttäjän syöttämillä koneluokan ja raja-arvojen tiedoilla
- envelope/demodulation laakerivikojen etsintään
- kurtosis ja skewness
- spektrikursorit ja harmonisten automaattinen tunnistus
- mittausraportin vienti

## Mittaustarkkuudesta

Puhelimen MEMS-kiihtyvyysanturin kaista, kohinataso, näytteenottotaajuus ja mekaaninen kiinnitys vaihtelevat laitteittain. Sovellusta ei pidä käsitellä kalibroidun teollisuusvärähtelymittarin korvaajana. Se soveltuu erityisen hyvin saman koneen toistettaviin trendimittauksiin, kun puhelimen sijainti ja kiinnitys pidetään samoina.
