package dev.velox.launcher.net;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads many files in parallel, skipping those already present and valid.
 * The pool exists only for the duration of {@link #run}; nothing lingers in the background.
 */
public final class DownloadQueue {
    public record Item(String url, Path target, Http.Expect expect) {}

    public interface Progress {
        void update(int done, int total, long bytes);

        Progress NONE = (d, t, b) -> {};
    }

    private final Http http;
    private final int parallelism;
    private final List<Item> items = new ArrayList<>();

    public DownloadQueue(Http http, int parallelism) {
        this.http = http;
        this.parallelism = parallelism;
    }

    public void add(String url, Path target, Http.Expect expect) {
        items.add(new Item(url, target, expect));
    }

    public int size() {
        return items.size();
    }

    /**
     * @param verifyExisting hash existing files instead of only checking their size. Hashing
     *                       thousands of assets costs seconds, so it is reserved for repairs.
     * @return number of files actually downloaded
     */
    public int run(Progress progress, boolean verifyExisting) throws IOException {
        int total = items.size();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "velox-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (Item it : items) {
                futures.add(pool.submit(() -> {
                    Http.Expect check = verifyExisting ? it.expect() : Http.Expect.sizeOnly(it.expect().size());
                    if (!Http.matches(it.target(), check)) {
                        bytes.addAndGet(http.download(it.url(), it.target(), it.expect()));
                        downloaded.incrementAndGet();
                    }
                    progress.update(done.incrementAndGet(), total, bytes.get());
                    return null;
                }));
            }
            List<String> failures = new ArrayList<>();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failures.add(e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
            if (!failures.isEmpty()) {
                throw new IOException(failures.size() + " of " + total + " downloads failed; first: " + failures.get(0));
            }
            return downloaded.get();
        } finally {
            pool.shutdownNow();
            items.clear();
        }
    }
}
