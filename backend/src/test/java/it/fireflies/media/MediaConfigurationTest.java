package it.fireflies.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Scelta dello storage all'avvio: in produzione niente cartella locale e niente S3 senza configurazione. */
class MediaConfigurationTest {

    private final MediaConfiguration configuration = new MediaConfiguration();

    @Test
    void cartellaLocaleAmmessaInSviluppo(@TempDir java.nio.file.Path dir) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        MediaStorage storage = configuration.mediaStorage(properties("filesystem", dir.toString(), emptyS3()), env);
        assertThat(storage).isInstanceOf(FileSystemMediaStorage.class);
    }

    @Test
    void cartellaLocaleVietataInProduzione(@TempDir java.nio.file.Path dir) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> configuration.mediaStorage(properties("filesystem", dir.toString(), emptyS3()), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FIREFLIES_MEDIA_STORAGE=s3");
    }

    @Test
    void s3SenzaChiaviBloccaLAvvio() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        MediaProperties.S3 s3 = new MediaProperties.S3("https://esempio.r2.cloudflarestorage.com", "auto", "foto", "", "", true);
        assertThatThrownBy(() -> configuration.mediaStorage(properties("s3", null, s3), env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FIREFLIES_S3_ACCESS_KEY_ID");
    }

    @Test
    void valoreSconosciutoBloccaLAvvio() {
        assertThatThrownBy(() -> configuration.mediaStorage(properties("dropbox", null, emptyS3()), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void urlPubbliciDallaBaseConfigurata() {
        assertThat(properties("s3", null, emptyS3()).publicUrl("a/b/w480.jpg")).isEqualTo("/media/a/b/w480.jpg");
        MediaProperties cdn = new MediaProperties("s3", "https://foto.esempio.it/", new MediaProperties.Filesystem(null), emptyS3());
        assertThat(cdn.publicUrl("a/b/w480.jpg")).isEqualTo("https://foto.esempio.it/a/b/w480.jpg");
    }

    @Test
    void chiaviFuoriDallaCartellaRifiutate(@TempDir java.nio.file.Path dir) {
        FileSystemMediaStorage storage = new FileSystemMediaStorage(dir);
        assertThatThrownBy(() -> storage.put("../fuori.jpg", new byte[] {1}, "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
        storage.put("a/b/w480.jpg", new byte[] {1}, "image/jpeg");
        assertThat(dir.resolve("a/b/w480.jpg")).exists();
        storage.delete("a/b/w480.jpg");
        assertThat(dir.resolve("a")).as("cartelle vuote rimosse").doesNotExist();
    }

    private static MediaProperties properties(String storage, String root, MediaProperties.S3 s3) {
        return new MediaProperties(storage, "/media", new MediaProperties.Filesystem(root), s3);
    }

    private static MediaProperties.S3 emptyS3() {
        return new MediaProperties.S3(null, "auto", null, null, null, true);
    }
}
