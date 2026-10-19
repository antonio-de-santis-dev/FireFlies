package it.fireflies.media;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import it.fireflies.audit.AuditAction;
import it.fireflies.audit.AuditLog;
import it.fireflies.common.ApiException;

/**
 * Libreria foto. Ordine delle operazioni, scelto perché un errore non lasci mai il sito incoerente:
 * <ol>
 *   <li>verifica e ricodifica in memoria ({@link ImageProcessor}): un file non valido non tocca né storage né database;</li>
 *   <li>scrittura delle varianti nello storage con chiavi nuove: se fallisce, si cancella quanto scritto e si risponde 503;</li>
 *   <li>transazione sul database: se fallisce, le varianti appena scritte vengono cancellate;</li>
 *   <li>solo dopo il commit si cancellano i file vecchi (sostituzione o eliminazione), con nuovi tentativi se serve.</li>
 * </ol>
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Usage(String type, String label) {
    }

    public record MediaView(UUID id, String alt, String credit, int width, int height, String src, String srcset,
            String originalMimeType, int originalBytes, boolean inGallery, Integer galleryOrder,
            List<VariantView> variants, List<Usage> usages, Instant createdAt) {
    }

    public record VariantView(String size, int width, int height, String mimeType, int bytes, String url) {
    }

    private final ImageProcessor processor = new ImageProcessor();
    /** Una ricodifica alla volta: una foto da 50 megapixel occupa centinaia di MB durante l'elaborazione. */
    private final Semaphore processing = new Semaphore(1, true);

    private final MediaStore store;
    private final MediaStorage storage;
    private final MediaOrphans orphans;
    private final MediaCatalog catalog;
    private final MediaProperties properties;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final Clock clock;

    MediaService(MediaStore store, MediaStorage storage, MediaOrphans orphans, MediaCatalog catalog,
            MediaProperties properties, TransactionTemplate tx, AuditLog audit, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.orphans = orphans;
        this.catalog = catalog;
        this.properties = properties;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock;
    }

    public List<MediaView> library() {
        Map<UUID, List<MediaStore.Usage>> usages = store.allUsages();
        return store.library().stream().map(a -> view(a, usages.getOrDefault(a.id(), List.of()))).toList();
    }

    public MediaView upload(UUID actorId, byte[] data, String altText, String credit) {
        String alt = requireAlt(altText);
        String cleanCredit = optional(credit, 120, "credit");
        ImageProcessor.Processed image = process(data);
        UUID id = UUID.randomUUID();
        String prefix = newPrefix(id);
        List<String> written = write(prefix, image);
        try {
            tx.executeWithoutResult(status -> {
                store.insertReady(id, prefix, image, alt, cleanCredit, actorId, Instant.now(clock));
                audit.record(actorId, AuditAction.MEDIA_UPLOADED, "MEDIA", id.toString(), Map.of(
                        "bytes", image.bytes(), "width", image.width(), "height", image.height(),
                        "variants", image.variants().size()));
            });
        } catch (RuntimeException e) {
            orphans.deleteNowOrLater(written, "UPLOAD_ROLLBACK");
            throw e;
        }
        return get(id);
    }

    /** Nuovo file per una foto esistente: stesso id, stesso testo alternativo, stessi usi. */
    public MediaView replace(UUID actorId, UUID id, byte[] data) {
        if (!store.exists(id)) {
            throw notFound();
        }
        ImageProcessor.Processed image = process(data);
        String prefix = newPrefix(id);
        List<String> written = write(prefix, image);
        List<String> previous = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                MediaStore.AssetRow current = store.lock(id).orElseThrow(MediaService::notFound);
                current.variants().forEach(v -> previous.add(v.storageKey()));
                store.replaceFile(id, prefix, image, Instant.now(clock));
                audit.record(actorId, AuditAction.MEDIA_REPLACED, "MEDIA", id.toString(), Map.of(
                        "bytes", image.bytes(), "width", image.width(), "height", image.height()));
            });
        } catch (RuntimeException e) {
            orphans.deleteNowOrLater(written, "REPLACE_ROLLBACK");
            throw e;
        }
        orphans.deleteNowOrLater(previous, "REPLACED");
        return get(id);
    }

    public MediaView update(UUID actorId, UUID id, String altText, String credit, Boolean inGallery) {
        tx.executeWithoutResult(status -> {
            MediaStore.AssetRow current = store.lock(id).orElseThrow(MediaService::notFound);
            String alt = altText == null ? current.altText() : requireAlt(altText);
            String cleanCredit = credit == null ? current.credit() : optional(credit, 120, "credit");
            boolean gallery = inGallery == null ? current.inGallery() : inGallery;
            store.updateDetails(id, alt, cleanCredit, gallery);
            Map<String, Object> changed = new LinkedHashMap<>();
            if (!alt.equals(current.altText())) {
                changed.put("altText", true);
            }
            if (gallery != current.inGallery()) {
                changed.put("inGallery", gallery);
            }
            audit.record(actorId, AuditAction.MEDIA_UPDATED, "MEDIA", id.toString(), changed);
        });
        return get(id);
    }

    public List<MediaView> reorder(UUID actorId, List<UUID> ordered) {
        tx.executeWithoutResult(status -> {
            store.reorder(ordered);
            audit.record(actorId, AuditAction.MEDIA_REORDERED, "MEDIA", "libreria", Map.of("count", ordered.size()));
        });
        return library();
    }

    /**
     * Elimina una foto non usata. Se è usata da menu, contenuti o eventi: {@code 409 MEDIA_IN_USE} con l'elenco
     * degli usi (la chiave esterna {@code ON DELETE RESTRICT} resta l'ultima difesa).
     */
    public void delete(UUID actorId, UUID id) {
        List<String> keys = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            MediaStore.AssetRow current = store.lock(id).orElseThrow(MediaService::notFound);
            List<MediaStore.Usage> usages = store.usages(id);
            if (!usages.isEmpty()) {
                List<String> labels = usages.stream().map(MediaService::describe).toList();
                throw new ApiException(HttpStatus.CONFLICT, "MEDIA_IN_USE", "Foto in uso",
                        "Non puoi eliminare questa foto: è usata in " + String.join(", ", labels)
                                + ". Sostituiscila prima lì.",
                        Map.of("usages", usages.stream().map(u -> new Usage(u.type(), describe(u))).toList()));
            }
            current.variants().forEach(v -> keys.add(v.storageKey()));
            store.delete(id);
            audit.record(actorId, AuditAction.MEDIA_DELETED, "MEDIA", id.toString(), Map.of("variants", keys.size()));
        });
        orphans.deleteNowOrLater(keys, "DELETED");
    }

    public MediaView get(UUID id) {
        MediaStore.AssetRow asset = store.find(id).orElseThrow(MediaService::notFound);
        return view(asset, store.usages(id));
    }

    private ImageProcessor.Processed process(byte[] data) {
        boolean acquired = false;
        try {
            acquired = processing.tryAcquire(20, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Server occupato",
                        "Stiamo già elaborando un'altra foto: riprova tra qualche secondo.", Map.of("retryAfterSeconds", 5));
            }
            return processor.process(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (acquired) {
                processing.release();
            }
        }
    }

    private List<String> write(String prefix, ImageProcessor.Processed image) {
        List<String> written = new ArrayList<>();
        try {
            for (ImageProcessor.Variant v : image.variants()) {
                String key = prefix + "/" + v.fileName();
                storage.put(key, v.bytes(), v.mimeType());
                written.add(key);
            }
            return written;
        } catch (MediaStorage.StorageException e) {
            log.warn("Storage foto non disponibile ({}): {}", storage.describe(), e.getMessage());
            orphans.deleteNowOrLater(written, "UPLOAD_FAILED");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Archivio foto non raggiungibile",
                    "Non è stato possibile salvare la foto: l'archivio non risponde. Nulla è stato modificato, riprova tra poco.",
                    Map.of("retryAfterSeconds", 30));
        }
    }

    private MediaView view(MediaStore.AssetRow a, List<MediaStore.Usage> usages) {
        MediaRef ref = catalog.ref(a);
        return new MediaView(a.id(), a.altText(), a.credit(), a.width(), a.height(), ref.src(), ref.srcset(),
                a.mimeType(), a.bytes(), a.inGallery(), a.galleryOrder(),
                a.variants().stream().map(v -> new VariantView(v.size(), v.width(), v.height(), v.mimeType(), v.bytes(),
                        properties.publicUrl(v.storageKey()))).toList(),
                usages.stream().map(u -> new Usage(u.type(), describe(u))).toList(), a.createdAt());
    }

    /** Descrizione dell'uso pronta per la UI (stessi testi del mockup approvato). */
    static String describe(MediaStore.Usage u) {
        return switch (u.type()) {
            case "MENU_ITEM" -> "menu «" + u.label() + "»";
            case "EVENT_POSTER", "EVENT_GALLERY" -> "evento «" + u.label() + "»";
            case "HERO_MENU" -> "testata del menu";
            case "HERO_HOME", "HOME_HERO" -> "foto di apertura della home";
            case "OWNER_FRONT", "OWNER_BACK" -> "titolare «" + u.label() + "»";
            case "STAFF" -> "staff «" + u.label() + "»";
            default -> u.type().toLowerCase();
        };
    }

    private static String newPrefix(UUID id) {
        byte[] token = new byte[6];
        RANDOM.nextBytes(token);
        return id + "/" + HexFormat.of().formatHex(token);
    }

    private static String requireAlt(String altText) {
        String alt = altText == null ? "" : altText.strip();
        if (alt.length() < 5 || alt.length() > 250) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Dati non validi",
                    "Descrivi la foto per chi non la vede (da 5 a 250 caratteri).", Map.of("field", "altText"));
        }
        return alt;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Dati non validi",
                    "Testo troppo lungo (massimo " + max + " caratteri).", Map.of("field", field));
        }
        return v;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Foto non trovata", "La foto non esiste più.");
    }
}
