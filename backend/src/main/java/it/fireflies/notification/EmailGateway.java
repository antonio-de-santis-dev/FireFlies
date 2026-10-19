package it.fireflies.notification;

/** Invio di email transazionali. In V1 solo {@link FakeEmailGateway}; il provider reale si sceglie in FASE 7. */
public interface EmailGateway {

    SendResult send(String to, String subject, String body, String idempotencyKey);

    String providerName();
}
