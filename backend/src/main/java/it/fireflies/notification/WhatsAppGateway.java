package it.fireflies.notification;

import java.util.List;

/**
 * Invio di un modello WhatsApp approvato (solo messaggi di servizio, nessun testo libero). Implementazioni:
 * {@link DisabledWhatsAppGateway} (predefinita, nessuna rete), {@link FakeWhatsAppGateway} (simulato) e
 * {@link MetaCloudWhatsAppGateway} (Cloud API ufficiale di Meta, solo modalità META_TEST verso i numeri di prova).
 */
public interface WhatsAppGateway {

    /**
     * @param toE164 numero del destinatario in formato E.164 (con «+»)
     * @param templateName nome del modello approvato su Meta
     * @param language codice lingua del modello (es. {@code it})
     * @param bodyParameters valori di {{1}}, {{2}}, … nell'ordine del modello
     * @param idempotencyKey chiave stabile tra i ritentativi automatici (Meta non la supporta: serve al finto e ai log)
     */
    SendResult sendTemplate(String toE164, String templateName, String language, List<String> bodyParameters, String idempotencyKey);

    /** Valore registrato in {@code notification_attempts.provider}. */
    String providerName();

    WhatsAppProperties.Mode mode();
}
