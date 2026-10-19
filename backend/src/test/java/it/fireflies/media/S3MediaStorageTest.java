package it.fireflies.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Adattatore S3 (Cloudflare R2 / MinIO) contro un finto endpoint S3 locale: niente account cloud, niente costi.
 * Verifica che le richieste siano quelle che R2 si aspetta (percorso, header, firma SigV4) e che gli errori del
 * provider diventino {@link MediaStorage.StorageException}. Le chiavi sono valori casuali di test.
 */
class S3MediaStorageTest {

    record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private S3MediaStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes()));
            int code = status.get();
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        storage = new S3MediaStorage(new MediaProperties.S3(endpoint, "auto", "foto-fireflies",
                "test-" + UUID.randomUUID(), "test-" + UUID.randomUUID(), true));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void putFirmatoConTipoECacheImmutabile() {
        byte[] content = {1, 2, 3, 4, 5};
        storage.put("abc/123/w480.jpg", content, "image/jpeg");

        Received put = received.getFirst();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/foto-fireflies/abc/123/w480.jpg");
        assertThat(put.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(put.header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(put.header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=test-");
        assertThat(put.body()).containsExactly(content);
    }

    @Test
    void deleteSullaChiave() {
        status.set(204);
        storage.delete("abc/123/w480.jpg");
        assertThat(received.getFirst().method()).isEqualTo("DELETE");
        assertThat(received.getFirst().path()).isEqualTo("/foto-fireflies/abc/123/w480.jpg");
    }

    @Test
    void erroreDelProviderDiventaStorageException() {
        status.set(503);
        assertThatThrownBy(() -> storage.put("k/w480.jpg", new byte[] {1}, "image/jpeg"))
                .isInstanceOf(MediaStorage.StorageException.class);
        assertThat(received).as("l'SDK riprova prima di arrendersi").hasSizeGreaterThan(1);
    }

    @Test
    void endpointIrraggiungibileDiventaStorageException() {
        server.stop(0);
        assertThatThrownBy(() -> storage.delete("k/w480.jpg")).isInstanceOf(MediaStorage.StorageException.class);
    }
}
