# ADR-0002 — PostgreSQL + Flyway, invarianti nel database

- Stato: **Accettata** (PostgreSQL e Flyway: C-01) · dettagli **proposti**
- Data: 19/10/2026

## Contesto
Le regole più importanti (niente overbooking, niente doppio tavolo, niente duplicati) devono reggere anche a bug applicativi e a due gestori che cliccano insieme.

## Decisione
- PostgreSQL 17 per sviluppo e test; in produzione ≥ 17 secondo il provider.
- Le invarianti critiche sono **anche** vincoli del database: `CHECK (occupied_covers <= capacity_covers)` su ogni quanto del registro di occupazione, `CHECK (confirmed_covers <= capacity_covers)` sulla quota evento, `EXCLUDE USING gist` sui tavoli attivi, indici unique parziali per duplicati e idempotenza, `CHECK (confirmation_mode = 'MANUAL')`, allineamento a 15 minuti degli orari.
- Le verifiche che coinvolgono più righe (somma su intervalli, eventi esclusivi) usano **lock in ordine deterministico** (prenotazione → evento → zona → quanti per orario → tavoli per id; DECISION_LOG T-05) dentro una transazione `READ COMMITTED` (rev. 2, vedi ADR-0006).
- Migrazioni Flyway scritte a mano, incrementali, mai modificate dopo il rilascio; schema Spring Session incluso nelle migrazioni.
- `ddl-auto` di Hibernate sempre `validate` (mai `update`).

## Alternative scartate
- Controlli solo applicativi con `SELECT COUNT(*)`: soggetti a gare senza lock (master plan §4).
- `SERIALIZABLE` su tutte le transazioni: più ritentativi e complessità senza bisogno; basta l'update condizionale sul contatore.

## Conseguenze
- I test di integrazione richiedono PostgreSQL reale (niente H2).
- Le violazioni (`23514` check, `23505` unique, `23P01` exclusion) vengono tradotte in errori `409` comprensibili.
- Spike SQL eseguiti: [evidence/FASE1_SQL_SPIKE.md](../evidence/FASE1_SQL_SPIKE.md) (vincoli di base) e [evidence/FASE1_SQL_SPIKE_2.md](../evidence/FASE1_SQL_SPIKE_2.md) (occupazione per intervalli, eventi esclusivi, outbox).
- Il primo spike verificava anche il vecchio contatore per fascia (`capacity_buckets`), oggi sostituito: quei risultati restano validi solo come prova del meccanismo di update condizionale.
