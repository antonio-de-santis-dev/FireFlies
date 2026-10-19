package it.fireflies.support;

import java.util.concurrent.atomic.AtomicBoolean;

import it.fireflies.media.MediaStorage;

/** Storage dei test: quello vero (cartella temporanea) con un interruttore per simulare un provider irraggiungibile. */
public class SwitchableMediaStorage implements MediaStorage {

    private final MediaStorage real;
    private final AtomicBoolean failPut = new AtomicBoolean();
    private final AtomicBoolean failDelete = new AtomicBoolean();

    public SwitchableMediaStorage(MediaStorage real) {
        this.real = real;
    }

    public void failPuts(boolean fail) {
        failPut.set(fail);
    }

    public void failDeletes(boolean fail) {
        failDelete.set(fail);
    }

    public void reset() {
        failPut.set(false);
        failDelete.set(false);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (failPut.get()) {
            throw new StorageException("simulato: storage non raggiungibile", null);
        }
        real.put(key, content, contentType);
    }

    @Override
    public void delete(String key) {
        if (failDelete.get()) {
            throw new StorageException("simulato: storage non raggiungibile", null);
        }
        real.delete(key);
    }

    @Override
    public String describe() {
        return "test (" + real.describe() + ")";
    }
}
