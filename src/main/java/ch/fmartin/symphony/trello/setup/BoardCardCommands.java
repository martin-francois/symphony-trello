package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloCardSelectors;
import ch.fmartin.symphony.trello.tracker.TrelloCardWrites;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.tracker.TrelloException;
import ch.fmartin.symphony.trello.tracker.TrelloListRoles;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.common.base.Ascii;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Runs the `symphony-trello card` commands against one connected board. The commands resolve the
/// board and its Trello credentials like `start` does, so an agent that runs them never handles
/// the key or token, and they write through [TrelloCardWrites], so the workflow's `trello_tools`
/// policy refuses the same writes it refuses for the worker.
@NullMarked
final class BoardCardCommands {
    private static final Set<String> CREDENTIAL_FAILURE_CODES =
            Set.of("trello_auth_failed", "trello_permission_denied");

    private final LocalWorkerManager workerManager;
    private final Map<String, String> environment;
    private final URI trelloApi;
    private final TrelloClient trello;
    private final TrelloCardWrites writes;
    private final ObjectMapper json;

    BoardCardCommands(LocalWorkerManager workerManager, Map<String, String> environment) {
        this(workerManager, environment, TrelloBoardSetup.DEFAULT_ENDPOINT);
    }

    /// `trelloApi` is the only endpoint that may receive the Trello key and token. The CLI passes the
    /// official Trello API, so no workflow setting can send the credentials anywhere else; tests pass
    /// a fake Trello API.
    BoardCardCommands(LocalWorkerManager workerManager, Map<String, String> environment, URI trelloApi) {
        this.workerManager = workerManager;
        this.environment = Map.copyOf(environment);
        this.trelloApi = trelloApi;
        this.trello = new TrelloClient(new ObjectMapper());
        this.writes = new TrelloCardWrites(trello);
        this.json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    int run(CardCommandRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(), request.configDir(), Optional.empty(), Optional.empty(), environment);
        CardCommandTarget target = workerManager.cardCommandTarget(
                paths, request.board(), request.workflow(), request.operation().commandName(), trelloApi);
        Result result;
        try {
            result = execute(request.operation(), session(paths, target));
        } catch (TrelloException e) {
            throw failure(target, e);
        }
        out.println(request.json() ? json.writeValueAsString(result) : CardCommandText.render(result));
        return 0;
    }

    private Session session(LocalWorkerPaths paths, CardCommandTarget target) {
        EffectiveConfig config = target.config().withResolvedBoardId(trello.resolveBoardId(target.config()));
        ConnectedBoard board = target.board();
        return new Session(
                config,
                new BoardView(
                        board.boardName(),
                        config.tracker().resolvedBoardId(),
                        board.boardUrl(),
                        board.workflowPath().toString()),
                trello.fetchBoardLists(config),
                listRoles(paths, board, config));
    }

    private Result execute(CardCommandRequest.Operation operation, Session session) {
        return switch (operation) {
            case CardCommandRequest.ListCards list -> listCards(session, list.listOrRole());
            case CardCommandRequest.ShowCard show -> showCard(session, show.card());
            case CardCommandRequest.CreateCard create -> createCard(session, create);
            case CardCommandRequest.MoveCard move -> moveCard(session, move);
            case CardCommandRequest.CommentOnCard comment -> commentOnCard(session, comment);
            case CardCommandRequest.ArchiveCard archive -> archiveCard(session, archive);
        };
    }

    private Result listCards(Session session, Optional<String> listOrRole) {
        List<TrelloClient.BoardList> lists =
                listOrRole.map(selector -> listsMatching(session, selector)).orElseGet(session::openLists);
        List<TrelloClient.BoardCard> cards = trello.fetchOpenBoardCards(session.config());
        List<ListWithCards> listed = new ArrayList<>();
        for (TrelloClient.BoardList list : lists) {
            List<CardSummary> inList = new ArrayList<>();
            for (TrelloClient.BoardCard card : cards) {
                if (list.id().equals(card.listId())) {
                    inList.add(new CardSummary(
                            card.id(), card.shortLink(), TrelloCardSelectors.cardUrl(card.shortLink()), card.name()));
                }
            }
            listed.add(new ListWithCards(listView(session, list), inList));
        }
        return new Listed(ReadStatus.OK, session.board(), listed);
    }

