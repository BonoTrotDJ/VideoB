# VideoB

VideoB e un lettore video per Android TV / Google TV che apre stream da liste URL importate oppure da liste create manualmente.

L'app permette di:

- creare liste manuali con nome e URL
- importare liste da URL testuali come `prog.txt`
- aggiornare una lista importata quando vuoi rileggendo la sorgente
- mostrare metadati utili per ogni evento come giorno, ora, lingua e sport
- aprire il link direttamente nel player interno

## Uso

Nel menu laterale puoi creare due tipi di lista:

- `Manuale`: aggiungi a mano i link video che vuoi salvare
- `Da URL`: inserisci un URL sorgente e lascia che VideoB importi i link compatibili

Per le liste importate, puoi premere `Aggiorna Lista` in qualsiasi momento per ricaricare i contenuti dalla sorgente.

Esempio di sorgente supportata:

- `https://sportsonline.st`

## Sorgenti supportate

VideoB supporta:

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
