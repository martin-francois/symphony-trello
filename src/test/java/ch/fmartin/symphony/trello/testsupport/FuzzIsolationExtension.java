package ch.fmartin.symphony.trello.testsupport;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.parallel.Isolated;

/// Fails a test class when one of its `@FuzzTest` executions reads or writes a file or a socket.
///
/// Java Flight Recorder records file and socket I/O for the whole JVM, so each `@FuzzTest` execution is
/// wrapped in a [FuzzerExecution] event and only I/O on the same thread inside that event counts. The
/// class must be `@Isolated` because other test classes would flood the JVM-wide recording with their own
/// file events. Flight Recorder has no event for creating, deleting, or moving a file, for a connection
/// attempt, or for a DNS lookup, so those stay unchecked. ADR 0091 records the trade-offs.
public final class FuzzIsolationExtension implements BeforeAllCallback, InvocationInterceptor, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(FuzzIsolationExtension.class);
    private static final String FILE_READ = "jdk.FileRead";
    private static final String FILE_WRITE = "jdk.FileWrite";
    private static final List<String> IO_EVENTS =
            List.of(FILE_READ, FILE_WRITE, "jdk.FileForce", "jdk.SocketRead", "jdk.SocketWrite");
    // Every class loader, and the JVM when it resolves a class, enters loading through this method.
    private static final String CLASS_LOADING_METHOD = "loadClass";
    private static final String ACTIVE_FUZZING_SWITCH = "JAZZER_FUZZ";
    // The values Jazzer itself accepts as "on" for JAZZER_FUZZ.
    private static final Set<String> ACTIVE_FUZZING_VALUES = Set.of("1", "true", "yes");

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        checkState(
                testClass.isAnnotationPresent(Isolated.class),
                "%s uses FuzzIsolationExtension and must be @Isolated so other tests' I/O stays out of the recording",
                testClass.getName());
        if (activeFuzzing()) {
            // A fuzzing run writes its corpus and findings on the fuzzing thread, which is not target I/O.
            return;
        }
        context.getStore(NAMESPACE).put(IoRecording.class, new IoRecording());
    }

    @Override
    public void interceptTestTemplateMethod(
            Invocation<@Nullable Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        Method method = invocationContext.getExecutable();
        IoRecording recording = extensionContext.getStore(NAMESPACE).get(IoRecording.class, IoRecording.class);
        if (recording == null || !method.isAnnotationPresent(FuzzTest.class)) {
            invocation.proceed();
            return;
        }
        var execution = new FuzzerExecution();
        execution.target = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        recording.executions.incrementAndGet();
        execution.begin();
        try {
            invocation.proceed();
        } finally {
            execution.commit();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws IOException {
        IoRecording recording = context.getStore(NAMESPACE).remove(IoRecording.class, IoRecording.class);
        if (recording == null) {
            return;
        }

        List<RecordedEvent> executions = new ArrayList<>();
        List<RecordedEvent> io = new ArrayList<>();
        for (RecordedEvent event : recording.stopAndRead()) {
            (FuzzerExecution.NAME.equals(event.getEventType().getName()) ? executions : io).add(event);
        }
        List<String> violations = new ArrayList<>();
        for (RecordedEvent execution : executions) {
            for (RecordedEvent event : io) {
                if (insideExecution(event, execution) && !expectedIo(event)) {
                    violations.add(execution.getString("target") + ": " + describe(event));
                }
            }
        }

        assertThat(executions)
                .as("Flight Recorder recorded every @FuzzTest execution")
                .hasSize(recording.executions.get());
        assertThat(violations)
                .as("fuzz tests must not touch files or sockets; rerun the named test to reproduce")
                .isEmpty();
    }

    private static boolean activeFuzzing() {
        return isOn(System.getenv(ACTIVE_FUZZING_SWITCH)) || isOn(System.getProperty(ACTIVE_FUZZING_SWITCH));
    }

    private static boolean isOn(@Nullable String value) {
        return value != null && ACTIVE_FUZZING_VALUES.contains(value.toLowerCase(Locale.ROOT));
    }

    private static boolean insideExecution(RecordedEvent event, RecordedEvent execution) {
        Instant start = event.getStartTime();
        return event.getThread() != null
                && execution.getThread() != null
                && event.getThread().getJavaThreadId() == execution.getThread().getJavaThreadId()
                && !start.isBefore(execution.getStartTime())
                && !start.isAfter(execution.getEndTime());
    }

    private static boolean expectedIo(RecordedEvent event) {
        if (!event.hasField("path")) {
            return false;
        }
        String type = event.getEventType().getName();
        String path = event.getString("path");
        // A write without a path goes to a standard stream, such as a log line on stderr.
        if (path == null) {
            return FILE_WRITE.equals(type);
        }
        // The first execution of each target loads its classes, which reads class files and jars.
        return FILE_READ.equals(type)
                && (path.endsWith(".class") || path.endsWith(".jar"))
                && loadsAClass(event.getStackTrace());
    }

    private static boolean loadsAClass(@Nullable RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return false;
        }
        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (CLASS_LOADING_METHOD.equals(frame.getMethod().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String describe(RecordedEvent event) {
        String target = event.hasField("path") ? event.getString("path") : event.getString("host");
        return event.getEventType().getName() + " " + target;
    }

    /// The Flight Recorder recording of one test class and the number of fuzz test executions it wrapped.
    private static final class IoRecording {
        private final Recording recording = new Recording();
        private final AtomicInteger executions = new AtomicInteger();

        IoRecording() {
            for (String event : IO_EVENTS) {
                recording.enable(event).withThreshold(Duration.ZERO).withoutStackTrace();
            }
            // The stack tells class loading apart from a target that reads a .class or .jar file itself.
            recording.enable(FILE_READ).withStackTrace();
            recording.enable(FuzzerExecution.class);
            recording.start();
        }

        List<RecordedEvent> stopAndRead() throws IOException {
            Path dump = Files.createTempFile("fuzz-isolation", ".jfr");
            try (recording) {
                recording.stop();
                recording.dump(dump);
                return RecordingFile.readAllEvents(dump);
            } finally {
                Files.deleteIfExists(dump);
            }
        }
    }

    @Name(FuzzerExecution.NAME)
    static final class FuzzerExecution extends Event {
        static final String NAME = "ch.fmartin.symphony.trello.FuzzerExecution";

        String target;
    }
}
