# Video BonoTrot v1.0.14

## Novità

- i canali su embed protetto (es. `waterera.net`, `*.dynbush.net`) ora si aprono e si vedono dentro il player interno, senza passare dal browser;
- la pagina player dell'embed viene caricata nella WebView con le intestazioni `Sec-Fetch` corrette: il server rispondeva 403 / "Not allowed" (schermo nero) senza di esse;
- il token della verifica antabot (Cloudflare Turnstile) viene completato automaticamente in app: la POST di verifica viene deviata tramite bridge OkHttp con User-Agent da browser, perché le richieste della WebView TV venivano rifiutate;
- rimosso lo script dei popunder (finestre pubblicitarie) dalle pagine player;
- risolto l'overflow del link GitHub nel menu laterale (banner giallo "overflowed by pixels" su TV);
- retry automatico del collegamento al ritorno in app dopo "Apri nel browser".

## Build

- versione applicazione: `1.0.14`;
- build Android: `16`;
- tag release consigliato: `v1.0.14`.
