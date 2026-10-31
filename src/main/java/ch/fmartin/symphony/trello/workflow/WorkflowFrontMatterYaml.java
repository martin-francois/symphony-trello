package ch.fmartin.symphony.trello.workflow;

import ch.fmartin.symphony.trello.SingleLineText;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;

/// Reads `WORKFLOW.md` front matter into the map that the workflow loader, the startup port check,
/// and setup share. Scalars decode exactly as Jackson's YAML reader decodes them; on top of that the
/// reader applies the YAML rules of `SPEC.md` section 5.2: aliases resolve to their anchored value,
/// merge keys and repeated keys fail, and front matter without content is an empty map. ADR 0147
/// explains why the rules sit on Jackson's event hook instead of another YAML library.
public final class WorkflowFrontMatterYaml {
    /// The most YAML nodes that all aliases in one front matter may copy, so a few lines of nested
    /// aliases cannot expand into a huge config. A real workflow copies a few hundred at most. ADR
    /// 0147 records why this value; `SPEC.md` section 5.2 and the tests take it from here.
    public static final int MAX_ALIAS_EXPANDED_NODES = 10_000;

    /// The most scalar characters that all aliases in one front matter may copy, so a few aliases of
    /// one long scalar cannot turn into a config that a setup write would store gigabytes of. A real
    /// workflow copies a few kilobytes at most. ADR 0147 records why this value; `SPEC.md` section
    /// 5.2 and the tests take it from here.
    public static final int MAX_ALIAS_COPIED_CHARACTERS = 1_000_000;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper(new Factory());

    private WorkflowFrontMatterYaml() {}

    /// Returns the front matter as a map, or `null` when the YAML is the single value `null`.
    /// Throws [RuleViolation] when the YAML breaks one of the section 5.2 rules, and another
    /// [JsonProcessingException] when it is not valid YAML or not a map.
    public static @Nullable LinkedHashMap<String, Object> readMap(String frontMatter) throws JsonProcessingException {
        return MAPPER.readValue(frontMatter, MAP_TYPE);
    }

    /// A front matter that is valid YAML but breaks a section 5.2 rule.
    /// [JsonProcessingException#getOriginalMessage()] is one line that names the offending key or
    /// alias and says how to fix it, without any value from the front matter.
    public static final class RuleViolation extends JsonParseException {
        @Serial
        private static final long serialVersionUID = 1L;

        RuleViolation(YAMLParser parser, String message) {
            super(parser, message);
        }
    }

    /// Creates [FrontMatterParser] for the [Reader] that `YAMLFactory.createParser(String)` opens,
    /// the only entry point [#readMap(String)] uses. [YAMLParser.Feature#EMPTY_DOCUMENT_AS_EMPTY_OBJECT]
    /// turns empty and comment-only front matter into an empty map. It enables the feature on top of
    /// the defaults, because `YAMLFactory.builder()` starts without them and would then read `key:` as
    /// `""` instead of null.
    private static final class Factory extends YAMLFactory {
        @Serial
        private static final long serialVersionUID = 1L;

        Factory() {
            enable(YAMLParser.Feature.EMPTY_DOCUMENT_AS_EMPTY_OBJECT);
        }

        @Override
        protected YAMLParser _createParser(Reader reader, IOContext ctxt) {
            return new FrontMatterParser(
                    ctxt, _parserFeatures, _yamlParserFeatures, _loaderOptions, _objectCodec, reader);
        }
    }

    /// Replaces each alias with the events of its anchored node before Jackson turns events into
    /// tokens, and checks every mapping key Jackson reads. [YAMLParser#getEvent()] is the hook Jackson
    /// provides for exactly this kind of event rewriting.
    private static final class FrontMatterParser extends YAMLParser {
        // The events of anchored nodes in document order, with each alias inside them kept as a
        // reference to the node it named there. Every anchor is a range of this one list, so memory
        // grows with the input and not with how deeply anchored collections nest.
        private final List<Piece> recorded = new ArrayList<>();
        private long recordedNodes;
        private long recordedCharacters;
        // The latest complete definition of each anchor.
        private final Map<String, Anchored> anchors = new HashMap<>();
        // When each anchor name was last declared, counted in document order. An anchored collection
        // declares its name where it starts, so a later declaration inside it wins once both end.
        private final Map<String, Integer> latestDeclarations = new HashMap<>();
        private int declarations;
        // Collections of the document that have started and not ended.
        private int openCollections;
        // Anchored collections whose end event has not arrived yet; the innermost is first.
        private final Deque<Recording> openRecordings = new ArrayDeque<>();
        // The parts of a copy still to hand to Jackson; the innermost alias is first.
        private final Deque<Replay> replays = new ArrayDeque<>();
        // The alias in the document whose copy Jackson reads, or null while it reads the document.
        private @Nullable AliasEvent copyingAlias;
        // The keys read so far in each open mapping; the innermost is first.
        private final Deque<OpenMapping> openMappings = new ArrayDeque<>();
        private long aliasExpandedNodes;
        // Collections Jackson has been handed and not yet closed, copies included.
        private int deliveredDepth;
        private long aliasCopiedCharacters;

