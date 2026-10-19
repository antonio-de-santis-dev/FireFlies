package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import it.fireflies.common.ConstraintViolationTranslator;
import it.fireflies.support.Concurrency;
import it.fireflies.support.Concurrency.Result;
import it.fireflies.support.PostgresIntegrationTest;

/**
 * Garanzie dello schema dell'outbox (ADR-0005), verificate con l'SQL che userà il worker di FASE 7.
 * Si verifica la presa in carico unica per lease, NON una consegna «exactly-once».
 */
class OutboxClaimIT extends PostgresIntegrationTest {

    private static final String CLAIM = """
            UPDATE notification_outbox
               SET state = 'SENDING', lease_token = ?, locked_until = now() + interval '2 minutes', attempts = attempts + 1
             WHERE id IN (SELECT id FROM notification_outbox
                           WHERE state = 'PENDING' AND next_attempt_at <= now()
                           ORDER BY next_attempt_at
                           LIMIT 10
                             FOR UPDATE SKIP LOCKED)
            RETURNING id
            """;

    private UUID enqueue(String key) {
        return jdbc.sql("""
                INSERT INTO notification_outbox (business_event_key, aggregate_type, aggregate_id, channel, recipient_kind,
                                                 recipient, template_code, payload)
                VALUES (?, 'RESERVATION', gen_random_uuid(), 'SMS', 'CUSTOMER', '+390000000001', 'RESERVATION_CONFIRMED',
                        '{"riferimento":"FF-PROVA1"}'::jsonb)
                RETURNING id
                """).param(key).query(UUID.class).single();
    }

    @Test
    void unFattoUnaSolaNotifica() {
        enqueue("reservation:1:CONFIRMED:CUSTOMER:SMS");
        Throwable duplicate = catchThrowable(() -> enqueue("reservation:1:CONFIRMED:CUSTOMER:SMS"));
        assertThat(ConstraintViolationTranslator.constraintName(duplicate)).contains("ux_outbox_business_key");
    }

    @Test
    void quattroWorkerNessunMessaggioPresoDueVolte() {
        for (int i = 0; i < 200; i++) {
            enqueue("reservation:" + i + ":CONFIRMED:CUSTOMER:SMS");
        }
        List<Callable<List<UUID>>> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            workers.add(() -> {
                List<UUID> mine = new ArrayList<>();
                List<UUID> batch;
                do {
                    UUID token = UUID.randomUUID();
                    batch = tx.execute(s -> jdbc.sql(CLAIM).param(token).query(UUID.class).list());
                    mine.addAll(batch);
                } while (!batch.isEmpty());
                return mine;
            });
        }
        List<Result<List<UUID>>> results = Concurrency.run(workers);

        assertThat(results).allMatch(Result::ok);
        List<UUID> all = results.stream().flatMap(r -> r.value().stream()).toList();
        assertThat(all).hasSize(200).doesNotHaveDuplicates();
    }

    @Test
    void workerConLeaseScadutoNonRegistraLEsito() {
        UUID id = enqueue("reservation:42:CONFIRMED:CUSTOMER:SMS");
        UUID slowWorker = UUID.randomUUID();
        UUID recoveryWorker = UUID.randomUUID();
        jdbc.sql("UPDATE notification_outbox SET state = 'SENDING', lease_token = ?, locked_until = now() - interval '1 second' WHERE id = ?")
                .params(slowWorker, id).update();

        int reclaimed = jdbc.sql("""
                UPDATE notification_outbox SET lease_token = ?, locked_until = now() + interval '2 minutes'
                 WHERE id = ? AND state = 'SENDING' AND locked_until < now()
                """).params(recoveryWorker, id).update();
        int lateWrite = jdbc.sql("UPDATE notification_outbox SET state = 'SENT' WHERE id = ? AND lease_token = ?")
                .params(id, slowWorker).update();
        int ownWrite = jdbc.sql("UPDATE notification_outbox SET state = 'SENT', lease_token = NULL, locked_until = NULL WHERE id = ? AND lease_token = ?")
                .params(id, recoveryWorker).update();

        assertThat(reclaimed).isEqualTo(1);
        assertThat(lateWrite).as("token vecchio: nessun effetto").isZero();
        assertThat(ownWrite).isEqualTo(1);
    }

    @Test
    void tentativoRegistratoPrimaDellaChiamataAlProvider() {
        UUID id = enqueue("reservation:7:CONFIRMED:CUSTOMER:SMS");
        jdbc.sql("INSERT INTO notification_attempts (outbox_id, attempt_no, provider, idempotency_key_used) VALUES (?, 1, 'fake', ?)")
                .params(id, id.toString()).update();
        // Esito non ancora registrato = worker interrotto durante la chiamata → in FASE 7 sarà trattato come incerto.
        Long open = jdbc.sql("SELECT count(*) FROM notification_attempts WHERE outbox_id = ? AND finished_at IS NULL AND outcome IS NULL")
                .param(id).query(Long.class).single();
        assertThat(open).isEqualTo(1);
        Throwable inconsistent = catchThrowable(() -> jdbc.sql(
                "UPDATE notification_attempts SET outcome = 'ACCEPTED' WHERE outbox_id = ?").param(id).update());
        assertThat(ConstraintViolationTranslator.constraintName(inconsistent)).contains("ck_attempt_finished");
    }
}
