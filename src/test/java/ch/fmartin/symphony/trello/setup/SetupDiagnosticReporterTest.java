package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupDiagnosticReporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesSanitizedReportWithInstallerToolWorkflowAndLogContext() throws Exception {
        // given
        Path appHome = tempDir.resolve("app");
        Path configDir = tempDir.resolve("config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        int port = freePort();
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.private-board.md");
        Path env = configDir.resolve(".env");
        String workflowContent =
                """
                ---
                tracker:
                  board_id: "private-board-id"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                  in_progress_state: "In Progress"
                server:
                  port: %d
                agent:
                  max_concurrent_agents: 2
                codex:
                  additional_writable_roots:
                    - "/home/alice/private-project"
                ---
                Body
                """
                        .formatted(port);
        Files.writeString(workflow, workflowContent, StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve("connected-boards.json"))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Sensitive Board Name",
                        "https://trello.com/b/private-key/sensitive-board",
                        workflow,
                        env,
                        workspaceRoot,
                        port,
                        true,
                        List.of(tempDir.resolve("private-project")),
                        false))));
        Files.writeString(
                stateHome.resolve("WORKFLOW.private-board.md.abc.err"),
                ("""
                token=secret-value
                "api_token":"json-secret"
                Authorization: Bearer bearer-secret
                Authorization: Basic basic-secret
                Authorization: token ghp-secret
                Authorization: OAuth oauth_consumer_key="oauth-key-secret", oauth_token="oauth-token-secret"
                path=/home/alice/private-project
                path=/Volumes/Client Work/repo
                unquoted workflow /Volumes/Client Work/repo/WORKFLOW.md failed
                quoted="/Users/Jane Doe/project"
                windows="C:\\Users\\Jane Doe\\repo"
                pr url https://github.com/private-org/client-repo/pull/123
                https remote https://github.com/private-org/client-repo.git
                repository git@github.com:private/repo.git
                ssh remote ssh://git@github.com/private-org/client-repo.git
                branch feature/private-ref
                nested parent path %s
                """)
                        .formatted(appHome.resolve("Secret Client").resolve("repo")),
                StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                """
                repo_url=git@github.com:private/repo.git
                ref=feature/private-ref
                TRELLO_TOKEN=secret-value
                """,
                StandardCharsets.UTF_8);
        Files.writeString(stateHome.resolve("large.log"), largeLog(), StandardCharsets.UTF_8);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "/usr/bin/git\n", "sh", "-c", "command -v -- \"$1\"", "sh", "git")
                .returns(0, "git version 2.45.0\n", "git", "--version")
                .returns(0, "/usr/bin/codex\n", "sh", "-c", "command -v -- \"$1\"", "sh", "codex")
                .returns(0, "codex-cli 1.2.3\n", "codex", "--version")
                .returns(1, "not logged in\n", "codex", "login", "status");
        var reporter = new SetupDiagnosticReporter(
                Map.of(
                        "SYMPHONY_TRELLO_APP_HOME", appHome.toString(),
                        "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                        "SYMPHONY_TRELLO_WORKSPACE_ROOT", workspaceRoot.toString(),
                        "SYMPHONY_TRELLO_STATE_HOME", stateHome.toString(),
                        "SYMPHONY_TRELLO_DOTENV", env.toString(),
                        "SYMPHONY_TRELLO_REPO_URL", "git@github.com:private/repo.git",
                        "SYMPHONY_TRELLO_REF", "feature/private-ref",
                        "SHELL", "/bin/bash"),
                commands);

        Path report;
        HttpServer server = fakeLocalServer(port);
        try {

            // when
            report = reporter.write(
                            new TrelloBoardSetupException(
                                    "setup_start_unhealthy",
                                    "No connected Trello board matched \"Top Secret Board\". "
                                            + "No active list was provided. "
                                            + "Open lists: Secret Queue v2.1, Internal Backlog v3.2"),
                            List.of(
                                    "setup-local",
                                    "--non-interactive",
                                    "--name",
                                    "Top Secret Board",
                                    "--token",
                                    "split-secret",
                                    "--key=inline-secret",
                                    "--board-name",
                                    "Private Launch Board",
                                    "--board=private-short-link",
                                    "--active",
                                    "Secret Queue"))
                    .orElseThrow();
        } finally {
            server.stop(0);
        }

        // then
        assertThat(report).exists();
        assertThat(report)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "## Failure",
                        "setup_start_unhealthy",
                        "## Installer Context",
                        "## Tool Availability",
                        "codex-cli 1.2.3; login=not-ok",
                        "## Workflow Summary",
                        String.valueOf(port),
                        "## Local Health Probes",
                        "\"configured_board_hash\"",
                        "\"running_count\":1",
                        "\"retrying_count\":1",
                        "## Recent Logs",
                        "tail-line-149")
                .doesNotContain(
                        "prefix-line-0",
                        "private-board-id",
                        "older-private-short-link",
                        "private-key",
                        "Sensitive Board Name",
                        "secret-value",
                        "json-secret",
                        "bearer-secret",
                        "basic-secret",
                        "ghp-secret",
                        "oauth-key-secret",
                        "oauth-token-secret",
                        "git@github.com:private/repo.git",
                        "feature/private-ref",
                        "Top Secret Board",
                        "split-secret",
                        "inline-secret",
                        "Private Launch Board",
                        "private-short-link",
                        "Secret Queue",
                        "Secret Queue v2.1",
                        "Internal Backlog v3.2",
                        "private-card-id",
                        "TRELLO-private",
                        "private runtime message",
                        "/home/alice/private-project",
                        "/Volumes/Client Work/repo",
                        "/Users/Jane Doe/project",
                        "C:\\Users\\Jane Doe\\repo",
                        "Client Work",
                        "Jane Doe",
                        "private-org",
                        "client-repo",
                        "Secret Client",
                        tempDir.toString());
    }

    @Test
    void handledSetupFailureUsesRequestPathsForReportContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("custom-config");
        Path workspaceRoot = tempDir.resolve("custom-workspaces");
        Path manifest = Path.of("relative-connected-boards.json");
        Path resolvedManifest = configDir.resolve(manifest);
        Path workflow = configDir.resolve("WORKFLOW.request.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: "request-board-id"
                server:
                  port: 19090
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(resolvedManifest)
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "request-board-id",
                        "request-key",
                        "Request Board",
                        "https://trello.com/b/request-key/request-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19090,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException(
                        "setup_request_failed",
                        "Setup failed for Private Queue, Private Done, Private Progress, and Private Blocked. "
                                + "This token can access multiple Trello Workspaces. "
                                + "Available Workspaces: abc (Secret Workspace), def (Client Space)"),
                request(configDir, workspaceRoot, manifest, workflow, env),
                terminal);

        // then
        assertThat(report).hasValueSatisfying(path -> {
            assertThat(path).startsWith(tempDir.resolve("state").resolve("troubleshooting"));
            assertThat(path)
                    .content(StandardCharsets.UTF_8)
                    .contains("board_count:** 1", "19090")
                    .doesNotContain(
                            "Request Board",
                            "request-board-id",
                            "Private Queue",
                            "Private Done",
                            "Private Progress",
                            "Private Blocked",
                            "Secret Workspace",
                            "Client Space",
                            tempDir.toString());
        });
        assertThat(terminal.stderr()).contains("Troubleshooting report written:");
    }

    @Test
    void dryRunFailureDoesNotWriteTroubleshootingReport() {
        // given
        Path configDir = tempDir.resolve("dry-run-config");
        Path workspaceRoot = tempDir.resolve("dry-run-workspaces");
        Path manifest = Path.of("connected-boards.json");
        Path workflow = configDir.resolve("WORKFLOW.dry-run.md");
        Path env = configDir.resolve(".env");
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException("setup_dry_run_failed", "dry run failed"),
                request(configDir, workspaceRoot, manifest, workflow, env, true),
                terminal);

        // then
        assertThat(report).isEmpty();
        assertThat(tempDir.resolve("state")).doesNotExist();
        assertThat(terminal.stderr()).doesNotContain("Troubleshooting report written:");
    }

    private static HttpServer fakeLocalServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(
                "/api/v1/local-status",
                exchange -> writeJson(
                        exchange,
                        """
                {"boardId":"private-board-id","configuredBoardId":"older-private-short-link","workflowPath":"/home/alice/private-project/WORKFLOW.md"}
                """));
        server.createContext(
                "/api/v1/state",
                exchange -> writeJson(
                        exchange,
                        """
                {
                  "generatedAt":"2026-05-11T00:00:00Z",
                  "counts":{"running":1,"retrying":1},
                  "running":[{"cardId":"private-card-id","cardIdentifier":"TRELLO-private","lastMessage":"private runtime message"}],
                  "retrying":[{"cardId":"private-card-id-2","cardIdentifier":"TRELLO-private-2","error":"private retry message"}],
                  "routing":{"activeLists":["Ready for Codex"],"terminalLists":["Done"],"handoffLists":[]}
                }
                """));
        server.start();
        return server;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(body);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String largeLog() {
        StringBuilder log = new StringBuilder();
        for (int index = 0; index < 5_000; index++) {
            log.append("prefix-line-").append(index).append(" secret=old\n");
        }
        for (int index = 0; index < 150; index++) {
            log.append("tail-line-").append(index).append('\n');
        }
        return log.toString();
    }

    private static LocalSetupRequest request(
            Path configDir, Path workspaceRoot, Path manifest, Path workflow, Path env) {
        return request(configDir, workspaceRoot, manifest, workflow, env, false);
    }

    private static LocalSetupRequest request(
            Path configDir, Path workspaceRoot, Path manifest, Path workflow, Path env, boolean dryRun) {
        return new LocalSetupRequest(
                LocalSetupRequest.Action.SETUP,
                dryRun,
                true,
                false,
                false,
                Optional.empty(),
                Optional.of("key"),
                Optional.of("token"),
                Optional.of("Request Board"),
                Optional.empty(),
                Optional.empty(),
                List.of("Private Queue"),
                List.of("Private Done"),
                "Private Progress",
                false,
                "Private Blocked",
                Optional.of(workflow),
                Optional.of(workspaceRoot),
                Optional.of(configDir),
                Optional.of(manifest),
                Optional.empty(),
                1,
                false,
                Optional.of(env),
                List.of(),
                false,
                true,
                false,
                URI.create("https://api.trello.com/1"));
    }
}
