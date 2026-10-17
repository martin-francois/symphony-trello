package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloCardReferenceParserFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloChecklistClassifierFuzzer;
import ch.fmartin.symphony.trello.fuzz.WorkflowLoaderFuzzer;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.common.base.CaseFormat;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/// Replays every standalone fuzzer's checked-in seed corpus through its real `fuzzerTestOneInput` entry
/// point and fails if an execution reads or writes a file or a socket. Maven copies each
/// `oss-fuzz/corpora/<Fuzzer>` directory to this class's Jazzer regression inputs, see `pom.xml`.
///
/// Java Flight Recorder records file and socket I/O for the whole JVM, so each execution is wrapped in a
/// [FuzzerExecution] event and only I/O on the same thread inside that event counts. The class runs
/// isolated because other test classes would flood the JVM-wide recording with their own file events, and
/// its methods share one recording, so they run on one thread. The replay takes a few seconds.
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
final class StandaloneFuzzerIsolationTest {
    private static final Path CORPORA = Path.of("oss-fuzz/corpora");
    private static final String INPUTS_RESOURCE = StandaloneFuzzerIsolationTest.class.getSimpleName() + "Inputs/";
    private static final List<String> IO_EVENTS =
            List.of("jdk.FileRead", "jdk.FileWrite", "jdk.FileForce", "jdk.SocketRead", "jdk.SocketWrite");
    private static final List<Path> PROTECTED_FUZZING_CONFIGURATION =
            List.of(Path.of("oss-fuzz/build.sh"), Path.of(".clusterfuzzlite/build.sh"), Path.of("pom.xml"));

    @TempDir
    static Path recordingDir;

    private static Recording recording;

    @BeforeAll
    static void startRecording() {
        recording = new Recording();
        for (String event : IO_EVENTS) {
            recording.enable(event).withThreshold(Duration.ZERO).withoutStackTrace();
        }
        recording.enable(FuzzerExecution.class);
        recording.start();
    }

    @AfterAll
    static void fuzzerExecutionsPerformNoFileOrSocketIo() throws IOException {
        // given
        Path dump = recordingDir.resolve("fuzzer-isolation.jfr");
        recording.stop();
        recording.dump(dump);
        recording.close();

        // when
        List<RecordedEvent> executions = new ArrayList<>();
        List<RecordedEvent> io = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
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

        // then
        assertThat(executions).as("the seed corpora were replayed").isNotEmpty();
        assertThat(violations)
                .as("standalone fuzzers must not touch files or sockets; rerun the named target to reproduce")
                .isEmpty();
    }

    @FuzzTest
    void repositorySourceFuzzer(FuzzedDataProvider data) {
        // given
        FuzzerExecution execution = FuzzerExecution.starting(RepositorySourceFuzzer.class);

        // when
        RepositorySourceFuzzer.fuzzerTestOneInput(data);

        // then
        execution.commit();
    }

    @FuzzTest
    void trelloCardReferenceParserFuzzer(FuzzedDataProvider data) {
        // given
        FuzzerExecution execution = FuzzerExecution.starting(TrelloCardReferenceParserFuzzer.class);

        // when
        TrelloCardReferenceParserFuzzer.fuzzerTestOneInput(data);

        // then
        execution.commit();
    }

    @FuzzTest
    void trelloChecklistClassifierFuzzer(FuzzedDataProvider data) {
        // given
        FuzzerExecution execution = FuzzerExecution.starting(TrelloChecklistClassifierFuzzer.class);

        // when
        TrelloChecklistClassifierFuzzer.fuzzerTestOneInput(data);

        // then
        execution.commit();
    }

    @FuzzTest
    void workflowLoaderFuzzer(FuzzedDataProvider data) {
        // given
        FuzzerExecution execution = FuzzerExecution.starting(WorkflowLoaderFuzzer.class);

        // when
        WorkflowLoaderFuzzer.fuzzerTestOneInput(data);

        // then
        execution.commit();
    }

    @Test
    void everySeedCorpusIsReplayedByAMatchingMethod() throws IOException, URISyntaxException {
        // given
        Map<String, Integer> corpusSeeds = new TreeMap<>();
        Map<String, Integer> replayedSeeds = new TreeMap<>();

        // when
        try (var directories = Files.newDirectoryStream(CORPORA, Files::isDirectory)) {
            for (Path directory : directories) {
                String fuzzer = directory.getFileName().toString();
                corpusSeeds.put(fuzzer, fileCount(directory));
                replayedSeeds.put(fuzzer, replayedSeedCount(fuzzer));
            }
        }

        // then
        assertThat(replayedSeeds)
                .as("each oss-fuzz/corpora directory needs a @FuzzTest method named after its fuzzer in lower"
                        + " camel case and a matching testResource mapping in pom.xml")
                .containsExactlyInAnyOrderEntriesOf(corpusSeeds);
    }

    @Test
    void fuzzingConfigurationKeepsJazzerNetworkDetectorEnabled() throws IOException {
        // given
        Map<Path, String> buildFiles = new TreeMap<>();

        // when
        for (Path file : PROTECTED_FUZZING_CONFIGURATION) {
            buildFiles.put(file, Files.readString(file));
        }

        // then
        assertThat(buildFiles)
                .as("Jazzer's ServerSideRequestForgery hook reports every network connection during fuzzing")
                .allSatisfy((file, content) -> assertThat(content)
                        .as("%s must not disable Jazzer hooks", file)
                        .doesNotContain("disabled_hooks", "ServerSideRequestForgery"));
    }

    private static int replayedSeedCount(String fuzzer) throws IOException, URISyntaxException {
        String method = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, fuzzer);
        boolean fuzzTest;
        try {
            fuzzTest = StandaloneFuzzerIsolationTest.class
                    .getDeclaredMethod(method, FuzzedDataProvider.class)
                    .isAnnotationPresent(FuzzTest.class);
        } catch (NoSuchMethodException missing) {
            fuzzTest = false;
        }
        URL inputs = StandaloneFuzzerIsolationTest.class.getResource(INPUTS_RESOURCE + method);
        return fuzzTest && inputs != null ? fileCount(Path.of(inputs.toURI())) : 0;
    }

    private static int fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return Math.toIntExact(files.count());
        }
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
        String path = event.getString("path");
        // A file event without a path is a write to a standard stream, such as a log line on stderr.
        if (path == null) {
            return true;
        }
        // The first execution of each target loads its classes, which reads class files and jars.
        return event.getEventType().getName().equals("jdk.FileRead")
                && (path.endsWith(".class") || path.endsWith(".jar"));
    }

    private static String describe(RecordedEvent event) {
        String target = event.hasField("path") ? event.getString("path") : event.getString("host");
        return event.getEventType().getName() + " " + target;
    }

    @Name(FuzzerExecution.NAME)
    static final class FuzzerExecution extends Event {
        static final String NAME = "ch.fmartin.symphony.trello.FuzzerExecution";

        String target;

        static FuzzerExecution starting(Class<?> target) {
            var execution = new FuzzerExecution();
            execution.target = target.getSimpleName();
            execution.begin();
            return execution;
        }
    }
}
