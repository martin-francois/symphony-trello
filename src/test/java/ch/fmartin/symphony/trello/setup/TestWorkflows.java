package ch.fmartin.symphony.trello.setup;

/**
 * Fluent builder for the minimal {@code WORKFLOW.md} markdown exercised by the setup and worker
 * tests.
 *
 * <p>The canonical inlined block across these tests is a YAML front matter with a {@code tracker}
 * (kind plus {@code board_id}) and a {@code server.port}, followed by a one-line markdown body. This
 * builder reproduces that exact byte layout: each field is emitted verbatim, so callers keep full
 * control over quoting, environment-variable references, and deliberately malformed values that the
 * parsing tests assert against. Only blocks whose fields match this shape exactly should adopt the
 * builder; bespoke blocks with extra sections (active_states, polling, codex, priority labels, ...)
 * stay inlined so their distinguishing fields remain visible.
 */
final class TestWorkflows {
    private String kind = "trello";
    private String boardId = "board-1";
    private String port = "18080";
    private String body = "# Queue";

    private TestWorkflows() {}

    static TestWorkflows workflow() {
        return new TestWorkflows();
    }

    /** Tracker {@code kind} value, emitted verbatim (defaults to {@code trello}). */
    TestWorkflows kind(String value) {
        this.kind = value;
        return this;
    }

    /** {@code board_id} value, emitted verbatim so the caller controls quoting. */
    TestWorkflows boardId(String value) {
        this.boardId = value;
        return this;
    }

    /** {@code server.port} value, emitted verbatim (literal, quoted, or {@code $VAR}). */
    TestWorkflows port(int value) {
        this.port = Integer.toString(value);
        return this;
    }

    /** {@code server.port} value, emitted verbatim (literal, quoted, or {@code $VAR}). */
    TestWorkflows port(String value) {
        this.port = value;
        return this;
    }

    /** Markdown body after the front matter, emitted verbatim (defaults to {@code # Queue}). */
    TestWorkflows body(String value) {
        this.body = value;
        return this;
    }

    /** Renders the workflow markdown, terminated with a trailing newline like a text block. */
    String render() {
        return """
                ---
                tracker:
                  kind: %s
                  board_id: %s
                server:
                  port: %s
                ---
                %s
                """
                .formatted(kind, boardId, port, body);
    }
}
