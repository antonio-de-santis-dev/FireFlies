package it.fireflies.support;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Esegue N operazioni davvero in parallelo: tutti i thread partono insieme da un {@link CountDownLatch}.
 * Ogni operazione deve aprire la propria transazione.
 */
public final class Concurrency {

    /** Esito di un'operazione: valore oppure errore. */
    public record Result<T>(T value, Throwable error) {

        public boolean ok() {
            return error == null;
        }

        public boolean deadlock() {
            return hasSqlState(error, "40P01");
        }
    }

    private Concurrency() {
    }

    public static <T> List<Result<T>> run(List<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Result<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Result<>(task.call(), null);
                    } catch (Throwable e) {
                        return new Result<T>(null, e);
                    }
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            List<Result<T>> results = new ArrayList<>();
            for (Future<Result<T>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Esecuzione concorrente interrotta", e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static boolean hasSqlState(Throwable error, String sqlState) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
