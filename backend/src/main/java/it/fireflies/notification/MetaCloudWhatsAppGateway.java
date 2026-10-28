package it.fireflies.notification;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Adattatore della WhatsApp Cloud API ufficiale di Meta ({@code POST /{versione}/{phone-number-id}/messages}, modello
 * approvato, autenticazione Bearer). Usato solo in modalità META_TEST, in sviluppo, verso i numeri di prova in elenco:
 * un altro destinatario è rifiutato <b>prima</b> di qualunque chiamata.
 * <p>
 * Classificazione degli esiti (ADR-0005):
 * <ul>
 * <li>risposta 2xx con l'id del messaggio → ACCEPTED (id salvato per il webhook);</li>
 * <li>errori di Meta sicuramente definitivi (account bloccato 131031, token scaduto o non valido 190, modello mancante o
 * non approvato 132001/132000/…, destinatario non autorizzato 131030, numero non valido, permessi) → REJECTED_PERMANENT;</li>
 * <li>limiti di frequenza (4, 80007, 130429, 131048, 131056, HTTP 429) e servizio non disponibile (2, 131016), connessione
 * non riuscita → TRANSIENT_ERROR: la richiesta non è stata accettata, si ritenta con attesa crescente;</li>
 * <li>timeout della risposta, errore interno di Meta (1, 131000, HTTP 5xx), risposta illeggibile → UNKNOWN: Meta non
 * offre una chiave di idempotenza e il messaggio potrebbe essere partito, quindi niente ritentativo automatico
 * (rischio di doppio invio documentato in WHATSAPP_CLOUD_API_REPORT.md §Errori).</li>
 * </ul>
 * Nei log e nel database finiscono solo codici tecnici: mai il token, il testo del messaggio, il messaggio d'errore di
 * Meta (può contenere il numero) o il numero in chiaro.
 */
