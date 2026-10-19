# ADR-0002 — PostgreSQL + Flyway, invarianti nel database

- Stato: **Accettata** (PostgreSQL e Flyway: C-01) · dettagli **proposti**
- Data: 19/10/2026

## Contesto
Le regole più importanti (niente overbooking, niente doppio tavolo, niente duplicati) devono reggere anche a bug applicativi e a due gestori che cliccano insieme.

## Decisione
- PostgreSQL 17 per sviluppo e test; in produzione ≥ 17 secondo il provider.
- Le invarianti critiche sono **anche** vincoli del database: `CHECK (confirmed_covers <= capacity_covers)`, `EXCLUDE USING gist` sui tavoli attivi, indici unique parziali per duplicati e idempotenza, `CHECK (confirmation_mode = 'MANUAL')`.
- Migrazioni Flyway scritte a mano, incrementali, mai modificate dopo il rilascio; schema Spring Session incluso nelle migrazioni.
- `ddl-auto` di Hibernate sempre `validate` (mai `update`).

## Alternative scartate
- Controlli solo applicativi con `SELECT COUNT(*)`: soggetti a gare senza lock (master plan §4).
- `SERIALIZABLE` su tutte le transazioni: più ritentativi e complessità senza bisogno; basta l'update condizionale sul contatore.

## Conseguenze
- I test di integrazione richiedono PostgreSQL reale (niente H2).
- Le violazioni (`23514` check, `23505` unique, `23P01` exclusion) vengono tradotte in errori `409` comprensibili.
- Spike SQL eseguito: vedi [evidence/FASE1_SQL_SPIKE.md](../evidence/FASE1_SQL_SPIKE.md).
