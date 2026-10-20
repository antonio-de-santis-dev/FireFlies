package it.fireflies.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Email finta: nessun invio reale, solo memoria e log (senza indirizzo né contenuto). */
public class FakeEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeEmailGateway.class);

    public record FakeEmail(String to, String subject, String body, String idempotencyKey, String providerMessageId) {
    }

    private final Map<String, String> idByKey = new ConcurrentHashMap<>();
    /** Esiti forzati per i test (errore temporaneo, rifiuto, esito ignoto): si consumano uno per invio. */
    private final java.util.Queue<SendResult.Outcome> scripted = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public void script(SendResult.Outcome... outcomes) {
        scripted.addAll(List.of(outcomes));
    }

    public void reset() {
        scripted.clear();
        idByKey.clear();
        delivered.clear();
    }
    private final List<FakeEmail> delivered = new CopyOnWriteArrayList<>();

    @Override
    public SendResult send(String to, String subject, String body, String idempotencyKey) {
        SendResult.Outcome forced = scripted.poll();
        if (forced != null && forced != SendResult.Outcome.ACCEPTED) {
            return new SendResult(forced, null, "FAKE_" + forced.name());
        }
        String id = idByKey.computeIfAbsent(idempotencyKey, key -> {
            String newId = "fake-email-" + UUID.randomUUID();
            delivered.add(new FakeEmail(to, subject, body, key, newId));
            log.info("EMAIL FINTA (nessun invio reale), id {}", newId);
            return newId;
        });
        return SendResult.accepted(id);
    }

    @Override
    public String providerName() {
        return "fake";
    }

    public List<FakeEmail> delivered() {
        return List.copyOf(delivered);
    }
}