final class MetaCloudWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(MetaCloudWhatsAppGateway.class);
    private static final Pattern E164 = Pattern.compile("\\+?\\d{8,15}");
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;

    private static final Set<Integer> RATE_LIMIT = Set.of(4, 80007, 130429, 131048, 131056);
    private static final Set<Integer> UNAVAILABLE = Set.of(2, 131016);
    private static final Set<Integer> INTERNAL = Set.of(1, 131000);
    private static final Map<Integer, String> PERMANENT = Map.ofEntries(
            Map.entry(131031, "ACCOUNT_LOCKED"),
            Map.entry(131030, "RECIPIENT_NOT_ALLOWED"),
            Map.entry(131026, "UNDELIVERABLE"),
            Map.entry(131047, "REENGAGEMENT_REQUIRED"),
            Map.entry(131051, "UNSUPPORTED_MESSAGE"),
            Map.entry(131042, "PAYMENT_ISSUE"),
            Map.entry(131008, "INVALID_PARAMETER"),
            Map.entry(131009, "INVALID_PARAMETER"),
            Map.entry(100, "INVALID_PARAMETER"),
            Map.entry(132000, "TEMPLATE_PARAM_MISMATCH"),
            Map.entry(132001, "TEMPLATE_NOT_FOUND"),
            Map.entry(132005, "TEMPLATE_REJECTED"),
            Map.entry(132007, "TEMPLATE_REJECTED"),
            Map.entry(132012, "TEMPLATE_PARAM_FORMAT"),
            Map.entry(132015, "TEMPLATE_PAUSED"),
            Map.entry(132016, "TEMPLATE_DISABLED"),
            Map.entry(133010, "PHONE_NOT_REGISTERED"),
            Map.entry(368, "POLICY_BLOCKED"),
            Map.entry(10, "PERMISSION_DENIED"),
            Map.entry(200, "PERMISSION_DENIED"),
            Map.entry(3, "PERMISSION_DENIED"));

    private final WhatsAppProperties config;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Set<String> allowed;

    MetaCloudWhatsAppGateway(WhatsAppProperties config, HttpClient http, ObjectMapper json) {
        this.config = config;
        this.http = http;
        this.json = json;
        this.allowed = Set.copyOf(config.allowedRecipients());
    }

    @Override
    public SendResult sendTemplate(String toE164, String templateName, String language, List<String> bodyParameters,
            String idempotencyKey) {
        if (toE164 == null || !E164.matcher(toE164).matches()) {
            return permanent("INVALID_RECIPIENT");
        }
        String to = WhatsAppProperties.digits(toE164);
        if (!allowed.contains(to)) {
            // META_TEST: solo i numeri di prova dichiarati; nessuna chiamata a Meta per gli altri
            log.info("WhatsApp META_TEST: destinatario {} non in FIREFLIES_WHATSAPP_TEST_RECIPIENTS, invio bloccato",
                    FakeSmsGateway.mask(toE164));
            return permanent("RECIPIENT_NOT_ALLOWED");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(to, templateName, language, bodyParameters), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // connessione mai aperta: la richiesta non è arrivata a Meta
            return logged(new SendResult(SendResult.Outcome.TRANSIENT_ERROR, null, "NETWORK_CONNECT"), toE164);
        } catch (HttpTimeoutException e) {
            return logged(new SendResult(SendResult.Outcome.UNKNOWN, null, "TIMEOUT"), toE164);
        } catch (IOException e) {
            return logged(new SendResult(SendResult.Outcome.UNKNOWN, null, "NETWORK_ERROR"), toE164);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return logged(new SendResult(SendResult.Outcome.UNKNOWN, null, "INTERRUPTED"), toE164);
        }
        return logged(classify(response.statusCode(), response.body()), toE164);
    }

    private URI endpoint() {
        String base = config.graphBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/" + config.graphApiVersion() + "/" + config.phoneNumberId() + "/messages");
    }

    private String body(String to, String templateName, String language, List<String> parameters) {
        List<Map<String, String>> params = parameters.stream().map(p -> Map.of("type", "text", "text", p)).toList();
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", language));
        template.put("components", List.of(Map.of("type", "body", "parameters", params)));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messaging_product", "whatsapp");
        message.put("recipient_type", "individual");
        message.put("to", to);
        message.put("type", "template");
        message.put("template", template);
        return json.writeValueAsString(message);
    }

    /** Risposta HTTP di Meta → esito. Pubblico nel package per i test unitari. */
    SendResult classify(int status, String body) {
        JsonNode root = parse(body);
        if (status >= 200 && status < 300) {
            String id = root == null ? "" : root.path("messages").path(0).path("id").asString("");
            return id.isBlank() || id.length() > 120 ? new SendResult(SendResult.Outcome.UNKNOWN, null, "META_NO_MESSAGE_ID")
                    : SendResult.accepted(id);
        }
        JsonNode error = root == null ? null : root.path("error");
        int code = error == null || !error.path("code").isNumber() ? -1 : error.path("code").asInt();
        int subcode = error == null ? -1 : error.path("error_subcode").asInt(-1);
        if (code == 190) {
            return permanent((subcode == 463 ? "TOKEN_EXPIRED" : "AUTH_INVALID") + ":190");
        }
        if (PERMANENT.containsKey(code)) {
            return permanent(PERMANENT.get(code) + ":" + code);
        }
        if (RATE_LIMIT.contains(code) || (code < 0 && status == 429)) {
            return new SendResult(SendResult.Outcome.TRANSIENT_ERROR, null, "RATE_LIMITED" + (code < 0 ? "" : ":" + code));
        }
        if (UNAVAILABLE.contains(code) || (code < 0 && status == 503)) {
            return new SendResult(SendResult.Outcome.TRANSIENT_ERROR, null, "SERVICE_UNAVAILABLE" + (code < 0 ? "" : ":" + code));
        }
        if (code < 0 && (status == 401 || status == 403)) {
            return permanent("AUTH_INVALID");
        }
        if (INTERNAL.contains(code) || status >= 500 || code < 0) {
            // errore interno o risposta illeggibile: Meta potrebbe aver accettato il messaggio
            return new SendResult(SendResult.Outcome.UNKNOWN, null, "META_ERROR" + (code < 0 ? ":HTTP_" + status : ":" + code));
        }
        return permanent("META_ERROR:" + code);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_RESPONSE_CHARS) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SendResult permanent(String code) {
        return new SendResult(SendResult.Outcome.REJECTED_PERMANENT, null, code);
    }

    private static SendResult logged(SendResult result, String toE164) {
        if (result.outcome() == SendResult.Outcome.ACCEPTED) {
            log.info("WhatsApp META_TEST a {}: accettato da Meta", FakeSmsGateway.mask(toE164));
        } else {
            log.warn("WhatsApp META_TEST a {}: {} ({})", FakeSmsGateway.mask(toE164), result.outcome(), result.errorCode());
        }
        return result;
    }

    @Override
    public String providerName() {
        return "meta-test";
    }

    @Override
    public WhatsAppProperties.Mode mode() {
        return WhatsAppProperties.Mode.META_TEST;
    }
}
