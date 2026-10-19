package it.fireflies.demo;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import it.fireflies.content.ContentService;

/**
 * Con il profilo {@code prod} l'applicazione non parte se:
 * <ul>
 *   <li>è richiesto il caricamento dei dati demo ({@code fireflies.demo.seed=true});</li>
 *   <li>il database contiene dati demo ({@code site_settings.demo_content}), ad esempio perché è stato copiato
 *       da un ambiente di sviluppo.</li>
 * </ul>
 * Meglio un avvio fallito con un messaggio chiaro che prezzi o avvisi finti davanti ai clienti. Il controllo avviene
 * dopo le migrazioni e prima che il server HTTP accetti richieste.
 */
@Component
@Profile("prod")
class DemoDataGuard implements SmartInitializingSingleton {

    private final DemoProperties properties;
    private final ContentService content;

    DemoDataGuard(DemoProperties properties, ContentService content) {
        this.properties = properties;
        this.content = content;
    }

    @Override
    public void afterSingletonsInstantiated() {
        check(properties.seed(), content.hasDemoContent());
    }

    static void check(boolean seedRequested, boolean demoInDatabase) {
        if (seedRequested) {
            throw new IllegalStateException("fireflies.demo.seed=true con profilo prod: i dati demo non possono "
                    + "essere caricati in produzione. Togli la variabile FIREFLIES_DEMO_SEED.");
        }
        if (demoInDatabase) {
            throw new IllegalStateException("Il database di produzione contiene dati DIMOSTRATIVI "
                    + "(site_settings.demo_content = true): usa un database nuovo oppure ripulisci i contenuti demo "
                    + "come descritto in docs/operations/LOCAL_SETUP.md §8 prima di avviare.");
        }
    }
}
