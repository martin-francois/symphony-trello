package ch.fmartin.symphony.trello.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/// Records what one class's logger publishes on the threads a filter accepts. Tests running in
/// parallel log through the same logger, so the filter keeps their records out. The handler leaves
/// the logger's level, filters and other handlers alone, and closing it removes it.
public final class ThreadLogRecords extends Handler implements AutoCloseable {
    private final Logger logger;
    private final Predicate<Thread> threads;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    /// The filter runs on the logging thread for every record, so it can accept a thread that the
    /// test learns only after the handler is installed.
    public ThreadLogRecords(Class<?> loggerOwner, Predicate<Thread> threads) {
        this.logger = Logger.getLogger(loggerOwner.getName());
        this.threads = threads;
        logger.addHandler(this);
    }

    /// Records only what the calling thread logs.
    public static ThreadLogRecords onCurrentThread(Class<?> loggerOwner) {
        Thread owner = Thread.currentThread();
        return new ThreadLogRecords(loggerOwner, owner::equals);
    }

    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    @Override
    public void publish(LogRecord record) {
        if (threads.test(Thread.currentThread())) {
            records.add(record);
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
