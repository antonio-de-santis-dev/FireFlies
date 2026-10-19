package it.fireflies.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Garanzie «nessun SMS/email reale» (TEST_STRATEGY S-1, S-2, S-5). */
class NotificationGatewaysConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class, NotificationGatewaysConfiguration.class);

    @Test
    void perDefaultSoloGatewayFinti() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SmsGateway.class)).isInstanceOf(FakeSmsGateway.class);
            assertThat(ctx.getBean(EmailGateway.class)).isInstanceOf(FakeEmailGateway.class);
        });
    }

    @Test
    void providerSmsRealeConProfiloTestBloccaLAvvio() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("fireflies.notifications.sms.provider=un-provider-reale")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("ammesso solo 'fake'"));
    }

    @Test
    void providerSmsRealeConProfiloLocalBloccaLAvvio() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("fireflies.notifications.sms.provider=un-provider-reale")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void anchePerProdNessunAdattatoreRealeInV1() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("fireflies.notifications.sms.provider=un-provider-reale")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("FASE 7"));
    }

    @Test
    void providerEmailRealeInTestBloccaLAvvio() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("fireflies.notifications.email.provider=un-provider-reale")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void ilFakeRispettaLaChiaveDiIdempotenza() {
        FakeSmsGateway sms = new FakeSmsGateway();
        SendResult first = sms.send("+390000000001", "Prova", "chiave-1");
        SendResult retry = sms.send("+390000000001", "Prova", "chiave-1");
        assertThat(retry.providerMessageId()).isEqualTo(first.providerMessageId());
        assertThat(sms.delivered()).hasSize(1);
        assertThat(FakeSmsGateway.mask("+390000000001")).isEqualTo("***001");
    }
}
