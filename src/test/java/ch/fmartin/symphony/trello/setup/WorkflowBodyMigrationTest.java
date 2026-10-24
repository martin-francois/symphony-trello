package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CliExitCodes.SETUP_FAILURE;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.GITHUB_INPUTS;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.NEWER_VERSION;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.NON_GITHUB_INPUTS;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.OLDER_VERSION;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.RUNNING_VERSION;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.backupOf;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.otherVersionBody;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.targetBody;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.workflowFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.GeneratedWorkflow;
import ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.MigrationRun;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowBodyMigrationTest {
    private static final String PREFIX = "Team rule: write Trello comments in German.\n\n";
    private static final String SUFFIX = "\n## Local Additions\n\nRun the smoke tests before handoff.\n";
    private static final String GENERATED_TITLE = "# Trello Card\n\n";
    private static final String HAND_WRITTEN_BODY = "Hand-written prompt.\n";

    @TempDir
    Path configDir;

    private WorkflowMigrationFixture fixture;

    @BeforeEach
    void createFixture() {
        fixture = new WorkflowMigrationFixture(configDir);
    }

    @EnumSource(Direction.class)
    @ParameterizedTest
    void migratesAnUnchangedGeneratedBodyAfterConfirmationAndKeepsMetadata(Direction direction) throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(direction.sourceVersion, "Board A", GITHUB_INPUTS, UnaryOperator.identity());

        // when
        MigrationRun run = fixture.run(fixture.request(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "MIGRATE   \"Board A\" (" + workflow.path() + "): unchanged generated body from Symphony "
                                + direction.sourceVersion,
                        direction.explanation,
                        "Only the text after the closing --- line changes.",
                        "OK  Migrated " + workflow.path(),
                        "Backup: " + backupOf(workflow.path()),
                        "also reinstall Symphony " + direction.sourceVersion)
                .doesNotContain("Warning: your kept text");
        assertThat(workflow.path()).hasContent(workflowFile(GITHUB_INPUTS, targetBody(GITHUB_INPUTS)));
        assertThat(metadataOf(Files.readString(workflow.path()))).isEqualTo(metadataOf(workflow.content()));
        assertThat(backupOf(workflow.path())).hasContent(workflow.content());
        assertThat(fixture.recorded(workflow.path()))
                .get()
                .extracting(GeneratedWorkflowRecord::symphonyVersion, GeneratedWorkflowRecord::body)
                .containsExactly(RUNNING_VERSION, targetBody(GITHUB_INPUTS));
    }

    @MethodSource("additionsInBothDirections")
    @ParameterizedTest(name = "{0} on {1}")
    void keepsUserPrefixAndSuffixByteForByteAndWarnsBeforeConfirming(Additions additions, Direction direction)
            throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(direction.sourceVersion, "Board B", NON_GITHUB_INPUTS, additions::around);

        // when
        MigrationRun run = fixture.run(fixture.request(), "yes");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .containsSubsequence(
                        "with your own text before or after it",
                        direction.explanation,
                        "the generated body stays exactly as it is.",
                        "Warning: your kept text can conflict with the new generated body",
                        "Replace the generated body in \"Board B\"",
                        "OK  Migrated " + workflow.path());
        assertThat(workflow.path())
                .hasContent(workflowFile(NON_GITHUB_INPUTS, additions.around(targetBody(NON_GITHUB_INPUTS))));
        assertThat(metadataOf(Files.readString(workflow.path()))).isEqualTo(metadataOf(workflow.content()));
    }

    static List<Arguments> additionsInBothDirections() {
        List<Arguments> cases = new ArrayList<>();
        for (Additions additions : Additions.values()) {
            for (Direction direction : Direction.values()) {
                cases.add(Arguments.of(additions, direction));
            }
        }
        return cases;
    }

    @EnumSource(Direction.class)
    @ParameterizedTest
    void leavesDeclinedWorkflowsUnchangedWithADirectionSpecificWarning(Direction direction) throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(direction.sourceVersion, "Board C", GITHUB_INPUTS, UnaryOperator.identity());

        // when
        MigrationRun run = fixture.run(fixture.request(), "n");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "SKIPPED  This workflow keeps its current body: " + workflow.path(), direction.declineWarning);
        assertThat(workflow.path()).hasContent(workflow.content());
        assertThat(backupOf(workflow.path())).doesNotExist();
        assertThat(fixture.recorded(workflow.path()))
                .get()
                .extracting(GeneratedWorkflowRecord::symphonyVersion)
                .isEqualTo(direction.sourceVersion);
    }

    @MethodSource("customizationsInBothDirections")
    @ParameterizedTest(name = "{0} on {1}")
    void requiresManualMigrationForCustomizedBodiesAndWritesTheTargetBodyPreview(
            Customization customization, Direction direction) throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(direction.sourceVersion, "Board D", GITHUB_INPUTS, customization.edit);
        Path preview = fixture.targetBodyPath(workflow.path());

        // when
        MigrationRun run = fixture.run(fixture.request());

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "MANUAL    \"Board D\" (" + workflow.path() + ")",
                        customization.description,
                        "Manual workflow migration needed",
                        "Symphony " + RUNNING_VERSION + " generated body:\n      " + preview,
                        "Keep the workflow metadata unchanged",
                        "symphony-trello migrate-workflows --workflow '" + workflow.path() + "' --mark-migrated",
                        "Guide: https://github.com/martin-francois/symphony-trello/blob/v" + RUNNING_VERSION
                                + "/README.md#migrate-generated-workflows",
                        direction.manualNote)
                .doesNotContain("Replace the generated body");
        assertThat(workflow.path()).hasContent(workflow.content());
        assertThat(preview).hasContent(targetBody(GITHUB_INPUTS));
    }

    static List<Arguments> customizationsInBothDirections() {
        List<Arguments> cases = new ArrayList<>();
        for (Customization customization : Customization.values()) {
            for (Direction direction : Direction.values()) {
                cases.add(Arguments.of(customization, direction));
            }
        }
        return cases;
    }

    @EnumSource(Direction.class)
    @ParameterizedTest
    void treatsMalformedFrontMatterAsManualWithoutWriting(Direction direction) throws Exception {
        // given
        String original = "# Notes without front matter\n" + otherVersionBody(GITHUB_INPUTS);
        Path workflow = fixture.connectedWorkflow("Board E", GITHUB_INPUTS, original);
        fixture.record(workflow, direction.sourceVersion, GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));

        // when
        MigrationRun run = fixture.run(fixture.request());

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains("MANUAL    \"Board E\"", "the front matter is missing or unclosed", "Fix the workflow file");
        assertThat(workflow).hasContent(original);
        assertThat(fixture.targetBodyPath(workflow)).doesNotExist();
    }

    @Test
    void usesCurrentListSettingsOnlyToRecognizeOrPreviewUnversionedWorkflows() throws Exception {
        // given
        Path current = fixture.connectedWorkflow(
                "Current Board", GITHUB_INPUTS, workflowFile(GITHUB_INPUTS, targetBody(GITHUB_INPUTS)));
        String unknownContent = workflowFile(NON_GITHUB_INPUTS, otherVersionBody(NON_GITHUB_INPUTS));
        Path unknown = fixture.connectedWorkflow("Unknown Board", NON_GITHUB_INPUTS, unknownContent);

        // when
        MigrationRun run = fixture.run(fixture.request());

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "CURRENT   \"Current Board\"",
                        "MANUAL    \"Unknown Board\"",
                        "Symphony has no record of the generated body this workflow started from",
                        "Symphony rendered this body from the workflow's current list settings.")
                .doesNotContain("Replace the generated body");
        assertThat(unknown).hasContent(unknownContent);
        assertThat(fixture.targetBodyPath(unknown)).hasContent(targetBody(NON_GITHUB_INPUTS));
        assertThat(fixture.recorded(current))
                .get()
                .extracting(GeneratedWorkflowRecord::inputs, GeneratedWorkflowRecord::body)
                .containsExactly(GITHUB_INPUTS, targetBody(GITHUB_INPUTS));
        assertThat(fixture.recorded(unknown)).isEmpty();
    }

    @Test
    void skipsWorkflowsWhoseGeneratedBodyDidNotChange() throws Exception {
        // given
        String customized = workflowFile(GITHUB_INPUTS, HAND_WRITTEN_BODY);
        Path workflow = fixture.connectedWorkflow("Board F", GITHUB_INPUTS, customized);
        fixture.record(workflow, OLDER_VERSION, GITHUB_INPUTS, targetBody(GITHUB_INPUTS));

        // when
        MigrationRun run = fixture.run(fixture.request());

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains("OK        \"Board F\"", "the generated workflow body did not change")
                .doesNotContain("Manual workflow migration needed", "Replace the generated body");
        assertThat(workflow).hasContent(customized);
    }

    @Test
    void appliesABatchConfirmationOnlyToReplaceableWorkflowsInAMixedSet() throws Exception {
        // given
        GeneratedWorkflow exact =
                fixture.generatedBy(OLDER_VERSION, "Exact Board", GITHUB_INPUTS, UnaryOperator.identity());
        GeneratedWorkflow additions =
                fixture.generatedBy(OLDER_VERSION, "Additions Board", NON_GITHUB_INPUTS, Additions.PREFIX_ONLY::around);
        GeneratedWorkflow customized =
                fixture.generatedBy(OLDER_VERSION, "Customized Board", GITHUB_INPUTS, body -> HAND_WRITTEN_BODY);

        // when
        MigrationRun run = fixture.run(fixture.request(), "all");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .containsSubsequence(
                        "MIGRATE   \"Exact Board\"",
                        "MIGRATE   \"Additions Board\"",
                        "MANUAL    \"Customized Board\"",
                        "Replace the generated body in all 2 workflows above?",
                        "OK  Migrated " + exact.path(),
                        "OK  Migrated " + additions.path())
                .doesNotContain("Replace the generated body in \"");
        assertThat(exact.path()).hasContent(workflowFile(GITHUB_INPUTS, targetBody(GITHUB_INPUTS)));
        assertThat(additions.path())
                .hasContent(
                        workflowFile(NON_GITHUB_INPUTS, Additions.PREFIX_ONLY.around(targetBody(NON_GITHUB_INPUTS))));
        assertThat(customized.path()).hasContent(customized.content());
    }

    @Test
    void asksForEachWorkflowWhenTheBatchAnswerIsEach() throws Exception {
        // given
        GeneratedWorkflow first =
                fixture.generatedBy(OLDER_VERSION, "First Board", GITHUB_INPUTS, UnaryOperator.identity());
        GeneratedWorkflow second =
                fixture.generatedBy(OLDER_VERSION, "Second Board", NON_GITHUB_INPUTS, UnaryOperator.identity());

        // when
        MigrationRun run = fixture.run(fixture.request(), "", "n", "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .containsSubsequence(
                        "Enter all, each, or none [each]",
                        "Replace the generated body in \"First Board\"",
                        "SKIPPED  This workflow keeps its current body: " + first.path(),
                        "Replace the generated body in \"Second Board\"",
                        "OK  Migrated " + second.path());
        assertThat(first.path()).hasContent(first.content());
        assertThat(second.path()).hasContent(workflowFile(NON_GITHUB_INPUTS, targetBody(NON_GITHUB_INPUTS)));
    }

    @Test
    void dryRunShowsTheSamePlanWithoutWritingAnything() throws Exception {
        // given
        GeneratedWorkflow exact =
                fixture.generatedBy(OLDER_VERSION, "Exact Board", GITHUB_INPUTS, UnaryOperator.identity());
        GeneratedWorkflow manual =
                fixture.generatedBy(OLDER_VERSION, "Manual Board", GITHUB_INPUTS, body -> HAND_WRITTEN_BODY);
        Path current = fixture.connectedWorkflow(
                "Current Board", GITHUB_INPUTS, workflowFile(GITHUB_INPUTS, targetBody(GITHUB_INPUTS)));
        String storeBefore = Files.readString(configDir.resolve(GeneratedWorkflowStore.FILE_NAME));

        // when
        MigrationRun run = fixture.run(fixture.dryRun());

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "MIGRATE   \"Exact Board\"",
                        "MANUAL    \"Manual Board\"",
                        "CURRENT   \"Current Board\"",
                        "WOULD write the Symphony " + RUNNING_VERSION + " generated body to:\n      "
                                + fixture.targetBodyPath(manual.path()),
                        "WOULD back up and replace the generated body in:\n    " + exact.path())
                .doesNotContain("Replace the generated body in");
        assertThat(exact.path()).hasContent(exact.content());
        assertThat(manual.path()).hasContent(manual.content());
        assertThat(backupOf(exact.path())).doesNotExist();
        assertThat(fixture.targetBodyPath(manual.path())).doesNotExist();
        assertThat(configDir.resolve(GeneratedWorkflowStore.FILE_NAME)).hasContent(storeBefore);
        assertThat(fixture.recorded(current)).isEmpty();
    }

    @EnumSource(Direction.class)
    @ParameterizedTest
    void nonInteractiveRunsReportWithoutAskingOrWritingFiles(Direction direction) throws Exception {
        // given
        GeneratedWorkflow replaceable =
                fixture.generatedBy(direction.sourceVersion, "Board G", GITHUB_INPUTS, UnaryOperator.identity());
        GeneratedWorkflow manual =
                fixture.generatedBy(direction.sourceVersion, "Board H", GITHUB_INPUTS, body -> HAND_WRITTEN_BODY);

        // when
        MigrationRun run = fixture.run(fixture.nonInteractive(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains(
                        "No workflow was changed. Run symphony-trello migrate-workflows in a terminal",
                        "in a terminal to write the Symphony " + RUNNING_VERSION + " generated body next to this",
                        direction.nonInteractiveNote)
                .doesNotContain("Replace the generated body in");
        assertThat(replaceable.path()).hasContent(replaceable.content());
        assertThat(manual.path()).hasContent(manual.content());
        assertThat(fixture.targetBodyPath(manual.path())).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void treatsLfAndCrlfBodiesAlikeAndKeepsTheirStyle(String lineBreak) throws Exception {
        // given
        String original = workflowFile(
                        GITHUB_INPUTS, Additions.PREFIX_AND_SUFFIX.around(otherVersionBody(GITHUB_INPUTS)))
                .replace("\n", lineBreak);
        Path workflow = fixture.connectedWorkflow("Board H", GITHUB_INPUTS, original);
        fixture.record(workflow, OLDER_VERSION, GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));

        // when
        MigrationRun run = fixture.run(fixture.request(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("MIGRATE   \"Board H\"");
        assertThat(workflow)
                .hasContent(workflowFile(GITHUB_INPUTS, Additions.PREFIX_AND_SUFFIX.around(targetBody(GITHUB_INPUTS)))
                        .replace("\n", lineBreak));
    }

    @Test
    void preservesPosixPermissionsOfTheMigratedWorkflowAndItsBackup() throws Exception {
        // given
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
        GeneratedWorkflow workflow =
                fixture.generatedBy(OLDER_VERSION, "Board I", GITHUB_INPUTS, UnaryOperator.identity());
        Files.setPosixFilePermissions(workflow.path(), permissions);

        // when
        MigrationRun run = fixture.run(fixture.request(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(Files.getPosixFilePermissions(workflow.path())).isEqualTo(permissions);
        assertThat(Files.getPosixFilePermissions(backupOf(workflow.path()))).isEqualTo(permissions);
    }

    @Test
    void reportsAFailedReplacementAsAnUnexpectedFailureAndLeavesTheOriginalInPlace() throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(OLDER_VERSION, "Board J", GITHUB_INPUTS, UnaryOperator.identity());
        var writer = new WorkflowBodyWriter(
                WorkflowMigrationFixture.clock(),
                (source, target) -> {
                    throw new IOException("simulated disk full");
                },
                ignored -> {});

        // when
        MigrationRun run = WorkflowMigrationFixture.run(fixture.migration(writer), fixture.request(), "y");

        // then
        assertThat(run.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(run.failureCode()).hasValue("setup_workflow_migration_failed");
        assertThat(SetupDiagnosticReporter.shouldReport(
                        new TrelloBoardSetupException("setup_workflow_migration_failed", "failed")))
                .as("a failed workflow write must produce a troubleshooting report")
                .isTrue();
        assertThat(run.output())
                .contains(
                        "FAILED  Could not migrate this workflow (the workflow was not changed (simulated disk full)):\n"
                                + "          " + workflow.path(),
                        "The backup of the original workflow is: " + backupOf(workflow.path()));
        assertThat(workflow.path()).hasContent(workflow.content());
        assertThat(fixture.recorded(workflow.path()))
                .get()
                .extracting(GeneratedWorkflowRecord::symphonyVersion)
                .isEqualTo(OLDER_VERSION);
    }

    @Test
    void restoresTheBackupWhenTheMigratedWorkflowFailsValidation() throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(OLDER_VERSION, "Board K", GITHUB_INPUTS, UnaryOperator.identity());
        var checks = new AtomicInteger();
        var writer = new WorkflowBodyWriter(
                WorkflowMigrationFixture.clock(),
                (source, target) -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING),
                checked -> {
                    if (checked.equals(workflow.path()) && checks.incrementAndGet() == 1) {
                        throw new IOException("simulated validation failure");
                    }
                });

        // when
        MigrationRun run = WorkflowMigrationFixture.run(fixture.migration(writer), fixture.request(), "y");

        // then
        assertThat(run.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(run.failureCode()).hasValue("setup_workflow_migration_failed");
        assertThat(run.output())
                .contains(
                        "the migrated workflow did not pass validation (simulated validation failure)",
                        "Symphony restored the original workflow from the backup: " + backupOf(workflow.path()));
        assertThat(workflow.path()).hasContent(workflow.content());
    }

    @Test
    void doesNotFollowASymbolicLinkInTheConnectedWorkflowSet() throws Exception {
        // given
        Path outside = Files.createDirectories(configDir.resolve("outside")).resolve("real.md");
        String original = workflowFile(GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));
        Files.writeString(outside, original);
        Path workflow = fixture.connectedWorkflow("Linked Board", GITHUB_INPUTS, "placeholder");
        Files.delete(workflow);
        try {
            Files.createSymbolicLink(workflow, outside);
        } catch (UnsupportedOperationException | IOException e) {
            abort("symbolic links are unavailable: " + e.getMessage());
        }
        fixture.record(workflow, OLDER_VERSION, GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));

        // when
        MigrationRun run = fixture.run(fixture.request(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output())
                .contains("MANUAL    \"Linked Board\"", "the workflow path is a symbolic link")
                .doesNotContain("Replace the generated body");
        assertThat(outside).hasContent(original);
        assertThat(configDir.resolve("outside")).isDirectoryNotContaining("glob:**/real.md.*");
    }

    @Test
    void checksOnlyConnectedOrExplicitlySelectedWorkflows() throws Exception {
        // given
        String unrelatedContent = workflowFile(GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));
        Path unrelated = configDir.resolve("WORKFLOW.unrelated.md");
        Files.writeString(unrelated, unrelatedContent);
        fixture.record(unrelated, OLDER_VERSION, GITHUB_INPUTS, otherVersionBody(GITHUB_INPUTS));
        Path explicitDirectory = Files.createDirectories(configDir.resolve("repository"));
        Path explicit = explicitDirectory.resolve("WORKFLOW.md");
        Files.writeString(explicit, workflowFile(NON_GITHUB_INPUTS, otherVersionBody(NON_GITHUB_INPUTS)));
        fixture.record(explicit, OLDER_VERSION, NON_GITHUB_INPUTS, otherVersionBody(NON_GITHUB_INPUTS));

        // when
        MigrationRun all = fixture.run(fixture.request());
        MigrationRun selected = fixture.run(fixture.explicitWorkflow(explicit, false), "y");

        // then
        assertThat(all.output()).contains("No connected workflows to check.").doesNotContain(unrelated.toString());
        assertThat(selected.output())
                .contains("MIGRATE   \"WORKFLOW.md\" (" + explicit + ")", "OK  Migrated " + explicit);
        assertThat(explicit).hasContent(workflowFile(NON_GITHUB_INPUTS, targetBody(NON_GITHUB_INPUTS)));
        assertThat(unrelated).hasContent(unrelatedContent);
    }

    @Test
    void checksOnlyTheBoardSelectedByName() throws Exception {
        // given
        GeneratedWorkflow other =
                fixture.generatedBy(OLDER_VERSION, "Other Board", GITHUB_INPUTS, UnaryOperator.identity());
        GeneratedWorkflow selected =
                fixture.generatedBy(OLDER_VERSION, "Selected Board", GITHUB_INPUTS, UnaryOperator.identity());

        // when
        MigrationRun run = fixture.run(fixture.board("Selected Board"), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("OK  Migrated " + selected.path()).doesNotContain("Other Board");
        assertThat(other.path()).hasContent(other.content());
    }

    @Test
    void rejectsAMajorVersionDowngradeBeforeCheckingWorkflows() throws Exception {
        // given
        GeneratedWorkflow workflow = fixture.generatedBy("2.0.0", "Board L", GITHUB_INPUTS, UnaryOperator.identity());

        // when
        MigrationRun fromNewerMajor = fixture.run(fixture.fromVersion("2.1.0"), "y");
        MigrationRun recordFromNewerMajor = fixture.run(fixture.request(), "y");

        // then
        assertThat(fromNewerMajor.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(fromNewerMajor.output())
                .contains(
                        "REJECTED  Symphony 2.1.0 was replaced by Symphony " + RUNNING_VERSION,
                        "Reinstall a Symphony 2.x release")
                .doesNotContain("Board L");
        assertThat(recordFromNewerMajor.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(recordFromNewerMajor.output())
                .contains("REJECTED  \"Board L\"", "downgrading to another major version is not supported")
                .doesNotContain("Replace the generated body");
        assertThat(workflow.path()).hasContent(workflow.content());
    }

    @Test
    void marksAManualMigrationSoLaterChecksCompareAgainstTheCurrentBody() throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(OLDER_VERSION, "Board M", GITHUB_INPUTS, body -> HAND_WRITTEN_BODY);

        // when
        MigrationRun mark = fixture.run(fixture.explicitWorkflow(workflow.path(), true));
        MigrationRun later = fixture.run(fixture.request());

        // then
        assertThat(mark.output())
                .contains("OK  Recorded " + workflow.path() + " as migrated to the Symphony " + RUNNING_VERSION);
        assertThat(later.output()).contains("OK        \"Board M\"").doesNotContain("MANUAL");
        assertThat(workflow.path()).hasContent(workflow.content());
        assertThat(fixture.recorded(workflow.path()))
                .get()
                .extracting(GeneratedWorkflowRecord::symphonyVersion)
                .isEqualTo(RUNNING_VERSION);
    }

    @Test
    void ignoresUnknownFieldsWrittenByANewerVersionOfTheStore() throws Exception {
        // given
        GeneratedWorkflow workflow =
                fixture.generatedBy(NEWER_VERSION, "Board N", GITHUB_INPUTS, UnaryOperator.identity());
        Path store = configDir.resolve(GeneratedWorkflowStore.FILE_NAME);
        Files.writeString(
                store,
                Files.readString(store)
                        .replace("\"githubEnabled\"", "\"newerInput\" : \"x\", \"githubEnabled\"")
                        .replace("\"formatVersion\"", "\"newerField\" : 7, \"formatVersion\""));

        // when
        MigrationRun run = fixture.run(fixture.request(), "y");

        // then
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("OK  Migrated " + workflow.path());
        assertThat(workflow.path()).hasContent(workflowFile(GITHUB_INPUTS, targetBody(GITHUB_INPUTS)));
    }

    private static String metadataOf(String content) {
        return WorkflowFileText.parse(content).map(WorkflowFileText::metadata).orElseThrow();
    }

    /// Update and supported downgrade share one flow; only the wording around the decision differs.
    enum Direction {
        UPDATE(
                OLDER_VERSION,
                "The generated workflow changed since Symphony " + OLDER_VERSION,
                "It does not get the Symphony " + RUNNING_VERSION + " workflow fixes and features.",
                "Run symphony-trello migrate-workflows in a terminal",
                "Keep the workflow metadata unchanged"),
        DOWNGRADE(
                NEWER_VERSION,
                "This workflow was created for a newer Symphony version (Symphony " + NEWER_VERSION + ")",
                Direction.NEWER_FEATURES_WARNING,
                Direction.NEWER_FEATURES_WARNING,
                Direction.NEWER_FEATURES_WARNING);

        private static final String NEWER_FEATURES_WARNING =
                "It may mention features that Symphony " + RUNNING_VERSION + " does not support";

        private final String sourceVersion;
        private final String explanation;
        private final String declineWarning;
        private final String nonInteractiveNote;
        private final String manualNote;

        Direction(
                String sourceVersion,
                String explanation,
                String declineWarning,
                String nonInteractiveNote,
                String manualNote) {
            this.sourceVersion = sourceVersion;
            this.explanation = explanation;
            this.declineWarning = declineWarning;
            this.nonInteractiveNote = nonInteractiveNote;
            this.manualNote = manualNote;
        }
    }

    /// User text kept around the generated body.
    enum Additions {
        PREFIX_ONLY(PREFIX, ""),
        SUFFIX_ONLY("", SUFFIX),
        PREFIX_AND_SUFFIX(PREFIX, SUFFIX);

        private final String prefix;
        private final String suffix;

        Additions(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }

        String around(String body) {
            return prefix + body + suffix;
        }
    }

    /// Edits that make the recorded generated body unusable for an automatic migration.
    enum Customization {
        INTERNAL_EDIT(
                body -> body.replace("## Trello Comments", "## Trello Comments (read twice)"), Customization.EDITED),
        REORDERED_TEXT(body -> body.substring(GENERATED_TITLE.length()) + GENERATED_TITLE, Customization.EDITED),
        DELETED_TEXT(body -> body.replace("## Trello Comments", ""), Customization.EDITED),
        DUPLICATE_GENERATED_BODY(body -> body + "\n" + body, "appears more than once");

        private static final String EDITED = "was edited, reordered, or partly removed";

        private final UnaryOperator<String> edit;
        private final String description;

        Customization(UnaryOperator<String> edit, String description) {
            this.edit = edit;
            this.description = description;
        }
    }
}
