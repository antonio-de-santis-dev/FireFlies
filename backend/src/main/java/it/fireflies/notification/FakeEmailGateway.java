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
    private final List<FakeEmail> delivered = new CopyOnWriteArrayList<>();

    @Override
    public SendResult send(String to, String subject, String body, String idempotencyKey) {
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
