package com.nightlight.app.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-wide executors. All database and network-adjacent work runs off the
 * main thread; results are delivered on the main thread.
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io;
    private final ExecutorService network;
    private final Executor main;

    private AppExecutors() {
        ThreadFactory ioFactory = namedFactory("nightlight-io");
        ThreadFactory netFactory = namedFactory("nightlight-net");
        io = Executors.newSingleThreadExecutor(ioFactory);
        network = Executors.newFixedThreadPool(4, netFactory);
        main = new MainThreadExecutor();
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    /** Serial executor for database writes (avoids write contention). */
    public ExecutorService io() {
        return io;
    }

    /** Parallel executor for HTTP + JSON work. */
    public ExecutorService network() {
        return network;
    }

    public Executor main() {
        return main;
    }

    public static void onMain(Runnable r) {
        INSTANCE.main.execute(r);
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger count = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + count.getAndIncrement());
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        };
    }

    private static final class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}