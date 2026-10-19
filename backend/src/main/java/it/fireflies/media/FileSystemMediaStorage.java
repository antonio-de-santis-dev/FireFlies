package it.fireflies.media;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Storage su cartella locale, solo per sviluppo e test (in produzione l'avvio lo rifiuta).
 * Le chiavi sono generate dal backend ({@code <uuid>/<token>/w960.jpg}); si verifica comunque che restino
 * dentro la cartella radice.
 */
final class FileSystemMediaStorage implements MediaStorage {

    private final Path root;

    FileSystemMediaStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile creare la cartella delle foto " + this.root, e);
        }
    }

    Path root() {
        return root;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("Scrittura non riuscita: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
            // cartelle rimaste vuote (<uuid>/<token>/ e <uuid>/)
            for (Path dir = target.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isPresent()) {
                        break;
                    }
                }
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new StorageException("Cancellazione non riuscita: " + key, e);
        }
    }

    @Override
    public String describe() {
        return "cartella locale " + root;
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Chiave fuori dalla cartella delle foto: " + key);
        }
        return target;
    }
}
