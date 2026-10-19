# ADR-0006 — Prenotazioni V1: conferma manuale, capienza per presenza contemporanea, tavoli facoltativi

- Stato: **Accettata** nei principi (C-02 … C-05) · rev. 2 del 19/10/2026: modello di capienza **proposto** (P-03)
- Sostituisce la rev. 1 «capienza a contatori per fascia di arrivo» (file rinominato), ora **superata**.

## Contesto
La rev. 1 contava ogni prenotazione solo nella fascia d'arrivo e dava agli eventi un contatore indipendente. Due difetti segnalati dal committente:
1. prenotazioni in fasce diverse ma sovrapposte nel tempo (19:00–21:00 e 20:00–22:00) potevano superare insieme la capienza reale;
2. evento e servizio ordinario con contatori separati potevano sommare più persone di quante ne contenga il locale.

## Decisione
1. **Zone di capienza** (`capacity_zones`): spazi fisicamente separati con capienza fisica decisa dal titolare; predefinito una sola zona. Le aree dei tavoli appartengono a una zona e non hanno capienza propria.
2. **Registro di occupazione a quanti da 15 minuti** (`capacity_slots`): per zona e quanto, capienza effettiva e persone confermate presenti. Una prenotazione confermata occupa tutti i quanti di `[inizio, fine)`.
3. Conferma, modifica, rilascio: una transazione che blocca i quanti coinvolti **in ordine di tempo** (`FOR UPDATE`), controlla e aggiorna; `CHECK (occupied ≤ capacity)` come ultima difesa.
4. **Eventi sulla stessa capienza**: le prenotazioni per evento occupano gli stessi quanti; la capienza dell'evento è solo una quota aggiuntiva sulla riga evento. Evento **esclusivo** (predefinito) marca i suoi quanti e impedisce il servizio ordinario; evento limitato a un'area possibile solo su una zona fisicamente separata.
5. Cambi di capienza serializzati con le conferme tramite lock sulla riga della zona (`FOR SHARE` / `FOR UPDATE`); mai annullamenti automatici.
6. Conferma sempre manuale (`CHECK (confirmation_mode = 'MANUAL')`); tavoli manuali e facoltativi; posti insufficienti bloccati salvo eccezione OWNER auditata.

## Alternative valutate
| Alternativa | Perché no |
|---|---|
| Contatore per fascia di arrivo (rev. 1) | non vede le sovrapposizioni tra fasce |
| `SELECT … FOR UPDATE` di un «giorno» + calcolo del picco con una query di sweep sulle prenotazioni | corretto, ma l'invariante vive solo nel codice (nessun vincolo dichiarativo), serializza l'intera giornata e si complica con eventi che passano la mezzanotte |
| Vincolo di esclusione o range per persone | PostgreSQL non ha vincoli di «somma su intervalli sovrapposti» |
| `SERIALIZABLE` + ricalcolo | ritentativi frequenti nei picchi e diagnosi più difficile |
| Capienza derivata dai tavoli | richiede l'assegnazione automatica (V2) |

## Conseguenze
- Tabelle: −1 (`capacity_buckets`) +2 (`capacity_zones`, `capacity_slots`). Colonne nuove: `reservations.occupied_until`, `events.exclusive`, `events.confirmed_covers`, `events.zone_id`, campi di eccezione posti sulle assegnazioni.
- Vincoli operativi V1 dichiarati in BOOKING_RULES §3.6 (granularità 15 min, durata fissa, clienti senza prenotazione da coprire con un margine).
- Verificato su PostgreSQL 17: [spike 2](../evidence/FASE1_SQL_SPIKE_2.md) — 60 conferme parallele casuali senza superare la capienza né deadlock; gare evento esclusivo/ordinarie sempre con un solo vincitore.
- Passare in V2 a durate variabili, hold di posti per gli eventi o auto-conferma non richiede di riscrivere queste tabelle.
