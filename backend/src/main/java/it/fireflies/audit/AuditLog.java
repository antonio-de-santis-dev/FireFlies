package it.fireflies.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * Registro delle azioni rilevanti per la sicurezza e dei cambiamenti amministrativi (tabella {@code audit_logs}).
 * Se chiamato dentro una transazione ne fa parte: l'azione e la sua traccia si salvano insieme.
 * Regola: nei metadati mai password, token, né dati personali di clienti.
 */
@Service
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditLog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorUserId, AuditAction action, String objectType, String objectId, Map<String, ?> metadata) {
        jdbc.sql("""
                INSERT INTO audit_logs (actor_user_id, action, object_type, object_id, metadata)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """)
                .params(actorUserId, action.name(), objectType, objectId, json.writeValueAsString(metadata))
                .update();
    }
}
