package it.fireflies.media;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import it.fireflies.security.GestoreDetails;

/**
 * Libreria foto: {@code /api/v1/admin/media} (staff e titolare) e galleria pubblica {@code /api/v1/public/gallery}.
 * Il caricamento passa dal backend (multipart, massimo 5 MB): il file viene verificato e ricodificato prima che
 * qualunque byte arrivi allo storage pubblico (FASE4_REPORT §3, revisione di ADR-0004).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MediaController {

    record MediaPatch(@Size(max = 250) String altText, @Size(max = 120) String credit, Boolean inGallery) {
    }

    record MediaOrder(@NotNull @Size(max = 2000) List<@NotNull UUID> mediaIds) {
    }

    private final MediaService media;
    private final MediaCatalog catalog;

    MediaController(MediaService media, MediaCatalog catalog) {
        this.media = media;
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/public/gallery")
    ResponseEntity<List<MediaRef>> gallery() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(catalog.gallery());
    }

    @GetMapping("/api/v1/admin/media")
    @PreAuthorize("hasRole('STAFF')")
    List<MediaService.MediaView> library() {
        return media.library();
    }

    @PostMapping(path = "/api/v1/admin/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<MediaService.MediaView> upload(@AuthenticationPrincipal GestoreDetails actor,
            @RequestPart("file") MultipartFile file, @RequestParam(name = "altText", required = false) String altText,
            @RequestParam(name = "credit", required = false) String credit) throws IOException {
        if (file.getSize() > ImageProcessor.MAX_BYTES) {
            throw ImageProcessor.tooLarge();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(media.upload(actor.id(), file.getBytes(), altText, credit));
    }

    @PutMapping(path = "/api/v1/admin/media/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STAFF')")
    MediaService.MediaView replace(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > ImageProcessor.MAX_BYTES) {
            throw ImageProcessor.tooLarge();
        }
        return media.replace(actor.id(), id, file.getBytes());
    }

    @PatchMapping("/api/v1/admin/media/{id}")
    @PreAuthorize("hasRole('STAFF')")
    MediaService.MediaView update(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id,
            @Valid @RequestBody MediaPatch body) {
        return media.update(actor.id(), id, body.altText(), body.credit(), body.inGallery());
    }

    @PutMapping("/api/v1/admin/media/order")
    @PreAuthorize("hasRole('STAFF')")
    List<MediaService.MediaView> reorder(@AuthenticationPrincipal GestoreDetails actor, @Valid @RequestBody MediaOrder body) {
        return media.reorder(actor.id(), body.mediaIds());
    }

    @DeleteMapping("/api/v1/admin/media/{id}")
    @PreAuthorize("hasRole('STAFF')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal GestoreDetails actor, @PathVariable UUID id) {
        media.delete(actor.id(), id);
        return ResponseEntity.noContent().build();
    }
}
