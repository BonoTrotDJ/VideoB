# Video BonoTrot

Video BonoTrot e un lettore video per Android TV / Google TV che apre stream da liste URL importate oppure da liste create manualmente.

## Download

- Ultima release: `https://github.com/BonoTrotDJ/VideoB/releases/latest`
- Release `v1.0.3`: `https://github.com/BonoTrotDJ/VideoB/releases/tag/v1.0.3`
- APK `v1.0.3`: `https://github.com/BonoTrotDJ/VideoB/releases/download/v1.0.3/app-release.apk`
- codice Downloader per installazione diretta: `5776063`

L'app permette di:

- creare liste manuali con nome e URL
- importare liste da URL testuali come `prog.txt`
- aggiornare una lista importata quando vuoi rileggendo la sorgente
- mostrare metadati utili per ogni evento come giorno, ora, lingua e sport
- passare alla modalità `Link` per vedere solo i link trovati, senza programmazione
- aprire il link direttamente nel player interno
- riconoscere gli eventi di calcio e mostrare gli stemmi delle squadre
- scaricare gli stemmi in background mentre la lista è già consultabile
- aggiornare gli stemmi in tempo reale senza dover cambiare schermata
- svuotare la cache loghi direttamente dal menu laterale
- creare rapidamente la lista `Sport` dal menu laterale
- versione aggiornata per la corretta visione anche su Amazon Fire TV Stick

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

Quando pubblichi un tag Git come `v1.0.3`, GitHub Actions builda automaticamente la release Android e allega un APK alla GitHub Release corrispondente.
