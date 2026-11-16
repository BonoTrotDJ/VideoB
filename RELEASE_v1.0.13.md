# Video BonoTrot v1.0.13

## Novità

- risolto: i link degli eventi restavano bloccati finché la sorgente non veniva aperta una volta in un browser esterno;
- i cookie delle richieste OkHttp (risoluzione stream, proxy locale, download pagine) ora vengono conservati e ri-inviati, invece di essere scartati ad ogni chiamata;
- cookie condivisi tra WebView e OkHttp e persistiti su disco: l'autorizzazione ottenuta resta valida anche dopo il riavvio dell'app;
- le verifiche di sicurezza antabot (es. Cloudflare) vengono lasciate completare nella WebView interna, con messaggio "Verifica di sicurezza in corso", invece di essere interrotte da un reload immediato;
- al ritorno in app dopo "apri nel browser" il collegamento viene riprovato automaticamente, senza dover uscire e rientrare nell'evento.

## Build

- versione applicazione: `1.0.13`;
- build Android: `15`;
- tag release consigliato: `v1.0.13`.
