-- FireFlies Aperitif — V11 (FASE 8, performance): indice per la presa in carico del worker delle notifiche, che legge
-- PENDING e FAILED scaduti in ordine di next_attempt_at (ix_outbox_ready copre solo PENDING). Misura su 100.000 righe:
-- da 25,4 ms (scansione completa) a 0,1 ms. Solo un indice nuovo: nessun dato cambia.
CREATE INDEX ix_outbox_due ON notification_outbox (next_attempt_at, id) WHERE state IN ('PENDING', 'FAILED');
