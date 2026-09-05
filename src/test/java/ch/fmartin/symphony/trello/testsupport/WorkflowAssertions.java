package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkflowAssertions {
    private final String content;

    private WorkflowAssertions(Path workflow) {
        try {
            this.content = Files.readString(workflow);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read workflow " + workflow, e);
        }
    }

    public static WorkflowAssertions assertThatWorkflow(Path workflow) {
        return new WorkflowAssertions(workflow);
    }

    public WorkflowAssertions hasGithubFlow() {
        assertThat(content).contains("## Pull Request Publication");
        return this;
    }

    public WorkflowAssertions hasNoGithubFlow() {
        assertThat(content).doesNotContain("## Pull Request Publication", "linked PR comments");
        return this;
    }

    public WorkflowAssertions hasMerging() {
        assertThat(content).contains("Merging");
        return this;
    }

    public WorkflowAssertions doesNotHaveMerging() {
        assertThat(content).doesNotContain("Merging");
        return this;
    }

    public WorkflowAssertions hasAdditionalWritableRoot(Path root) {
        assertThat(content).contains(root.toString());
        return this;
    }

    public WorkflowAssertions hasNoAdditionalWritableRoot(Path root) {
        assertThat(content).doesNotContain(root.toString());
        return this;
    }

    public WorkflowAssertions hasNoAdditionalWritableRoots() {
        assertThat(content).doesNotContain("  additional_writable_roots:");
        return this;
    }

    public WorkflowAssertions hasDangerFullAccess() {
        assertThat(content).contains("turn_sandbox_policy:", "dangerFullAccess");
        return this;
    }

    public WorkflowAssertions hasNoDangerFullAccess() {
        assertThat(content).doesNotContain("dangerFullAccess");
        return this;
    }

    public WorkflowAssertions hasNetworkEnabledWorkspaceSandbox() {
        assertThat(content)
                .contains("turn_sandbox_policy:", "type: workspaceWrite", "networkAccess: true")
                .doesNotContain("dangerFullAccess");
        return this;
    }

    public WorkflowAssertions hasNoNetworkEnabledWorkspaceSandbox() {
        assertThat(content).doesNotContain("networkAccess: true");
        return this;
    }

    public WorkflowAssertions hasServerPort(int port) {
        assertThat(content).contains("port: " + port);
        return this;
    }

    public WorkflowAssertions doesNotHaveServerPort(int port) {
        assertThat(content).doesNotContain("port: " + port);
        return this;
    }

    public WorkflowAssertions contains(String... fragments) {
        assertThat(content).contains(fragments);
        return this;
    }

    public WorkflowAssertions doesNotContain(String... fragments) {
        assertThat(content).doesNotContain(fragments);
        return this;
    }

    public WorkflowAssertions hasBoardId(String idOrKey) {
        assertThat(content).contains("board_id: \"" + idOrKey + "\"");
        return this;
    }

    public WorkflowAssertions hasMaxAgents(int maxAgents) {
        assertThat(content).contains("max_concurrent_agents: " + maxAgents);
        return this;
    }

    public WorkflowAssertions hasRepositoryDefaultUrl(String repositoryUrl) {
        assertThat(content).contains("default_url: \"" + repositoryUrl + "\"");
        return this;
    }

    public WorkflowAssertions hasRepositoryDefaults(String repositoryUrl, String repositoryPath) {
        assertThat(content)
                .contains("default_url: \"" + repositoryUrl + "\"", "default_path: \"" + repositoryPath + "\"");
        return this;
    }

    public WorkflowAssertions hasNoRepositoryDefaultUrl() {
        assertThat(content).contains("default_url: null");
        return this;
    }

    public WorkflowAssertions hasActiveStates(String... states) {
        assertThat(content).contains(states);
        return this;
    }

    public WorkflowAssertions hasTerminalStates(String... states) {
        assertThat(content).contains(states);
        return this;
    }

    public WorkflowAssertions hasInProgressState() {
        assertThat(content).contains("In Progress");
        return this;
    }

    public WorkflowAssertions hasNoInProgressState() {
        assertThat(content).doesNotContain("In Progress");
        return this;
    }

    public WorkflowAssertions hasBlockedState() {
        assertThat(content).contains("Blocked");
        return this;
    }

    public WorkflowAssertions hasNoBlockedState() {
        assertThat(content).doesNotContain("Blocked");
        return this;
    }

    public WorkflowAssertions hasNoPullRequestPublicationOrMergeRequirements() {
        assertThat(content)
                .doesNotContain("## Pull Request Publication", "## Merge From \"Merging\"", "linked PR comments");
        return this;
    }
}