        FrontMatterParser(
                IOContext ctxt,
                int parserFeatures,
                int formatFeatures,
                LoaderOptions loaderOptions,
                @Nullable ObjectCodec codec,
                Reader reader) {
            super(ctxt, parserFeatures, formatFeatures, loaderOptions, codec, reader);
        }

        @Override
        protected @Nullable Event getEvent() throws IOException {
            Event copied = nextCopiedEvent();
            if (copied != null) {
                return delivered(copied);
            }
            copyingAlias = null;
            Event event = super.getEvent();
            if (event instanceof AliasEvent alias) {
                Anchored anchored = resolve(alias);
                if (!openRecordings.isEmpty()) {
                    append(new Copy(anchored));
                }
                copyingAlias = alias;
                replays.push(new Replay(anchored));
                return getEvent();
            }
            if (event != null) {
                read(event);
                delivered(event);
            }
            return event;
        }

        // Jackson stops at its nesting limit with a generic parse error. A copy can push valid YAML
        // past that limit, so the copy fails with a message that names the alias instead.
        private Event delivered(Event event) throws RuleViolation {
            if (event instanceof CollectionStartEvent) {
                deliveredDepth++;
                AliasEvent alias = copyingAlias;
                int maxDepth = streamReadConstraints().getMaxNestingDepth();
                if (alias != null && deliveredDepth > maxDepth) {
                    throw violation(
                            "aliases nest the front matter deeper than %d levels (alias *%s, front matter line %d); "
                                    + "use the alias at a shallower level or write the value instead",
                            maxDepth, SingleLineText.escaped(alias.getAnchor()), line(alias.getStartMark()));
                }
            } else if (event instanceof CollectionEndEvent) {
                deliveredDepth--;
            }
            return event;
        }

        @Override
        public @Nullable JsonToken nextToken() throws IOException {
            JsonToken token = super.nextToken();
            if (token == JsonToken.START_OBJECT) {
                openMappings.push(new OpenMapping());
            } else if (token == JsonToken.END_OBJECT) {
                openMappings.pop();
            } else if (token == JsonToken.FIELD_NAME) {
                checkKey();
            }
            return token;
        }

        // A copy can hold a further alias, which replays the node it named where the copied node was
        // defined; the explicit stack keeps long alias chains off the call stack.
        private @Nullable Event nextCopiedEvent() {
            while (!replays.isEmpty()) {
                Replay replay = replays.peek();
                if (replay.next == replay.end) {
                    replays.pop();
                    continue;
                }
                Piece piece = recorded.get(replay.next);
                replay.next++;
                switch (piece) {
                    case Read read -> {
                        return read.event();
                    }
                    case Copy copy -> replays.push(new Replay(copy.anchored()));
                }
            }
            return null;
        }

        private Anchored resolve(AliasEvent alias) throws RuleViolation {
            String anchor = alias.getAnchor();
            String name = SingleLineText.escaped(anchor);
            int line = line(alias.getStartMark());
            Anchored anchored = anchors.get(anchor);
            if (anchored == null && latestDeclarations.containsKey(anchor)) {
                throw violation(
                        "uses the alias *%s (front matter line %d) inside the node its anchor &%s marks; "
                                + "replace the alias with a value",
                        name, line, name);
            }
            if (anchored == null) {
                throw violation(
                        "uses the alias *%s (front matter line %d) before an anchor &%s defines it; "
                                + "define the anchor earlier or write the value instead",
                        name, line, name);
            }
            aliasExpandedNodes += anchored.nodes();
            if (aliasExpandedNodes > MAX_ALIAS_EXPANDED_NODES) {
                throw violation(
                        "aliases expand to more than %d YAML nodes (alias *%s, front matter line %d); "
                                + "use fewer aliases or smaller anchored values",
                        MAX_ALIAS_EXPANDED_NODES, name, line);
            }
            aliasCopiedCharacters += anchored.characters();
            if (aliasCopiedCharacters > MAX_ALIAS_COPIED_CHARACTERS) {
                throw violation(
                        "aliases copy more than %d characters of scalars (alias *%s, front matter line %d); "
                                + "use fewer aliases or shorter anchored values",
                        MAX_ALIAS_COPIED_CHARACTERS, name, line);
            }
            return anchored;
        }

        // Keeps the events that anchored nodes need, and defines an anchor once its node is complete.
        // Only events read from the document declare anchors: the anchor inside a copy was declared
        // earlier and may have been redeclared since.
        private void read(Event event) {
            int start = recorded.size();
            long nodesBefore = recordedNodes;
            long charactersBefore = recordedCharacters;
            String anchor = event instanceof NodeEvent node ? node.getAnchor() : null;
            if (anchor != null) {
                declarations++;
                latestDeclarations.put(anchor, declarations);
            }
            if (anchor != null && event instanceof CollectionStartEvent) {
                anchors.remove(anchor);
                openRecordings.push(
                        new Recording(anchor, declarations, start, openCollections, nodesBefore, charactersBefore));
            }
            if (!openRecordings.isEmpty() || (anchor != null && event instanceof ScalarEvent)) {
                append(new Read(event));
            }
            if (anchor != null && event instanceof ScalarEvent) {
                anchors.put(anchor, anchoredSince(start, nodesBefore, charactersBefore));
            }
            if (event instanceof CollectionStartEvent) {
                openCollections++;
            } else if (event instanceof CollectionEndEvent) {
                openCollections--;
                completeRecording();
            }
        }

