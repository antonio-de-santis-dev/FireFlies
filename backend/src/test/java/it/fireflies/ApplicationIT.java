package it.fireflies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import it.fireflies.notification.EmailGateway;
import it.fireflies.notification.FakeEmailGateway;
import it.fireflies.notification.FakeSmsGateway;
import it.fireflies.notification.SmsGateway;
import it.fireflies.support.PostgresIntegrationTest;

/** Avvio completo con PostgreSQL reale: health, profilo di test, solo gateway finti. */
@AutoConfigureMockMvc
class ApplicationIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SmsGateway smsGateway;

    @Autowired
    EmailGateway emailGateway;

    @Test
    void healthEReadinessUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void dettagliDellHealthNonEsposti() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void endpointActuatorNonPubbliciChiusi() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }

    @Test
    void nessunProviderRealeDiNotifiche() {
        assertThat(smsGateway).isInstanceOf(FakeSmsGateway.class);
        assertThat(smsGateway.providerName()).isEqualTo("fake");
        assertThat(emailGateway).isInstanceOf(FakeEmailGateway.class);
    }
}
