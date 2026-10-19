package it.fireflies.media;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sceglie lo storage delle foto e lo verifica all'avvio:
 * <ul>
 *   <li>{@code filesystem}: solo profili {@code local} e {@code test}; le varianti sono servite dal backend su {@code /media/**};</li>
 *   <li>{@code s3}: endpoint, bucket e chiavi obbligatori (variabili {@code FIREFLIES_S3_*}), altrimenti l'avvio si ferma.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class MediaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MediaConfiguration.class);

    @Bean
    MediaStorage mediaStorage(MediaProperties properties, Environment environment) {
        MediaStorage storage = switch (properties.storage().toLowerCase()) {
            case "filesystem" -> filesystem(properties, environment);
            case "s3" -> s3(properties.s3());
            default -> throw new IllegalStateException("fireflies.media.storage «" + properties.storage()
                    + "» non valido: usa 'filesystem' (sviluppo) oppure 's3' (Cloudflare R2, MinIO).");
        };
        log.info("Foto: storage {}", storage.describe());
        return storage;
    }

    private static MediaStorage filesystem(MediaProperties properties, Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("In produzione le foto vanno su uno storage S3-compatibile (ADR-0004): "
                    + "imposta FIREFLIES_MEDIA_STORAGE=s3 e le variabili FIREFLIES_S3_*.");
        }
        String root = properties.filesystem().root();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Imposta fireflies.media.filesystem.root (cartella locale delle foto).");
        }
        return new FileSystemMediaStorage(Path.of(root));
    }

    private static MediaStorage s3(MediaProperties.S3 s3) {
        requireText(s3.endpoint(), "FIREFLIES_S3_ENDPOINT");
        requireText(s3.bucket(), "FIREFLIES_S3_BUCKET");
        requireText(s3.accessKeyId(), "FIREFLIES_S3_ACCESS_KEY_ID");
        requireText(s3.secretAccessKey(), "FIREFLIES_S3_SECRET_ACCESS_KEY");
        return new S3MediaStorage(s3);
    }

    private static void requireText(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Storage S3 senza configurazione: imposta la variabile " + variable + ".");
        }
    }

    /** Sviluppo e test: il backend serve le varianti dalla cartella locale, con la stessa cache del CDN. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = "fireflies.media.storage", havingValue = "filesystem", matchIfMissing = true)
    WebMvcConfigurer localMediaFiles(MediaProperties properties) {
        String location = Path.of(properties.filesystem().root()).toAbsolutePath().normalize().toUri().toString();
        String folder = location.endsWith("/") ? location : location + "/";
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/media/**")
                        .addResourceLocations(folder)
                        .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
            }
        };
    }
}