        // Each collection has its own end event, so one end event completes at most the innermost
        // anchored collection.
        private void completeRecording() {
            Recording innermost = openRecordings.peek();
            if (innermost == null || innermost.openCollectionsAround() != openCollections) {
                return;
            }
            openRecordings.pop();
            if (latestDeclarations.get(innermost.anchor()) == innermost.declaration()) {
                anchors.put(
                        innermost.anchor(),
                        anchoredSince(innermost.start(), innermost.nodesBefore(), innermost.charactersBefore()));
            }
        }

        private Anchored anchoredSince(int start, long nodesBefore, long charactersBefore) {
            return new Anchored(
                    start, recorded.size(), recordedNodes - nodesBefore, recordedCharacters - charactersBefore);
        }

        private void append(Piece piece) {
            recorded.add(piece);
            switch (piece) {
                case Read(ScalarEvent scalar) -> {
                    recordedNodes++;
                    recordedCharacters += scalar.getValue().length();
                }
                case Read(CollectionStartEvent _) -> {
                    recordedNodes++;
                }
                case Read _ -> {}
                case Copy(Anchored anchored) -> {
                    recordedNodes += anchored.nodes();
                    recordedCharacters += anchored.characters();
                }
            }
        }

        // A key that an alias supplies is named by the alias, so the message shows no anchored value,
        // and its line is the line of the alias. Key text is escaped so the message stays one line.
        private void checkKey() throws IOException {
            String key = currentName();
            AliasEvent alias = copyingAlias;
            String label = SingleLineText.escaped(alias == null ? key : "*" + alias.getAnchor());
            int line = alias == null ? currentTokenLocation().getLineNr() : line(alias.getStartMark());
            OpenMapping mapping = openMappings.peek();
            if (mapping != null) {
                mapping.currentKeyLabel = label;
            }
            if (isMergeKey(key)) {
                throw violation(
                        "uses a YAML merge key at %s (front matter line %d), which is not supported; "
                                + "repeat the keys of the merged mapping there instead",
                        displayPath(), line);
            }
            if (mapping != null && !mapping.keys.add(key)) {
                throw violation(
                        "has the key %s more than once (front matter line %d); "
                                + "keep one entry and remove the others",
                        displayPath(), line);
            }
        }

        // A merge key is the plain scalar `<<` or a key tagged `!!merge`. A quoted "<<", or one with
        // the non-specific tag `!`, which YAML reads as a string, is an ordinary string key.
        private boolean isMergeKey(String key) {
            if (!(_lastEvent instanceof ScalarEvent scalar)) {
                return false;
            }
            String explicitTag = scalar.getTag();
            Tag tag = explicitTag == null
                    ? _yamlResolver.resolve(
                            NodeId.scalar, key, scalar.getImplicit().canOmitTagInPlainScalar())
                    : new Tag(explicitTag);
            return Tag.MERGE.equals(tag);
        }

        private RuleViolation violation(String problem, Object... arguments) {
            return new RuleViolation(this, "Workflow front matter " + problem.formatted(arguments));
        }

        private static int line(Mark mark) {
            return mark.getLine() + 1;
        }

        // Spells the key path the way config errors do, such as codex.turn_timeout_ms or hooks[0].
        // Each open mapping matches one object context, innermost first in both.
        private String displayPath() {
            List<String> segments = new ArrayList<>();
            Iterator<OpenMapping> mappings = openMappings.iterator();
            for (JsonStreamContext current = getParsingContext();
                    current != null && !current.inRoot();
                    current = current.getParent()) {
                segments.addFirst(
                        current.inArray()
                                ? "[" + current.getCurrentIndex() + "]"
                                : "." + mappings.next().currentKeyLabel);
            }
            String path = String.join("", segments);
            return path.startsWith(".") ? path.substring(1) : path;
        }
    }

    // One part of the recorded events: an event read from the document, or the copy an alias made.
    private sealed interface Piece permits Read, Copy {}

    private record Read(Event event) implements Piece {}

    private record Copy(Anchored anchored) implements Piece {}

    // An anchored node: the range of recorded pieces from its first event up to its end, and how many
    // nodes and scalar characters a copy of it holds.
    private record Anchored(int start, int end, long nodes, long characters) {}

    private record Recording(
            String anchor,
            int declaration,
            int start,
            int openCollectionsAround,
            long nodesBefore,
            long charactersBefore) {}

    private static final class Replay {
        private int next;
        private final int end;

        Replay(Anchored anchored) {
            next = anchored.start();
            end = anchored.end();
        }
    }

    private static final class OpenMapping {
        private final Set<String> keys = new HashSet<>();
        private String currentKeyLabel = "";
    }
}