    private Result showCard(Session session, String selector) {
        Card card = card(session, selector);
        return new Shown(ReadStatus.OK, session.board(), CardDetails.of(card, cardList(session, card)));
    }

    private Result createCard(Session session, CardCommandRequest.CreateCard create) {
        TrelloClient.BoardList list = destination(session, create.listOrRole());
        TrelloClient.CreatedCard created =
                writes.createCard(session.config(), list, create.title(), create.description());
        return new Changed(
                Change.CARD_CREATED,
                session.board(),
                new CardSummary(
                        created.id(),
                        created.shortLink(),
                        TrelloCardSelectors.cardUrl(created.shortLink()),
                        create.title().strip()),
                listView(session, list));
    }

    private Result moveCard(Session session, CardCommandRequest.MoveCard move) {
        Card card = changeableCard(session, move.card());
        TrelloClient.BoardList list = destination(session, move.listOrRole());
        writes.moveCard(session.config(), card.id(), list);
        return new Changed(Change.CARD_MOVED, session.board(), summary(card), listView(session, list));
    }

    private Result commentOnCard(Session session, CardCommandRequest.CommentOnCard comment) {
        Card card = changeableCard(session, comment.card());
        writes.addComment(session.config(), card.id(), comment.text());
        return new Changed(Change.COMMENT_ADDED, session.board(), summary(card), null);
    }

    private Result archiveCard(Session session, CardCommandRequest.ArchiveCard archive) {
        Card card = changeableCard(session, archive.card());
        if (!card.title().equals(archive.confirmTitle())) {
            throw new TrelloBoardSetupException(
                    "setup_card_archive_title_mismatch",
                    "--confirm-title does not match the card title " + DisplayNames.quotedName(card.title())
                            + ". Show the person you work for this title and ask again before archiving.");
        }
        writes.archiveCard(session.config(), card.id());
        return new Changed(Change.CARD_ARCHIVED, session.board(), summary(card), null);
    }

    private Card changeableCard(Session session, String selector) {
        Card card = card(session, selector);
        if (card.closed()) {
            throw new TrelloBoardSetupException(
                    "setup_card_archived", "The card is archived. Restore it in Trello before changing it.");
        }
        return card;
    }

    private Card card(Session session, String selector) {
        String lookupId = TrelloCardSelectors.lookupId(selector)
                .orElseThrow(() -> new TrelloBoardSetupException(
                        "setup_card_invalid_reference",
                        "CARD must be a Trello card id, a short link, or a https://trello.com/c/ URL."));
        return switch (trello.fetchCardDetails(session.config(), lookupId)) {
            case CardLookupResult.Found found -> onSelectedBoard(session, found.card());
            case CardLookupResult.Missing _ ->
                throw new TrelloBoardSetupException(
                        "setup_card_not_found", "No Trello card matches " + DisplayNames.quotedName(selector) + ".");
            case CardLookupResult.Failed failed -> throw new TrelloException(failed.code(), failed.message());
        };
    }

    private static Card onSelectedBoard(Session session, Card card) {
        if (!session.board().id().equals(card.boardId())) {
            throw new TrelloBoardSetupException(
                    "setup_card_not_on_board",
                    "The card belongs to another Trello board. Card commands only work on cards of the selected board "
                            + DisplayNames.quotedName(session.board().name()) + ".");
        }
        return card;
    }

    private static TrelloClient.BoardList destination(Session session, String listOrRole) {
        return switch (session.roles().resolve(session.openLists(), listOrRole)) {
            case TrelloListRoles.Destination.Found found -> found.list();
            case TrelloListRoles.Destination.RoleWithSeveralLists several ->
                throw new TrelloBoardSetupException(
                        "setup_card_list_role_ambiguous",
                        "The " + several.role().key() + " role covers several open lists: "
                                + quotedListNames(several.lists()) + ". Name one of these lists instead.");
            case TrelloListRoles.Destination other -> throw unresolvedList(session, listOrRole, other);
        };
    }

