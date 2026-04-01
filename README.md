# Video BonoTrot

Video BonoTrot e un lettore video per Android TV / Google TV che apre stream da liste URL importate oppure da liste create manualmente.

## Download

- Ultima release: `https://github.com/BonoTrotDJ/VideoB/releases/latest`
- Release `v1.0.0`: `https://github.com/BonoTrotDJ/VideoB/releases/tag/v1.0.0`
- APK `v1.0.0`: `https://github.com/BonoTrotDJ/VideoB/releases/download/v1.0.0/app-release.apk`

L'app permette di:

- creare liste manuali con nome e URL
- importare liste da URL testuali come `prog.txt`
- aggiornare una lista importata quando vuoi rileggendo la sorgente
- mostrare metadati utili per ogni evento come giorno, ora, lingua e sport
- passare alla modalità `Link` per vedere solo i link trovati, senza programmazione
- aprire il link direttamente nel player interno

## Uso

Nel menu laterale puoi creare due tipi di lista:

- `Manuale`: aggiungi a mano i link video che vuoi salvare
- `Da URL`: inserisci un URL sorgente e lascia che Video BonoTrot importi i link compatibili

Per le liste importate, puoi premere `Aggiorna Lista` in qualsiasi momento per ricaricare i contenuti dalla sorgente.

Per le liste importate puoi anche usare il pulsante `Link`:

- mostra solo i link univoci trovati
- nasconde la programmazione per giorno e orario
- mantiene lingua e nome canale/link in una vista più compatta

Esempio di sorgente supportata:

- `https://sportsonline.st`

## Sorgenti supportate

Video BonoTrot supporta:

- liste testuali con link video `.php`
- pagine da cui estrarre link candidati

Per le sorgenti testuali, l'app prova a riconoscere automaticamente:

- giorno dell'evento
- orario
- lingua del canale
- sport dell'evento

## Sviluppo

Progetto Flutter.

Avvio locale:

```bash
flutter pub get
flutter run
```

## Release APK

Quando pubblichi un tag Git come `v1.0.1`, GitHub Actions builda automaticamente la release Android e allega un APK alla GitHub Release corrispondente.
