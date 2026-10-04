package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.fuzz.RepositorySourceFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloCardPayloadFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloCardReferenceParserFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloChecklistClassifierFuzzer;
import ch.fmartin.symphony.trello.fuzz.TrelloHandoffToolFuzzer;
import ch.fmartin.symphony.trello.fuzz.WorkflowLoaderFuzzer;
import ch.fmartin.symphony.trello.testsupport.FuzzIsolationExtension;
import ch.fmartin.symphony.trello.tracker.InMemoryTrelloBoard;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

/// Replays every standalone fuzzer's checked-in seed corpus through its real `fuzzerTestOneInput` entry
/// point. [FuzzIsolationExtension] fails the class if an execution reads or writes a file or a socket.
/// Maven copies each `oss-fuzz/corpora/<Fuzzer>` directory to this class's Jazzer regression inputs, see
/// `pom.xml`. The replay takes a few seconds.
@ExtendWith(FuzzIsolationExtension.class)
@Isolated
final class StandaloneFuzzerIsolationTest {
    private static final Path CORPORA = Path.of("oss-fuzz/corpora");
    private static final String INPUTS_RESOURCE = StandaloneFuzzerIsolationTest.class.getSimpleName() + "Inputs/";

    @BeforeAll
    static void initializeRequestMetadata() {
        InMemoryTrelloBoard.initializeRequestMetadata();
    }

    @FuzzTest
    void repositorySourceFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        RepositorySourceFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @FuzzTest
    void trelloCardReferenceParserFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        TrelloCardReferenceParserFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @FuzzTest
    void trelloChecklistClassifierFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        TrelloChecklistClassifierFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @FuzzTest
    void trelloHandoffToolFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        TrelloHandoffToolFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @FuzzTest
    void workflowLoaderFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        WorkflowLoaderFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @FuzzTest
    void trelloCardPayloadFuzzer(FuzzedDataProvider data) {
        // given
        FuzzedDataProvider seed = data;

        // when
        TrelloCardPayloadFuzzer.fuzzerTestOneInput(seed);

        // then
        // FuzzIsolationExtension checks the file and socket I/O of this execution after the class.
    }

    @Test
    void everySeedCorpusIsReplayedByAMatchingMethod() throws IOException, URISyntaxException {
        // given
        Map<String, Integer> corpusSeeds = new TreeMap<>();
        Map<String, Integer> replayedSeeds = new TreeMap<>();
        List<String> fuzzersWithoutReplayMethod = new ArrayList<>();

        // when
        try (var directories = Files.newDirectoryStream(CORPORA, Files::isDirectory)) {
            for (Path directory : directories) {
                String fuzzer = directory.getFileName().toString();
                String method = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, fuzzer);
                corpusSeeds.put(fuzzer, fileCount(directory));
                replayedSeeds.put(fuzzer, replayInputCount(method));
                if (!hasReplayMethod(method)) {
                    fuzzersWithoutReplayMethod.add(fuzzer);
                }
            }
        }

        // then
        assertThat(corpusSeeds)
                .as("each oss-fuzz/corpora directory holds at least one seed")
                .isNotEmpty()
                .doesNotContainValue(0);
        assertThat(fuzzersWithoutReplayMethod)
                .as(
                        "each oss-fuzz/corpora directory needs a @FuzzTest method named after its fuzzer in lower camel case")
                .isEmpty();
        assertThat(replayedSeeds)
                .as("each oss-fuzz/corpora directory needs a matching testResource mapping in pom.xml")
                .containsExactlyInAnyOrderEntriesOf(corpusSeeds);
    }

    private static boolean hasReplayMethod(String method) {
        try {
            return StandaloneFuzzerIsolationTest.class
                    .getDeclaredMethod(method, FuzzedDataProvider.class)
                    .isAnnotationPresent(FuzzTest.class);
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static int replayInputCount(String method) throws IOException, URISyntaxException {
        URL inputs = StandaloneFuzzerIsolationTest.class.getResource(INPUTS_RESOURCE + method);
        return inputs == null ? 0 : fileCount(Path.of(inputs.toURI()));
    }

    private static int fileCount(Path directory) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            return Iterables.size(files);
        }
    }
}