    /// The lists that `card list --list` shows: one list by name, or every open list with a role.
    private static List<TrelloClient.BoardList> listsMatching(Session session, String listOrRole) {
        return switch (session.roles().resolve(session.openLists(), listOrRole)) {
            case TrelloListRoles.Destination.Found found -> List.of(found.list());
            case TrelloListRoles.Destination.RoleWithSeveralLists several -> several.lists();
            case TrelloListRoles.Destination other -> throw unresolvedList(session, listOrRole, other);
        };
    }

    private static TrelloBoardSetupException unresolvedList(
            Session session, String listOrRole, TrelloListRoles.Destination destination) {
        String requested = DisplayNames.quotedName(listOrRole);
        return switch (destination) {
            case TrelloListRoles.Destination.AmbiguousListName _ ->
                new TrelloBoardSetupException(
                        "setup_card_list_ambiguous",
                        "Several open lists are named " + requested + ". Rename the duplicates in Trello.");
            case TrelloListRoles.Destination.RoleWithoutList withoutList ->
                new TrelloBoardSetupException(
                        "setup_card_list_role_unset",
                        "The workflow gives no open list the "
                                + withoutList.role().key() + " role. Name a list instead: "
                                + quotedListNames(session.openLists()) + ".");
            default ->
                new TrelloBoardSetupException(
                        "setup_card_list_not_found",
                        "No open list or list role matches " + requested + ". Open lists: "
                                + quotedListNames(session.openLists()) + ". Roles: " + TrelloListRoles.ROLE_NAMES
                                + ".");
        };
    }

    private static String quotedListNames(List<TrelloClient.BoardList> lists) {
        return DisplayNames.quotedList(
                lists.stream().map(TrelloClient.BoardList::name).toList());
    }

    private static @Nullable ListView cardList(Session session, Card card) {
        return session.lists().stream()
                .filter(list -> list.id().equals(card.listId()))
                .findAny()
                .map(list -> listView(session, list))
                .orElse(null);
    }

    private static ListView listView(Session session, TrelloClient.BoardList list) {
        List<String> roles = session.roles().rolesOf(list.name()).stream()
                .map(TrelloListRoles.Role::key)
                .toList();
        boolean open = !list.closed();
        return new ListView(
                list.id(),
                list.name(),
                roles,
                open && TrelloCardWrites.movableTo(session.config(), session.openLists(), list),
                open && TrelloCardWrites.creatableIn(session.config(), list));
    }

    private static CardSummary summary(Card card) {
        return new CardSummary(
                card.id(), Objects.requireNonNullElse(card.shortLink(), card.id()), cardUrl(card), card.title());
    }

    /// The card URL from its short link, else the URL Trello reported, else a URL from the card id,
    /// which Trello also resolves.
    private static String cardUrl(Card card) {
        String shortLink = card.shortLink();
        if (shortLink != null) {
            return TrelloCardSelectors.cardUrl(shortLink);
        }
        return Objects.requireNonNullElseGet(card.url(), () -> TrelloCardSelectors.cardUrl(card.id()));
    }

    /// The list roles of the selected workflow. Active, in-progress, blocked, and terminal lists are
    /// workflow settings; the human-review and merging lists come from what setup recorded when it
    /// generated the workflow, or from the shape setup writes when no record exists.
    private static TrelloListRoles listRoles(LocalWorkerPaths paths, ConnectedBoard board, EffectiveConfig config) {
        Optional<GeneratedWorkflowBodyInputs> inputs =
                recordedInputs(paths, board).or(() -> metadataInputs(board));
        return TrelloListRoles.of(
                config,
                inputs.map(GeneratedWorkflowBodyInputs::reviewState).orElse(null),
                inputs.map(GeneratedWorkflowBodyInputs::mergingState).orElse(null));
    }

    private static Optional<GeneratedWorkflowBodyInputs> recordedInputs(LocalWorkerPaths paths, ConnectedBoard board) {
        try {
            return GeneratedWorkflowStore.find(
                            GeneratedWorkflowStore.besideManifest(paths.manifestPath())
                                    .records(),
                            board.workflowPath())
                    .map(GeneratedWorkflowRecord::inputs);
        } catch (IOException e) {
            // Without a readable record the roles fall back to the workflow's own settings.
            return Optional.empty();
        }
    }

