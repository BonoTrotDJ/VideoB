# Video BonoTrot v1.0.15

## Novità

- recupero automatico completo quando un canale parte male alla prima apertura: non serve più uscire dal player e riaprire il link a mano;
- la pagina player dell'embed che resta giù ("STREAM IS OFFLINE", "Not allowed", "CAPTCHA FAILED") viene ricaricata rapidamente (max 2 volte), poi il player si riavvia passando dal percorso completo di risoluzione dello stream, identico alla riapertura manuale del canale;
- se il canale resta irraggiungibile anche dopo il riavvio, appare "Canale momentaneamente offline, riprova più tardi." (contatore anti-loop);
- il player nativo riprova da solo se lo stream non è ancora attivo (403/404 sui manifest), prima di mostrare l'errore;
- il messaggio "Errore Connessione" resta a schermo 4 secondi e poi il player si chiude da solo, tornando alla lista;
- nuovo messaggio "Token in acquisizione, la trasmissione sarà pronta tra pochi secondi" durante la verifica di sicurezza, visibile finché il video non riproduce davvero;
- nuova schermata di avvio: sfondo verde scuro con l'icona dell'app e la scritta "BonoTrot" al centro, al posto dello schermo bianco (nessun flash bianco anche in modalità scura).

## Build

- versione applicazione: `1.0.15`;
- build Android: `22`;
- tag release consigliato: `v1.0.15`.
