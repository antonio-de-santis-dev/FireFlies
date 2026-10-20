-- FireFlies Aperitif — V10 (FASE 7): statistiche anonime per giorno e fascia, registro delle pulizie notturne.
-- Solo tabelle nuove: nessun dato esistente cambia (migrazione non distruttiva).

-- Contatori anonimi (nessun nome, telefono, email, nota o riferimento) delle prenotazioni eliminate dalla pulizia:
-- restano dopo la cancellazione dei dati personali e alimentano il riepilogo della capienza per i giorni passati.
CREATE TABLE reservation_stats (
    service_date     date        NOT NULL,
    band             varchar(12) NOT NULL,
    requests         integer     NOT NULL DEFAULT 0,
    confirmed        integer     NOT NULL DEFAULT 0,
    confirmed_covers integer     NOT NULL DEFAULT 0,
    rejected         integer     NOT NULL DEFAULT 0,
    cancelled        integer     NOT NULL DEFAULT 0,
    expired          integer     NOT NULL DEFAULT 0,
    no_show          integer     NOT NULL DEFAULT 0,
    from_web         integer     NOT NULL DEFAULT 0,
    for_events       integer     NOT NULL DEFAULT 0,
    capacity_covers  integer,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_date, band),
    CONSTRAINT ck_stats_band     CHECK (band IN ('COLAZIONE', 'POMERIGGIO', 'APERITIVO', 'SERA')),
    CONSTRAINT ck_stats_counts   CHECK (requests >= 0 AND confirmed >= 0 AND confirmed_covers >= 0 AND rejected >= 0
                                        AND cancelled >= 0 AND expired >= 0 AND no_show >= 0 AND from_web >= 0
                                        AND for_events >= 0 AND confirmed <= requests),
    CONSTRAINT ck_stats_capacity CHECK (capacity_covers IS NULL OR capacity_covers >= 0)
);
COMMENT ON TABLE reservation_stats IS 'Statistiche aggregate senza dati personali: sopravvivono alla pulizia delle prenotazioni.';

-- Una riga per lavoro e giorno: rende idempotente la pulizia (una sola esecuzione riuscita al giorno, anche con il
-- doppio 02:00 del cambio d'ora o con più istanze) e conserva l'esito per i controlli.
CREATE TABLE maintenance_runs (
    job         varchar(40) NOT NULL,
    run_date    date        NOT NULL,
    status      varchar(12) NOT NULL,
    attempts    integer     NOT NULL DEFAULT 1,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    details     jsonb,
    last_error  varchar(300),
    PRIMARY KEY (job, run_date),
    CONSTRAINT ck_run_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
);
