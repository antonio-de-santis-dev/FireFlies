# ADR-0006 — Prenotazioni V1: conferma manuale, capienza a contatori, tavoli facoltativi

- Stato: **Accettata** nei principi (C-02, C-03, C-04, C-05) · modello a contatori per fascia **proposto** (P-03)
- Data: 19/10/2026

## Decisione
- Ogni richiesta (ordinaria o evento) nasce `PENDING`; solo un gestore la conferma. `confirmation_mode` vincolato a `MANUAL` nel database.
- La capienza è in **coperti**, decisa dal gestore per fascia prenotabile (e per data con eccezioni) e per evento. Ogni fascia-giorno ed ogni evento hanno un contatore `capacity_buckets` aggiornato con un update condizionale atomico.
- Le richieste in attesa non consumano coperti.
- Il tavolo è facoltativo, scelto a mano, protetto dal vincolo di esclusione.
- Gli eventi bloccano per default le richieste ordinarie sovrapposte; i conflitti con prenotazioni esistenti vengono mostrati, mai risolti automaticamente.

## Alternative scartate
- **Capienza calcolata dai tavoli**: richiede proprio l'algoritmo di assegnazione rimandato a V2.
- **Capienza per intervalli sovrapposti (occupazione minuto per minuto)**: più precisa ma complessa da spiegare e da configurare; non serve a un locale che ragiona per fasce.
- **`SELECT … FOR UPDATE` sulla fascia + `COUNT(*)`**: corretto, ma più lento e più facile da sbagliare dell'update condizionale; inoltre non lascia un vincolo dichiarativo.

## Conseguenze
- Una prenotazione lunga che sconfina nella fascia successiva conta solo nella fascia d'arrivo: limite accettato e documentato.
- Passare in V2 a capienza per area o ad auto-conferma richiederà nuove colonne/valori e una nuova approvazione, senza riscrivere le tabelle esistenti.
