package it.fireflies.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code fireflies.media.*}: dove finiscono le foto (ADR-0004). Le credenziali S3/R2 arrivano solo da variabili
 * d'ambiente o dal secret manager; qui non c'è alcun valore predefinito per chiavi o bucket.
 *
 * @param storage       {@code filesystem} (sviluppo e test) oppure {@code s3} (Cloudflare R2, MinIO, AWS S3)
 * @param publicBaseUrl base degli URL pubblici delle varianti (in produzione il dominio CDN del bucket)
 */
@ConfigurationProperties("fireflies.media")
public record MediaProperties(
        @DefaultValue("filesystem") String storage,
        @DefaultValue("/media") String publicBaseUrl,
        @DefaultValue Filesystem filesystem,
        @DefaultValue S3 s3) {

    /** @param root cartella locale delle varianti (servita dal backend su {@code /media/**}) */
    public record Filesystem(String root) {
    }

    /**
     * @param endpoint        es. {@code https://<account>.r2.cloudflarestorage.com}
     * @param region          {@code auto} per R2
     * @param pathStyle       indirizzi {@code endpoint/bucket/chiave} (necessario per MinIO, accettato da R2)
     */
    public record S3(String endpoint, @DefaultValue("auto") String region, String bucket, String accessKeyId,
            String secretAccessKey, @DefaultValue("true") boolean pathStyle) {
    }

    public boolean usesFilesystem() {
        return "filesystem".equalsIgnoreCase(storage);
    }

    public String publicUrl(String key) {
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + "/" + key;
    }
}
