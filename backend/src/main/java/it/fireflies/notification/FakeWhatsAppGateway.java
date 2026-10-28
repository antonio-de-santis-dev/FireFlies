package it.fireflies.notification;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modalità FAKE: WhatsApp simulato, nessuna rete e nessuna approvazione di Meta necessaria. Registra i messaggi in
 * memoria (per i test e la verifica manuale) e nel log con il numero mascherato. La stessa chiave di idempotenza non
 * produce un secondo messaggio. Gli esiti si possono forzare per provare errori come quelli di Meta.
 */
public class FakeWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeWhatsAppGateway.class);

    public record FakeWhatsApp(String toE164, String templateName, String language, List<String> parameters,
            String idempotencyKey, String providerMessageId) {
    }

    private final Map<String, String> idByKey = new ConcurrentHashMap<>();
    private final Queue<SendResult> scripted = new ConcurrentLinkedQueue<>();
    private final List<FakeWhatsApp> delivered = new CopyOnWriteArrayList<>();

    /** Esiti forzati per i test (es. {@code ACCOUNT_LOCKED} permanente): si consumano uno per invio. */
    public void script(SendResult... results) {
        scripted.addAll(List.of(results));
    }

    public void reset() {
        scripted.clear();
        idByKey.clear();
        delivered.clear();
    }

    @Override
    public SendResult sendTemplate(String toE164, String templateName, String language, List<String> bodyParameters,
            String idempotencyKey) {
        SendResult forced = scripted.poll();
        if (forced != null && forced.outcome() != SendResult.Outcome.ACCEPTED) {
            return forced;
        }
        String id = idByKey.computeIfAbsent(idempotencyKey, key -> {
            String newId = "wamid.FAKE-" + UUID.randomUUID();
            delivered.add(new FakeWhatsApp(toE164, templateName, language, List.copyOf(bodyParameters), key, newId));
            log.info("WHATSAPP FINTO (nessun invio reale) a {}: modello {}, id {}", FakeSmsGateway.mask(toE164), templateName, newId);
            return newId;
        });
        return SendResult.accepted(id);
    }

    @Override
    public String providerName() {
        return "fake";
    }

    @Override
    public WhatsAppProperties.Mode mode() {
        return WhatsAppProperties.Mode.FAKE;
    }

    public List<FakeWhatsApp> delivered() {
        return List.copyOf(delivered);
    }
}
