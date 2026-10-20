# Misure di performance — FASE 8 (20/10/2026)

Stessa macchina per tutte le misure. Prima = commit `d3eaebc`, dopo = FASE 8 con la divisione del codice e l'indice V11.
Comandi: `node scripts/e2e.mjs tests/perf.spec.ts` (risultati in `tests/perf-results.json`); query con `EXPLAIN ANALYZE`.

## Sito pubblico (telefono 390 px, latenza 150 ms, 1,6 Mbit/s, CPU ×4)
| Misura | Prima | Dopo | Differenza |
|---|---|---|---|
| JavaScript trasferito (gzip) | 108.224 B | 78.380 B | −27,6 % |
| CSS trasferito (gzip) | 21.360 B | 21.360 B | = |
| Immagini della home | 66.204 B | 66.204 B | = (già varianti ridotte con `srcset`) |
| Richieste totali | 8 | 8 | = |
| DOMContentLoaded | 1.145 ms | 917 ms | −20 % |
| Contenuto visibile (titolo della home) | 2.190 ms | 1.970 ms | −10 % |
| Chiamate API all'avvio | 4 (site, menu, gallery, events) | 4 | nessun duplicato |

Build (`npm run build`): prima un unico `index.js` da 378 kB (108 kB gzip); dopo `index.js` 254 kB (78 kB gzip) +
`AdminArea.js` 123 kB (32 kB gzip, solo per `/gestione`, scaricato in anticipo già dalla pagina di accesso) + `Login.js` 4 kB.

## Area gestori (panoramica, rete locale)
| Misura | Prima | Dopo (3 esecuzioni) |
|---|---|---|
| Pronta («Richieste da gestire» visibile) | 190 ms | 425 / 161 / 189 ms (la prima con la JVM appena avviata) |
| Chiamate API | 12, nessun duplicato | 12, nessun duplicato |

Prima del precaricamento dalla pagina di accesso la stessa misura era 438 ms: senza precaricamento la divisione del
codice avrebbe rallentato la prima apertura dell'area gestori.

## Database (PostgreSQL 17, 200.000 prenotazioni e 100.000 notifiche sintetiche in un database isolato, poi eliminato)
| Query | Piano | Tempo |
|---|---|---|
| Richieste: conteggi per stato da oggi | scansione parallela | 28,8 ms |
| Richieste: pagina «In attesa» da oggi | `ix_res_pending_start` | 15,4 ms |
| Calendario di oggi | indice | 0,7 ms |
| Riepilogo di 14 giorni | `ix_res_date_status` | 3,4 ms |
| Ricerca per nome (tutte le date) | scansione parallela | 68,0 ms |
| Pulizia: lotto di prenotazioni vecchie | chiave primaria + `ix_outbox_aggregate` | 3,6 ms |
| Worker: presa in carico, **prima** | scansione parallela | 25,4 ms |
| Worker: presa in carico, **dopo V11** (`ix_outbox_due`) | indice | 0,1 ms |

Con la conservazione di un mese le tabelle reali restano molto più piccole: nessun altro indice è giustificato. La ricerca
per nome resta una scansione; un indice trigram (`pg_trgm`) servirebbe solo con volumi molto maggiori.

I vincoli del database hanno rifiutato i primi dati sintetici incoerenti (stati che occupano posti senza registro, notifiche
saltate senza motivo, richieste dal sito senza privacy): prova indiretta che le difese dello schema funzionano.
