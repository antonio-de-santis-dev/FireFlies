package it.fireflies.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMS finto: non contatta nessuna rete. Registra i messaggi in memoria e nel log (numero mascherato).
 * Simula un provider che rispetta la chiave di idempotenza: la stessa chiave non produce un secondo messaggio.
 */
public class FakeSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeSmsGateway.class);

    public record FakeSms(String toE164, String text, String idempotencyKey, String providerMessageId) {
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
    private final List<FakeSms> delivered = new CopyOnWriteArrayList<>();

    @Override
    public SendResult send(String toE164, String text, String idempotencyKey) {
        SendResult.Outcome forced = scripted.poll();
        if (forced != null && forced != SendResult.Outcome.ACCEPTED) {
            return new SendResult(forced, null, "FAKE_" + forced.name());
        }
        String id = idByKey.computeIfAbsent(idempotencyKey, key -> {
            String newId = "fake-sms-" + UUID.randomUUID();
            delivered.add(new FakeSms(toE164, text, key, newId));
            log.info("SMS FINTO (nessun invio reale) a {}: {} caratteri, id {}", mask(toE164), text.length(), newId);
            return newId;
        });
        return SendResult.accepted(id);
    }

    @Override
    public String providerName() {
        return "fake";
    }

    public List<FakeSms> delivered() {
        return List.copyOf(delivered);
    }

    static String mask(String phone) {
        return phone == null || phone.length() < 4 ? "***" : "***" + phone.substring(phone.length() - 3);
    }
}
