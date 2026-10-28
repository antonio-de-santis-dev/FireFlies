package it.fireflies.notification;

import java.util.List;

/** Modalità DISABLED: non contatta nessuno. Il dispatcher non lo chiama nemmeno (messaggio SKIPPED WHATSAPP_DISABLED). */
final class DisabledWhatsAppGateway implements WhatsAppGateway {

    @Override
    public SendResult sendTemplate(String toE164, String templateName, String language, List<String> bodyParameters,
            String idempotencyKey) {
        return new SendResult(SendResult.Outcome.REJECTED_PERMANENT, null, "WHATSAPP_DISABLED");
    }

    @Override
    public String providerName() {
        return "disabled";
    }

    @Override
    public WhatsAppProperties.Mode mode() {
        return WhatsAppProperties.Mode.DISABLED;
    }
}
