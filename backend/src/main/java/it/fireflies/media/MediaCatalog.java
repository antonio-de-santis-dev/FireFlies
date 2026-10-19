package it.fireflies.media;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import it.fireflies.common.ApiException;

/** Lettura della libreria per gli altri moduli (menu, contenuti, eventi): solo foto {@code READY}. */
@Service
public class MediaCatalog {

    private final MediaStore store;
    private final MediaProperties properties;

    MediaCatalog(MediaStore store, MediaProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public Map<UUID, MediaRef> refs(Collection<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return store.findAll(wanted).stream()
                .collect(Collectors.toMap(MediaStore.AssetRow::id, this::ref, (a, b) -> a, LinkedHashMap::new));
    }

    public Optional<MediaRef> ref(UUID id) {
        return id == null ? Optional.empty() : store.find(id).map(this::ref);
    }

    public List<MediaRef> gallery() {
        return store.gallery().stream().map(this::ref).toList();
    }

    /** La foto scelta per un contenuto deve esistere ed essere pronta; altrimenti {@code 422 MEDIA_NOT_FOUND}. */
    public void requireReady(UUID id, String field) {
        if (id != null && !store.exists(id)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA_NOT_FOUND", "Foto non disponibile",
                    "La foto scelta non esiste più nella libreria: scegline un'altra.", Map.of("field", field));
        }
    }

    MediaRef ref(MediaStore.AssetRow asset) {
        List<MediaStore.VariantRow> variants = asset.variants();
        MediaStore.VariantRow main = variants.stream().filter(v -> v.size().equals("W960")).findFirst()
                .orElse(variants.isEmpty() ? null : variants.getLast());
        String srcset = variants.stream().map(v -> properties.publicUrl(v.storageKey()) + " " + v.width() + "w")
                .collect(Collectors.joining(", "));
        return new MediaRef(asset.id(), asset.altText(), asset.width(), asset.height(),
                main == null ? null : properties.publicUrl(main.storageKey()), srcset);
    }
}
