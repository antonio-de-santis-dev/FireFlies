package it.fireflies.notification;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fireflies.whatsapp.*}, valorizzate dalle variabili {@code FIREFLIES_WHATSAPP_*} (vedi application.yml e
 * WHATSAPP_CLOUD_API_REPORT.md). Nessun valore predefinito per credenziali e numeri: senza configurazione la modalità
 * è {@link Mode#DISABLED} e non parte nulla.
 * <p>
 * {@link #toString()} non contiene mai token, segreti né numeri: un log della configurazione non espone nulla.
 */
@ConfigurationProperties("fireflies.whatsapp")
public record WhatsAppProperties(
        @DefaultValue("DISABLED") Mode mode,
        String accessToken,
        String phoneNumberId,
        String wabaId,
        @DefaultValue("v21.0") String graphApiVersion,
        @DefaultValue("") String testRecipients,
        String webhookVerifyToken,
        String appSecret,
        @DefaultValue("https://graph.facebook.com") URI graphBaseUrl,
        @DefaultValue("fireflies_prenotazione_confermata") String templateName,
        @DefaultValue("it") String templateLanguage,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration requestTimeout) {

    /**
     * <ul>
     * <li>{@code DISABLED} (predefinita): nessun invio, nessuna chiamata esterna; le conferme funzionano come sempre;</li>
     * <li>{@code FAKE}: invio simulato e registrato, nessuna rete;</li>
     * <li>{@code META_TEST}: API reale di Meta, solo sviluppo e solo verso i numeri di prova in elenco;</li>
     * <li>{@code PRODUCTION}: riservata, oggi blocca l'avvio (serve un'autorizzazione separata del titolare).</li>
     * </ul>
     */
    public enum Mode { DISABLED, FAKE, META_TEST, PRODUCTION }

    static final Pattern GRAPH_VERSION = Pattern.compile("v\\d{1,3}\\.\\d{1,2}");
    static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z0-9_]{1,512}");
    static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(_[A-Z]{2})?");
    static final Pattern PHONE_NUMBER_ID = Pattern.compile("\\d{5,30}");

    /** Numeri di prova autorizzati in META_TEST, solo cifre (prefisso internazionale incluso, senza «+»). */
    public List<String> allowedRecipients() {
        if (testRecipients == null || testRecipients.isBlank()) {
            return List.of();
        }
        return Arrays.stream(testRecipients.split("[,;\\n]+")).map(WhatsAppProperties::digits).filter(s -> !s.isEmpty())
                .distinct().toList();
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean hasPhoneNumberId() {
        return phoneNumberId != null && !phoneNumberId.isBlank();
    }

    public boolean hasWabaId() {
        return wabaId != null && !wabaId.isBlank();
    }

    public boolean hasWebhookVerifyToken() {
        return webhookVerifyToken != null && !webhookVerifyToken.isBlank();
    }

    public boolean hasAppSecret() {
        return appSecret != null && !appSecret.isBlank();
    }

    /** Il webhook risponde solo se verify token e App Secret ci sono entrambi; altrimenti 404. */
    public boolean webhookEnabled() {
        return hasWebhookVerifyToken() && hasAppSecret();
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    @Override
    public String toString() {
        return "WhatsAppProperties[mode=" + mode + ", accessToken=" + (hasAccessToken() ? "***" : "-")
                + ", phoneNumberId=" + (hasPhoneNumberId() ? "***" : "-") + ", wabaId=" + (hasWabaId() ? "***" : "-")
                + ", graphApiVersion=" + graphApiVersion + ", testRecipients=" + allowedRecipients().size()
                + ", webhookVerifyToken=" + (hasWebhookVerifyToken() ? "***" : "-") + ", appSecret=" + (hasAppSecret() ? "***" : "-")
                + ", templateName=" + templateName + ", templateLanguage=" + templateLanguage + "]";
    }
}
