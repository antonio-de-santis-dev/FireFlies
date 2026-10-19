package it.fireflies.media;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * File dello storage da cancellare. Si cancellano subito dopo il commit; se lo storage non risponde la chiave
 * finisce in {@code media_orphans} e il job la riprova (ogni ora, con attesa crescente fino a 24 ore).
 * Un file orfano occupa spazio ma non è mai visibile dal sito: nessun contenuto lo referenzia più.
 */
@Component
public class MediaOrphans {

    private static final Logger log = LoggerFactory.getLogger(MediaOrphans.class);

    private final MediaStorage storage;
    private final JdbcClient jdbc;
    private final Clock clock;

    MediaOrphans(MediaStorage storage, JdbcClient jdbc, Clock clock) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void deleteNowOrLater(Collection<String> keys, String reason) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Storage non raggiungibile: {} da cancellare più tardi ({})", key, reason);
                jdbc.sql("""
                        INSERT INTO media_orphans (storage_key, reason, last_error, created_at, next_try_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (storage_key) DO NOTHING
                        """)
                        .params(key, reason, truncate(e.getMessage()), now(), now().plusMinutes(5))
                        .update();
            }
        }
    }

    /** Riprova le cancellazioni scadute. Restituisce quante chiavi sono state eliminate. */
    @Scheduled(initialDelayString = "${fireflies.media.orphan-sweep:PT1H}", fixedDelayString = "${fireflies.media.orphan-sweep:PT1H}")
    public int sweep() {
        List<String> due = jdbc.sql("SELECT storage_key FROM media_orphans WHERE next_try_at <= ? ORDER BY next_try_at LIMIT 200")
                .param(now()).query(String.class).list();
        int deleted = 0;
        for (String key : due) {
            try {
                storage.delete(key);
                jdbc.sql("DELETE FROM media_orphans WHERE storage_key = ?").param(key).update();
                deleted++;
            } catch (RuntimeException e) {
                jdbc.sql("""
                        UPDATE media_orphans
                           SET attempts = attempts + 1, last_error = ?,
                               next_try_at = ? + least(interval '24 hours', interval '1 hour' * (attempts + 1))
                         WHERE storage_key = ?
                        """)
                        .params(truncate(e.getMessage()), now(), key).update();
            }
        }
        if (!due.isEmpty()) {
            log.info("Pulizia storage: {} file eliminati su {} in attesa", deleted, due.size());
        }
        return deleted;
    }

    private java.time.OffsetDateTime now() {
        return Instant.now(clock).atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String message) {
        String m = message == null ? "errore sconosciuto" : message;
        return m.length() > 250 ? m.substring(0, 250) : m;
    }
}
