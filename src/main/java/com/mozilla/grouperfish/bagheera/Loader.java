package com.mozilla.grouperfish.bagheera;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mozilla.grouperfish.base.Helpers;
import com.mozilla.grouperfish.cli.Grouperfish;
import com.mozilla.grouperfish.json.JSONConverter;
import com.mozilla.grouperfish.model.Entity;


/** Helps loading a remote bagheera installation with documents. */
public class Loader<T extends Entity> {

    private final String mapUrl_;
    private final JSONConverter<T> converter_;
    private static Logger log = LoggerFactory.getLogger(Grouperfish.class);

    /**
     * @param mapUrl The url to a bagheera map resource to use as destination.
     *               Example: http://localhost:8080/map/grouperfish-docs
     */
    public Loader(final String mapUrl, final JSONConverter<T> converter) {
        converter_ = converter;
        mapUrl_ = mapUrl;
    }

    /**
     * Loads document into bagheera using a multithreaded client. Returns the
     * number of document loaded.
     */

    public int load(Iterable<T> stream) {

        log.debug("Starting import into map '{}'", mapUrl_);
        final ExecutorService workers = workers();

        // So modulo does not match right away, we set i != 0
        int i = 1;
        List<T> batch = new ArrayList<T>(BATCH_SIZE);
        for (T item : stream) {
            batch.add(item);
            if (i % BATCH_SIZE == 0) {
                workers.submit(new InsertTask<T>(mapUrl_, converter_, batch));
                batch = new ArrayList<T>(BATCH_SIZE);
            }
            if (i % 50000 == 0) {
                log.info("Queued {} items into map {}", i, mapUrl_);
            }
            ++i;
        }
        if (!batch.isEmpty()) {
            workers.submit(new InsertTask<T>(mapUrl_, converter_, batch));
        }

        // Submit will block until it is safe to shut down:
        shutdownGracefully(workers);
        return i;
    }

    /**
     * So there is this factory where all workers do is running and then relax
     * at the pool, and where all clients must wait in a queue. It is a pretty
     * fun work environment... until everyone gets garbage collected that is.
     */
    private ExecutorService workers() {
        return new ThreadPoolExecutor(
                5, 10, 90, TimeUnit.SECONDS,

                new ArrayBlockingQueue<Runnable>(100),

                new ThreadFactory() {
                    @Override
                    public Thread newThread(final Runnable r) {

                        Thread worker = new Thread(r) {
                            @Override
                            public void run() {
                                super.run();
                            }
                        };

                        worker.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                            @Override
                            public void uncaughtException(final Thread t, final Throwable e) {
                                log.error("Uncaught exception from bagheera load worker.", e);
                            }
                        });
                        return worker;
                    }
                },

                new RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable task,
                            ThreadPoolExecutor executor) {
                        try {
                            executor.getQueue().put(task);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );
    }

    private void shutdownGracefully(final ExecutorService pool) {
        pool.shutdown();
        try {
            if (pool.awaitTermination(120, TimeUnit.SECONDS))
                return;
            pool.shutdownNow();
            if (pool.awaitTermination(60, TimeUnit.SECONDS))
                return;
            log.error("Importer pool did not terminate within timeout.");
            System.exit(1);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Each insert task submits a batch of items */
    static class InsertTask<T extends Entity> implements Runnable {

        private static final Charset UTF8 = Charset.forName("UTF8");

        private final String mapUrl_;
        private final List<T> items_;
        private final JSONConverter<T> converter_;

        InsertTask(final String mapUrl, final JSONConverter<T> converter, final List<T> items) {
            mapUrl_ = mapUrl;
            items_ = items;
            converter_ = converter;
        }

        @Override
        public void run() {
            log.trace("Insert task has {} items", items_.size());
            if (items_.size() == 0)
                return;
            for (T item : items_) {
                log.trace("Writing '{}' to '{}'", converter_.encode(item), mapUrl_ + "/" + item.id());
                int retriesLeft = 5;
                while (retriesLeft > 0) {
                    try {
                        final HttpURLConnection conn =
                            (HttpURLConnection) new URL(mapUrl_ + "/" + item.id()).openConnection();
                        conn.setDoInput(true);
                        conn.setDoOutput(true);
                        conn.setUseCaches (false);
                        conn.setRequestProperty("Content-Type", "application/json");
                        Writer wr = new OutputStreamWriter(conn.getOutputStream(), UTF8);
                        wr.write(converter_.encode(item));
                        wr.flush();
                        wr.close();

                        final int status = conn.getResponseCode();
                        if (status >= 200 && status < 400) {
                            log.trace("HTTP response status code: {}", status);
                        }
                        else {

                            log.warn("HTTP error status: {} ({})", status, Helpers.consume(conn.getErrorStream(), UTF8));
                        }

                        retriesLeft = 0;
                    } catch (IOException e) {
                        final Entity from = items_.get(0);
                        final Entity to = items_.get(items_.size() - 1);
                        log.error(String.format("While inserting batch %s,%s", from.id(), to.id()));
                        log.error("IO Error in importer", e);
                        --retriesLeft;
                        if (retriesLeft == 0) {
                            log.error("No retries left. Giving up.", e);
                        }
                    }
                }
            }
        }
    }


    public static final int BATCH_SIZE = 100;
}
