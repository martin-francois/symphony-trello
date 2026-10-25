package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedWorkflowStoreTest {
    @TempDir
    Path directory;

    @Test
    void replacingOneWorkflowRetainsTheOtherWorkflowRecord() throws IOException {
        // given
        var store = GeneratedWorkflowStore.inConfigDir(directory);
        Path first = directory.resolve("first.md");
        Path second = directory.resolve("second.md");
        store.put(record(first, "old body"));
        store.put(record(second, "other body"));

        // when
        store.put(record(first, "new body"));

        // then
        assertThat(store.records())
                .extracting(GeneratedWorkflowRecord::body)
                .containsExactlyInAnyOrder("new body", "other body");
        assertThat(ConnectedBoardRepository.jsonMapper()
                        .readTree(store.storePath().toFile())
                        .get("formatVersion")
                        .intValue())
                .isEqualTo(GeneratedWorkflowStore.FORMAT_VERSION);
    }

    @Test
    void readersIgnoreFutureFieldsAtEveryStoredBoundary() throws IOException {
        // given
        var store = GeneratedWorkflowStore.inConfigDir(directory);
        Path workflow = directory.resolve("workflow.md");
        store.put(record(workflow, "body from a newer version"));
        var json = ConnectedBoardRepository.jsonMapper();
        var document = json.readTree(store.storePath().toFile());
        ((ObjectNode) document).put("futureStoreField", true);
        var stored = document.get("workflows").get(0);
        ((ObjectNode) stored).put("futureRecordField", true);
        ((ObjectNode) stored.get("inputs")).put("futureInputField", true);
        json.writeValue(store.storePath().toFile(), document);

        // when
        List<GeneratedWorkflowRecord> loaded = store.records();

        // then
        assertThat(loaded).containsExactly(record(workflow, "body from a newer version"));
    }

    @Test
    void aStoreWriteFailureReportsAWarningWithoutChangingTheWorkflow() throws IOException {
        // given
        Path workflow = Files.writeString(directory.resolve("workflow.md"), "finished workflow");
        Path unusableConfig = Files.writeString(directory.resolve("not-a-directory"), "keep");

        // when
        var warning = GeneratedWorkflowStore.recordGenerated(
                unusableConfig.resolve(ConnectedBoardManifest.FILE_NAME), workflow, inputs());

        // then
        assertThat(warning).hasValueSatisfying(message -> assertThat(message)
                .contains("Could not record", "manual workflow migration"));
        assertThat(workflow).hasContent("finished workflow");
        assertThat(unusableConfig).hasContent("keep");
    }

    @Test
    void aManifestNamedLikeTheStoreIsNotOverwritten() throws IOException {
        // given
        Path manifest = Files.writeString(directory.resolve(GeneratedWorkflowStore.FILE_NAME), "{\"boards\":[]}");
        Path workflow = Files.writeString(directory.resolve("workflow.md"), "finished workflow");

        // when
        var warning = GeneratedWorkflowStore.recordGenerated(manifest, workflow, inputs());

        // then
        assertThat(warning).hasValueSatisfying(message -> assertThat(message).contains("Could not record"));
        assertThat(manifest).hasContent("{\"boards\":[]}");
        assertThat(workflow).hasContent("finished workflow");
    }

    @Test
    void aManifestAliasToTheStoreIsNotOverwritten() throws IOException {
        // given
        Path target = Files.writeString(directory.resolve(GeneratedWorkflowStore.FILE_NAME), "{\"boards\":[]}");
        Path manifest = directory.resolve("boards.json");
        try {
            Files.createSymbolicLink(manifest, target);
        } catch (IOException | UnsupportedOperationException e) {
            abort("symbolic links are unavailable");
        }
        Path workflow = Files.writeString(directory.resolve("workflow.md"), "finished workflow");

        // when
        var warning = GeneratedWorkflowStore.recordGenerated(manifest, workflow, inputs());

        // then
        assertThat(warning).hasValueSatisfying(message -> assertThat(message).contains("Could not record"));
        assertThat(manifest).hasContent("{\"boards\":[]}");
        assertThat(workflow).hasContent("finished workflow");
    }

    @Test
    void recordingAnotherWorkflowPreservesUnknownFieldsFromNewerVersions() throws IOException {
        // given
        var store = GeneratedWorkflowStore.inConfigDir(directory);
        store.put(record(directory.resolve("newer.md"), "newer body"));
        var json = ConnectedBoardRepository.jsonMapper();
        var document = (ObjectNode) json.readTree(store.storePath().toFile());
        document.put("futureStoreField", "keep root");
        var stored = (ObjectNode) document.get("workflows").get(0);
        stored.put("futureRecordField", "keep record");
        ((ObjectNode) stored.get("inputs")).put("futureInputField", "keep input");
        json.writeValue(store.storePath().toFile(), document);

        // when
        store.put(record(directory.resolve("other.md"), "other body"));

        // then
        var saved = json.readTree(store.storePath().toFile());
        assertThat(saved.get("futureStoreField")).isEqualTo(document.get("futureStoreField"));
        assertThat(saved.get("workflows").get(0)).isEqualTo(stored);
    }

    private static GeneratedWorkflowRecord record(Path workflow, String body) {
        return new GeneratedWorkflowRecord(workflow, "1.0.0", inputs(), body);
    }

    private static GeneratedWorkflowBodyInputs inputs() {
        return new GeneratedWorkflowBodyInputs(List.of("Ready"), List.of("Done"), null, null, null, null, false);
    }
}
