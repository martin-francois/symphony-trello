package ch.fmartin.symphony.trello.setup;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/// Renders report fixtures through the CommonMark parser and GFM table extension.
final class MarkdownRendering {
    private MarkdownRendering() {}

    static String render(String markdown) {
        List<Extension> extensions = List.of(TablesExtension.create());
        var parser = Parser.builder().extensions(extensions).build();
        var renderer = HtmlRenderer.builder().extensions(extensions).build();
        return renderer.render(parser.parse(markdown));
    }
}
