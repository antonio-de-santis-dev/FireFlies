# ADR-0003 — Sessioni server-side (Spring Session JDBC) + CSRF, stessa origine

- Stato: **Accettata** (sessioni server-side, OWNER/STAFF: master plan §2) · tabella JDBC e durata **proposte** (P-15, P-27)
- Data: 19/10/2026

## Decisione
- Login con email e password; hash bcrypt (costo 12) tramite `DelegatingPasswordEncoder` (Argon2id possibile in seguito senza migrazione dei dati).
- Sessione salvata in PostgreSQL (Spring Session JDBC): sopravvive ai riavvii, nessun Redis.
- Cookie `HttpOnly; Secure; SameSite=Lax`; id di sessione rigenerato al login; scadenza per inattività.
- CSRF con cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN` (pattern ufficiale per SPA).
- Frontend e API sulla **stessa origine** → niente CORS aperto.
- Blocco account temporaneo dopo 5 tentativi falliti + limite per IP.
- Nessun account creato dalle migrazioni; primo OWNER da comando una tantum.

## Alternative scartate
- **JWT nel browser**: revoca e logout più difficili, rischio XSS sul token; nessun vantaggio per una sola applicazione.
- **Sessione solo in memoria**: ogni riavvio disconnette i gestori.

## Conseguenze
- Tabelle `spring_session*` nella migrazione; job di pulizia sessioni scadute (incluso in Spring Session).
- Se in futuro il frontend fosse su un altro dominio servirebbe rivedere `SameSite` e CORS.