    private static Optional<GeneratedWorkflowBodyInputs> metadataInputs(ConnectedBoard board) {
        try {
            return GeneratedWorkflowBodyInputs.fromMetadata(
                    new WorkflowLoader().load(board.workflowPath()).config(), Optional.of(board.githubEnabled()));
        } catch (WorkflowException e) {
            // cardCommandTarget already loaded and checked this workflow and reports its errors; here
            // unusable metadata only leaves the review and merging roles unset.
            return Optional.empty();
        }
    }

    private static TrelloBoardSetupException failure(CardCommandTarget target, TrelloException e) {
        var failure = new TrelloBoardSetupException(e.code(), e.getMessage(), e);
        return CREDENTIAL_FAILURE_CODES.contains(e.code())
                ? target.credentialContext().apply(failure)
                : failure;
    }

    private record Session(
            EffectiveConfig config, BoardView board, List<TrelloClient.BoardList> lists, TrelloListRoles roles) {
        List<TrelloClient.BoardList> openLists() {
            return TrelloCardWrites.openLists(lists);
        }
    }

    /// What one card command prints, as JSON with `--json` and as text otherwise. The JSON field
    /// names are part of the command's output contract.
    sealed interface Result {
        BoardView board();
    }

    record Listed(ReadStatus status, BoardView board, List<ListWithCards> lists) implements Result {}

    record Shown(ReadStatus status, BoardView board, CardDetails card) implements Result {}

    /// The status of a read. Its JSON form is `ok`.
    enum ReadStatus {
        OK;

        @JsonValue
        String status() {
            return Ascii.toLowerCase(name());
        }
    }

    record Changed(Change status, BoardView board, CardSummary card, @Nullable ListView list) implements Result {}

    /// The write a [Changed] result reports. Its JSON form is the lower-case name, such as
    /// `card_moved`.
    enum Change {
        CARD_CREATED,
        CARD_MOVED,
        COMMENT_ADDED,
        CARD_ARCHIVED;

        @JsonValue
        String status() {
            return Ascii.toLowerCase(name());
        }
    }

    record BoardView(String name, String id, String url, String workflow) {}

    record ListView(String id, String name, List<String> roles, boolean moveAllowed, boolean createAllowed) {}

    record ListWithCards(ListView list, List<CardSummary> cards) {}

    record CardSummary(String id, String shortLink, String url, String title) {}

    record CardDetails(
            String id,
            @Nullable String shortLink,
            String url,
            String title,
            String description,
            @Nullable ListView list,
            boolean archived,
            List<String> labels,
            List<ChecklistView> checklists,
            List<AttachmentView> attachments,
            List<CommentView> comments) {
        static CardDetails of(Card card, @Nullable ListView list) {
            return new CardDetails(
                    card.id(),
                    card.shortLink(),
                    cardUrl(card),
                    card.title(),
                    Objects.requireNonNullElse(card.description(), ""),
                    list,
                    card.closed(),
                    card.labels(),
                    card.checklists().stream().map(ChecklistView::of).toList(),
                    card.attachments().stream().map(AttachmentView::of).toList(),
                    card.comments().stream().map(CommentView::of).toList());
        }
    }

    record ChecklistView(@Nullable String name, List<ChecklistItemView> items) {
        static ChecklistView of(Card.Checklist checklist) {
            return new ChecklistView(
                    checklist.name(),
                    checklist.items().stream().map(ChecklistItemView::of).toList());
        }
    }

    record ChecklistItemView(@Nullable String text, boolean complete) {
        static ChecklistItemView of(Card.ChecklistItem item) {
            return new ChecklistItemView(item.text(), item.complete());
        }
    }

    record AttachmentView(@Nullable String name, @Nullable String url) {
        static AttachmentView of(Card.Attachment attachment) {
            return new AttachmentView(attachment.name(), attachment.url());
        }
    }

    record CommentView(@Nullable String author, @Nullable String createdAt, String text) {
        static CommentView of(Card.Comment comment) {
            Instant createdAt = comment.createdAt();
            return new CommentView(comment.author(), createdAt == null ? null : createdAt.toString(), comment.text());
        }
    }
}
