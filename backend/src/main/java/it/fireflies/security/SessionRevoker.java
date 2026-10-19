package it.fireflies.security;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Chiude le sessioni attive di un account (disattivazione, cambio di ruolo, nuova password):
 * il ruolo in sessione non può restare più ampio di quello sul database.
 */
@Component
public class SessionRevoker {

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions;

    SessionRevoker(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessions) {
        this.sessions = sessions;
    }

    /** @return numero di sessioni chiuse */
    public int revokeAll(UUID userId, String exceptSessionId) {
        FindByIndexNameSessionRepository<? extends Session> repository = sessions.getIfAvailable();
        if (repository == null) {
            return 0; // avvio senza web (procedura amministrativa): non ci sono sessioni da chiudere
        }
        int closed = 0;
        for (String sessionId : repository.findByPrincipalName(userId.toString()).keySet()) {
            if (!sessionId.equals(exceptSessionId)) {
                repository.deleteById(sessionId);
                closed++;
            }
        }
        return closed;
    }
}
