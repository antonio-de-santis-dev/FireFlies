# ADR-0004 — Immagini su storage S3-compatibile (candidato Cloudflare R2), upload firmato

- Stato: **Accettata** (C-07) · formato varianti **proposto** (P-17)
- Data: 19/10/2026

## Decisione
- Interfaccia `MediaStorage` con due implementazioni: `S3MediaStorage` (AWS SDK v2, endpoint configurabile → R2 o MinIO) e `FileSystemMediaStorage` (solo sviluppo/test).
- Flusso: `upload-intent` (record `PENDING_UPLOAD`, chiave casuale, URL PUT firmato 5 min, content-type e dimensione vincolati) → il browser carica direttamente → `finalize` (HEAD, lettura dei primi byte per il tipo reale, dimensioni, ricodifica, varianti 480/960/1600, EXIF/GPS rimossi) → `READY`.
- Nel DB solo metadati e chiavi; nessun URL firmato salvato. URL pubblici costruiti da una base configurabile (dominio CDN).
- Upload non finalizzati eliminati da un job orario.
- Credenziali dello storage solo nel backend, da variabili d'ambiente.

## Alternative scartate
- Byte nel database: vietato dal master plan.
- MongoDB/GridFS: vietato.
- Upload attraverso il backend: consuma banda e memoria della JVM; resta come ripiego se il provider non supportasse gli URL firmati.

## Conseguenze
- Il bucket R2 richiede una regola CORS per il PUT dal dominio del sito.
- Ricodifica in Java: lettura WebP con TwelveMonkeys; output JPEG in V1 (WebP/AVIF in uscita dopo, P-17).
