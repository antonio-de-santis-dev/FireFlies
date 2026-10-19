package it.fireflies.notification;

/**
 * Invio di SMS. In V1 l'unica implementazione è {@link FakeSmsGateway}: un provider reale si aggiunge solo
 * in FASE 7, dopo l'autorizzazione dei costi, e richiede comunque profilo {@code prod} e interruttore del titolare.
 */
public interface SmsGateway {

    /**
     * @param idempotencyKey chiave stabile tra i ritentativi automatici dello stesso messaggio
     */
    SendResult send(String toE164, String text, String idempotencyKey);

    String providerName();
}
