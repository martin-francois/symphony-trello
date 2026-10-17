package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Renders the GitHub-flavored Markdown tables in diagnostics reports.
///
/// Only table syntax is escaped: a pipe becomes `\|`, and each CR or LF becomes a space. Every
/// other character stays verbatim, because users paste the report as plain text and readers and
/// tests match column names and redaction markers literally. Callers redact values before adding a
/// row. commonmark-java was evaluated as a replacement and rejected because its Markdown renderer
/// changes those bytes; see docs/adr/0120-keep-local-diagnostics-markdown-table-helper.md.
final class MarkdownTable {
    private final List<String> headers;
    private final List<Alignment> alignments;
    private final List<List<String>> rows;

    private MarkdownTable(List<String> headers, List<Alignment> alignments) {
        checkArgument(headers != null && !headers.isEmpty(), "Markdown table must have at least one header");
        checkArgument(
                alignments != null && alignments.size() == headers.size(),
                "Markdown table alignment count must match header count");
        this.headers = List.copyOf(headers);
        this.alignments = List.copyOf(alignments);
        this.rows = new ArrayList<>();
    }

    static MarkdownTable of(List<String> headers, List<Alignment> alignments) {
        return new MarkdownTable(headers, alignments);
    }

    static MarkdownTable leftAligned(List<String> headers) {
        return new MarkdownTable(
                headers, headers.stream().map(header -> Alignment.LEFT).toList());
    }

    MarkdownTable row(Object... cells) {
        checkArgument(
                cells.length == headers.size(),
                "Markdown table row cell count must match header count: expected %s but got %s",
                headers.size(),
                cells.length);
        rows.add(Arrays.stream(cells).map(MarkdownTable::escape).toList());
        return this;
    }

    void appendTo(StringBuilder body) {
        appendRow(body, headers);
        appendRow(body, alignments.stream().map(Alignment::marker).toList());
        rows.forEach(row -> appendRow(body, row));
    }

    private static void appendRow(StringBuilder body, List<String> cells) {
        body.append("| ");
        body.append(String.join(" | ", cells));
        body.append(" |\n");
    }

    private static String escape(Object cell) {
        if (cell == null) {
            return "";
        }
        return cell.toString().replace("|", "\\|").replace('\r', ' ').replace('\n', ' ');
    }

    enum Alignment {
        LEFT("---"),
        RIGHT("---:"),
        CENTER(":---:");

        private final String marker;

        Alignment(String marker) {
            this.marker = marker;
        }

        String marker() {
            return marker;
        }
    }
}
