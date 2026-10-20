# Requisiti prima del deploy

> Creato il 20/10/2026 (approvazione della FASE 5, decisione C-29). Ogni voce va chiusa, con evidenza, prima della
> messa in produzione (FASE 8). Nessuna di queste richiede Redis, Kafka o altri componenti complessi.

| # | Requisito | Stato | Evidenza richiesta |
|---|---|---|---|
| V-01 | IP reale del client dietro un reverse proxy affidabile; strategia di rate limiting in produzione | **chiuso in configurazione e test (FASE 8)** | `application-prod.yml`: `RemoteIpValve` con fiducia solo nelle reti private/loopback, ultimo IP non fidato; Caddy unico ingresso, backend senza porte pubbliche (`infra/compose.prod.yaml`); `ProxyHeadersIT`: un `X-Forwarded-For` falsificato non aggira il limite di 5 richieste/ora; decisione: **una sola istanza** (limiti in memoria). Resta da controllare sul server reale con `curl` dopo l'installazione |
| V-02 | Comportamento della dashboard quando più richieste in attesa competono per gli stessi posti | aperto | prova d'uso con i gestori su richieste concorrenti; eventuale indicatore nell'elenco |
| V-03 | Dati demo sostituiti da capienza, tavoli, orari, contatti ed eventi reali | aperto | database nuovo (mai una copia del demo), dati inseriti dai titolari, controllo a vista |
| V-04 | Informativa privacy, conservazione dei dati, protezione di telefoni, email e note | in parte (FASE 7) | **fatto**: pulizia notturna (prenotazioni dopo 1 mese con statistiche anonime, eventi dopo 2, notifiche finali dopo 90 giorni, audit dopo 12 mesi). **Manca**: testo validato, conferma dei periodi con il titolare/consulente, backup cifrati con la stessa conservazione, log controllati |
| V-05 | Notifiche reali (FASE 7) | sospeso | provider email/SMS scelto, costi autorizzati, credenziali nel secret manager, dominio del mittente, testi dei modelli approvati; finché manca, `fireflies.notifications.worker.enabled=false` in prod (l'avvio si rifiuta se acceso) |
| V-06 | Pulizia notturna in produzione (FASE 7) | strumenti pronti (FASE 8) | fuso Europe/Rome del server indifferente (cron con zona esplicita); controllo di `maintenance_runs` nel monitoraggio (FAILED ripetuti = allarme); backup prima della prima esecuzione su dati reali | — `infra/scripts/check-health.sh` controlla `maintenance_runs` e l'età del backup |

| V-07 | Pagina pubblica definitiva (FASE 8) | aperto | `frontend/index.html`: togliere `noindex, nofollow`, «Prototipo» dal titolo e «Contenuti da confermare» dalla descrizione, solo con i contenuti reali approvati |
| V-08 | Infrastruttura (FASE 8) | proposta pronta, nulla acquistato | decisioni D-1…D-6 di `DEPLOY.md` (server, dominio, R2, chiave dei backup); collaudo sul dominio reale (DEPLOY.md §7) |
| V-09 | Backup in produzione (FASE 8) | procedura provata in isolamento | cron giornaliero, copia fuori sede **cifrata**, conservazione 30 giorni, primo ripristino di prova sul server (`evidence/RESTORE_DRILL.md`) |
| V-10 | Numero di telefono del locale (FASE 8) | aperto | inserito dal titolare in Impostazioni: senza numero «Organizza un evento / compleanno» mostra solo l'avviso e Instagram |

Altri punti già annotati nei report di fase:
- date di fine supporto di Spring Boot 4.1 (FASE2_REPORT A-1);
- strumenti di sviluppo con avvisi (`vite` 5 / `esbuild`: solo server di sviluppo, nessun effetto sui file pubblicati; aggiornamento a Vite 8 da pianificare, FASE8_REPORT §11);
- bucket R2 reale, budget, lifecycle e monitoraggio dello storage (MASTER_PLAN §8);
- provider email/SMS approvato e costi autorizzati (Q-07); in assenza, le notifiche restano nell'outbox (V-05).
