package it.fireflies.db;

import static it.fireflies.support.BookingFixtures.at;
import static it.fireflies.support.BookingFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import it.fireflies.common.ConstraintViolationTranslator;
import it.fireflies.support.PostgresIntegrationTest;

/** Ogni invariante critica è anche un vincolo del database (DATA_MODEL §4): qui li si viola di proposito. */
class DatabaseConstraintsIT extends PostgresIntegrationTest {

    private void assertViolates(String constraint, Runnable statement) {
        Throwable failure = catchThrowable(statement::run);
        assertThat(failure).as("doveva violare " + constraint).isInstanceOf(DataAccessException.class);
        assertThat(ConstraintViolationTranslator.constraintName(failure)).contains(constraint);
    }

    private UUID reservation(UUID zone, UUID window, String status, String source, String phone, Instant start, Instant end) {
        return jdbc.sql("""
                INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, occupied_until,
                                          guest_count, service_window_id, capacity_zone_id, customer_name, customer_phone_e164,
                                          privacy_notice_version, privacy_ack_at)
                VALUES (?, ?, ?, DATE '2030-05-10', ?, ?, ?, 2, ?, ?, 'Cliente demo', ?, '2026-10', now())
                RETURNING id
                """)
                .params("FF-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), status, source,
                        utc(start), utc(end), "PENDING".equals(status) ? null : utc(end), window, zone, phone)
                .query(UUID.class).single();
    }

    @Test
    void registroNonSuperaLaCapienza() {
        UUID zone = fixtures.zone("Sala", 5);
        assertViolates("ck_slot_not_overbooked", () -> jdbc.sql(
                "INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers, occupied_covers) VALUES (?, ?, 5, 6)")
                .params(zone, utc(at("19:00"))).update());
    }

    @Test
    void quantiAllineatiA15Minuti() {
        UUID zone = fixtures.zone("Sala", 5);
        assertViolates("ck_slot_aligned", () -> jdbc.sql(
                "INSERT INTO capacity_slots (zone_id, slot_start, capacity_covers) VALUES (?, ?, 5)")
                .params(zone, utc(at("19:00").plusSeconds(600))).update());
    }

    @Test
    void confermaAutomaticaImpossibileInV1() {
        assertViolates("ck_policy_manual_only", () -> jdbc.sql("""
                INSERT INTO booking_policies (effective_from, confirmation_mode, min_lead_minutes, max_advance_days,
                                              max_party_size_web, pending_expiry_minutes_before_start)
                VALUES (DATE '2031-01-01', 'AUTO', 60, 60, 12, 60)
                """).update());
    }

