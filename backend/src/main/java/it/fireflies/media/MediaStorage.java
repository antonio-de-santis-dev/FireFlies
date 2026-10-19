package it.fireflies.media;

/**
 * Archivio dei file delle foto (solo varianti già ricodificate: l'originale caricato non viene mai salvato).
 * Nel database restano metadati e chiavi, mai i byte (MASTER_PLAN, ADR-0004).
 */
public interface MediaStorage {

    /** Scrive (o sovrascrive) un oggetto. Errore di rete o del provider → {@link StorageException}. */
    void put(String key, byte[] content, String contentType);

    /** Cancella un oggetto; un oggetto già assente non è un errore. */
    void delete(String key);

    /** Nome leggibile per i log (mai credenziali). */
    String describe();

    /** Lo storage non ha completato l'operazione: nulla è stato confermato al client. */
    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
