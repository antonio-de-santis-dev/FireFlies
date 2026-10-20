# Requisiti prima del deploy

> Creato il 20/10/2026 (approvazione della FASE 5, decisione C-29). Ogni voce va chiusa, con evidenza, prima della
> messa in produzione (FASE 8). Nessuna di queste richiede Redis, Kafka o altri componenti complessi.

| # | Requisito | Stato | Evidenza richiesta |
|---|---|---|---|
| V-01 | IP reale del client dietro un reverse proxy affidabile; strategia di rate limiting in produzione | aperto | configurazione del proxy (sovrascrive `X-Forwarded-For`), backend non raggiungibile direttamente, fiducia limitata agli indirizzi del proxy; test con header falsificato; decisione «una istanza» oppure contatori su PostgreSQL |
| V-02 | Comportamento della dashboard quando più richieste in attesa competono per gli stessi posti | aperto | prova d'uso con i gestori su richieste concorrenti; eventuale indicatore nell'elenco |
| V-03 | Dati demo sostituiti da capienza, tavoli, orari, contatti ed eventi reali | aperto | database nuovo (mai una copia del demo), dati inseriti dai titolari, controllo a vista |
| V-04 | Informativa privacy, conservazione dei dati, protezione di telefoni, email e note | in parte (FASE 7) | **fatto**: pulizia notturna (prenotazioni dopo 1 mese con statistiche anonime, eventi dopo 2, notifiche finali dopo 90 giorni, audit dopo 12 mesi). **Manca**: testo validato, conferma dei periodi con il titolare/consulente, backup cifrati con la stessa conservazione, log controllati |
| V-05 | Notifiche reali (FASE 7) | sospeso | provider email/SMS scelto, costi autorizzati, credenziali nel secret manager, dominio del mittente, testi dei modelli approvati; finché manca, `fireflies.notifications.worker.enabled=false` in prod (l'avvio si rifiuta se acceso) |
| V-06 | Pulizia notturna in produzione (FASE 7) | da verificare | fuso Europe/Rome del server indifferente (cron con zona esplicita); controllo di `maintenance_runs` nel monitoraggio (FAILED ripetuti = allarme); backup prima della prima esecuzione su dati reali |

Altri punti già annotati nei report di fase:
- date di fine supporto di Spring Boot 4.1 (FASE2_REPORT A-1);
- bucket R2 reale, budget, lifecycle e monitoraggio dello storage (MASTER_PLAN §8);
- provider email/SMS approvato e costi autorizzati (Q-07); in assenza, le notifiche restano nell'outbox (V-05).
