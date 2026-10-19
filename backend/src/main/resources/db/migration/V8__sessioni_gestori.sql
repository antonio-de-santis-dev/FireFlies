-- FireFlies Aperitif — V8: sessioni server-side dei gestori (FASE 3).
-- Schema copiato da spring-session-jdbc 4.1.1 (org/springframework/session/jdbc/schema-postgresql.sql),
-- gestito da Flyway invece che dall'inizializzazione automatica (spring.session.jdbc.initialize-schema=never).
-- PRINCIPAL_NAME contiene l'id dell'utente: serve a chiudere tutte le sessioni di un account (disattivazione,
-- cambio di ruolo, reset della password).

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- Ricerca degli eventi di sicurezza di un gestore (accessi, modifiche).
CREATE INDEX ix_audit_actor ON audit_logs (actor_user_id, occurred_at DESC) WHERE actor_user_id IS NOT NULL;

-- Email salvate sempre in minuscolo e senza spazi: un solo account per indirizzo.
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_email_normalized
    CHECK (email = lower(trim(email)) AND position('@' IN email) > 1);
