# Video BonoTrot

Versione: `1.0.0+1`

Download per il test:

- `https://fromsmash.com/Video-BonoTrot-Google`

## Cos'e

Video BonoTrot e un lettore video per Google TV / Android TV che apre stream da liste manuali oppure da liste importate da URL.

## Come scaricarla

1. apri il link `https://fromsmash.com/Video-BonoTrot-Google`
2. scarica il pacchetto di test
3. estrai il file `VideoB.apk`

## Come installarla su Google TV / Android TV

### Metodo 1: installazione dal televisore

1. scarica il pacchetto dal link
2. estrai `VideoB.apk`
3. copia il file sul televisore con chiavetta USB, Google Drive, Telegram, Send Files to TV o app simili
4. apri un file manager sul televisore
5. seleziona `VideoB.apk`
6. conferma l'installazione

### Metodo 2: installazione con ADB

Se il televisore e il computer sono sulla stessa rete:

1. attiva `Opzioni sviluppatore`
2. attiva `Debug USB` oppure `Debug di rete`
3. collega il televisore via ADB
4. installa l'APK con:

```bash
adb install VideoB.apk
```

Se l'app e gia presente, aggiorna senza disinstallare:

```bash
adb install -r VideoB.apk
```

## Nota importante sui dati

Per non perdere le liste salvate:

- evita di disinstallare l'app prima di aggiornarla
- usa un aggiornamento sopra la versione esistente
- se usi ADB, preferisci `adb install -r VideoB.apk`

La disinstallazione completa su Android TV cancella i dati interni dell'app.

## Per utenti GitHub

Questa guida va bene come base per permettere agli utenti GitHub di provare l'app.

Per presentarla meglio nel repository, ti consiglio anche di aggiungere:

- 2 o 3 screenshot dell'app
- una breve descrizione delle funzioni principali
- una nota chiara che spiega che si tratta di una build di test per Google TV / Android TV
