package it.fireflies.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 17 reale per i test di integrazione, con immagine fissata per digest (riproducibile).
 * Un solo container per tutta la suite: i test condividono il contesto Spring e quindi il bean.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    /** Stessa immagine di infra/compose.yaml. */
    public static final String IMAGE =
            "postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withEnv("TZ", "UTC");
    }
}
