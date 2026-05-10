package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkflowAssertions {
    private final String content;

    private WorkflowAssertions(Path workflow) {
        try {
            this.content = Files.readString(workflow, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow " + workflow, e);
        }
    }

    static WorkflowAssertions assertThatWorkflow(Path workflow) {
        return new WorkflowAssertions(workflow);
    }

    WorkflowAssertions hasGithubFlow() {
        assertThat(content).contains("## Pull Request Publication");
        return this;
    }

    WorkflowAssertions hasNoGithubFlow() {
        assertThat(content).doesNotContain("## Pull Request Publication", "linked PR comments");
        return this;
    }

    WorkflowAssertions hasMerging() {
        assertThat(content).contains("Merging");
        return this;
    }

    WorkflowAssertions doesNotHaveMerging() {
        assertThat(content).doesNotContain("Merging");
        return this;
    }

    WorkflowAssertions hasAdditionalWritableRoot(Path root) {
        assertThat(content).contains(root.toString());
        return this;
    }

    WorkflowAssertions hasNoAdditionalWritableRoot(Path root) {
        assertThat(content).doesNotContain(root.toString());
        return this;
    }

    WorkflowAssertions hasNoAdditionalWritableRoots() {
        assertThat(content).doesNotContain("additional_writable_roots");
        return this;
    }

    WorkflowAssertions hasDangerFullAccess() {
        assertThat(content).contains("turn_sandbox_policy:", "dangerFullAccess");
        return this;
    }

    WorkflowAssertions hasNoDangerFullAccess() {
        assertThat(content).doesNotContain("dangerFullAccess");
        return this;
    }

    WorkflowAssertions hasServerPort(int port) {
        assertThat(content).contains("port: " + port);
        return this;
    }

    WorkflowAssertions doesNotHaveServerPort(int port) {
        assertThat(content).doesNotContain("port: " + port);
        return this;
    }

    WorkflowAssertions hasBoardId(String idOrKey) {
        assertThat(content).contains("board_id: \"" + idOrKey + "\"");
        return this;
    }

    WorkflowAssertions hasMaxAgents(int maxAgents) {
        assertThat(content).contains("max_concurrent_agents: " + maxAgents);
        return this;
    }

    WorkflowAssertions hasActiveStates(String... states) {
        assertThat(content).contains(states);
        return this;
    }

    WorkflowAssertions hasTerminalStates(String... states) {
        assertThat(content).contains(states);
        return this;
    }

    WorkflowAssertions hasInProgressState() {
        assertThat(content).contains("In Progress");
        return this;
    }

    WorkflowAssertions hasNoInProgressState() {
        assertThat(content).doesNotContain("In Progress");
        return this;
    }

    WorkflowAssertions hasBlockedState() {
        assertThat(content).contains("Blocked");
        return this;
    }

    WorkflowAssertions hasNoBlockedState() {
        assertThat(content).doesNotContain("Blocked");
        return this;
    }

    WorkflowAssertions hasNoPullRequestPublicationOrLandingRequirements() {
        assertThat(content)
                .doesNotContain("## Pull Request Publication", "## Landing From \"Merging\"", "linked PR comments");
        return this;
    }
}
