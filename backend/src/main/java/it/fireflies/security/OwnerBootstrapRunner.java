package it.fireflies.security;

import java.io.Console;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Procedura amministrativa per il primo titolare (docs/operations/LOCAL_SETUP.md §3):
 * <pre>
 * java -jar fireflies-backend.jar --spring.main.web-application-type=none \
 *      --fireflies.bootstrap.create-owner=true \
 *      --fireflies.bootstrap.email=titolare@dominio.it --fireflies.bootstrap.display-name="Nome Cognome"
 * </pre>
 * La password si inserisce a richiesta (non compare a schermo) oppure arriva dalla variabile d'ambiente
 * {@code FIREFLIES_BOOTSTRAP_PASSWORD}; non è accettata come argomento, che resterebbe nella cronologia della shell.
 * Al termine l'applicazione si chiude.
 */
@Component
@ConditionalOnProperty(name = "fireflies.bootstrap.create-owner", havingValue = "true")
class OwnerBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrapRunner.class);

    private final OwnerBootstrap bootstrap;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    OwnerBootstrapRunner(OwnerBootstrap bootstrap, Environment environment, ConfigurableApplicationContext context) {
        this.bootstrap = bootstrap;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (environment.containsProperty("fireflies.bootstrap.password")
                || args.containsOption("FIREFLIES_BOOTSTRAP_PASSWORD")) {
            throw new IllegalStateException("Non passare la password come argomento: usa la richiesta interattiva "
                    + "o la variabile d'ambiente FIREFLIES_BOOTSTRAP_PASSWORD.");
        }
        String email = environment.getRequiredProperty("fireflies.bootstrap.email");
        String displayName = environment.getRequiredProperty("fireflies.bootstrap.display-name");
        String password = readPassword();

        var id = bootstrap.createFirstOwner(email, displayName, password);
        log.info("Primo titolare creato: {} (id {}). Ora puoi accedere all'area gestori.", email, id);

        if (environment.getProperty("fireflies.bootstrap.exit", Boolean.class, true)) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private String readPassword() {
        String fromEnv = environment.getProperty("FIREFLIES_BOOTSTRAP_PASSWORD");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Nessun terminale interattivo: imposta FIREFLIES_BOOTSTRAP_PASSWORD.");
        }
        char[] first = console.readPassword("Password del titolare (almeno %d caratteri): ", PasswordPolicy.MIN_LENGTH);
        char[] second = console.readPassword("Ripeti la password: ");
        try {
            if (first == null || !Arrays.equals(first, second)) {
                throw new IllegalStateException("Le due password non coincidono.");
            }
            return new String(first);
        } finally {
            if (first != null) {
                Arrays.fill(first, ' ');
            }
            if (second != null) {
                Arrays.fill(second, ' ');
            }
        }
    }
}