    @Test
    void prenotazioniCoerenti() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        assertViolates("ck_res_aligned", () -> reservation(zone, window, "PENDING", "PHONE", "+390000000001",
                at("19:00").plusSeconds(300), at("21:00")));
        assertViolates("ck_res_phone", () -> reservation(zone, window, "PENDING", "PHONE", "3330000000",
                at("19:00"), at("21:00")));
        assertViolates("ck_res_occupying", () -> jdbc.sql("""
                INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, guest_count,
                                          service_window_id, customer_name, customer_phone_e164)
                VALUES ('FF-NOZONE', 'CONFIRMED', 'PHONE', DATE '2030-05-10', ?, ?, 2, ?, 'Cliente demo', '+390000000001')
                """).params(utc(at("19:00")), utc(at("21:00")), window).update());
        assertViolates("ck_res_web_privacy", () -> jdbc.sql("""
                INSERT INTO reservations (public_ref, status, source, reservation_date, starts_at, ends_at, guest_count,
                                          service_window_id, customer_name, customer_phone_e164)
                VALUES ('FF-NOPRIV', 'PENDING', 'WEB', DATE '2030-05-10', ?, ?, 2, ?, 'Cliente demo', '+390000000001')
                """).params(utc(at("19:00")), utc(at("21:00")), window).update());
    }

    @Test
    void doppioInvioDalSitoBloccatoMaNonPerIlGestore() {
        UUID zone = fixtures.zone("Sala", 10);
        UUID window = fixtures.window(zone);
        reservation(zone, window, "PENDING", "WEB", "+390000000009", at("19:00"), at("21:00"));
        assertViolates("ux_res_web_duplicate",
                () -> reservation(zone, window, "PENDING", "WEB", "+390000000009", at("19:00"), at("21:00")));
        assertThatCode(() -> reservation(zone, window, "PENDING", "PHONE", "+390000000009", at("19:00"), at("21:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void stessoTavoloMaiDueVolteNelloStessoOrario() {
        UUID zone = fixtures.zone("Sala", 20);
        UUID window = fixtures.window(zone);
        UUID table = table(zone);
        UUID first = fixtures.confirm(zone, window, at("19:00"), Duration.ofHours(2), 2);
        UUID overlapping = fixtures.confirm(zone, window, at("20:00"), Duration.ofHours(2), 2);
        UUID adjacent = fixtures.confirm(zone, window, at("21:00"), Duration.ofHours(2), 2);

        assign(first, table, at("19:00"), at("21:00"));
        assertViolates("ex_table_no_overlap", () -> assign(overlapping, table, at("20:00"), at("22:00")));
        assertThatCode(() -> assign(adjacent, table, at("21:00"), at("23:00"))).as("intervalli adiacenti").doesNotThrowAnyException();

        jdbc.sql("UPDATE reservation_table_assignments SET active = false, released_at = now() WHERE reservation_id = ?")
                .param(adjacent).update();
        jdbc.sql("UPDATE reservation_table_assignments SET active = false, released_at = now() WHERE reservation_id = ?")
                .param(first).update();
        assertThatCode(() -> assign(overlapping, table, at("20:00"), at("22:00"))).as("tavolo liberato").doesNotThrowAnyException();
    }

    @Test
    void eccezionePostiSoloConAutoreEMotivo() {
        UUID zone = fixtures.zone("Sala", 20);
        UUID reservation = fixtures.confirm(zone, fixtures.window(zone), at("19:00"), Duration.ofHours(2), 6);
        UUID table = table(zone);
        assertViolates("ck_override_complete", () -> jdbc.sql("""
                INSERT INTO reservation_table_assignments (reservation_id, table_id, occupied, seats_override)
                VALUES (?, ?, tstzrange(?, ?, '[)'), true)
                """).params(reservation, table, utc(at("19:00")), utc(at("21:00"))).update());
    }

    @Test
    void quotaEventoNonSuperabile() {
        assertViolates("ck_event_quota", () -> jdbc.sql("""
                INSERT INTO events (slug, title, kind, starts_at, ends_at, booking_mode, capacity_covers, confirmed_covers)
                VALUES ('dj-set-prova', 'DJ set di prova', 'SERATA', ?, ?, 'REQUEST', 30, 31)
                """).params(utc(at("22:00")), utc(at("23:45"))).update());
    }

    @Test
    void fotoInUsoNonCancellabile() {
        UUID media = jdbc.sql("""
                INSERT INTO media_assets (storage_key, status, mime_type, bytes, width, height, alt_text)
                VALUES ('media/prova/original', 'READY', 'image/jpeg', 1000, 800, 600, 'Foto di prova') RETURNING id
                """).query(UUID.class).single();
        UUID category = jdbc.sql("INSERT INTO menu_categories (slug, name) VALUES ('prova', 'Prova') RETURNING id")
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO menu_items (category_id, name, photo_media_id) VALUES (?, 'Voce di prova', ?)")
                .params(category, media).update();
        Throwable failure = catchThrowable(() -> jdbc.sql("DELETE FROM media_assets WHERE id = ?").param(media).update());
        assertThat(ConstraintViolationTranslator.constraintName(failure)).contains("fk_item_photo");
        jdbc.sql("DELETE FROM menu_items").update();
        jdbc.sql("DELETE FROM menu_categories").update();
    }

    @Test
    void outboxInInvioSempreConLease() {
        assertViolates("ck_outbox_lease", () -> jdbc.sql("""
                INSERT INTO notification_outbox (business_event_key, aggregate_type, aggregate_id, channel, recipient_kind,
                                                 template_code, state)
                VALUES ('prova:1', 'RESERVATION', gen_random_uuid(), 'SMS', 'CUSTOMER', 'RESERVATION_CONFIRMED', 'SENDING')
                """).update());
    }

    @Test
    void unaSolaEccezionePerGiornoEFascia() {
        jdbc.sql("INSERT INTO booking_exceptions (service_date, kind) VALUES (DATE '2030-08-15', 'CLOSED')").update();
        assertViolates("ux_exception_day_window",
                () -> jdbc.sql("INSERT INTO booking_exceptions (service_date, kind) VALUES (DATE '2030-08-15', 'CLOSED')").update());
        assertViolates("ck_exception_override", () -> jdbc.sql(
                "INSERT INTO booking_exceptions (service_date, kind) VALUES (DATE '2030-08-16', 'CAPACITY_OVERRIDE')").update());
    }

    private UUID table(UUID zone) {
        UUID area = jdbc.sql("INSERT INTO dining_areas (zone_id, name) VALUES (?, ?) RETURNING id")
                .params(zone, "Area " + UUID.randomUUID()).query(UUID.class).single();
        return jdbc.sql("INSERT INTO dining_tables (area_id, code, min_seats, max_seats) VALUES (?, 'T1', 2, 4) RETURNING id")
                .param(area).query(UUID.class).single();
    }

    private void assign(UUID reservation, UUID table, Instant from, Instant to) {
        jdbc.sql("INSERT INTO reservation_table_assignments (reservation_id, table_id, occupied) VALUES (?, ?, tstzrange(?, ?, '[)'))")
                .params(reservation, table, utc(from), utc(to)).update();
    }
}
