# ADR-0001 — Monolite modulare Java 21 + Spring Boot 4.1

- Stato: **Accettata** (architettura: decisione C-01 del committente) · versione Boot **proposta** (P-01)
- Data: 19/10/2026

## Contesto
Un locale con 1–3 titolari e poco staff; un solo sviluppatore/agente; traffico basso con picchi serali; le prenotazioni richiedono transazioni ACID tra contatori, prenotazioni, tavoli e notifiche.

## Decisione
Un unico deploy Spring Boot con moduli interni (`reservation`, `capacity`, `dining`, `event`, `menu`, `media`, `content`, `notification`, `security`, `audit`, `common`), dipendenze aggiunte una alla volta, nessun ciclo, facciate pubbliche per modulo. Versione proposta: Spring Boot 4.1.x (ultima su Maven Central il 19/10/2026: 4.1.1), Java 21, Maven wrapper.

## Alternative scartate
- **Microservizi**: ricomprano transazioni e join che qui sono gratis (skill microservices-architecture); costi operativi sproporzionati.
- **CMS headless**: l'area gestori disegnata nel mockup e le regole di prenotazione non ci stanno.
- **Spring Boot 3.5.x**: ultima linea 3.x; scegliere oggi una linea vicina alla fine del supporto open source significherebbe migrare a breve. Da confermare sul calendario spring.io.

## Conseguenze
- Boot 4 porta Spring Framework 7, Jackson 3, Hibernate 7: la skill java-spring-review documenta fino a Boot 3.x, quindi le differenze vanno verificate sulla documentazione ufficiale durante la FASE 2.
- Confini tra moduli verificati con un test (Spring Modulith facoltativo, P-18).
